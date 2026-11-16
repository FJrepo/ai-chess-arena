package dev.aichessarena.service;

import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import com.github.bhlangonijr.chesslib.Board;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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
    GameRepository gameRepository;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameMoveService gameMoveService;

    @Inject
    GameTurnService gameTurnService;

    @Inject
    GameConversationService gameConversationService;

    /**
     * One entry per game loop that is alive. The entry belongs to exactly one loop (identity matters):
     * a pause only flags it, and only the owning loop removes it when it exits, so a resumed game can
     * never run two loops at once.
     */
    private final Map<UUID, GameLoop> runningGames = new ConcurrentHashMap<>();

    static final class GameLoop {
        private volatile boolean stopRequested;

        void requestStop() {
            stopRequested = true;
        }

        boolean shouldContinue() {
            return !stopRequested;
        }
    }

    /**
     * Starts or resumes a WAITING or PAUSED game.
     *
     * @throws WebApplicationException 409 if the game is running, in progress or finished; 404 if it does not exist
     */
    public GameDto startGame(UUID gameId) {
        GameLoop loop = tryClaimLoop(gameId);
        if (loop == null) {
            throw new GameStateConflictException("Game is already running");
        }
        GameDto started;
        try {
            started = gameLifecycleService.markGameStarted(gameId);
        } catch (RuntimeException e) {
            runningGames.remove(gameId, loop);
            throw e;
        }
        spawnGameLoop(gameId, loop, null, null);
        return started;
    }

    /** Continues an IN_PROGRESS game after a human move was recorded. */
    void continueAfterHumanMove(UUID gameId, String lastMoveSan, String lastMessage) {
        GameLoop loop = tryClaimLoop(gameId);
        if (loop == null) {
            LOG.warnf("Game %s is already running", gameId);
            return;
        }
        spawnGameLoop(gameId, loop, lastMoveSan, lastMessage);
    }

    private void spawnGameLoop(UUID gameId, GameLoop loop, String lastMoveSan, String lastMessage) {
        Thread.startVirtualThread(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                runGameLoop(gameId, loop, lastMoveSan, lastMessage);
            } catch (Exception e) {
                LOG.errorf(e, "Game loop failed for game %s", gameId);
                pauseAfterLoopFailure(gameId, e);
            } finally {
                runningGames.remove(gameId, loop);
                // A pause may have flagged this loop just as it was finishing (e.g. returning for a human turn).
                // Paired with the re-check in pauseGame, at least one side persists the pause.
                if (!loop.shouldContinue()) {
                    pauseQuietly(gameId);
                }
                if (requestContext.isActive()) {
                    requestContext.terminate();
                }
            }
        });
    }

    private void pauseQuietly(UUID gameId) {
        try {
            gameLifecycleService.markGamePaused(gameId);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not pause game %s", gameId);
        }
    }

    private void pauseAfterLoopFailure(UUID gameId, Exception failure) {
        try {
            gameLifecycleService.pauseWithReason(gameId,
                    "The game loop stopped unexpectedly (" + failure.getClass().getSimpleName() + "); resume to retry");
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not pause game %s after loop failure", gameId);
        }
    }

    /**
     * Requests a pause. A running loop is only flagged and pauses itself after its current turn
     * (repeated requests are harmless); a game waiting for a human move is paused immediately.
     */
    public void pauseGame(UUID gameId) {
        GameLoop loop = runningGames.get(gameId);
        if (loop != null) {
            loop.requestStop();
            if (runningGames.get(gameId) == loop) {
                return; // the loop persists the pause when it exits
            }
            // The loop released the game while we flagged it; persist the pause ourselves below.
        }

        Game game = gameRepository.findById(gameId);
        if (game != null && game.status == GameStatus.IN_PROGRESS) {
            gameLifecycleService.markGamePaused(gameId);
        }
        // A loop may have been claimed meanwhile (e.g. right after a human move); make it stop too.
        GameLoop late = runningGames.get(gameId);
        if (late != null && late != loop) {
            late.requestStop();
        }
    }

    public boolean isRunning(UUID gameId) {
        GameLoop loop = runningGames.get(gameId);
        return loop != null && loop.shouldContinue();
    }

    /**
     * The time limit per move for a game: the tournament's (null when it has no limit), or 60 seconds for a game
     * outside a tournament. Deliberately no ternary: mixing {@code Integer} and {@code int} would unbox null.
     */
    static Integer moveTimeoutSecondsFor(Game game) {
        if (game.tournament == null) {
            return Tournament.DEFAULT_MOVE_TIMEOUT_SECONDS;
        }
        return game.tournament.moveTimeoutSeconds;
    }

    void runGameLoop(UUID gameId, GameLoop loop, String lastMoveSan, String lastMessage) {
        GameConversationService.ConversationState conversation = gameConversationService.initializeConversation(gameId);
        Board board = conversation.board();
        List<OpenRouterService.ChatMsg> whiteHistory = conversation.whiteHistory();
        List<OpenRouterService.ChatMsg> blackHistory = conversation.blackHistory();

        while (loop.shouldContinue()) {
            if (chessService.isGameOver(board)) {
                endGame(gameId, board);
                return;
            }

            String sideToMove = chessService.getSideToMove(board);
            int moveNumber = chessService.getMoveNumber(board);
            List<String> legalMoves = chessService.getLegalMovesAsSan(board);
            String fen = board.getFen();
            // This loop's request context, and with it its Hibernate session, lasts for the whole game, while moves,
            // costs and tournament settings are committed by other transactions. The session would keep returning the
            // game and tournament it loaded first, so every turn starts from an empty session and reads them anew.
            gameRepository.detachAll();
            Game game = gameRepository.findById(gameId);
            String pgn = game.pgn;
            String modelId = "WHITE".equals(sideToMove) ? game.whiteModelId : game.blackModelId;
            String playerName = "WHITE".equals(sideToMove) ? game.whitePlayerName : game.blackPlayerName;
            String opponentName = "WHITE".equals(sideToMove) ? game.blackPlayerName : game.whitePlayerName;
            List<OpenRouterService.ChatMsg> history = "WHITE".equals(sideToMove) ? whiteHistory : blackHistory;

            if (isHumanTurn(game, sideToMove)) {
                gameTurnService.broadcastHumanTurnReady(gameId, game.totalCostUsd, sideToMove);
                return;
            }

            boolean isFirstMove = moveNumber == 1 && "WHITE".equals(sideToMove);

            int maxRetries = game.tournament != null ? game.tournament.maxRetries : 3;
            Integer moveTimeoutSeconds = moveTimeoutSecondsFor(game);
            int maxOutputTokens = game.tournament != null
                    ? game.tournament.maxOutputTokens : Tournament.DEFAULT_MAX_OUTPUT_TOKENS;
            // A changed chat mode or resignation setting applies from this turn on: rebuild both prompts so prompt,
            // answer handling and the mode and prompt version recorded on the move agree.
            if (GameConversationService.configuredChatMode(game) != conversation.chatMode()
                    || GameConversationService.configuredResignationAllowed(game)
                    != conversation.resignationAllowed()) {
                conversation = gameConversationService.refreshSystemPrompts(game, conversation);
            }
            GameConversationService.PromptContext prompt =
                    "WHITE".equals(sideToMove) ? conversation.whitePrompt() : conversation.blackPrompt();
            GameTurnService.TurnResult turnResult = gameTurnService.playTurn(new GameTurnService.TurnRequest(
                    gameId,
                    pgn,
                    fen,
                    board,
                    sideToMove,
                    moveNumber,
                    legalMoves,
                    modelId,
                    playerName,
                    opponentName,
                    history,
                    lastMoveSan,
                    lastMessage,
                    isFirstMove,
                    maxRetries,
                    moveTimeoutSeconds,
                    prompt.version(),
                    prompt.hash(),
                    game.totalCostUsd,
                    loop::shouldContinue,
                    maxOutputTokens,
                    prompt.chatMode(),
                    conversation.chatMode(),
                    prompt.resignationOffered()
            ));

            if (turnResult.isTerminal() || turnResult.pausedGame()) {
                return;
            }

            if (!turnResult.moveRecorded()) {
                break;
            }

            lastMoveSan = turnResult.moveSan();
            lastMessage = turnResult.message();
        }

        // Game was paused
        gameLifecycleService.markGamePaused(gameId);
    }

    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        gameMoveService.updateMoveEvaluation(moveId, result);
    }

    void endGame(UUID gameId, Board board) {
        ChessService.GameEndInfo endInfo = chessService.getGameEndInfo(board);
        gameLifecycleService.completeGame(gameId, endInfo);
    }

    public void overrideMove(UUID gameId, String san) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != GameStatus.PAUSED || runningGames.containsKey(gameId)) {
            throw new GameStateConflictException("Pause the game before overriding moves");
        }
        gameMoveService.applyOverrideMove(gameId, san, game.currentFen);
    }

    public void submitHumanMove(UUID gameId, String san, String from, String to, String promotion, String message) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != GameStatus.IN_PROGRESS) {
            throw new WebApplicationException("Game is not awaiting a human move", Response.Status.CONFLICT);
        }
        if (runningGames.containsKey(gameId)) {
            throw new GameStateConflictException("Game is still processing a turn");
        }
        boolean hasSan = san != null && !san.isBlank();
        boolean hasCoordinates = from != null && !from.isBlank() && to != null && !to.isBlank();
        if (!hasSan && !hasCoordinates) {
            throw new IllegalArgumentException("Move is required");
        }

        Board board = chessService.boardFromFen(game.currentFen);
        String sideToMove = chessService.getSideToMove(board);
        if (!isHumanTurn(game, sideToMove)) {
            throw new WebApplicationException("It is not a human-controlled turn", Response.Status.CONFLICT);
        }

        int moveNumber = chessService.getMoveNumber(board);
        ChessService.ValidMoveResult result = hasCoordinates
                ? chessService.validateAndApplyCoordinates(board, from, to, promotion)
                : chessService.validateAndApply(board, san.trim());
        if (!result.valid()) {
            throw new IllegalArgumentException("Invalid move: " + result.error());
        }

        String playerName = "WHITE".equals(sideToMove) ? game.whitePlayerName : game.blackPlayerName;
        gameMoveService.recordHumanMove(
                gameId,
                moveNumber,
                sideToMove,
                result.san(),
                result.fen(),
                playerName,
                message,
                game.currentFen
        );

        if (chessService.isGameOver(board)) {
            endGame(gameId, board);
            return;
        }

        continueAfterHumanMove(gameId, result.san(), message);
    }

    private boolean isHumanTurn(Game game, String color) {
        var participant = "WHITE".equals(color) ? game.whiteParticipant : game.blackParticipant;
        return participant != null && participant.controlType == dev.aichessarena.entity.TournamentParticipant.ControlType.HUMAN;
    }

    /** Claims the game for a new loop; returns null if a loop (running or stopping) already owns it. */
    GameLoop tryClaimLoop(UUID gameId) {
        GameLoop loop = new GameLoop();
        return runningGames.putIfAbsent(gameId, loop) == null ? loop : null;
    }

    /** Releases the claim only if it still belongs to {@code loop}. */
    void releaseLoop(UUID gameId, GameLoop loop) {
        runningGames.remove(gameId, loop);
    }

}
