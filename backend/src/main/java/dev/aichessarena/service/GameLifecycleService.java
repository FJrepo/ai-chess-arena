package dev.aichessarena.service;

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
import java.time.LocalDateTime;
import java.util.UUID;

@ApplicationScoped
public class GameLifecycleService {

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    TournamentService tournamentService;

    @Inject
    GameWebSocket gameWebSocket;

    @Transactional
    public void markGameStarted(UUID gameId) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.IN_PROGRESS;
        if (game.startedAt == null) {
            game.startedAt = LocalDateTime.now();
        }
        gameRepository.persist(game);
        gameWebSocket.broadcastGameStatus(gameId, "IN_PROGRESS", null, null, totalMoves(gameId), game.totalCostUsd);
    }

    @Transactional
    public void markGamePaused(UUID gameId) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.PAUSED;
        gameRepository.persist(game);
        gameWebSocket.broadcastGameStatus(gameId, "PAUSED", null, null, totalMoves(gameId), game.totalCostUsd);
    }

    @Transactional
    public void completeGame(UUID gameId, ChessService.GameEndInfo endInfo) {
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.COMPLETED;
        game.result = GameResult.valueOf(endInfo.result());
        game.resultReason = ResultReason.valueOf(endInfo.reason());
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
        Game game = gameRepository.findById(gameId);
        game.status = GameStatus.FORFEIT;
        game.result = "WHITE".equals(forfeitColor) ? GameResult.WHITE_FORFEIT : GameResult.BLACK_FORFEIT;
        game.resultReason = reason;
        game.completedAt = LocalDateTime.now();
        gameRepository.persist(game);
        return game;
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
