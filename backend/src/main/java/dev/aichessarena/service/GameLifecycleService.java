package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameDto;
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
import java.time.LocalDateTime;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GameLifecycleService {

    private static final Logger LOG = Logger.getLogger(GameLifecycleService.class);
    static final int STATUS_DETAIL_MAX_LENGTH = 500;
    static final String RESTART_DETAIL = "Paused because the server restarted; resume to continue";

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

    /** Null outside CDI (unit tests): then broadcasts go out at once. */
    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    @Inject
    TournamentAutoRunService autoRuns;

    @Inject
    TournamentRunScheduler runScheduler;

    /**
     * Moves a WAITING or PAUSED game to IN_PROGRESS under the tournament and game row locks.
     *
     * @return the game as it is after the transition
     * @throws WebApplicationException 404 if the game does not exist, 409 if it is in any other state
     */
    @Transactional
    public GameDto markGameStarted(UUID gameId) {
        return markGameStarted(gameId, false);
    }

    @Transactional
    public GameDto markGameStarted(UUID gameId, boolean automatic) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        requireActiveTournament(game);
        if (game.status != GameStatus.WAITING && game.status != GameStatus.PAUSED) {
            throw new WebApplicationException(startConflictMessage(game.status), Response.Status.CONFLICT);
        }
        if (autoRuns != null) autoRuns.requireStartAllowed(game, automatic);
        game.status = GameStatus.IN_PROGRESS;
        game.startedAt = game.startedAt != null ? game.startedAt : LocalDateTime.now();
        game.statusDetail = null;
        game.statusRevision++;
        gameRepository.persist(game);
        announceStatus(game, null, null);
        return DtoMapper.toDto(game, false);
    }

    /** Called in the tournament cancellation transaction, under the tournament then game locks. */
    @Transactional
    public void cancelGame(UUID gameId) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || isTerminal(game)) {
            return;
        }
        game.status = GameStatus.CANCELLED;
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
     * No game loop survives a restart, so games left IN_PROGRESS would be stuck (start returns 409).
     * Pause them with a visible reason so they can be resumed. Called once at startup by {@link GameRecovery}.
     */
    @Transactional
    public void pauseGamesOrphanedByRestart() {
        int paused = gameRepository.update(
                "status = ?1, statusDetail = ?2, statusRevision = statusRevision + 1 where status = ?3",
                GameStatus.PAUSED, RESTART_DETAIL, GameStatus.IN_PROGRESS
        );
        if (paused > 0) {
            LOG.infof("Paused %d game(s) that were in progress before the restart", paused);
        }
    }

    @Transactional
    public void markGamePaused(UUID gameId) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS) {
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
        if (game == null || game.status != GameStatus.IN_PROGRESS) {
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
        if (game == null || isTerminal(game) || isTournamentCancelled(game)) {
            return;
        }
        game.status = GameStatus.COMPLETED;
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
        if (game.status != GameStatus.IN_PROGRESS || isTournamentCancelled(game)
                || !GameMoveService.samePosition(game.currentFen, expectedFen)
                || !WinAdjudication.enabledFor(game)) {
            return false;
        }
        String winner = WinAdjudication.winner(moveRepository.findByGameId(gameId));
        if (winner == null) {
            return false;
        }
        completeGame(gameId, new ChessService.GameEndInfo(winner + "_WINS", "ADJUDICATION"));
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
        if (!GameMoveService.samePosition(game.currentFen, resignation.expectedFen())) {
            throw new GameStateConflictException("Position changed while the model was thinking");
        }
        if (resignation.costUsd() != null) {
            game.totalCostUsd = game.totalCostUsd.add(resignation.costUsd());
        }
        String color = resignation.color();
        String message = resignation.message();
        if (message != null && !message.isBlank()) {
            ChatMessage chat = new ChatMessage();
            chat.game = game;
            chat.moveNumber = resignation.moveNumber();
            chat.senderModel = resignation.modelId();
            chat.senderColor = color;
            chat.message = message;
            chatMessageRepository.persist(chat);
        }
        game.status = GameStatus.COMPLETED;
        game.result = "WHITE".equals(color) ? GameResult.BLACK_WINS : GameResult.WHITE_WINS;
        game.resultReason = ResultReason.RESIGNATION;
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        game.statusRevision++;
        gameRepository.persist(game);
        LOG.infof("Game %s: %s resigned at move %d", gameId, color, resignation.moveNumber());

        // The last words go out before the game-over update, in the same callback, so their order is kept.
        Runnable lastWords = message == null || message.isBlank() ? null
                : () -> gameWebSocket.broadcastChat(gameId, resignation.moveNumber(), color,
                        resignation.modelId(), resignation.playerName(), message);
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
    public void forfeitInvalidMoves(UUID gameId, String forfeitColor, int maxRetries) {
        Game game = markForfeit(gameId, forfeitColor, ResultReason.FORFEIT_INVALID_MOVES);
        if (game == null) {
            return;
        }
        broadcastForfeitAfterCommit(game, forfeitColor,
                "Failed to produce a legal move after %d attempts".formatted(maxRetries));
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
    private void announceStatus(Game game, Runnable first, Runnable then) {
        if (runScheduler != null && game.tournament != null) {
            UUID tournamentId = game.tournament.id;
            AfterCommit.run(transactionRegistry, () -> runScheduler.changed(tournamentId));
        }

        UUID gameId = game.id;
        String status = game.status.name();
        String result = game.result != null ? game.result.name() : null;
        String resultReason = game.resultReason != null ? game.resultReason.name() : null;
        String detail = game.statusDetail;
        long revision = game.statusRevision;
        int moves = totalMoves(gameId);
        BigDecimal cost = game.totalCostUsd;
        boolean terminal = isTerminal(game);
        afterCommit(() -> {
            if (terminal && gameEngineService != null) {
                gameEngineService.requestStop(gameId);
            }
            if (first != null) {
                first.run();
            }
            gameWebSocket.broadcastStatusChange(gameId, status, result, resultReason, moves, cost, detail, revision);
            if (then != null) {
                then.run();
            }
        });
    }

    /** Runs {@code broadcast} after the commit (see {@link AfterCommit}). */
    void afterCommit(Runnable broadcast) {
        AfterCommit.run(transactionRegistry, broadcast);
    }

    private Game markForfeit(UUID gameId, String forfeitColor, ResultReason reason) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS || isTournamentCancelled(game)) {
            // Only a game that is actually being played can be forfeited (never a paused or finished one).
            return null;
        }
        game.status = GameStatus.FORFEIT;
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
        if (game.tournament != null) {
            tournamentService.advanceWinner(game.id);
        }
    }

    private int totalMoves(UUID gameId) {
        return Math.toIntExact(moveRepository.count("game.id", gameId));
    }
}
