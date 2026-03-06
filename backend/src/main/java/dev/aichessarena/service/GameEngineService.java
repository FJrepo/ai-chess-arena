package dev.aichessarena.service;

import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import com.github.bhlangonijr.chesslib.Board;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
    ChatMessageRepository chatMessageRepository;

    @Inject
    CostTrackingService costTrackingService;

    @Inject
    TournamentService tournamentService;

    @Inject
    StockfishService stockfishService;

    @Inject
    GameWebSocket gameWebSocket;

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
        markGameStarted(gameId);

        Board board = loadBoardState(gameId);
        String lastMoveSan = null;
        String lastMessage = null;

        // Build conversation histories for each side
        List<OpenRouterService.ChatMsg> whiteHistory = new ArrayList<>();
        List<OpenRouterService.ChatMsg> blackHistory = new ArrayList<>();

        // Initialize system prompts
        Game game = gameRepository.findById(gameId);
        String whitePromptTemplate = getCustomPrompt(game, "WHITE");
        String blackPromptTemplate = getCustomPrompt(game, "BLACK");
        String whiteSystemPrompt = promptService.buildSystemPrompt(
                whitePromptTemplate, "WHITE",
                game.blackPlayerName, game.blackModelId);
        String blackSystemPrompt = promptService.buildSystemPrompt(
                blackPromptTemplate, "BLACK",
                game.whitePlayerName, game.whiteModelId);
        String whitePromptVersion = promptService.resolvePromptVersion(whitePromptTemplate);
        String blackPromptVersion = promptService.resolvePromptVersion(blackPromptTemplate);
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
                    forfeitGameOnTimeout(gameId, sideToMove, moveTimeoutSeconds);
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

                persistMove(gameId, moveNumber, sideToMove, parseResult.move(),
                        moveResult.fen(), modelId, llmResponse, attempt - 1,
                        "WHITE".equals(sideToMove) ? whitePromptVersion : blackPromptVersion,
                        "WHITE".equals(sideToMove) ? whitePromptHash : blackPromptHash);

                // Update game PGN and FEN
                updateGameState(gameId, moveResult.fen(), parseResult.move(), moveNumber, sideToMove);

                // Store chat message if present
                if (parseResult.message() != null && !parseResult.message().isBlank()) {
                    persistChatMessage(gameId, moveNumber, modelId, sideToMove, parseResult.message());
                    broadcastChat(gameId, moveNumber, sideToMove, modelId, playerName, parseResult.message());
                }

                // Broadcast the move
                broadcastMove(gameId, moveNumber, sideToMove, parseResult.move(),
                        moveResult.fen(), modelId, llmResponse.responseTimeMs(), attempt - 1);

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
        markGamePaused(gameId);
    }

    @Transactional
    void markGameStarted(UUID gameId) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.IN_PROGRESS;
        if (game.startedAt == null) {
            game.startedAt = LocalDateTime.now();
        }
        gameRepository.persist(game);
        int totalMoves = Math.toIntExact(moveRepository.count("game.id", gameId));
        gameWebSocket.broadcastGameStatus(gameId, "IN_PROGRESS", null, null, totalMoves, game.totalCostUsd);
    }

    @Transactional
    void markGamePaused(UUID gameId) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.PAUSED;
        gameRepository.persist(game);
        int totalMoves = Math.toIntExact(moveRepository.count("game.id", gameId));
        gameWebSocket.broadcastGameStatus(gameId, "PAUSED", null, null, totalMoves, game.totalCostUsd);
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

    @Transactional
    void persistMove(UUID gameId, int moveNumber, String color, String san, String fen,
                     String modelId, OpenRouterService.LlmResponse llmResponse, int retryCount,
                     String promptVersion, String promptHash) {
        dev.aichessarena.entity.Move move = new dev.aichessarena.entity.Move();
        move.game = gameRepository.findById(gameId);
        move.moveNumber = moveNumber;
        move.color = color;
        move.san = san;
        move.fen = fen;
        move.modelId = modelId;
        move.promptVersion = promptVersion;
        move.promptHash = promptHash;
        move.promptTokens = llmResponse.promptTokens();
        move.completionTokens = llmResponse.completionTokens();
        if (llmResponse.costUsd() != null) {
            move.costUsd = BigDecimal.valueOf(llmResponse.costUsd());
            costTrackingService.addMoveCost(gameId, move.costUsd);
        }
        move.responseTimeMs = llmResponse.responseTimeMs();
        move.retryCount = retryCount;
        move.rawResponse = llmResponse.rawResponse();
        moveRepository.persist(move);

        // Queue evaluation in background
        UUID moveId = move.id;
        LOG.infof("Queuing Stockfish evaluation for move %d in game %s", moveNumber, gameId);
        stockfishService.evaluate(fen, 12).thenAccept(result -> {
            try {
                Arc.container().instance(GameEngineService.class).get().updateMoveEvaluation(moveId, result);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to update move evaluation for move %s", moveId);
            }
        }).exceptionally(ex -> {
            LOG.errorf(ex, "Stockfish evaluation failed for move %s", moveId);
            return null;
        });
    }

    @Transactional
    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        LOG.debugf("Updating move %s with evaluation: cp=%d, mate=%d", moveId, result.cp(), result.mate());
        dev.aichessarena.entity.Move move = moveRepository.findById(moveId);
        if (move != null) {
            move.evaluationCp = result.cp();
            move.evaluationMate = result.mate();
            moveRepository.persist(move);
            gameWebSocket.broadcastEvaluation(move.game.id, move.moveNumber, move.color, result.cp(), result.mate());
        } else {
            LOG.warnf("Could not find move %s to update evaluation", moveId);
        }
    }

    @Transactional
    void updateGameState(UUID gameId, String fen, String san, int moveNumber, String color) {
        Game game = gameRepository.findById(gameId);
        game.currentFen = fen;

        // Update PGN
        String moveStr;
        if ("WHITE".equals(color)) {
            moveStr = moveNumber + ". " + san;
        } else {
            moveStr = san;
        }
        game.pgn = (game.pgn == null ? "" : game.pgn + " ") + moveStr;
        gameRepository.persist(game);
    }

    @Transactional
    void persistChatMessage(UUID gameId, int moveNumber, String modelId,
                            String color, String message) {
        ChatMessage chat = new ChatMessage();
        chat.game = gameRepository.findById(gameId);
        chat.moveNumber = moveNumber;
        chat.senderModel = modelId;
        chat.senderColor = color;
        chat.message = message;
        chatMessageRepository.persist(chat);
    }

    @Transactional
    void endGame(UUID gameId, Board board) {
        ChessService.GameEndInfo endInfo = chessService.getGameEndInfo(board);
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.COMPLETED;
        game.result = GameResult.valueOf(endInfo.result());
        game.resultReason = ResultReason.valueOf(endInfo.reason());
        game.completedAt = LocalDateTime.now();
        gameRepository.persist(game);

        gameWebSocket.broadcastGameStatus(gameId, "COMPLETED", endInfo.result(),
                endInfo.reason(), game.moves.size(), game.totalCostUsd);

        if (game.tournament != null) {
            tournamentService.advanceWinner(gameId);
        }
    }

    @Transactional
    void forfeitGame(UUID gameId, String forfeitColor, int maxRetries) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.FORFEIT;
        game.result = "WHITE".equals(forfeitColor) ? GameResult.WHITE_FORFEIT : GameResult.BLACK_FORFEIT;
        game.resultReason = ResultReason.FORFEIT_INVALID_MOVES;
        game.completedAt = LocalDateTime.now();
        gameRepository.persist(game);

        gameWebSocket.broadcastGameStatus(gameId, "FORFEIT", game.result.name(),
                game.resultReason.name(), game.moves.size(), game.totalCostUsd);
        gameWebSocket.broadcastForfeit(gameId, forfeitColor,
                "Failed to produce a legal move after %d attempts".formatted(maxRetries));

        if (game.tournament != null) {
            tournamentService.advanceWinner(gameId);
        }
    }

    @Transactional
    void forfeitGameOnTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.FORFEIT;
        game.result = "WHITE".equals(forfeitColor) ? GameResult.WHITE_FORFEIT : GameResult.BLACK_FORFEIT;
        game.resultReason = ResultReason.TIMEOUT;
        game.completedAt = LocalDateTime.now();
        gameRepository.persist(game);

        gameWebSocket.broadcastGameStatus(gameId, "FORFEIT", game.result.name(),
                game.resultReason.name(), game.moves.size(), game.totalCostUsd);
        gameWebSocket.broadcastForfeit(
                gameId,
                forfeitColor,
                "Move timed out after %d seconds".formatted(moveTimeoutSeconds)
        );

        if (game.tournament != null) {
            tournamentService.advanceWinner(gameId);
        }
    }

    @Transactional
    public void overrideMove(UUID gameId, String san) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != GameStatus.PAUSED || Boolean.TRUE.equals(runningGames.get(gameId))) {
            throw new WebApplicationException("Pause the game before overriding moves", Response.Status.CONFLICT);
        }
        if (san == null || san.isBlank()) {
            throw new IllegalArgumentException("Move is required");
        }

        Board board = chessService.boardFromFen(game.currentFen);
        String sideToMove = chessService.getSideToMove(board);
        int moveNumber = chessService.getMoveNumber(board);

        String normalizedSan = san.trim();
        ChessService.ValidMoveResult result = chessService.validateAndApply(board, normalizedSan);
        if (!result.valid()) {
            throw new IllegalArgumentException("Invalid move: " + result.error());
        }

        dev.aichessarena.entity.Move move = new dev.aichessarena.entity.Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = sideToMove;
        move.san = normalizedSan;
        move.fen = result.fen();
        move.promptVersion = "admin-override";
        move.promptHash = null;
        move.isOverride = true;
        moveRepository.persist(move);

        updateGameState(gameId, result.fen(), normalizedSan, moveNumber, sideToMove);

        broadcastMove(gameId, moveNumber, sideToMove, normalizedSan, result.fen(), "admin-override", 0, 0);
    }

    private String getCustomPrompt(Game game, String color) {
        if (game.tournament == null) return null;
        var participant = "WHITE".equals(color) ? game.whiteParticipant : game.blackParticipant;
        if (participant != null && participant.customSystemPrompt != null) {
            return participant.customSystemPrompt;
        }
        return game.tournament.defaultSystemPrompt;
    }

    private void broadcastMove(UUID gameId, int moveNumber, String color, String san,
                                String fen, String modelId, long responseTimeMs, int retryCount) {
        Game game = gameRepository.findById(gameId);
        gameWebSocket.broadcastMove(gameId, moveNumber, color, san, fen,
                game.pgn, modelId, responseTimeMs, retryCount);
    }

    private void broadcastChat(UUID gameId, int moveNumber, String color,
                                String modelId, String playerName, String message) {
        gameWebSocket.broadcastChat(gameId, moveNumber, color, modelId, playerName, message);
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
