package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
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
    TournamentService tournamentService;

    @Inject
    GameWebSocket gameWebSocket;

    /**
     * Moves a WAITING or PAUSED game to IN_PROGRESS in one conditional update.
     *
     * @return the game as it is after the transition
     * @throws WebApplicationException 404 if the game does not exist, 409 if it is in any other state
     */
    @Transactional
    public GameDto markGameStarted(UUID gameId) {
        if (!gameRepository.markStartedIfStartable(gameId, LocalDateTime.now())) {
            Game game = gameRepository.findById(gameId);
            if (game == null) {
                throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
            }
            throw new WebApplicationException(startConflictMessage(game.status), Response.Status.CONFLICT);
        }
        Game game = gameRepository.findByIdForUpdate(gameId);
        gameWebSocket.broadcastGameStatus(gameId, "IN_PROGRESS", null, null, totalMoves(gameId), game.totalCostUsd);
        return DtoMapper.toDto(game, false);
    }

    /**
     * No game loop survives a restart, so games left IN_PROGRESS would be stuck (start returns 409).
     * Pause them with a visible reason so they can be resumed. Called once at startup by {@link GameRecovery}.
     */
    @Transactional
    public void pauseGamesOrphanedByRestart() {
        int paused = gameRepository.update(
                "status = ?1, statusDetail = ?2 where status = ?3",
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
        gameRepository.persist(game);
        gameWebSocket.broadcastGameStatus(gameId, "PAUSED", null, null, totalMoves(gameId), game.totalCostUsd);
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
        gameRepository.persist(game);
        LOG.warnf("Game %s paused: %s", gameId, boundedDetail);
        gameWebSocket.broadcastGameStatus(
                gameId, "PAUSED", null, null, totalMoves(gameId), game.totalCostUsd,
                null, null, null, boundedDetail
        );
    }

    @Transactional
    public void completeGame(UUID gameId, ChessService.GameEndInfo endInfo) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (isTerminal(game)) {
            LOG.warnf("Ignoring completion of game %s: already %s", gameId, game.status);
            return;
        }
        game.status = GameStatus.COMPLETED;
        game.result = GameResult.valueOf(endInfo.result());
        game.resultReason = ResultReason.valueOf(endInfo.reason());
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        gameRepository.persist(game);

        gameWebSocket.broadcastGameStatus(
                gameId,
                "COMPLETED",
                endInfo.result(),
                endInfo.reason(),
                game.moves.size(),
                game.totalCostUsd
        );

        advanceTournamentWinnerIfNeeded(game);
    }

    @Transactional
    public void forfeitInvalidMoves(UUID gameId, String forfeitColor, int maxRetries) {
        Game game = markForfeit(gameId, forfeitColor, ResultReason.FORFEIT_INVALID_MOVES);
        if (game == null) {
            return;
        }
        gameWebSocket.broadcastGameStatus(
                gameId,
                "FORFEIT",
                game.result.name(),
                game.resultReason.name(),
                game.moves.size(),
                game.totalCostUsd
        );
        gameWebSocket.broadcastForfeit(
                gameId,
                forfeitColor,
                "Failed to produce a legal move after %d attempts".formatted(maxRetries)
        );
        advanceTournamentWinnerIfNeeded(game);
    }

    @Transactional
    public void forfeitTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
        Game game = markForfeit(gameId, forfeitColor, ResultReason.TIMEOUT);
        if (game == null) {
            return;
        }
        gameWebSocket.broadcastGameStatus(
                gameId,
                "FORFEIT",
                game.result.name(),
                game.resultReason.name(),
                game.moves.size(),
                game.totalCostUsd
        );
        gameWebSocket.broadcastForfeit(
                gameId,
                forfeitColor,
                "Move timed out after %d seconds".formatted(moveTimeoutSeconds)
        );
        advanceTournamentWinnerIfNeeded(game);
    }

    private Game markForfeit(UUID gameId, String forfeitColor, ResultReason reason) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (isTerminal(game)) {
            LOG.warnf("Ignoring forfeit of game %s: already %s", gameId, game.status);
            return null;
        }
        game.status = GameStatus.FORFEIT;
        game.result = "WHITE".equals(forfeitColor) ? GameResult.WHITE_FORFEIT : GameResult.BLACK_FORFEIT;
        game.resultReason = reason;
        game.statusDetail = null;
        game.completedAt = LocalDateTime.now();
        gameRepository.persist(game);
        return game;
    }

    private static boolean isTerminal(Game game) {
        return game.status == GameStatus.COMPLETED || game.status == GameStatus.FORFEIT;
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
