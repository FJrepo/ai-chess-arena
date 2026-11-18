package dev.aichessarena.service;

import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import com.github.bhlangonijr.chesslib.Board;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class GameEngineService {

    private static final Logger LOG = Logger.getLogger(GameEngineService.class);

    @Inject
    ChessService chessService;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    ChatMessageRepository chatMessageRepository;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameMoveService gameMoveService;

    @Inject
    GameTurnService gameTurnService;

    @Inject
    GameConversationService gameConversationService;

    @Inject
    GameConfigurationHistory configurations;

    @Inject
    StockfishOpponentService stockfishOpponent;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    @Inject
    GameRunOwnership runOwnership;

    /**
     * One entry per game loop that is alive. The entry belongs to exactly one loop (identity matters):
     * a pause only flags it, and only the owning loop removes it when it exits, so a resumed game can
     * never run two loops at once.
     */
    private final Map<UUID, GameLoop> runningGames = new ConcurrentHashMap<>();

    /** Only human waits retain a conversation; active loops own theirs exclusively. Never persisted. */
    private final Map<UUID, GameConversationService.ConversationState> humanConversations = new ConcurrentHashMap<>();

    static final class GameLoop {
        /** This loop's fencing token, stored as the game's run owner while the loop owns the game. */
        private final UUID token = UUID.randomUUID();
        private volatile boolean stopRequested;
        /** The loop failed and could not commit its pause: its lease is kept for the safety tick. */
        private volatile boolean pauseFailed;

        UUID token() {
            return token;
        }

        synchronized void requestStop() {
            stopRequested = true;
        }

        boolean shouldContinue() {
            return !stopRequested;
        }
    }

    /**
     * Starts a WAITING game or resumes a PAUSED game immediately.
     *
     * @throws WebApplicationException 409 if the game is running, in progress or finished; 404 if it does not exist
     */
    public GameDto startGame(UUID gameId) {
        return startGame(gameId, false, false, null);
    }

    /** Allows a paused game to queue when all slots are occupied or reserved. */
    public GameDto startGame(UUID gameId, boolean queueIfFull) {
        return startGame(gameId, false, queueIfFull, null);
    }

    /**
     * Like {@link #startGame(UUID, boolean)}; {@code acknowledgedRequest} (the game's
     * {@code uncertainRequestSentAt}) is the operator's statement that spend for that unresolved model request was
     * checked, the only way to resume such a game.
     *
     * @throws GameStateConflictException 409 if the game has an unresolved request and nothing acknowledges it (the
     *         game is then held in the inspection pause), or if an acknowledgement finds nothing to acknowledge
     */
    public GameDto startGame(UUID gameId, boolean queueIfFull, java.time.Instant acknowledgedRequest) {
        return startGame(gameId, false, queueIfFull, acknowledgedRequest);
    }

    /** Returns null while an earlier loop still owns the game, or while it has an unresolved request. */
    public GameDto startScheduledGame(UUID gameId) {
        return startGame(gameId, true, false, null);
    }

    static final String UNRESOLVED_START_CONFLICT = "A model request of this game may already have been charged. "
            + "Check spend, then resume with acknowledgeUncertainRequest set to its uncertainRequestSentAt";

    private GameDto startGame(UUID gameId, boolean automatic, boolean queueIfFull,
                              java.time.Instant acknowledgedRequest) {
        GameLoop loop = new GameLoop();
        GameDto started;
        try {
            started = gameLifecycleService.markGameStarted(gameId, automatic, queueIfFull,
                    () -> runningGames.putIfAbsent(gameId, loop) == null, loop.token(), acknowledgedRequest);
        } catch (RuntimeException e) {
            runningGames.remove(gameId, loop);
            throw e;
        }
        if (started != null && started.uncertainRequest()) {
            // Held in the inspection pause (committed unless the caller's transaction rolls back); no loop starts.
            runningGames.remove(gameId, loop);
            if (automatic) return null;
            throw new GameStateConflictException(UNRESOLVED_START_CONFLICT);
        }
        if (started == null || started.resumeRequestedAt() != null) return started;
        humanConversations.remove(gameId);
        if (transactionRegistry != null && transactionRegistry.getTransactionStatus() == Status.STATUS_ACTIVE) {
            transactionRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {}

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        spawnGameLoop(gameId, loop, null, null);
                    } else {
                        runningGames.remove(gameId, loop);
                    }
                }
            });
        } else {
            spawnGameLoop(gameId, loop, null, null);
        }
        return started;
    }

    void spawnGameLoop(UUID gameId, GameLoop loop, String lastMoveSan, String lastMessage) {
        // Every write this loop makes is fenced by its token (see GameRunOwnership), including its final pause.
        GameRunOwnership.Owner owner = new GameRunOwnership.Owner(gameId, loop.token());
        Thread.startVirtualThread(() -> ScopedValue.where(GameRunOwnership.CURRENT, owner).run(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                runGameLoop(gameId, loop, lastMoveSan, lastMessage);
            } catch (Exception e) {
                LOG.errorf(e, "Game loop failed for game %s", gameId);
                pauseAfterLoopFailure(gameId, loop, e);
            } finally {
                finishLoop(gameId, loop);
                if (requestContext.isActive()) {
                    requestContext.terminate();
                }
            }
        }));
    }

    /**
     * Ends a loop. The durable lease is released only once the game's final state is committed (its pause, and the
     * spend-check hold for an unresolved request); otherwise the lease is kept, so the safety tick recovers the game
     * after it expires.
     */
    private void finishLoop(UUID gameId, GameLoop loop) {
        synchronized (loop) {
            if (loop.shouldContinue()) {
                if (holdUnresolvedRequest(gameId) && !loop.pauseFailed) {
                    releaseOwnership(gameId, loop);
                }
                runningGames.remove(gameId, loop);
                return;
            }
        }
        humanConversations.remove(gameId);
        // Keep ownership until this loop's final pause check, so it cannot pause a newly resumed loop. The hold also
        // covers a game paused meanwhile (e.g. with its run), which the pause itself leaves alone.
        boolean settled = pauseQuietly(gameId) && holdUnresolvedRequest(gameId) && !loop.pauseFailed;
        if (settled) {
            releaseOwnership(gameId, loop);
        }
        runningGames.remove(gameId, loop);
    }

    /**
     * An answer still missing when a loop ends will never be recorded: hold the game for the operator. Returns whether
     * that check committed.
     */
    private boolean holdUnresolvedRequest(UUID gameId) {
        try {
            gameLifecycleService.pauseEndingLoop(gameId, null);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not check game %s for an unresolved request; keeping its lease", gameId);
            return false;
        }
    }

    /** A failed release leaves the lease to expire; the scheduler's safety tick then recovers the game. */
    private void releaseOwnership(UUID gameId, GameLoop loop) {
        if (runOwnership == null) return;
        try {
            runOwnership.release(gameId, loop.token());
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not release game %s", gameId);
        }
    }

    private boolean pauseQuietly(UUID gameId) {
        try {
            gameLifecycleService.markGamePaused(gameId);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not pause game %s", gameId);
            return false;
        }
    }

    private void pauseAfterLoopFailure(UUID gameId, GameLoop loop, Exception failure) {
        humanConversations.remove(gameId);
        try {
            // With an unresolved request this commits the inspection pause instead: a plain resume must not re-send.
            gameLifecycleService.pauseEndingLoop(gameId,
                    "The game loop stopped unexpectedly (" + failure.getClass().getSimpleName() + "); resume to retry");
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not pause game %s after loop failure", gameId);
            loop.pauseFailed = true;
        }
    }

    /**
     * Requests a pause. A running loop is only flagged and pauses itself after its current turn
     * (repeated requests are harmless); a game waiting for a human move is paused immediately.
     */
    public void pauseGame(UUID gameId) {
        humanConversations.remove(gameId);
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

    /** Flags the current owner without releasing its claim; only that loop can release it. */
    public void requestStop(UUID gameId) {
        GameLoop loop = runningGames.get(gameId);
        if (loop != null) {
            loop.requestStop();
        }
        humanConversations.remove(gameId);
    }

    /** Captures the loop and human wait that belong to the state being paused under the tournament lock. */
    Runnable captureStop(UUID gameId) {
        GameLoop loop = runningGames.get(gameId);
        var conversation = humanConversations.get(gameId);
        return () -> {
            if (loop != null) loop.requestStop();
            if (conversation != null) {
                humanConversations.computeIfPresent(gameId, (id, current) -> current == conversation ? null : current);
            }
        };
    }

    /** A fresh scalar read avoids retaining a game deleted or finished since this turn began. */
    void retainHumanConversation(UUID gameId, GameLoop loop, GameConversationService.ConversationState conversation) {
        boolean eligible = gameRepository.findConversationEligibleIds(Set.of(gameId)).contains(gameId);
        synchronized (loop) {
            // A commit can stop the loop after the eligibility read. Serialize the put with its stop flag.
            if (eligible && loop.shouldContinue()) {
                humanConversations.put(gameId, conversation);
            }
        }
    }

    /** Safety net for missed cleanup, using one query for the retained waits and none for an empty cache. */
    void sweepRetainedConversations() {
        var snapshot = Map.copyOf(humanConversations);
        if (snapshot.isEmpty()) return;
        Set<UUID> eligible = gameRepository.findConversationEligibleIds(snapshot.keySet());
        snapshot.forEach((id, conversation) -> {
            if (!eligible.contains(id)) humanConversations.remove(id, conversation);
        });
    }

    /**
     * The time limit per move for a game: the tournament's (null when it has no limit), or
     * {@link Tournament#DEFAULT_MOVE_TIMEOUT_SECONDS} (null: no limit) for a game outside a tournament.
     */
    static Integer moveTimeoutSecondsFor(Game game) {
        if (game.tournament == null) {
            return Tournament.DEFAULT_MOVE_TIMEOUT_SECONDS;
        }
        return game.tournament.moveTimeoutSeconds;
    }

    void runGameLoop(UUID gameId, GameLoop loop, String lastMoveSan, String lastMessage) {
        GameConversationService.ConversationState retained = humanConversations.remove(gameId);
        GameConversationService.ConversationState conversation = retained == null
                ? gameConversationService.initializeConversation(gameId)
                : gameConversationService.continueConversation(gameId, retained);
        Board board = conversation.board();
        List<OpenRouterService.ChatMsg> whiteHistory = conversation.whiteHistory();
        List<OpenRouterService.ChatMsg> blackHistory = conversation.blackHistory();

        // Starting, resuming and continuing after a human move all use the final committed ply as context.
        List<Move> moves = moveRepository.findByGameId(gameId);
        Move lastMove = moves.isEmpty() ? null : moves.getLast();
        lastMoveSan = lastMove != null ? lastMove.san : null;
        ChatMessage lastChat = lastMove != null && !lastMove.isBook
                ? chatMessageRepository.findByPly(gameId, lastMove.moveNumber, lastMove.color) : null;
        lastMessage = lastChat != null ? lastChat.message : null;

        while (loop.shouldContinue()) {
            if (chessService.isGameOver(board)) {
                endGame(gameId, board);
                return;
            }

            String sideToMove = chessService.getSideToMove(board);
            int moveNumber = chessService.getMoveNumber(board);
            String fen = board.getFen();
            // This loop's request context, and with it its Hibernate session, lasts for the whole game, while moves,
            // costs and tournament settings are committed by other transactions. The session would keep returning the
            // game and tournament it loaded first, so every turn starts from an empty session and reads them anew.
            gameRepository.detachAll();
            Game game = gameRepository.findById(gameId);
            if (game == null || game.status != GameStatus.IN_PROGRESS
                    || GameLifecycleService.isTournamentCancelled(game)) {
                return;
            }
            if (WinAdjudication.enabledFor(game) && gameLifecycleService.adjudicateIfEligible(gameId, fen)) {
                return;
            }
            String pgn = game.pgn;
            String modelId = "WHITE".equals(sideToMove) ? game.whiteModelId : game.blackModelId;
            String playerName = "WHITE".equals(sideToMove) ? game.whitePlayerName : game.blackPlayerName;
            String opponentName = "WHITE".equals(sideToMove) ? game.blackPlayerName : game.whitePlayerName;
            List<OpenRouterService.ChatMsg> history = "WHITE".equals(sideToMove) ? whiteHistory : blackHistory;

            if (isHumanTurn(game, sideToMove)) {
                retainHumanConversation(gameId, loop, conversation);
                gameTurnService.broadcastHumanTurnReady(gameId, game.totalCostUsd, sideToMove);
                if (!loop.shouldContinue()) {
                    humanConversations.remove(gameId);
                }
                return;
            }

            if (game.playerType(sideToMove) == Game.PlayerType.STOCKFISH) {
                String played = playStockfishTurn(game, board, fen, sideToMove, moveNumber, loop);
                if (played == null) return;
                lastMoveSan = played;
                lastMessage = null;
                continue;
            }

            boolean isFirstMove = moveNumber == 1 && "WHITE".equals(sideToMove);

            int maxAttempts = game.tournament != null ? game.tournament.maxAttempts : 3;
            Integer moveTimeoutSeconds = moveTimeoutSecondsFor(game);
            int maxOutputTokens = game.tournament != null
                    ? game.tournament.maxOutputTokens : Tournament.DEFAULT_MAX_OUTPUT_TOKENS;
            // Compare resolved hashes every turn, including templates and participant/shared instructions.
            conversation = gameConversationService.refreshSystemPrompts(game, conversation);
            GameConversationService.PromptContext prompt =
                    "WHITE".equals(sideToMove) ? conversation.whitePrompt() : conversation.blackPrompt();
            GameTurnService.TurnResult turnResult = gameTurnService.playTurn(new GameTurnService.TurnRequest(
                    gameId,
                    pgn,
                    fen,
                    board,
                    sideToMove,
                    moveNumber,
                    game.hasLegalMoveHints() ? chessService.getLegalMovesAsSan(board) : null,
                    modelId,
                    playerName,
                    opponentName,
                    history,
                    lastMoveSan,
                    lastMessage,
                    isFirstMove,
                    maxAttempts,
                    moveTimeoutSeconds,
                    prompt.version(),
                    prompt.hash(),
                    game.totalCostUsd,
                    loop::shouldContinue,
                    maxOutputTokens,
                    prompt.chatMode(),
                    conversation.chatMode(),
                    prompt.resignationOffered(),
                    // Read with the game this turn, so a changed tournament level or participant override applies now.
                    game.requestedReasoningEffort(sideToMove),
                    game.tournament != null ? game.tournament.banterLevel : null,
                    game.statusRevision,
                    configurations == null ? null : configurations.capture(game),
                    game.isBenchmark()
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

    private String playStockfishTurn(Game game, Board board, String fen, String color, int moveNumber, GameLoop loop) {
        String bestMove;
        try {
            bestMove = game.isBenchmark()
                    ? stockfishOpponent.chooseMove(board, game.stockfishElo, game.tournament.benchmarkDefinition,
                            loop::shouldContinue)
                    : stockfishOpponent.chooseMove(board, game.stockfishElo, loop::shouldContinue);
        } catch (IOException | TimeoutException e) {
            LOG.warnf(e, "Stockfish opponent search failed for game %s", game.id);
            gameLifecycleService.pauseWithReason(game.id, game.isBenchmark()
                    ? "Stockfish could not play a move. Check the benchmark's engine build and NNUE weights, then resume."
                    : "Stockfish could not play a move. Check Stockfish and resume.");
            return null;
        }
        if (bestMove == null || !loop.shouldContinue()) return null;

        var played = chessService.validateAndApplyCoordinates(board, bestMove.substring(0, 2), bestMove.substring(2, 4),
                bestMove.length() > 4 ? bestMove.substring(4) : null);
        if (!played.valid()) {
            gameLifecycleService.pauseWithReason(game.id, "Stockfish returned an illegal move. Resume to retry.");
            return null;
        }
        try {
            gameMoveService.recordStockfishMove(game.id, color, moveNumber, played, fen, game.statusRevision,
                    loop::shouldContinue, chessService.getGameEndInfo(board));
        } catch (GameStateConflictException e) {
            LOG.debugf("Discarding stale Stockfish move in game %s", game.id);
            return null;
        }
        return played.san();
    }

    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        gameMoveService.updateMoveEvaluation(moveId, result);
    }

    void endGame(UUID gameId, Board board) {
        humanConversations.remove(gameId);
        ChessService.GameEndInfo endInfo = chessService.getGameEndInfo(board);
        gameLifecycleService.completeGame(gameId, endInfo);
    }

    public void overrideMove(UUID gameId, String san) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        GameLifecycleService.requireActiveTournament(game);
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
        GameLifecycleService.requireActiveTournament(game);
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

        Board board = game.isPractice()
                ? gameConversationService.initializeBoard(gameId, game)
                : chessService.boardFromFen(game.currentFen);
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
        // The loop for the next turn is claimed first and owns the game from the move's own commit on.
        GameLoop loop = tryClaimLoop(gameId);
        if (loop == null) {
            throw new GameStateConflictException("Game is still processing a turn");
        }
        try {
            gameMoveService.recordHumanMove(
                    gameId,
                    moveNumber,
                    sideToMove,
                    result.san(),
                    result.fen(),
                    playerName,
                    game.practiceKind == Game.PracticeKind.HUMAN_STOCKFISH ? null : message,
                    game.currentFen,
                    loop.token()
            );
        } catch (RuntimeException e) {
            releaseLoop(gameId, loop);
            throw e;
        }

        if (chessService.isGameOver(board)) {
            try {
                endGame(gameId, board);
            } catch (RuntimeException e) {
                // Keep the durable lease: once it expires, recovery completes the decided game.
                releaseLoop(gameId, loop);
                throw e;
            }
            releaseOwnership(gameId, loop);
            releaseLoop(gameId, loop);
            return;
        }
        spawnGameLoop(gameId, loop, result.san(), message);
    }

    private boolean isHumanTurn(Game game, String color) {
        return game.playerType(color) == Game.PlayerType.HUMAN;
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
