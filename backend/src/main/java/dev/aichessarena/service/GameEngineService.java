package dev.aichessarena.service;

import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import com.github.bhlangonijr.chesslib.Board;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class GameEngineService {

    private static final Logger LOG = Logger.getLogger(GameEngineService.class);

    @Inject
    ChessService chessService;

    @Inject
    OpenRouterService openRouterService;

    @Inject
    PromptService promptService;

    @Inject
    ResponseParserService responseParserService;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameWebSocket gameWebSocket;

    @Inject
    GameMoveService gameMoveService;

    private final Map<UUID, Boolean> runningGames = new ConcurrentHashMap<>();

    public void startGame(UUID gameId) {
        if (runningGames.containsKey(gameId)) {
            LOG.warnf("Game %s is already running", gameId);
            return;
        }
        runningGames.put(gameId, true);

        Thread.startVirtualThread(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                runGameLoop(gameId);
            } catch (Exception e) {
                LOG.errorf(e, "Game loop failed for game %s", gameId);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.terminate();
                }
                runningGames.remove(gameId);
            }
        });
    }

    public void pauseGame(UUID gameId) {
        runningGames.put(gameId, false);
    }

    public boolean isRunning(UUID gameId) {
        return runningGames.getOrDefault(gameId, false);
    }

    private void runGameLoop(UUID gameId) {
        gameLifecycleService.markGameStarted(gameId);

        Board board = loadBoardState(gameId);
        String lastMoveSan = null;
        String lastMessage = null;

        // Build conversation histories for each side
        List<OpenRouterService.ChatMsg> whiteHistory = new ArrayList<>();
        List<OpenRouterService.ChatMsg> blackHistory = new ArrayList<>();

        // Initialize system prompts
        Game game = gameRepository.findById(gameId);
        PromptService.ResolvedPrompt whitePrompt = resolveSystemPrompt(game, "WHITE");
        PromptService.ResolvedPrompt blackPrompt = resolveSystemPrompt(game, "BLACK");
        String whiteSystemPrompt = whitePrompt.prompt();
        String blackSystemPrompt = blackPrompt.prompt();
        String whitePromptVersion = whitePrompt.version();
        String blackPromptVersion = blackPrompt.version();
        String whitePromptHash = promptService.computePromptHash(whiteSystemPrompt);
        String blackPromptHash = promptService.computePromptHash(blackSystemPrompt);

        whiteHistory.add(new OpenRouterService.ChatMsg("system", whiteSystemPrompt));
        blackHistory.add(new OpenRouterService.ChatMsg("system", blackSystemPrompt));

        // Rebuild history from existing moves if resuming
        rebuildHistory(gameId, board, whiteHistory, blackHistory);

        while (Boolean.TRUE.equals(runningGames.get(gameId))) {
            if (chessService.isGameOver(board)) {
                endGame(gameId, board);
                return;
            }

            String sideToMove = chessService.getSideToMove(board);
            int moveNumber = chessService.getMoveNumber(board);
            List<String> legalMoves = chessService.getLegalMovesAsSan(board);
            String fen = board.getFen();
            String pgn = game.pgn;

            // Get current player info
            game = gameRepository.findById(gameId);
            String modelId = "WHITE".equals(sideToMove) ? game.whiteModelId : game.blackModelId;
            String playerName = "WHITE".equals(sideToMove) ? game.whitePlayerName : game.blackPlayerName;
            String opponentName = "WHITE".equals(sideToMove) ? game.blackPlayerName : game.whitePlayerName;
            List<OpenRouterService.ChatMsg> history = "WHITE".equals(sideToMove) ? whiteHistory : blackHistory;

            boolean isFirstMove = moveNumber == 1 && "WHITE".equals(sideToMove);

            String turnPrompt = promptService.buildTurnPrompt(
                    pgn, fen, chessService.toAsciiBoard(board),
                    sideToMove, moveNumber, legalMoves,
                    opponentName, lastMoveSan, lastMessage, isFirstMove);

            history.add(new OpenRouterService.ChatMsg("user", turnPrompt));

            // Attempt loop with retries
            int maxRetries = game.tournament != null ? game.tournament.maxRetries : 3;
            int moveTimeoutSeconds = game.tournament != null ? game.tournament.moveTimeoutSeconds : 60;
            Instant turnStartedAt = Instant.now();
            Instant turnDeadlineAt = turnStartedAt.plusSeconds(moveTimeoutSeconds);
            long turnDeadlineAtMs = turnDeadlineAt.toEpochMilli();
            boolean moveMade = false;

            broadcastTurnTiming(gameId, game.totalCostUsd, sideToMove, turnStartedAt, turnDeadlineAt);

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                if (!Boolean.TRUE.equals(runningGames.get(gameId))) return;
                long remainingMs = turnDeadlineAtMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    forfeitGameOnTimeout(gameId, sideToMove, moveTimeoutSeconds);
                    return;
                }

                LOG.infof("Game %s: %s move %d, attempt %d", gameId, sideToMove, moveNumber, attempt);

                OpenRouterService.LlmResponse llmResponse = openRouterService.chat(
                        modelId, history, Duration.ofMillis(Math.max(remainingMs, 100L))
                );

                if (llmResponse.timedOut()) {
                    LOG.warnf("Game %s: %s timed out while generating move", gameId, modelId);
                    gameLifecycleService.forfeitTimeout(gameId, sideToMove, moveTimeoutSeconds);
                    return;
                }

                if (llmResponse.content() == null) {
                    LOG.errorf("Game %s: null response from %s", gameId, modelId);
                    broadcastRetry(gameId, sideToMove, attempt, "Model returned an empty response");
                    if (attempt < maxRetries) {
                        String retryPrompt = promptService.buildRetryPrompt(
                                promptService.getJsonParseError(), fen, legalMoves, attempt + 1, maxRetries);
                        history.add(new OpenRouterService.ChatMsg("user", retryPrompt));
                    }
                    continue;
                }

                ResponseParserService.ParseResult parseResult = responseParserService.parse(llmResponse.content());

                if (parseResult.isFailure()) {
                    LOG.warnf("Game %s: parse failure from %s: %s", gameId, modelId, parseResult.message());
                    broadcastRetry(gameId, sideToMove, attempt, "Failed to parse response as JSON");
                    if (attempt < maxRetries) {
                        history.add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
                        String retryPrompt = promptService.buildRetryPrompt(
                                promptService.getJsonParseError(), fen, legalMoves, attempt + 1, maxRetries);
                        history.add(new OpenRouterService.ChatMsg("user", retryPrompt));
                    }
                    continue;
                }

                // Validate move
                ChessService.ValidMoveResult moveResult = chessService.validateAndApply(board, parseResult.move());

                if (!moveResult.valid()) {
                    LOG.warnf("Game %s: illegal move '%s' from %s: %s",
                            gameId, parseResult.move(), modelId, moveResult.error());
                    broadcastRetry(gameId, sideToMove, attempt,
                            "Illegal move attempted: " + parseResult.move());
                    if (attempt < maxRetries) {
                        history.add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
                        String retryPrompt = promptService.buildRetryPrompt(
                                promptService.getIllegalMoveError(parseResult.move()),
                                fen, legalMoves, attempt + 1, maxRetries);
                        history.add(new OpenRouterService.ChatMsg("user", retryPrompt));
                    }
                    continue;
                }

                // Move is valid! Store it
                history.add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));

                gameMoveService.recordModelMove(
                        gameId,
                        moveNumber,
                        sideToMove,
                        parseResult.move(),
                        moveResult.fen(),
                        modelId,
                        playerName,
                        parseResult.message(),
                        llmResponse,
                        attempt - 1,
                        "WHITE".equals(sideToMove) ? whitePromptVersion : blackPromptVersion,
                        "WHITE".equals(sideToMove) ? whitePromptHash : blackPromptHash
                );

                game = gameRepository.findById(gameId);
                lastMoveSan = parseResult.move();
                lastMessage = parseResult.message();
                moveMade = true;
                break;
            }

            if (!moveMade) {
                // Forfeit due to invalid moves
                forfeitGame(gameId, sideToMove, maxRetries);
                return;
            }
        }

        // Game was paused
        gameLifecycleService.markGamePaused(gameId);
    }

    private Board loadBoardState(UUID gameId) {
        Game game = gameRepository.findById(gameId);
        return chessService.boardFromFen(game.currentFen);
    }

    private void rebuildHistory(UUID gameId, Board board,
                                 List<OpenRouterService.ChatMsg> whiteHistory,
                                 List<OpenRouterService.ChatMsg> blackHistory) {
        // For resume: reconstruct the board from stored moves
        List<dev.aichessarena.entity.Move> moves = moveRepository.findByGameId(gameId);
        if (moves.isEmpty()) return;

        // Reset board and replay
        board.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        for (dev.aichessarena.entity.Move move : moves) {
            chessService.validateAndApply(board, move.san);
        }
    }

    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        gameMoveService.updateMoveEvaluation(moveId, result);
    }

    void endGame(UUID gameId, Board board) {
        ChessService.GameEndInfo endInfo = chessService.getGameEndInfo(board);
        gameLifecycleService.completeGame(gameId, endInfo);
    }

    void forfeitGame(UUID gameId, String forfeitColor, int maxRetries) {
        gameLifecycleService.forfeitInvalidMoves(gameId, forfeitColor, maxRetries);
    }

    void forfeitGameOnTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
        gameLifecycleService.forfeitTimeout(gameId, forfeitColor, moveTimeoutSeconds);
    }

    public void overrideMove(UUID gameId, String san) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != GameStatus.PAUSED || Boolean.TRUE.equals(runningGames.get(gameId))) {
            throw new WebApplicationException("Pause the game before overriding moves", Response.Status.CONFLICT);
        }
        gameMoveService.applyOverrideMove(gameId, san);
    }

    private PromptService.ResolvedPrompt resolveSystemPrompt(Game game, String color) {
        if (game.tournament == null) {
            return promptService.buildSystemPrompt(
                    null,
                    null,
                    color,
                    "WHITE".equals(color) ? game.blackPlayerName : game.whitePlayerName,
                    "WHITE".equals(color) ? game.blackModelId : game.whiteModelId
            );
        }

        var participant = "WHITE".equals(color) ? game.whiteParticipant : game.blackParticipant;
        String opponentName = "WHITE".equals(color) ? game.blackPlayerName : game.whitePlayerName;
        String opponentModel = "WHITE".equals(color) ? game.blackModelId : game.whiteModelId;

        String legacyTemplate = null;
        if (participant != null && participant.customSystemPrompt != null && !participant.customSystemPrompt.isBlank()) {
            legacyTemplate = participant.customSystemPrompt;
        } else if (game.tournament.defaultSystemPrompt != null && !game.tournament.defaultSystemPrompt.isBlank()) {
            legacyTemplate = game.tournament.defaultSystemPrompt;
        }

        String customInstructions = null;
        if (participant != null && participant.customInstructions != null && !participant.customInstructions.isBlank()) {
            customInstructions = participant.customInstructions;
        } else if (game.tournament.sharedCustomInstructions != null
                && !game.tournament.sharedCustomInstructions.isBlank()) {
            customInstructions = game.tournament.sharedCustomInstructions;
        }

        return promptService.buildSystemPrompt(
                legacyTemplate,
                customInstructions,
                color,
                opponentName,
                opponentModel
        );
    }

    private void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
        gameWebSocket.broadcastRetry(gameId, color, attemptNumber, reason);
    }

    private void broadcastTurnTiming(UUID gameId, BigDecimal totalCostUsd, String activeColor,
                                     Instant turnStartedAt, Instant turnDeadlineAt) {
        int totalMoves = Math.toIntExact(moveRepository.count("game.id", gameId));
        gameWebSocket.broadcastGameStatus(
                gameId,
                "IN_PROGRESS",
                null,
                null,
                totalMoves,
                totalCostUsd,
                activeColor,
                turnStartedAt,
                turnDeadlineAt
        );
    }
}
