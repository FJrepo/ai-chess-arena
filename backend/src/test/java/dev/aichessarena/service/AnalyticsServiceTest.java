package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.AnalyticsHealthDto;
import dev.aichessarena.dto.AnalyticsHealthModelRowDto;
import dev.aichessarena.dto.AnalyticsModelComparisonDto;
import dev.aichessarena.dto.AnalyticsModelComparisonRowDto;
import dev.aichessarena.dto.ModelCostBreakdownDto;
import dev.aichessarena.dto.ModelReliabilityDetailDto;
import dev.aichessarena.dto.ModelReliabilityDto;
import dev.aichessarena.dto.ModelReliabilityResponseDto;
import dev.aichessarena.dto.TalkativenessDto;
import dev.aichessarena.dto.TournamentCostSummaryDto;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.ChatMessageRepository.PlyMessage;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.RejectedAttemptCost;
import dev.aichessarena.repository.MoveRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    private static final LocalDateTime YESTERDAY = LocalDateTime.now().minusDays(1);

    @Test
    void comparisonSeparatesDrawsForfeitsAndColorSplits() {
        Game game1 = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game game2 = finishedGame("model.alpha", "model.gamma", Game.GameResult.DRAW, null, YESTERDAY);
        Game game3 = finishedGame(
                "model.beta",
                "model.alpha",
                Game.GameResult.WHITE_FORFEIT,
                Game.ResultReason.TIMEOUT,
                YESTERDAY
        );

        AnalyticsService service = service(
                List.of(game1, game2, game3),
                List.of(
                        move(game1, "model.alpha", 1100L, "0.010000"),
                        move(game1, "model.beta", 1300L, "0.020000"),
                        move(game2, "model.alpha", 1200L, "0.011000"),
                        move(game2, "model.gamma", 1400L, "0.013000"),
                        move(game3, "model.beta", 1000L, "0.008000"),
                        move(game3, "model.alpha", 900L, null)
                ),
                List.of()
        );

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
        Game onlyGame = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);

        AnalyticsService service = service(
                List.of(onlyGame),
                List.of(
                        move(onlyGame, "model.alpha", 1000L, "0.010000"),
                        move(onlyGame, "model.beta", 1000L, "0.010000")
                ),
                List.of()
        );

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 2);

        assertTrue(response.models().isEmpty());
    }

    @Test
    void comparisonIgnoresHumanMovesWithoutModelIds() {
        Game game = finishedGame(null, "model.beta", Game.GameResult.BLACK_WINS, null, YESTERDAY);

        AnalyticsService service = service(
                List.of(game),
                List.of(move(game, null, 0L, null), move(game, "model.beta", 1000L, "0.010000")),
                List.of()
        );

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 0);

        assertEquals(1, response.models().size());
        assertEquals("model.beta", response.models().getFirst().modelId());
    }

    @Test
    void byesAndUnfinishedGamesAreNotGamesPlayed() {
        Game played = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game bye = finishedGame("model.alpha", null, Game.GameResult.WHITE_WINS, null, YESTERDAY);
        bye.blackParticipant = null;
        bye.blackPlayerName = "BYE";
        Game waiting = unfinishedGame("model.alpha", "model.beta", Game.GameStatus.WAITING);
        Game inProgress = unfinishedGame("model.alpha", "model.gamma", Game.GameStatus.IN_PROGRESS);
        Game paused = unfinishedGame("model.gamma", "model.alpha", Game.GameStatus.PAUSED);

        AnalyticsService service = service(
                List.of(played, bye, waiting, inProgress, paused),
                List.of(move(played, "model.alpha", 1000L, "0.010000"), move(inProgress, "model.alpha", 1000L, "0.010000")),
                List.of()
        );

        ModelReliabilityDto alpha = reliabilityRow(service.getReliability(30, null, 0), "model.alpha");
        assertEquals(1, alpha.gamesPlayed());
        assertEquals(1, alpha.gamesCompleted());
        assertEquals(0, alpha.forfeitCount());
        // Reliability is based on finished games only, like the comparison.
        assertEquals(1, alpha.movesSampled());

        AnalyticsModelComparisonDto comparison = service.getComparison(30, null, 0);
        assertEquals(1, comparison.gamesCount());
        assertEquals(1, row(comparison, "model.alpha").gamesPlayed());
        assertEquals(1, row(comparison, "model.alpha").movesSampled());

        AnalyticsHealthDto health = service.getHealth(30, null);
        assertEquals(4, health.gamesCount(), "the bye is not a game");
        assertEquals(2, health.activeGamesCount());
        assertEquals(1, health.completedGamesCount());
    }

    @Test
    void finishedGamesAreSelectedByCompletionTime() {
        LocalDateTime longAgo = LocalDateTime.now().minusDays(40);
        Game finishedRecently = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, longAgo);
        finishedRecently.completedAt = LocalDateTime.now().minusDays(2);
        Game finishedLongAgo = finishedGame("model.alpha", "model.beta", Game.GameResult.BLACK_WINS, null, longAgo);
        finishedLongAgo.completedAt = LocalDateTime.now().minusDays(35);
        Game staleWaiting = unfinishedGame("model.alpha", "model.beta", Game.GameStatus.WAITING);
        staleWaiting.createdAt = longAgo;

        AnalyticsService service = service(
                List.of(finishedRecently, finishedLongAgo, staleWaiting),
                List.of(
                        move(finishedRecently, "model.alpha", 1000L, "0.010000"),
                        move(finishedLongAgo, "model.alpha", 1000L, "0.010000")
                ),
                List.of()
        );

        AnalyticsModelComparisonDto comparison = service.getComparison(30, null, 0);
        assertEquals(1, comparison.gamesCount());
        AnalyticsModelComparisonRowDto alpha = row(comparison, "model.alpha");
        assertEquals(1, alpha.wins());
        assertEquals(1, alpha.movesSampled(), "moves follow their game, not their own creation time");

        assertEquals(1, service.getHealth(30, null).gamesCount());
    }

    @Test
    void firstTryLegalRateCountsRetriedMovesAndInvalidMoveForfeits() {
        Game won = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game forfeited = finishedGame(
                "model.alpha",
                "model.beta",
                Game.GameResult.WHITE_FORFEIT,
                Game.ResultReason.FORFEIT_INVALID_MOVES,
                YESTERDAY
        );

        AnalyticsService service = service(
                List.of(won, forfeited),
                List.of(
                        move(won, "model.alpha", 1000L, "0.010000", 0),
                        move(won, "model.alpha", 1000L, "0.010000", 0),
                        move(won, "model.alpha", 1000L, "0.010000", 2),
                        move(forfeited, "model.alpha", 1000L, "0.010000", 0),
                        move(won, "model.beta", 1000L, "0.010000", 0)
                ),
                List.of()
        );

        ModelReliabilityDto alpha = reliabilityRow(service.getReliability(30, null, 0), "model.alpha");
        // 3 first-try moves out of 4 moves plus 1 forfeited turn.
        assertEquals(0.6, alpha.firstTryLegalRate().doubleValue(), 0.0001);
        assertEquals(0.5, alpha.averageRetriesPerMove().doubleValue(), 0.0001);
        assertEquals(1, alpha.forfeitCount());

        AnalyticsModelComparisonRowDto alphaComparison = row(service.getComparison(30, null, 0), "model.alpha");
        assertEquals(0.6, alphaComparison.firstTryLegalRate().doubleValue(), 0.0001);
        assertEquals(alpha.finalScore(), alphaComparison.reliabilityScore());

        ModelReliabilityDetailDto detail = service.getReliabilityForModel("model.alpha", 30, null);
        assertNotNull(detail);
        assertEquals(60.0, detail.firstTryLegalScore().doubleValue(), 0.01);
        assertEquals(50.0, detail.forfeitScore().doubleValue(), 0.01);
        assertEquals(75.0, detail.retryScore().doubleValue(), 0.01);
        // 0.5 * 60 + 0.3 * 50 + 0.2 * 75, weighted by 4 of 60 moves toward 60.
        assertEquals(60.0, detail.rawScore().doubleValue(), 0.01);
        assertEquals(4.0 / 60.0, detail.sampleWeight().doubleValue(), 0.0001);
    }

    @Test
    void perModelCostIncludesRejectedAttempts() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game outside = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        outside.completedAt = LocalDateTime.now().minusDays(60);

        AnalyticsService service = service(
                List.of(game, outside),
                List.of(
                        move(game, "model.alpha", 1000L, "0.010000"),
                        move(game, "model.alpha", 1000L, "0.020000"),
                        move(game, "model.beta", 1000L, "0.004000")
                ),
                List.of(
                        new RejectedAttemptCost(game.id, "model.alpha", new BigDecimal("0.005")),
                        new RejectedAttemptCost(outside.id, "model.alpha", new BigDecimal("1.000"))
                )
        );

        AnalyticsModelComparisonRowDto alpha = row(service.getComparison(30, null, 0), "model.alpha");
        assertEquals(new BigDecimal("0.035000"), alpha.totalCostUsd());
        assertEquals(new BigDecimal("0.017500"), alpha.averageCostPerMoveUsd());
        assertEquals(new BigDecimal("0.035000"), alpha.costPerWinUsd());
        assertEquals(new BigDecimal("0.004000"), row(service.getComparison(30, null, 0), "model.beta").totalCostUsd());

        ModelReliabilityDto alphaReliability = reliabilityRow(service.getReliability(30, null, 0), "model.alpha");
        assertEquals(new BigDecimal("0.017500"), alphaReliability.averageCostPerMoveUsd());

        AnalyticsHealthDto health = service.getHealth(30, null);
        assertEquals(new BigDecimal("0.039000"), health.costTotalUsd());
        AnalyticsHealthModelRowDto alphaHealth = health.models().stream()
                .filter(model -> model.modelId().equals("model.alpha"))
                .findFirst()
                .orElseThrow();
        assertEquals(new BigDecimal("0.035000"), alphaHealth.totalCostUsd());
    }

    @Test
    void totalCostIsKnownForAModelThatOnlyHadRejectedAnswers() {
        Game game = finishedGame(
                "model.alpha",
                "model.beta",
                Game.GameResult.WHITE_FORFEIT,
                Game.ResultReason.FORFEIT_INVALID_MOVES,
                YESTERDAY
        );

        AnalyticsService service = service(
                List.of(game),
                List.of(),
                List.of(new RejectedAttemptCost(game.id, "model.alpha", new BigDecimal("0.03")))
        );

        AnalyticsModelComparisonRowDto alpha = row(service.getComparison(30, null, 0), "model.alpha");
        assertEquals(0, alpha.movesSampled());
        assertTrue(alpha.pricingAvailable());
        assertEquals(new BigDecimal("0.030000"), alpha.totalCostUsd());
        assertNull(alpha.averageCostPerMoveUsd(), "no moves, so no average per move");
        assertNull(alpha.costPerWinUsd());

        AnalyticsModelComparisonRowDto beta = row(service.getComparison(30, null, 0), "model.beta");
        assertFalse(beta.pricingAvailable(), "no priced answer at all");
        assertNull(beta.totalCostUsd());
    }

    @Test
    void tournamentCostSummaryIncludesRejectedAttemptsAndSkipsByes() {
        UUID tournamentId = UUID.randomUUID();
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        game.totalCostUsd = new BigDecimal("0.020");
        Game bye = finishedGame("model.alpha", null, Game.GameResult.WHITE_WINS, null, YESTERDAY);
        bye.blackParticipant = null;
        bye.blackPlayerName = "BYE";

        AnalyticsService service = service(
                List.of(game, bye),
                List.of(move(game, "model.alpha", 1000L, "0.010000"), move(game, "model.beta", 1000L, "0.004000")),
                List.of(new RejectedAttemptCost(game.id, "model.alpha", new BigDecimal("0.006")))
        );

        TournamentCostSummaryDto summary = service.getTournamentCostSummary(tournamentId);

        assertEquals(new BigDecimal("0.020000"), summary.averageCostPerGameUsd());
        assertEquals(new BigDecimal("0.010000"), summary.averageCostPerMoveUsd());
        ModelCostBreakdownDto alpha = summary.topModels().getFirst();
        assertEquals("model.alpha", alpha.modelId());
        assertEquals(new BigDecimal("0.016000"), alpha.totalCostUsd());
        assertEquals(new BigDecimal("0.016000"), alpha.averageCostPerMoveUsd());
    }

    @Test
    void comparisonReportsMoveQualityPerModelFromFinishedGamesOnly() {
        Game game1 = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game game2 = finishedGame("model.beta", "model.alpha", Game.GameResult.DRAW, null, YESTERDAY);
        Game running = unfinishedGame("model.alpha", "model.beta", Game.GameStatus.IN_PROGRESS);
        Move override = evaluatedMove(game1, null, 2, "BLACK", 100);
        override.isOverride = true;

        AnalyticsService service = service(
                List.of(game1, game2, running),
                // Deliberately not in play order: quality depends on the move before.
                List.of(
                        evaluatedMove(game1, "model.alpha", 2, "WHITE", 100), // loss 300, blunder
                        override,
                        evaluatedMove(game1, "model.beta", 1, "BLACK", 400), // loss 370, blunder
                        evaluatedMove(game1, "model.alpha", 1, "WHITE", 30), // loss 0
                        evaluatedMove(game2, "model.beta", 1, "WHITE", 20), // loss 0
                        evaluatedMove(game2, "model.alpha", 1, "BLACK", null), // not evaluated
                        evaluatedMove(running, "model.alpha", 1, "WHITE", -900),
                        evaluatedMove(running, "model.beta", 1, "BLACK", 900)
                ),
                List.of()
        );

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 0);

        AnalyticsModelComparisonRowDto alpha = row(response, "model.alpha");
        assertEquals(2, alpha.analyzedMoves());
        assertEquals(1, alpha.analyzedGames(), "game 2 has no judged move of alpha");
        assertEquals(new BigDecimal("150.0"), alpha.averageCentipawnLoss());
        assertEquals(1, alpha.blunders());
        assertEquals(0, alpha.mistakes());
        assertEquals(0, alpha.inaccuracies());
        assertEquals(new BigDecimal("1.00"), alpha.blundersPerGame());

        AnalyticsModelComparisonRowDto beta = row(response, "model.beta");
        assertEquals(2, beta.analyzedMoves());
        assertEquals(2, beta.analyzedGames());
        assertEquals(new BigDecimal("185.0"), beta.averageCentipawnLoss());
        assertEquals(1, beta.blunders());
        assertEquals(new BigDecimal("0.50"), beta.blundersPerGame());
    }

    @Test
    void comparisonWithoutEvaluationsHasNoMoveQuality() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        AnalyticsService service = service(
                List.of(game),
                List.of(move(game, "model.alpha", 1000L, null), move(game, "model.beta", 1000L, null)),
                List.of()
        );

        AnalyticsModelComparisonRowDto alpha = row(service.getComparison(30, null, 0), "model.alpha");

        assertEquals(0, alpha.analyzedMoves());
        assertNull(alpha.averageCentipawnLoss());
        assertNull(alpha.blundersPerGame());
    }

    @Test
    void talkativenessIsReportedPerChatModeForModelMovesOfFinishedGames() {
        Game game1 = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game game2 = finishedGame("model.beta", "model.alpha", Game.GameResult.DRAW, null, YESTERDAY);
        Game running = unfinishedGame("model.alpha", "model.beta", Game.GameStatus.IN_PROGRESS);
        Move override = chatMove(game1, null, 3, "WHITE", ChatMode.OPTIONAL);
        override.isOverride = true;

        AnalyticsService service = service(
                List.of(game1, game2, running),
                List.of(
                        // game 1 (OPTIONAL, then MANDATORY after a mode change): alpha is White, beta is Black.
                        chatMove(game1, "model.alpha", 1, "WHITE", ChatMode.OPTIONAL), // talks, 10 chars
                        chatMove(game1, "model.beta", 1, "BLACK", ChatMode.OPTIONAL), // silent
                        chatMove(game1, "model.alpha", 2, "WHITE", ChatMode.MANDATORY), // talks, 30 chars
                        chatMove(game1, "model.beta", 2, "BLACK", ChatMode.MANDATORY), // talks, 40 chars
                        override, // has a message, but is not a model move
                        // game 2 (OFF): excluded, even with a message.
                        chatMove(game2, "model.beta", 1, "WHITE", ChatMode.OFF),
                        chatMove(game2, "model.alpha", 1, "BLACK", ChatMode.OFF),
                        // A human move has no chat mode.
                        chatMove(game2, null, 2, "WHITE", null),
                        // game 3 is still running: excluded.
                        chatMove(running, "model.alpha", 1, "WHITE", ChatMode.MANDATORY)
                ),
                List.of(),
                List.of(
                        new PlyMessage(game1.id, 1, "WHITE", 10),
                        new PlyMessage(game1.id, 2, "WHITE", 30),
                        new PlyMessage(game1.id, 2, "BLACK", 40),
                        new PlyMessage(game1.id, 3, "WHITE", 99),
                        new PlyMessage(game2.id, 1, "WHITE", 50),
                        new PlyMessage(game2.id, 2, "WHITE", 50),
                        new PlyMessage(running.id, 1, "WHITE", 70)
                )
        );

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 0);

        AnalyticsModelComparisonRowDto alpha = row(response, "model.alpha");
        assertEquals(new TalkativenessDto(1, 1, new BigDecimal("1.0000"), new BigDecimal("10.0")),
                alpha.optionalChat());
        assertEquals(new TalkativenessDto(1, 1, new BigDecimal("1.0000"), new BigDecimal("30.0")),
                alpha.mandatoryChat());

        AnalyticsModelComparisonRowDto beta = row(response, "model.beta");
        assertEquals(new TalkativenessDto(1, 0, new BigDecimal("0.0000"), null), beta.optionalChat());
        assertEquals(new TalkativenessDto(1, 1, new BigDecimal("1.0000"), new BigDecimal("40.0")),
                beta.mandatoryChat());
    }

    @Test
    void talkativenessWithoutMovesInAModeHasNoRate() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        AnalyticsService service = service(
                List.of(game),
                List.of(
                        chatMove(game, "model.alpha", 1, "WHITE", ChatMode.OPTIONAL),
                        chatMove(game, "model.beta", 1, "BLACK", ChatMode.OPTIONAL),
                        chatMove(game, "model.alpha", 2, "WHITE", ChatMode.OPTIONAL)
                ),
                List.of(),
                List.of(new PlyMessage(game.id, 1, "WHITE", 12), new PlyMessage(game.id, 1, "BLACK", 0))
        );

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 0);

        AnalyticsModelComparisonRowDto alpha = row(response, "model.alpha");
        assertEquals(new TalkativenessDto(2, 1, new BigDecimal("0.5000"), new BigDecimal("12.0")),
                alpha.optionalChat());
        assertEquals(new TalkativenessDto(0, 0, null, null), alpha.mandatoryChat());
        // An empty message is not talking.
        assertEquals(new TalkativenessDto(1, 0, new BigDecimal("0.0000"), null),
                row(response, "model.beta").optionalChat());
    }

    @Test
    void talkativenessCountsADuplicatedPlyOnceWithItsFirstMoveAndFirstMessage() {
        // Databases from before V8 may hold duplicate plies (see docs/deployment.md).
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Move talking = chatMove(game, "model.alpha", 1, "WHITE", ChatMode.OPTIONAL);
        Move duplicate = chatMove(game, "model.alpha", 1, "WHITE", ChatMode.MANDATORY);
        duplicate.createdAt = talking.createdAt.plusSeconds(1);
        Move silent = chatMove(game, "model.alpha", 2, "WHITE", ChatMode.OPTIONAL);
        AnalyticsService service = service(
                List.of(game),
                // The later duplicate comes first: the choice must not depend on load order.
                List.of(duplicate, silent, talking),
                List.of(),
                List.of(
                        new PlyMessage(game.id, 1, "WHITE", 10),
                        new PlyMessage(game.id, 1, "WHITE", 30)
                )
        );

        AnalyticsModelComparisonRowDto alpha = row(service.getComparison(30, null, 0), "model.alpha");

        assertEquals(new TalkativenessDto(2, 1, new BigDecimal("0.5000"), new BigDecimal("10.0")),
                alpha.optionalChat());
        assertEquals(new TalkativenessDto(0, 0, null, null), alpha.mandatoryChat(),
                "the later duplicate's mode is not counted");
    }

    private Move chatMove(Game game, String modelId, int number, String color, ChatMode chatMode) {
        Move move = move(game, modelId, 1000L, null);
        move.moveNumber = number;
        move.color = color;
        move.chatMode = chatMode;
        return move;
    }

    private Move evaluatedMove(Game game, String modelId, int number, String color, Integer cp) {
        Move move = move(game, modelId, 1000L, null);
        move.moveNumber = number;
        move.color = color;
        move.san = "x";
        move.fen = "fen";
        move.evaluationCp = cp;
        return move;
    }

    private AnalyticsService service(List<Game> games, List<Move> moves, List<RejectedAttemptCost> attemptCosts) {
        return service(games, moves, attemptCosts, List.of());
    }

    private AnalyticsService service(List<Game> games, List<Move> moves, List<RejectedAttemptCost> attemptCosts,
                                     List<PlyMessage> messages) {
        AnalyticsService service = new AnalyticsService();
        service.gameRepository = new FakeGameRepository(games);
        service.moveRepository = new FakeMoveRepository(moves);
        service.moveAttemptRepository = new FakeMoveAttemptRepository(attemptCosts);
        AnalyticsWindowLoader loader = new AnalyticsWindowLoader();
        loader.gameRepository = service.gameRepository;
        loader.moveRepository = service.moveRepository;
        loader.moveAttemptRepository = service.moveAttemptRepository;
        loader.chatMessageRepository = new ChatMessageRepository() {
            @Override
            public List<PlyMessage> findPlyMessagesInWindow(LocalDateTime cutoff, UUID tournamentId) {
                return messages;
            }
        };
        service.windowLoader = loader;
        service.outcomeClassifier = new AnalyticsGameOutcomeClassifier();
        return service;
    }

    private AnalyticsModelComparisonRowDto row(AnalyticsModelComparisonDto response, String modelId) {
        return response.models().stream()
                .filter(row -> row.modelId().equals(modelId))
                .findFirst()
                .orElseThrow();
    }

    private ModelReliabilityDto reliabilityRow(ModelReliabilityResponseDto response, String modelId) {
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
        Game game = game(whiteModelId, blackModelId);
        game.status = result == Game.GameResult.WHITE_FORFEIT || result == Game.GameResult.BLACK_FORFEIT
                ? Game.GameStatus.FORFEIT
                : Game.GameStatus.COMPLETED;
        game.result = result;
        game.resultReason = resultReason;
        game.createdAt = createdAt;
        game.completedAt = createdAt.plusHours(1);
        return game;
    }

    private Game unfinishedGame(String whiteModelId, String blackModelId, Game.GameStatus status) {
        Game game = game(whiteModelId, blackModelId);
        game.status = status;
        game.createdAt = YESTERDAY;
        return game;
    }

    private Game game(String whiteModelId, String blackModelId) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.whiteParticipant = new TournamentParticipant();
        game.blackParticipant = new TournamentParticipant();
        game.whitePlayerName = whiteModelId != null ? whiteModelId : "Human";
        game.blackPlayerName = blackModelId != null ? blackModelId : "Human";
        game.whiteModelId = whiteModelId;
        game.blackModelId = blackModelId;
        return game;
    }

    private Move move(Game game, String modelId, long responseTimeMs, String costUsd) {
        return move(game, modelId, responseTimeMs, costUsd, 0);
    }

    private Move move(Game game, String modelId, long responseTimeMs, String costUsd, int retryCount) {
        Move move = new Move();
        move.id = UUID.randomUUID();
        move.game = game;
        move.modelId = modelId;
        move.createdAt = game.createdAt.plusMinutes(1);
        move.responseTimeMs = responseTimeMs;
        move.costUsd = costUsd == null ? null : new BigDecimal(costUsd);
        move.retryCount = retryCount;
        return move;
    }

    /** Applies the window rule of {@link GameRepository#findInWindow}: finished by completion, others by creation. */
    private static final class FakeGameRepository extends GameRepository {
        private final List<Game> games;

        private FakeGameRepository(List<Game> games) {
            this.games = games;
        }

        @Override
        public List<Game> findInWindow(LocalDateTime cutoff, UUID tournamentId) {
            return games.stream()
                    .filter(game -> AnalyticsGameOutcomeClassifier.isTerminal(game.status)
                            ? game.completedAt != null && !game.completedAt.isBefore(cutoff)
                            : !game.createdAt.isBefore(cutoff))
                    .toList();
        }

        @Override
        public List<Game> findByTournamentId(UUID tournamentId) {
            return games;
        }
    }

    /** Returns every move; the window loader must keep only the moves of the selected games. */
    private static final class FakeMoveRepository extends MoveRepository {
        private final List<Move> moves;

        private FakeMoveRepository(List<Move> moves) {
            this.moves = moves;
        }

        @Override
        public List<Move> findInWindow(LocalDateTime cutoff, UUID tournamentId) {
            return moves;
        }

        @Override
        public List<Move> findByTournamentId(UUID tournamentId) {
            return moves;
        }
    }

    private static final class FakeMoveAttemptRepository extends MoveAttemptRepository {
        private final List<RejectedAttemptCost> costs;

        private FakeMoveAttemptRepository(List<RejectedAttemptCost> costs) {
            this.costs = costs;
        }

        @Override
        public List<RejectedAttemptCost> findRejectedCostsInWindow(LocalDateTime cutoff, UUID tournamentId) {
            return costs;
        }

        @Override
        public List<RejectedAttemptCost> findRejectedCostsByTournamentId(UUID tournamentId) {
            return costs;
        }
    }
}
