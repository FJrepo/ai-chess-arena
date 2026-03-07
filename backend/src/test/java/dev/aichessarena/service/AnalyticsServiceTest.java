package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.AnalyticsModelComparisonDto;
import dev.aichessarena.dto.AnalyticsModelComparisonRowDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    @Test
    void comparisonSeparatesDrawsForfeitsAndColorSplits() {
        Game game1 = finishedGame(
                "model.alpha",
                "model.beta",
                Game.GameResult.WHITE_WINS,
                null,
                LocalDateTime.now().minusDays(1)
        );
        Game game2 = finishedGame(
                "model.alpha",
                "model.gamma",
                Game.GameResult.DRAW,
                null,
                LocalDateTime.now().minusDays(1)
        );
        Game game3 = finishedGame(
                "model.beta",
                "model.alpha",
                Game.GameResult.WHITE_FORFEIT,
                Game.ResultReason.TIMEOUT,
                LocalDateTime.now().minusDays(1)
        );

        Move alphaMove1 = move(game1, "model.alpha", 1100L, "0.010000");
        Move betaMove1 = move(game1, "model.beta", 1300L, "0.020000");
        Move alphaMove2 = move(game2, "model.alpha", 1200L, "0.011000");
        Move gammaMove2 = move(game2, "model.gamma", 1400L, "0.013000");
        Move betaMove3 = move(game3, "model.beta", 1000L, "0.008000");
        Move alphaMove3 = move(game3, "model.alpha", 900L, null);

        AnalyticsService service = new AnalyticsService();
        service.gameRepository = new FakeGameRepository(List.of(game1, game2, game3));
        service.moveRepository = new FakeMoveRepository(List.of(
                alphaMove1,
                betaMove1,
                alphaMove2,
                gammaMove2,
                betaMove3,
                alphaMove3
        ));
        service.windowLoader = loader(service.gameRepository, service.moveRepository);
        service.outcomeClassifier = new AnalyticsGameOutcomeClassifier();

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 0);

        assertEquals(3, response.gamesCount());
        assertEquals(3, response.models().size());

        AnalyticsModelComparisonRowDto alpha = row(response, "model.alpha");
        assertEquals(3, alpha.gamesPlayed());
        assertEquals(2, alpha.wins());
        assertEquals(1, alpha.draws());
        assertEquals(0, alpha.losses());
        assertEquals(0, alpha.forfeits());
        assertEquals(0, alpha.timeoutForfeits());
        assertEquals(0.5000, alpha.whiteWinRate().doubleValue(), 0.0001);
        assertEquals(1.0000, alpha.blackWinRate().doubleValue(), 0.0001);
        assertFalse(alpha.pricingAvailable());
        assertNull(alpha.totalCostUsd());
        assertNull(alpha.costPerWinUsd());

        AnalyticsModelComparisonRowDto beta = row(response, "model.beta");
        assertEquals(2, beta.gamesPlayed());
        assertEquals(0, beta.wins());
        assertEquals(0, beta.draws());
        assertEquals(2, beta.losses());
        assertEquals(1, beta.forfeits());
        assertEquals(1, beta.timeoutForfeits());
        assertTrue(beta.pricingAvailable());
        assertEquals(new BigDecimal("0.028000"), beta.totalCostUsd());
        assertNull(beta.costPerWinUsd());
    }

    @Test
    void comparisonRespectsMinGamesFilter() {
        Game onlyGame = finishedGame(
                "model.alpha",
                "model.beta",
                Game.GameResult.WHITE_WINS,
                null,
                LocalDateTime.now().minusDays(1)
        );

        AnalyticsService service = new AnalyticsService();
        service.gameRepository = new FakeGameRepository(List.of(onlyGame));
        service.moveRepository = new FakeMoveRepository(List.of(
                move(onlyGame, "model.alpha", 1000L, "0.010000"),
                move(onlyGame, "model.beta", 1000L, "0.010000")
        ));
        service.windowLoader = loader(service.gameRepository, service.moveRepository);
        service.outcomeClassifier = new AnalyticsGameOutcomeClassifier();

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 2);

        assertTrue(response.models().isEmpty());
    }

    private AnalyticsModelComparisonRowDto row(AnalyticsModelComparisonDto response, String modelId) {
        return response.models().stream()
                .filter(row -> row.modelId().equals(modelId))
                .findFirst()
                .orElseThrow();
    }

    private Game finishedGame(
            String whiteModelId,
            String blackModelId,
            Game.GameResult result,
            Game.ResultReason resultReason,
            LocalDateTime createdAt
    ) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = result == Game.GameResult.WHITE_FORFEIT || result == Game.GameResult.BLACK_FORFEIT
                ? Game.GameStatus.FORFEIT
                : Game.GameStatus.COMPLETED;
        game.result = result;
        game.resultReason = resultReason;
        game.whiteModelId = whiteModelId;
        game.blackModelId = blackModelId;
        game.createdAt = createdAt;
        return game;
    }

    private Move move(Game game, String modelId, long responseTimeMs, String costUsd) {
        Move move = new Move();
        move.id = UUID.randomUUID();
        move.game = game;
        move.modelId = modelId;
        move.createdAt = game.createdAt.plusMinutes(1);
        move.responseTimeMs = responseTimeMs;
        move.costUsd = costUsd == null ? null : new BigDecimal(costUsd);
        return move;
    }

    private AnalyticsWindowLoader loader(GameRepository gameRepository, MoveRepository moveRepository) {
        AnalyticsWindowLoader loader = new AnalyticsWindowLoader();
        loader.gameRepository = gameRepository;
        loader.moveRepository = moveRepository;
        return loader;
    }

    private static final class FakeGameRepository extends GameRepository {
        private final List<Game> games;

        private FakeGameRepository(List<Game> games) {
            this.games = games;
        }

        @Override
        public List<Game> findCreatedAfter(LocalDateTime cutoff) {
            return games.stream().filter(game -> !game.createdAt.isBefore(cutoff)).toList();
        }
    }

    private static final class FakeMoveRepository extends MoveRepository {
        private final List<Move> moves;

        private FakeMoveRepository(List<Move> moves) {
            this.moves = moves;
        }

        @Override
        public List<Move> findCreatedAfter(LocalDateTime cutoff) {
            return moves.stream().filter(move -> !move.createdAt.isBefore(cutoff)).toList();
        }
    }
}
