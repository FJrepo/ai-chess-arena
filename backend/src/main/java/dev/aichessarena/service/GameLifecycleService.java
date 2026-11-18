package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GameLifecycleService {

    private static final Logger LOG = Logger.getLogger(GameLifecycleService.class);
    static final int STATUS_DETAIL_MAX_LENGTH = 500;
    static final String RESTART_DETAIL = "Paused because the server restarted; resume to continue";

    @Inject
    OpenRouterKeyStore keys;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    ChatMessageRepository chatMessageRepository;

    @Inject
    TournamentService tournamentService;

    @Inject
    GameEngineService gameEngineService;

    @Inject
    GameWebSocket gameWebSocket;

    @Inject
    LiveGameUpdates liveUpdates;

    /** Null outside CDI (unit tests): then broadcasts go out at once. */
    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    @Inject
    TournamentAutoRunService autoRuns;

    @Inject
    TournamentRunScheduler runScheduler;

    @Inject
    GameConfigurationHistory configurations;

    @Inject
    GameRunOwnership runOwnership;

    @Inject
    GameConversationService conversations;

    @Inject
    ChessService chessService;

    /**
     * Starts a WAITING or PAUSED game under the tournament and game row locks, or records a queued resume.
     *
     * @return the game as it is after the transition
     * @throws WebApplicationException 404 if the game does not exist, 409 if it is in any other state
     */
    @Transactional
    public GameDto markGameStarted(UUID gameId) {
        return markGameStarted(gameId, false, false, () -> true);
    }

    @Transactional
    GameDto markGameStarted(UUID gameId, boolean automatic, boolean queueIfFull, BooleanSupplier claimLoop) {
        return markGameStarted(gameId, automatic, queueIfFull, claimLoop, null, null);
    }

    /**
     * Like {@link #markGameStarted(UUID, boolean, boolean, BooleanSupplier)}; a started game belongs to the loop with
     * token {@code runOwner} (null: no loop).
     *
     * <p>A game with an unresolved request (sent, its answer never recorded) never starts or queues without
     * {@code acknowledgedRequest}, the operator's statement that spend was checked for exactly that request (its
     * {@link GameDto#uncertainRequestSentAt()}): it is held in the inspection pause instead (committed) and returned
     * with {@link GameDto#uncertainRequest()} still set. Compared under the game lock, an acknowledgement clears the
     * record; it is rejected when there is nothing to acknowledge or it names another (e.g. newer) request.
     */
    @Transactional
    GameDto markGameStarted(UUID gameId, boolean automatic, boolean queueIfFull, BooleanSupplier claimLoop,
                            UUID runOwner, Instant acknowledgedRequest) {
        Game game = requireLockedGame(gameId);
        requireActiveTournament(game);
        if (keys != null && game.practiceKind != Game.PracticeKind.HUMAN_STOCKFISH) keys.requireKey();
        if (game.status != GameStatus.WAITING && game.status != GameStatus.PAUSED) {
            throw new WebApplicationException(startConflictMessage(game.status), Response.Status.CONFLICT);
        }
        if (game.requestSentAt == null && acknowledgedRequest != null) {
            throw new GameStateConflictException("This game has no unconfirmed model request; refresh and resume it");
        }
        if (acknowledgedRequest != null && !acknowledgedRequest.equals(game.requestSentAt)) {
            throw new GameStateConflictException(
                    "The unconfirmed model request has changed; refresh and check spend for the current one");
        }
        if (game.requestSentAt != null) {
            if (acknowledgedRequest == null) {
                holdForInspection(game, UNRESOLVED_REQUEST);
                return DtoMapper.toDto(game, false);
            }
            LOG.infof("Game %s: spend for the request sent at %s was checked by the operator", gameId,
                    game.requestSentAt);
            game.requestSentAt = null;
        }
        if (autoRuns != null && autoRuns.shouldQueueResume(game, automatic, queueIfFull)) {
            RunCapacity capacity;
            if (game.resumeRequestedAt == null) {
                game.resumeRequestedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                game.statusRevision++;
                capacity = announceStatus(game, null, null);
            } else {
                capacity = gameRepository.queuedResumeCapacity(game);
            }
            return DtoMapper.toDto(game, false, capacity);
        }
        if (!claimLoop.getAsBoolean()) {
            if (automatic) return null;
            throw new GameStateConflictException("The previous game loop is still running; try again shortly");
        }
        boolean capturedStart = configurations != null && game.status == GameStatus.WAITING && game.startedAt == null;
        if (capturedStart) {
            game.startingConfiguration = configurations.capture(game);
        }
        game.status = GameStatus.IN_PROGRESS;
        game.resumeRequestedAt = null;
        game.startedAt = game.startedAt != null ? game.startedAt : LocalDateTime.now();
        game.statusDetail = null;
        game.statusRevision++;
        if (runOwnership != null) {
            runOwnership.claim(game, runOwner);
        }
        gameRepository.persist(game);
        announceStatus(game, null, null);
        if (capturedStart && liveUpdates != null) {
            liveUpdates.changed(game, liveUpdates.change("gameDetailsChanged", gameId));
        }
        return DtoMapper.toDto(game, false);
    }

    @Transactional
    public GameDto cancelResumeRequest(UUID gameId, long statusRevision) {
        Game game = requireLockedGame(gameId);
        if (game.resumeRequestedAt == null || game.statusRevision != statusRevision) {
            throw new GameStateConflictException("The queued resume has changed; refresh and try again");
        }
        clearResumeRequest(game);
        return DtoMapper.toDto(game, false);
    }

    /** All callers hold the tournament row lock, including when advancing the final game. */
    void clearResumeRequests(UUID tournamentId) {
        for (Game game : gameRepository.list("tournament.id = ?1 and resumeRequestedAt is not null", tournamentId)) {
            clearResumeRequest(requireLockedGame(game.id));
        }
    }

    private void clearResumeRequest(Game game) {
        if (game.resumeRequestedAt != null) {
            game.resumeRequestedAt = null;
            game.statusRevision++;
            announceStatus(game, null, null);
        }
    }

    private Game requireLockedGame(UUID gameId) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        return game;
    }

    /** Called in the tournament cancellation transaction, under the tournament then game locks. */
    @Transactional
    public void cancelGame(UUID gameId) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || isTerminal(game)) {
            return;
        }
        game.status = GameStatus.CANCELLED;
        game.resumeRequestedAt = null;
        game.result = null;
        game.resultReason = null;
        game.advancedParticipant = null;
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        game.statusRevision++;
        gameRepository.persist(game);
        announceStatus(game, null, null);
    }

    /** Must be checked under the tournament/game locks by mutation paths, also for legacy inconsistent rows. */
    static void requireActiveTournament(Game game) {
        if (isTournamentCancelled(game)) {
            throw new GameStateConflictException("Tournament has been cancelled");
        }
    }

    static boolean isTournamentCancelled(Game game) {
        return game.tournament != null
                && game.tournament.status == TournamentStatus.CANCELLED;
    }

    /**
     * Games a restart may have interrupted: every game in progress, and every paused game (a pause, e.g. by the budget,
     * can land between a deciding move and its completion, or while a request is unanswered). Waiting games have made
     * no move since they were created. Called once at startup by {@link GameRecovery}.
     */
    @Transactional
    public List<UUID> gamesInterruptedByRestart() {
        return gameRepository.getEntityManager().createQuery("""
                select g.id from Game g
                where g.status = :inProgress or g.status = :paused
                order by g.id
                """, UUID.class)
                .setParameter("inProgress", GameStatus.IN_PROGRESS)
                .setParameter("paused", GameStatus.PAUSED)
                .getResultList();
    }

    /**
     * Games in progress or paused whose loop stopped renewing its lease, e.g. after it ended without being able to
     * commit its final state (or, after a human move that ended the game, its completion).
     */
    @Transactional
    public List<UUID> gamesWithExpiredLease() {
        return gameRepository.getEntityManager().createQuery("""
                select g.id from Game g
                where g.status in :open and g.runOwner is not null and g.runLeaseUntil <= :now
                order by g.id
                """, UUID.class)
                .setParameter("open", List.of(GameStatus.IN_PROGRESS, GameStatus.PAUSED))
                .setParameter("now", Instant.now())
                .getResultList();
    }

    /** Why a game's loop ended without finishing its turn, and what that means for its request in flight. */
    enum Interruption {
        RESTART("was in flight during a backend restart and may already have been charged"),
        EXPIRED_LEASE("was in flight when its game loop stopped and may already have been charged");

        private final String inFlight;

        Interruption(String inFlight) {
            this.inFlight = inFlight;
        }
    }

    // Why a model request needs the operator's spend check (see inspectionDetail).
    static final String UNRESOLVED_REQUEST = "has no recorded answer and may already have been charged";
    static final String DELIVERY_UNCERTAIN = "lost its connection after it was sent and may already have been charged";
    static final String UNRECORDED_ANSWER = "was answered, but the answer could not be saved, so its cost may be missing";
    static final String INSPECTION_SUFFIX = ". Check spend, then resume.";

    /**
     * Decides, under the tournament and game locks, how a game whose loop did not survive continues
     * ({@link TurnPolicy#afterInterruption}). For {@link TurnPolicy.Action#RESUME} the game now belongs to the loop with
     * {@code token}, which the caller starts after this transaction commits; any earlier owner is fenced out.
     *
     * @return the action taken, or null when the game needed no decision (no longer interrupted, or legacy rows of a
     *         cancelled tournament, which are paused)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public TurnPolicy.Action recoverInterruptedGame(UUID gameId, UUID token, Interruption cause) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null) {
            return null;
        }
        boolean inProgress = game.status == GameStatus.IN_PROGRESS;
        boolean open = inProgress || game.status == GameStatus.PAUSED;
        boolean candidate = open
                && (cause == Interruption.RESTART || GameRunOwnership.leaseExpired(game, Instant.now()));
        if (!candidate) {
            return null;
        }
        if (!inProgress) {
            // A paused game only needs finishing when decided, or holding while a request is unresolved.
            TurnPolicy.Action held = null;
            if (!isTournamentCancelled(game) && !completeIfDecided(game) && game.requestSentAt != null) {
                holdForInspection(game, cause.inFlight);
                held = TurnPolicy.Action.PAUSE_IN_FLIGHT;
            }
            if (runOwnership != null && GameRunOwnership.leaseExpired(game, Instant.now())) {
                runOwnership.claim(game, null);
            }
            return held;
        }
        if (isTournamentCancelled(game)) {
            // Legacy inconsistent row: nothing can run in a cancelled tournament.
            pauseLockedGame(game, RESTART_DETAIL);
            return null;
        }
        if (completeIfDecided(game)) {
            // The deciding move was committed, but the loop stopped before it could finish the game.
            if (runOwnership != null) {
                runOwnership.claim(game, null);
            }
            LOG.infof("Game %s was already decided when its loop was interrupted %s; completed it", gameId, cause);
            return null;
        }
        String side = sideToMove(game.currentFen);
        Game.PlayerType player = game.playerType(side);
        boolean providerConnected = keys == null || !keys.key().isBlank();
        TurnPolicy.Action action = TurnPolicy.afterInterruption(
                game.requestSentAt != null, player == null ? Game.PlayerType.HUMAN : player, providerConnected);
        switch (action) {
            case RESUME -> {
                if (runOwnership != null) {
                    runOwnership.claim(game, token);
                }
                // The new loop starts a new turn clock; the interrupted turn's clock is void.
                game.clearTurn();
                if (liveUpdates != null) {
                    liveUpdates.changed(game);
                }
                LOG.infof("Game %s resumes after its loop was interrupted (%s)", gameId, cause);
            }
            case AWAIT_HUMAN -> {
                if (runOwnership != null) {
                    runOwnership.claim(game, null);
                }
            }
            case PAUSE_IN_FLIGHT -> pauseLockedForInspection(game, cause.inFlight);
            case PAUSE_DISCONNECTED -> pauseLockedGame(game, DISCONNECTED_DETAIL);
            default -> throw new IllegalStateException("Unexpected recovery action " + action);
        }
        return action;
    }

    static final String DISCONNECTED_DETAIL =
            "The backend restarted without an OpenRouter connection. Connect OpenRouter, then resume.";

    /**
     * Completes a game whose committed position is already decided (mate, stalemate, draw rules or an eligible
     * adjudication), as the loop would have done. The caller holds the locks.
     */
    private boolean completeIfDecided(Game game) {
        if (isTerminal(game) || conversations == null || chessService == null) {
            return false;
        }
        Board board = conversations.initializeBoard(game.id, game);
        if (chessService.isGameOver(board)) {
            completeLockedGame(game, chessService.getGameEndInfo(board));
            return true;
        }
        return WinAdjudication.enabledFor(game) && adjudicateLocked(game);
    }

    /** "A model request (model, side, move n) {clause}. Check spend, then resume." for the side to move. */
    static String inspectionDetail(Game game, String clause) {
        String side = sideToMove(game.currentFen);
        String model = "WHITE".equals(side) ? game.whiteModelId : game.blackModelId;
        String player = "WHITE".equals(side) ? "White" : "Black";
        return ("A model request (%s, %s, move %d) %s" + INSPECTION_SUFFIX)
                .formatted(model == null ? "unknown model" : model, player, fullMoveNumber(game.currentFen), clause);
    }

    /**
     * Pauses a game the operator must inspect before another request is sent, for a request that {@code clause}
     * describes ({@link #UNRESOLVED_REQUEST}, {@link #DELIVERY_UNCERTAIN}, {@link #UNRECORDED_ANSWER}). Also applies to
     * a game already paused meanwhile, whose reason it replaces.
     */
    @Transactional
    public void pauseForInspection(UUID gameId, String clause) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || GameRunOwnership.staleWorker(game)
                || (game.status != GameStatus.IN_PROGRESS && game.status != GameStatus.PAUSED)) {
            return;
        }
        pauseLockedForInspection(game, clause);
    }

    void pauseLockedForInspection(Game game, String clause) {
        pauseLockedGame(game, inspectionDetail(game, clause));
    }

    /**
     * Keeps a game with an unresolved request in the inspection pause: pauses it with {@code clause} unless it already
     * is (paused with an inspection reason and no queued resume). The request record stays until the operator
     * acknowledges it.
     */
    void holdForInspection(Game game, String clause) {
        boolean held = game.status == GameStatus.PAUSED && game.resumeRequestedAt == null
                && game.statusDetail != null && game.statusDetail.endsWith(INSPECTION_SUFFIX);
        if (!held) {
            pauseLockedForInspection(game, clause);
        }
    }

    /**
     * A loop is ending: if its game still has an unresolved request, nothing will record the answer any more, so the
     * game is held in the inspection pause; otherwise an in-progress game pauses with {@code detail} (null: none).
     */
    @Transactional
    public void pauseEndingLoop(UUID gameId, String detail) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || GameRunOwnership.staleWorker(game)) {
            return;
        }
        if (game.requestSentAt != null && (game.status == GameStatus.IN_PROGRESS || game.status == GameStatus.PAUSED)) {
            holdForInspection(game, UNRESOLVED_REQUEST);
        } else if (detail != null && game.status == GameStatus.IN_PROGRESS) {
            pauseLockedGame(game, detail);
        }
    }

    /**
     * Pauses a game for the operator with {@code detail}. A queued resume is withdrawn, so the game is not started
     * without inspection. An unresolved request record stays (see {@link #markGameStarted}).
     */
    private void pauseLockedGame(Game game, String detail) {
        game.status = GameStatus.PAUSED;
        game.statusDetail = boundDetail(detail);
        game.resumeRequestedAt = null;
        game.statusRevision++;
        if (runOwnership != null) {
            runOwnership.claim(game, null);
        }
        gameRepository.persist(game);
        LOG.warnf("Game %s paused: %s", game.id, game.statusDetail);
        announceStatus(game, null, null);
    }

    static String sideToMove(String fen) {
        String[] fields = fen == null ? new String[0] : fen.trim().split("\\s+");
        return fields.length > 1 && "b".equals(fields[1]) ? "BLACK" : "WHITE";
    }

    private static int fullMoveNumber(String fen) {
        String[] fields = fen == null ? new String[0] : fen.trim().split("\\s+");
        try {
            return fields.length > 5 ? Integer.parseInt(fields[5]) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Transactional
    public void markGamePaused(UUID gameId) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS || GameRunOwnership.staleWorker(game)) {
            return;
        }
        if (game.requestSentAt != null) {
            // Called once no loop works on the game: an answer still missing will not be recorded any more.
            holdForInspection(game, UNRESOLVED_REQUEST);
            return;
        }
        game.status = GameStatus.PAUSED;
        game.statusDetail = null;
        game.statusRevision++;
        gameRepository.persist(game);
        announceStatus(game, null, null);
    }

    /**
     * Pauses a running game because of an operational problem that is not the model's fault
     * (for example, OpenRouter rejected the API key). The reason stays visible until the game is started again.
     */
    @Transactional
    public void pauseForProviderFailure(UUID gameId, String detail) {
        pauseWithReason(gameId, detail);
    }

    /** Pauses an IN_PROGRESS game and keeps {@code detail} visible until it is started again. */
    @Transactional
    public void pauseWithReason(UUID gameId, String detail) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS || GameRunOwnership.staleWorker(game)) {
            return;
        }
        String boundedDetail = boundDetail(detail);
        game.status = GameStatus.PAUSED;
        game.statusDetail = boundedDetail;
        game.statusRevision++;
        gameRepository.persist(game);
        LOG.warnf("Game %s paused: %s", gameId, boundedDetail);
        announceStatus(game, null, null);
    }

    @Transactional
    public void completeGame(UUID gameId, ChessService.GameEndInfo endInfo) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || isTerminal(game) || isTournamentCancelled(game) || GameRunOwnership.staleWorker(game)) {
            return;
        }
        completeLockedGame(game, endInfo);
    }

    void completeLockedGame(Game game, ChessService.GameEndInfo endInfo) {
        game.status = GameStatus.COMPLETED;
        game.resumeRequestedAt = null;
        game.result = GameResult.valueOf(endInfo.result());
        game.resultReason = ResultReason.valueOf(endInfo.reason());
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        game.statusRevision++;
        gameRepository.persist(game);

        announceStatus(game, null, null);

        advanceTournamentWinnerIfNeeded(game);
    }

    /**
     * Checks the stored window under the game lock and uses the normal completion path. Never waits for the engine.
     * Rechecking status and position protects against a concurrent pause, completion or move.
     */
    @Transactional
    public boolean adjudicateIfEligible(UUID gameId, String expectedFen) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null) {
            return false;
        }
        gameRepository.refreshForUpdate(game);
        if (game.status != GameStatus.IN_PROGRESS || isTournamentCancelled(game) || GameRunOwnership.staleWorker(game)
                || !GameMoveService.samePosition(game.currentFen, expectedFen)
                || !WinAdjudication.enabledFor(game)) {
            return false;
        }
        return adjudicateLocked(game);
    }

    /** Completes the locked game by adjudication when its stored evaluations decide it. */
    private boolean adjudicateLocked(Game game) {
        String winner = WinAdjudication.winner(moveRepository.findByGameId(game.id));
        if (winner == null) {
            return false;
        }
        if (configurations != null) {
            game.adjudicationConfigurationRevision = configurations.recordRevision(game.id, configurations.capture(game)).number();
        }
        completeLockedGame(game, new ChessService.GameEndInfo(winner + "_WINS", "ADJUDICATION"));
        return true;
    }

    /**
     * Ends an IN_PROGRESS game because {@code resigningColor} resigned: COMPLETED, the opponent wins, reason
     * RESIGNATION. Like a recorded move it requires the position the model answered for, and in the same transaction
     * it adds the answer's cost to the game and stores the resigning side's last words ({@code message}, null for
     * none) as a chat message of the ply it did not play. Tournament advancement follows as for any other result.
     *
     * @throws GameStateConflictException when the game is no longer in progress or the position changed meanwhile
     */
    @Transactional
    public void resign(Resignation resignation) {
        UUID gameId = resignation.gameId();
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS) {
            throw new GameStateConflictException("Game is no longer in progress");
        }
        requireActiveTournament(game);
        GameRunOwnership.requireOwner(game);
        if (!GameMoveService.samePosition(game.currentFen, resignation.expectedFen())) {
            throw new GameStateConflictException("Position changed while the model was thinking");
        }
        GameRunOwnership.answerRecorded(game);
        if (resignation.costUsd() != null) {
            game.totalCostUsd = game.totalCostUsd.add(resignation.costUsd());
        }
        String color = resignation.color();
        String message = resignation.message();
        ChatMessage storedChat = chatMessageRepository.persistMessage(game, resignation.moveNumber(), color, resignation.modelId(), message);
        UUID chatId = storedChat == null ? null : storedChat.id;
        game.status = GameStatus.COMPLETED;
        game.resumeRequestedAt = null;
        game.result = "WHITE".equals(color) ? GameResult.BLACK_WINS : GameResult.WHITE_WINS;
        game.resultReason = ResultReason.RESIGNATION;
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        game.statusRevision++;
        gameRepository.persist(game);
        LOG.infof("Game %s: %s resigned at move %d", gameId, color, resignation.moveNumber());

        // The last words go out before the game-over update, in the same callback, so their order is kept.
        Runnable lastWords = chatId == null ? null
                : () -> gameWebSocket.broadcastChat(gameId, chatId, resignation.moveNumber(), color,
                        resignation.modelId(), resignation.playerName(), message);
        if (liveUpdates != null && chatId != null) {
            liveUpdates.changed(game, liveUpdates.entityChange("committedChat", gameId, "chat",
                    DtoMapper.toDto(storedChat)));
        }
        announceStatus(game, lastWords, null);
        advanceTournamentWinnerIfNeeded(game);
    }

    /**
     * A model's resignation: the ply it did not play ({@code moveNumber}, {@code color}), the position it answered for,
     * its last words (null for none, or when chat is off) and the answer's cost (null when unknown).
     */
    public record Resignation(
            UUID gameId,
            int moveNumber,
            String color,
            String modelId,
            String playerName,
            String message,
            BigDecimal costUsd,
            String expectedFen
    ) {}

    @Transactional
    public void forfeitInvalidMoves(UUID gameId, String forfeitColor, int maxAttempts) {
        Game game = markForfeit(gameId, forfeitColor, ResultReason.FORFEIT_INVALID_MOVES);
        if (game == null) {
            return;
        }
        broadcastForfeitAfterCommit(game, forfeitColor,
                "Failed to produce a legal move after %d attempts".formatted(maxAttempts));
        advanceTournamentWinnerIfNeeded(game);
    }

    @Transactional
    public void forfeitTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
        Game game = markForfeit(gameId, forfeitColor, ResultReason.TIMEOUT);
        if (game == null) {
            return;
        }
        broadcastForfeitAfterCommit(game, forfeitColor,
                "Move timed out after %d seconds".formatted(moveTimeoutSeconds));
        advanceTournamentWinnerIfNeeded(game);
    }

    private void broadcastForfeitAfterCommit(Game game, String forfeitColor, String reason) {
        UUID gameId = game.id;
        announceStatus(game, null, () -> gameWebSocket.broadcastForfeit(gameId, forfeitColor, reason));
    }

    /**
     * Announces the game's status after the commit, with the status revision written in the same transaction
     * (clients drop an update older than one they have; see {@link Game#statusRevision}). {@code first} and
     * {@code then} run right before and after it in the same callback, for messages that belong to the change.
     * Everything sent is read now, while the game is attached.
     */
    private RunCapacity announceStatus(Game game, Runnable first, Runnable then) {
        if (runScheduler != null && game.tournament != null) {
            UUID tournamentId = game.tournament.id;
            AfterCommit.run(transactionRegistry, () -> runScheduler.changed(tournamentId));
        }

        UUID gameId = game.id;
        String status = game.status.name();
        String result = game.result != null ? game.result.name() : null;
        String resultReason = game.resultReason != null ? game.resultReason.name() : null;
        String detail = game.statusDetail;
        Instant resumeRequestedAt = game.resumeRequestedAt;
        RunCapacity capacity = resumeRequestedAt == null ? null : gameRepository.queuedResumeCapacity(game);
        long revision = game.statusRevision;
        int moves = totalMoves(gameId);
        BigDecimal cost = game.totalCostUsd;
        boolean terminal = isTerminal(game);
        game.clearTurn();
        if (liveUpdates != null) {
            liveUpdates.changed(game, liveUpdates.statusChange(gameId, status, result, resultReason, moves,
                    cost, detail, revision, resumeRequestedAt, capacity));
        }
        afterCommit(() -> {
            if (terminal && gameEngineService != null) {
                gameEngineService.requestStop(gameId);
            }
            if (first != null) {
                first.run();
            }
            gameWebSocket.broadcastStatusChange(
                    gameId, status, result, resultReason, moves, cost, detail, revision, resumeRequestedAt, capacity);
            if (then != null) {
                then.run();
            }
        });
        return capacity;
    }

    /** Runs {@code broadcast} after the commit (see {@link AfterCommit}). */
    void afterCommit(Runnable broadcast) {
        AfterCommit.run(transactionRegistry, broadcast);
    }

    private Game markForfeit(UUID gameId, String forfeitColor, ResultReason reason) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS || isTournamentCancelled(game)
                || GameRunOwnership.staleWorker(game)) {
            // Only a game that is actually being played can be forfeited (never a paused or finished one).
            return null;
        }
        game.status = GameStatus.FORFEIT;
        game.resumeRequestedAt = null;
        // The game is over: no request will follow, so an unresolved one is settled with the forfeit.
        GameRunOwnership.answerRecorded(game);
        game.result = "WHITE".equals(forfeitColor) ? GameResult.WHITE_FORFEIT : GameResult.BLACK_FORFEIT;
        game.resultReason = reason;
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        game.statusRevision++;
        gameRepository.persist(game);
        return game;
    }

    static boolean isTerminal(Game game) {
        return game.status == GameStatus.COMPLETED || game.status == GameStatus.FORFEIT
                || game.status == GameStatus.CANCELLED;
    }

    static String startConflictMessage(GameStatus status) {
        return switch (status) {
            case IN_PROGRESS -> "Game is already in progress";
            case COMPLETED, FORFEIT -> "Game has already finished";
            default -> "Game cannot be started from status " + status;
        };
    }

    private static String boundDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "Paused because of an OpenRouter problem";
        }
        return detail.length() <= STATUS_DETAIL_MAX_LENGTH
                ? detail
                : detail.substring(0, STATUS_DETAIL_MAX_LENGTH - 3) + "...";
    }

    private void advanceTournamentWinnerIfNeeded(Game game) {
        if (game.tournament != null && !game.isBenchmark()) {
            tournamentService.advanceWinner(game.id);
        }
    }

    private int totalMoves(UUID gameId) {
        return Math.toIntExact(moveRepository.count("game.id", gameId));
    }
}
