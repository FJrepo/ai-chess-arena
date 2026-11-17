package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.config.OpenRouterKeyStoreFixture;
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
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.ChatMessageRepository.PlyMessage;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.RejectedAttemptCost;
import dev.aichessarena.repository.MoveRepository;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    private static final LocalDateTime YESTERDAY = LocalDateTime.now().minusDays(1);

    @Test
    void analyticsCacheBoundsFilterCombinations() {
        var cache = new AnalyticsResponseCache<Object>();
        List<Object> initial = new ArrayList<>();
        for (int days = 1; days <= 65; days++) {
            initial.add(cache.get(Integer.toString(days), Object::new));
        }
        assertTrue(IntStream.rangeClosed(1, 65).anyMatch(days ->
                initial.get(days - 1) != cache.get(Integer.toString(days), Object::new)));
    }

    @Test
    void analyticsCacheExpiresThirtySecondsAfterSuccessfulWrite() {
        var now = new AtomicLong();
        var cache = new AnalyticsResponseCache<Object>(now::get);
        Object first = cache.get("filter", () -> {
            now.set(Duration.ofSeconds(10).toNanos());
            return new Object();
        });
        for (int seconds : List.of(15, 30, 39)) {
            now.set(Duration.ofSeconds(seconds).toNanos());
            assertSame(first, cache.get("filter", Object::new));
        }
        now.set(Duration.ofSeconds(40).toNanos());
        assertNotSame(first, cache.get("filter", Object::new));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameHealthFilterSharesTheLoadAndFailedLoadsCanBeRetried(boolean failFirstLoad) throws Exception {
        AnalyticsService service = service(List.of(), List.of(), List.of());
        var loadStarted = new CountDownLatch(1);
        var releaseLoad = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var failure = new CompletionException(new IllegalStateException("analytics database unavailable"));
        service.windowLoader.gameRepository = new GameRepository() {
            @Override
            public List<Game> findInWindow(LocalDateTime cutoff, UUID tournamentId) {
                if (tournamentId == null && calls.incrementAndGet() == 1) {
                    loadStarted.countDown();
                    try {
                        assertTrue(releaseLoad.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    if (failFirstLoad) {
                        throw failure;
                    }
                }
                return List.of();
            }
        };

        var first = new FutureTask<>(() -> service.getHealth(30, null));
        var second = new FutureTask<>(() -> service.getHealth(30, null));
        Thread.ofVirtual().start(first);
        try {
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS));
            Thread follower = Thread.ofVirtual().start(second);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (follower.getState() != Thread.State.WAITING && !second.isDone()) {
                    Thread.sleep(1);
                }
                // A different key can load while the first key is blocked.
                assertEquals(0, service.getHealth(30, UUID.randomUUID()).gamesCount());
            });
            assertFalse(second.isDone());
            assertEquals(1, calls.get());
        } finally {
            releaseLoad.countDown();
        }

        if (failFirstLoad) {
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> first.get(5, TimeUnit.SECONDS)).getCause());
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> second.get(5, TimeUnit.SECONDS)).getCause());
            var retried = service.getHealth(30, null);
            assertSame(retried, service.getHealth(30, null));
            assertEquals(2, calls.get());
        } else {
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void comparisonSharesTheExactWindowBoundaryWithReasoningTelemetry() {
        AnalyticsService service = service(List.of(), List.of(), List.of());
        service.getComparison(30, UUID.randomUUID(), 0);
        FakeMoveAttemptRepository repository = (FakeMoveAttemptRepository) service.moveAttemptRepository;
        assertNotNull(repository.costCutoff);
        assertSame(repository.costCutoff, repository.reasoningCutoff);
    }

    @Test
    void healthSkipsQueriesUsedOnlyByModelAnalytics() {
        AnalyticsService service = service(List.of(), List.of(), List.of());
        service.windowLoader.chatMessageRepository = new ChatMessageRepository() {
            @Override
            public List<PlyMessage> findPlyMessagesInWindow(LocalDateTime cutoff, UUID tournamentId) {
                throw new AssertionError("health does not use chat projections");
            }
        };
        service.windowLoader.moveAttemptRepository = new MoveAttemptRepository() {
            @Override
            public List<RejectedAttemptCost> findRejectedCostsInWindow(LocalDateTime cutoff, UUID tournamentId) {
                return List.of();
            }

            @Override
            public List<ResignationTurnAttempt> findResignationTurnAttemptsInWindow(
                    LocalDateTime cutoff, UUID tournamentId) {
                throw new AssertionError("health does not use resignation retries");
            }

            @Override
            public List<AttemptUsage> findUsageInWindow(LocalDateTime cutoff, UUID tournamentId) {
                throw new AssertionError("health does not use attempt token projections");
            }
        };

        assertEquals(0, service.getHealth(30, null).movesCount());
    }

    @Test
    void bookMovesNeverCountForModelHealthReliabilityCostChatQualityOrConversion() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        Move book = move(game, "model.alpha", 90000L, "9.000000", 8);
        book.isBook = true;
        book.moveNumber = 1;
        book.color = "WHITE";
        book.evaluationCp = 1200;
        book.chatMode = ChatMode.MANDATORY;
        book.promptTokens = 99999;
        Move played = move(game, "model.beta", 100L, "0.010000");
        played.moveNumber = 1;
        played.color = "BLACK";
        played.chatMode = ChatMode.OPTIONAL;
        played.evaluationCp = 0;
        AnalyticsService service = service(List.of(game), List.of(book, played), List.of(),
                List.of(new PlyMessage(game.id, 1, "WHITE", 50), new PlyMessage(game.id, 1, "BLACK", 10)));
        var comparison = service.getComparison(30, null, 0);
        var alpha = row(comparison, "model.alpha");
        var beta = row(comparison, "model.beta");
        assertEquals(0, alpha.movesSampled());
        assertEquals(0, alpha.analyzedMoves());
        assertEquals(0, alpha.mandatoryChat().moves());
        assertNull(alpha.totalCostUsd());
        assertEquals(0, alpha.conversion().winningPositionsReached());
        assertEquals(1, beta.movesSampled());
        assertEquals(1, beta.analyzedMoves(), "last book evaluation is still the real move's baseline");
        assertEquals(1, beta.optionalChat().moves());
        assertEquals(1, beta.optionalChat().messages());
        assertEquals(new BigDecimal("0.010000"), beta.totalCostUsd());
        var health = service.getHealth(30, null);
        assertEquals(1, health.movesCount());
        assertEquals(0, health.retriesTotal());
        assertEquals(0, health.promptTokensTotal());
        assertEquals(new BigDecimal("0.010000"), health.costTotalUsd());
        assertEquals(0, service.getReliability(30, null, 0).models().stream()
                .filter(row -> "model.alpha".equals(row.modelId())).findFirst().orElseThrow().movesSampled());
        played.evaluationCp = null;
        assertEquals(0, row(service(List.of(game), List.of(book, played), List.of()).getComparison(30, null, 0), "model.alpha").conversion().evaluatedGames(),
                "book-only scores cannot supply conversion coverage");
    }


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
    void duplicatePliesCountOnceForBothScoresAndKeepAllReportedCosts() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        Move first = move(game, "model.alpha", 1000L, "0.010", 0);
        Move duplicate = move(game, "model.alpha", 3000L, "0.020", 1);
        duplicate.createdAt = first.createdAt.plusSeconds(1);
        AnalyticsService service = service(List.of(game), List.of(duplicate, first),
                List.of(new RejectedAttemptCost(game.id, "model.alpha", new BigDecimal("0.005"))));

        ModelReliabilityDto reliability = reliabilityRow(service.getReliability(30, null, 0), "model.alpha");
        assertEquals(1, reliability.movesSampled());
        assertEquals(1.0, reliability.firstTryLegalRate().doubleValue());
        assertEquals(0.0, reliability.averageRetriesPerMove().doubleValue());
        assertEquals(1000L, reliability.averageResponseTimeMs());
        AnalyticsModelComparisonRowDto comparison = row(service.getComparison(30, null, 0), "model.alpha");
        assertEquals(1, comparison.movesSampled());
        assertEquals(reliability.firstTryLegalRate(), comparison.firstTryLegalRate());
        assertEquals(0.0, comparison.averageRetriesPerMove().doubleValue());
        assertEquals(1000L, comparison.averageResponseTimeMs());
        assertEquals(reliability.finalScore(), comparison.reliabilityScore());
        assertEquals(new BigDecimal("0.035000"), comparison.totalCostUsd());
        assertEquals(comparison.totalCostUsd(), comparison.averageCostPerMoveUsd());
        assertEquals(comparison.averageCostPerMoveUsd(), reliability.averageCostPerMoveUsd());
    }

    @Test
    void pricedDuplicateContributesSpendButCannotPriceAnUnpricedPly() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        Move first = move(game, "model.alpha", 1000L, null);
        Move duplicate = move(game, "model.alpha", 3000L, "0.02");
        duplicate.createdAt = first.createdAt.plusSeconds(1);
        AnalyticsService service = service(List.of(game), List.of(duplicate, first), List.of());

        ModelReliabilityDto reliability = reliabilityRow(service.getReliability(30, null, 0), "model.alpha");
        AnalyticsModelComparisonRowDto comparison = row(service.getComparison(30, null, 0), "model.alpha");
        assertEquals(1, reliability.movesSampled());
        assertEquals(1, comparison.movesSampled());
        assertEquals(new BigDecimal("0.020000"), service.getHealth(30, null).costTotalUsd());
        assertNull(comparison.averageCostPerMoveUsd());
        assertNull(reliability.averageCostPerMoveUsd());
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
                        move(won, "model.alpha", 1000L, "0.010000", 0, 2),
                        move(won, "model.alpha", 1000L, "0.010000", 2, 3),
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
                        move(game, "model.alpha", 1000L, "0.020000", 0, 2),
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

    @Test
    void talkativenessTakesTheFirstNonEmptyMessageOfADuplicatedPly() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        AnalyticsService service = service(
                List.of(game),
                List.of(chatMove(game, "model.alpha", 1, "WHITE", ChatMode.OPTIONAL)),
                List.of(),
                // Oldest first: an empty (whitespace-only, length 0 after trimming) message, then a real one.
                List.of(
                        new PlyMessage(game.id, 1, "WHITE", 0),
                        new PlyMessage(game.id, 1, "WHITE", 24),
                        new PlyMessage(game.id, 1, "WHITE", 50)
                )
        );

        AnalyticsModelComparisonRowDto alpha = row(service.getComparison(30, null, 0), "model.alpha");

        assertEquals(new TalkativenessDto(1, 1, new BigDecimal("1.0000"), new BigDecimal("24.0")),
                alpha.optionalChat());
    }

    @Test
    void resignationsAreCountedPerModelWithTheWinningChanceAtResignation() {
        // alpha (White) resigns after White's eval -50 at its move: Black just moved, alpha has ~45%: not losing.
        Game notLosing = finishedGame("model.alpha", "model.beta", Game.GameResult.BLACK_WINS,
                Game.ResultReason.RESIGNATION, YESTERDAY);
        // beta (Black) resigns facing +600 for White: ~10%, losing.
        Game lost = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS,
                Game.ResultReason.RESIGNATION, YESTERDAY);
        // alpha (Black) resigns, but the last move was never evaluated: counted, winning chance unknown.
        Game unknown = finishedGame("model.beta", "model.alpha", Game.GameResult.WHITE_WINS,
                Game.ResultReason.RESIGNATION, YESTERDAY);
        // A checkmate, a resignation by a human and a running game do not count as model resignations.
        Game checkmate = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS,
                Game.ResultReason.CHECKMATE, YESTERDAY);
        Game humanResigned = finishedGame(null, "model.beta", Game.GameResult.BLACK_WINS,
                Game.ResultReason.RESIGNATION, YESTERDAY);
        humanResigned.whiteParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
        Game running = unfinishedGame("model.alpha", "model.beta", Game.GameStatus.IN_PROGRESS);

        AnalyticsService service = service(
                List.of(notLosing, lost, unknown, checkmate, humanResigned, running),
                List.of(
                        // Deliberately not in play order: the last move in play order counts.
                        evaluatedMove(notLosing, "model.beta", 10, "BLACK", -50),
                        evaluatedMove(notLosing, "model.alpha", 10, "WHITE", 900),
                        evaluatedMove(lost, "model.alpha", 11, "WHITE", 600),
                        evaluatedMove(lost, "model.beta", 10, "BLACK", 0),
                        evaluatedMove(unknown, "model.alpha", 11, "BLACK", 0),
                        evaluatedMove(unknown, "model.beta", 12, "WHITE", null),
                        evaluatedMove(checkmate, "model.alpha", 30, "WHITE", null),
                        evaluatedMove(humanResigned, "model.beta", 15, "BLACK", 0)
                ),
                List.of()
        );

        AnalyticsModelComparisonDto response = service.getComparison(30, null, 0);

        AnalyticsModelComparisonRowDto alpha = row(response, "model.alpha");
        assertEquals(2, alpha.resignations());
        assertEquals(1, alpha.resignationsEvaluated());
        assertEquals(1, alpha.resignationsWhileNotLosing());
        assertEquals(1, alpha.wonByResignation());
        AnalyticsModelComparisonRowDto beta = row(response, "model.beta");
        assertEquals(1, beta.resignations());
        assertEquals(1, beta.resignationsEvaluated());
        assertEquals(0, beta.resignationsWhileNotLosing());
        assertEquals(3, beta.wonByResignation(), "alpha resigned twice, and a human resignation is a model win too");
        // A resignation is an ordinary loss and win otherwise.
        assertEquals(2, alpha.losses(), "alpha lost both games it resigned");
    }

    /**
     * Regression: 9 first-try moves, then an illegal answer, then a resignation showed 100% first-try legal and no
     * retries, because the resigned turn has no move to carry the retry. Both reliability views count it now; a
     * resignation on the first try changes nothing.
     */
    @Test
    void failedAttemptsBeforeAResignationCountInBothReliabilityViews() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.BLACK_WINS,
                Game.ResultReason.RESIGNATION, YESTERDAY);
        Game clean = finishedGame("model.gamma", "model.beta", Game.GameResult.BLACK_WINS,
                Game.ResultReason.RESIGNATION, YESTERDAY);
        List<Move> moves = new ArrayList<>();
        for (int number = 1; number <= 9; number++) {
            moves.add(chatMove(game, "model.alpha", number, "WHITE", ChatMode.OPTIONAL));
            moves.add(chatMove(game, "model.beta", number, "BLACK", ChatMode.OPTIONAL));
            moves.add(chatMove(clean, "model.gamma", number, "WHITE", ChatMode.OPTIONAL));
            moves.add(chatMove(clean, "model.beta", number, "BLACK", ChatMode.OPTIONAL));
        }
        AnalyticsService service = service(List.of(game, clean), moves, List.of());
        ((FakeMoveAttemptRepository) service.moveAttemptRepository).resignationAttempts = List.of(
                // alpha: an illegal answer and a disallowed resignation, then the resignation at move 10.
                resignationAttempt(game, "model.alpha", MoveAttempt.Outcome.ILLEGAL_MOVE),
                resignationAttempt(game, "model.alpha", MoveAttempt.Outcome.RESIGN_NOT_ALLOWED),
                resignationAttempt(game, "model.alpha", MoveAttempt.Outcome.RESIGNED),
                // gamma resigned on its first try: nothing to count.
                resignationAttempt(clean, "model.gamma", MoveAttempt.Outcome.PROVIDER_RETRYABLE),
                resignationAttempt(clean, "model.gamma", MoveAttempt.Outcome.RESIGNED),
                // Failures of a ply that did not end in a resignation are already on a move (or a forfeit).
                new MoveAttemptRepository.ResignationTurnAttempt(game.id, 3, "WHITE", "model.alpha",
                        MoveAttempt.Outcome.PARSE_FAILURE)
        );

        AnalyticsModelComparisonRowDto alpha = row(service.getComparison(30, null, 0), "model.alpha");
        // 9 first-try moves out of 10 turns; 2 retries over 10 turns.
        assertEquals(new BigDecimal("0.9000"), alpha.firstTryLegalRate());
        assertEquals(new BigDecimal("0.200000"), alpha.averageRetriesPerMove());
        assertEquals(9, alpha.movesSampled(), "moves stay moves");
        ModelReliabilityDto alphaReliability = reliabilityRow(service.getReliability(30, null, 0), "model.alpha");
        assertEquals(new BigDecimal("0.9000"), alphaReliability.firstTryLegalRate());
        assertEquals(new BigDecimal("0.2000"), alphaReliability.averageRetriesPerMove());

        AnalyticsModelComparisonRowDto gamma = row(service.getComparison(30, null, 0), "model.gamma");
        assertEquals(new BigDecimal("1.0000"), gamma.firstTryLegalRate());
        assertEquals(new BigDecimal("0.000000"), gamma.averageRetriesPerMove());
        ModelReliabilityDto gammaReliability = reliabilityRow(service.getReliability(30, null, 0), "model.gamma");
        assertEquals(new BigDecimal("1.0000"), gammaReliability.firstTryLegalRate());
        assertEquals(new BigDecimal("0.0000"), gammaReliability.averageRetriesPerMove());
    }

    private static MoveAttemptRepository.ResignationTurnAttempt resignationAttempt(Game game, String modelId,
                                                                                    MoveAttempt.Outcome outcome) {
        return new MoveAttemptRepository.ResignationTurnAttempt(game.id, 10, "WHITE", modelId, outcome);
    }

    @Test
    void theNotLosingThresholdIsThirtyFivePercent() {
        assertTrue(ResignationAnalysis.notLosing(0.35));
        assertFalse(ResignationAnalysis.notLosing(0.3499));
        assertFalse(ResignationAnalysis.notLosing(null));
        assertEquals(0.5, ResignationAnalysis.winChanceAtResignation(List.of(), "WHITE"));
    }

    @Test
    void promptCachingAggregatesAttemptsInFinishedNonByeGamesAndUsesTokenWeightedRate() {
        Game recent = finishedGame("model.alpha", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game unfinished = unfinishedGame("model.alpha", "model.beta", Game.GameStatus.PAUSED);
        Game old = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY.minusDays(40));
        Game bye = finishedGame("model.alpha", null, Game.GameResult.WHITE_WINS, null, YESTERDAY);
        bye.blackPlayerName = "BYE";
        bye.blackParticipant = null;
        AnalyticsService service = service(List.of(recent, unfinished, old, bye), List.of(), List.of());
        FakeMoveAttemptRepository attempts = (FakeMoveAttemptRepository) service.moveAttemptRepository;
        attempts.usage = List.of(
                usage(recent, " model.alpha ", 100, 80, 20),
                usage(recent, "model.alpha", 300, 0, 100),
                usage(recent, "model.beta", 0, 0, 0),
                usage(unfinished, "model.alpha", 10000, 10000, 0),
                usage(old, "model.alpha", 10000, 10000, 0),
                usage(bye, "model.alpha", 10000, 10000, 0));
        var response = service.getComparison(30, null, 0);
        var alpha = row(response, "model.alpha").promptCaching();
        assertEquals(400L, alpha.promptTokens());
        assertEquals(80L, alpha.cachedPromptTokens());
        assertEquals(0.2, alpha.cacheHitRate().doubleValue(), 0.000001);
        assertEquals(120L, alpha.cacheWriteTokens());
        assertEquals(0, new BigDecimal("0.000216").compareTo(alpha.estimatedSavingsUsd()));
        var beta = row(response, "model.beta").promptCaching();
        assertEquals(0L, beta.cachedPromptTokens());
        assertEquals(0L, beta.cacheWriteTokens());
        assertNull(beta.cacheHitRate(), "zero denominator is unknown");
        assertNull(beta.estimatedSavingsUsd(), "unknown catalog prices, even with zero cache reads");
    }

    @Test
    void promptCachingDoesNotTreatLegacyOrPartiallyReportedCountersAsZero() {
        Game game = finishedGame("model.alpha", "model.legacy", Game.GameResult.DRAW, null, YESTERDAY);
        AnalyticsService service = service(List.of(game), List.of(), List.of());
        ((FakeMoveAttemptRepository) service.moveAttemptRepository).usage = List.of(
                usage(game, "model.alpha", 100, 80, 10),
                usage(game, "model.alpha", 200, null, null));
        var response = service.getComparison(30, null, 0);
        var alpha = row(response, "model.alpha").promptCaching();
        assertEquals(300L, alpha.promptTokens());
        assertNull(alpha.cachedPromptTokens());
        assertNull(alpha.cacheWriteTokens());
        assertNull(alpha.cacheHitRate());
        assertNull(alpha.estimatedSavingsUsd());
        var legacy = row(response, "model.legacy").promptCaching();
        assertNull(legacy.promptTokens());
        assertNull(legacy.cachedPromptTokens());
        assertNull(legacy.cacheWriteTokens());
        assertNull(legacy.cacheHitRate());
        assertNull(legacy.estimatedSavingsUsd());
    }

    @Test
    void promptCachingKeepsReadAndWriteCompletenessIndependentAndDoesNotOverflowIntegerTotals() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        AnalyticsService service = service(List.of(game), List.of(), List.of());
        ((FakeMoveAttemptRepository) service.moveAttemptRepository).usage = List.of(
                usage(game, "model.alpha", Integer.MAX_VALUE, 0, null),
                usage(game, "model.alpha", Integer.MAX_VALUE, 0, 1),
                usage(game, "model.beta", null, null, 20));
        var response = service.getComparison(30, null, 0);
        var alpha = row(response, "model.alpha").promptCaching();
        assertEquals(4294967294L, alpha.promptTokens());
        assertEquals(0L, alpha.cachedPromptTokens());
        assertEquals(0, alpha.cacheHitRate().signum());
        assertEquals(0, alpha.estimatedSavingsUsd().signum());
        assertNull(alpha.cacheWriteTokens());
        var beta = row(response, "model.beta").promptCaching();
        assertNull(beta.promptTokens());
        assertNull(beta.cacheHitRate());
        assertEquals(20L, beta.cacheWriteTokens());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void comparisonDoesNotWaitForFailingCatalogAndTakesOnlyOneSnapshot(boolean stale) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000_000L);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch failRefresh = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models", exchange -> {
            int request = requests.incrementAndGet();
            boolean warmup = stale && request == 1;
            try {
                if (!warmup) {
                    refreshStarted.countDown();
                    if (!failRefresh.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the failed refresh");
                    }
                }
                byte[] body = (warmup ? """
                        {"data":[{"id":"model.alpha","pricing":{"prompt":"0.000003",
                        "input_cache_read":"0.0000003"}}]}
                        """ : "catalog unavailable").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(warmup ? 200 : 503, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            OpenRouterService catalog = new OpenRouterService() {
                @Override
                Map<String, BigDecimal> cacheReadSavingsSnapshot() {
                    snapshots.incrementAndGet();
                    return super.cacheReadSavingsSnapshot();
                }
            };
            catalog.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            catalog.keyStore = OpenRouterKeyStoreFixture.keys("test-key", java.nio.file.Path.of("target/test-keys"));
            catalog.objectMapper = new ObjectMapper();
            catalog.modelsClock = now::get;
            if (stale) {
                catalog.listModels(false, null, OpenRouterService.NO_LIMIT, null);
                now.addAndGet(300_001L);
            }
            Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
            Game other = finishedGame("model.gamma", "model.alpha", Game.GameResult.DRAW, null, YESTERDAY);
            AnalyticsService service = service(List.of(game, other), List.of(), List.of());
            service.openRouterService = catalog;
            ((FakeMoveAttemptRepository) service.moveAttemptRepository).usage = List.of(
                    usage(game, "model.alpha", 100, 80, 0),
                    usage(game, "model.beta", 100, 80, 0),
                    usage(other, "model.gamma", 100, 80, 0));

            // The client cannot finish until released below: even one synchronous fetch fails this bound.
            var response = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> service.getComparison(30, null, 0));
            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
            assertEquals(1, snapshots.get(), "all three rows share one price snapshot");
            assertEquals(stale ? 2 : 1, requests.get(), "only one refresh, excluding warm-up");
            assertEquals(3, response.models().size());
            assertEquals(stale ? new BigDecimal("0.0002160") : null,
                    row(response, "model.alpha").promptCaching().estimatedSavingsUsd());
            assertNull(row(response, "model.beta").promptCaching().estimatedSavingsUsd());
            assertNull(row(response, "model.gamma").promptCaching().estimatedSavingsUsd());

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.getComparison(31, null, 0));
            assertEquals(2, snapshots.get());
            // Simulate a slow failure: the full back-off starts when it finishes, not when it began.
            now.addAndGet(30_000L);
            failRefresh.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> catalog.listModels(false, null, OpenRouterService.NO_LIMIT, null));
            now.addAndGet(59_999L);
            catalog.listModels(false, null, OpenRouterService.NO_LIMIT, null);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.getComparison(32, null, 0));
            assertEquals(stale ? 2 : 1, requests.get(), "in-flight refresh and back-off suppress extra fetches");
        } finally {
            failRefresh.countDown();
            server.stop(0);
        }
    }

    @Test
    void comparisonReusesItsSnapshotEvenWhenLaterSnapshotsHaveDifferentPrices() {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        AnalyticsService service = service(List.of(game), List.of(), List.of());
        ((FakeMoveAttemptRepository) service.moveAttemptRepository).usage = List.of(
                usage(game, "model.alpha", 100, 80, 0), usage(game, "model.beta", 100, 80, 0));
        AtomicInteger snapshots = new AtomicInteger();
        service.openRouterService = new OpenRouterService() {
            @Override
            Map<String, BigDecimal> cacheReadSavingsSnapshot() {
                BigDecimal price = BigDecimal.valueOf(snapshots.incrementAndGet());
                return Map.of("model.alpha", price, "model.beta", price);
            }
        };
        var response = service.getComparison(30, null, 0);
        assertEquals(1, snapshots.get());
        assertEquals(new BigDecimal("80"), row(response, "model.alpha").promptCaching().estimatedSavingsUsd());
        assertEquals(new BigDecimal("80"), row(response, "model.beta").promptCaching().estimatedSavingsUsd());
    }

    @Test
    void conversionCountsWinningGamesOnceAndIncludesEveryDecisiveResult() {
        List<Game> games = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        for (Game.ResultReason reason : List.of(Game.ResultReason.CHECKMATE, Game.ResultReason.RESIGNATION,
                Game.ResultReason.ADJUDICATION, Game.ResultReason.TIMEOUT, Game.ResultReason.FORFEIT_INVALID_MOVES)) {
            Game game = finishedGame("model.alpha", "model.beta",
                    reason == Game.ResultReason.TIMEOUT || reason == Game.ResultReason.FORFEIT_INVALID_MOVES
                            ? Game.GameResult.BLACK_FORFEIT : Game.GameResult.WHITE_WINS, reason, YESTERDAY);
            games.add(game);
            // Alpha's winning position is first proved by the opponent's move. Overrides count as positions.
            Move first = evaluatedMove(game, "model.beta", 1, "BLACK", 600);
            first.isOverride = true;
            moves.add(first);
            moves.add(evaluatedMove(game, "model.alpha", 2, "WHITE", 1000));
        }
        var response = service(games, moves, List.of()).getComparison(30, null, 0);
        var alpha = row(response, "model.alpha").conversion();
        assertEquals(5L, alpha.evaluatedGames());
        assertEquals(5L, alpha.winningPositionsReached());
        assertEquals(5L, alpha.converted());
        assertEquals(1L, alpha.adjudicatedWins());
        assertEquals(0L, alpha.thrownAway());
        assertEquals(0L, row(response, "model.beta").conversion().winningPositionsReached());
    }

    @Test
    void conversionIncludesBothColorsDrawsLossesAndMissedMates() {
        Game draw = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW,
                Game.ResultReason.INSUFFICIENT_MATERIAL, YESTERDAY);
        Game loss = finishedGame("model.beta", "model.alpha", Game.GameResult.WHITE_WINS,
                Game.ResultReason.CHECKMATE, YESTERDAY);
        Game forfeit = finishedGame("model.beta", "model.alpha", Game.GameResult.BLACK_FORFEIT,
                Game.ResultReason.TIMEOUT, YESTERDAY);
        Move mate = evaluatedMove(draw, "model.beta", 1, "BLACK", null);
        mate.evaluationMate = 2;
        Move missed = evaluatedMove(draw, "model.alpha", 2, "WHITE", 1000);
        Move override = evaluatedMove(loss, "model.alpha", 1, "BLACK", null);
        override.evaluationMate = -2;
        override.isOverride = true;
        var response = service(List.of(draw, loss, forfeit), List.of(missed, mate, override,
                evaluatedMove(forfeit, "model.beta", 1, "WHITE", -1000)), List.of()).getComparison(30, null, 0);
        var alpha = row(response, "model.alpha").conversion();
        assertEquals(3L, alpha.winningPositionsReached());
        assertEquals(0L, alpha.converted());
        assertEquals(3L, alpha.thrownAway());
        assertEquals(1L, alpha.missedMates());
    }

    @Test
    void conversionDistinguishesNoDataFromZeroAndUsesTheNinetyPercentBoundary() {
        // Logistic model crosses 90% between 596 and 597 cp (the cutoff is not rounded).
        Game below = finishedGame("model.below", "model.other", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game at = finishedGame("model.at", "model.other", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        Game unknown = finishedGame("model.unknown", "model.other", Game.GameResult.DRAW, null, YESTERDAY);
        var response = service(List.of(below, at, unknown), List.of(
                evaluatedMove(below, "model.below", 1, "WHITE", 596),
                evaluatedMove(at, "model.at", 1, "WHITE", 597)), List.of()).getComparison(30, null, 0);
        assertEquals(0L, row(response, "model.below").conversion().winningPositionsReached());
        assertEquals(1L, row(response, "model.at").conversion().winningPositionsReached());
        var missing = row(response, "model.unknown").conversion();
        assertEquals(0L, missing.evaluatedGames());
        assertNull(missing.winningPositionsReached());
        assertNull(missing.converted());
        assertNull(missing.adjudicatedWins());
        assertNull(missing.thrownAway());
        assertNull(missing.missedMates());
        assertEquals(0L, row(response, "model.at").conversion().missedMates());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conversionIgnoresWinningAndEvaluatedPositionsFoundOnlyInLegacyDuplicates(boolean tiedTimestamp) {
        Game draw = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        Game unknown = finishedGame("model.unknown", "model.beta", Game.GameResult.WHITE_WINS, null, YESTERDAY);
        List<Move> moves = new ArrayList<>();
        for (Game game : List.of(draw, unknown)) {
            Move first = evaluatedMove(game, game.whiteModelId, 1, "WHITE", game == draw ? 0 : null);
            first.createdAt = YESTERDAY;
            first.id = new UUID(0, 1);
            Move duplicate = evaluatedMove(game, game.whiteModelId, 1, "WHITE", 1500);
            duplicate.createdAt = tiedTimestamp ? YESTERDAY : YESTERDAY.plusSeconds(1);
            duplicate.id = new UUID(0, 2);
            moves.add(duplicate); // Deliberately loaded before the canonical row.
            moves.add(first);
        }
        var response = service(List.of(draw, unknown), moves, List.of()).getComparison(30, null, 0);
        var alpha = row(response, "model.alpha").conversion();
        assertEquals(1L, alpha.evaluatedGames());
        assertEquals(0L, alpha.winningPositionsReached());
        assertEquals(0L, alpha.converted());
        assertEquals(0L, alpha.adjudicatedWins());
        assertEquals(0L, alpha.thrownAway());
        var missing = row(response, "model.unknown").conversion();
        assertEquals(0L, missing.evaluatedGames());
        assertNull(missing.winningPositionsReached());
        assertNull(missing.converted());
        assertNull(missing.missedMates());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conversionCountsEachMissedMateOnceAndUsesTheCanonicalPreviousPly(boolean tiedTimestamp) {
        Game game = finishedGame("model.alpha", "model.beta", Game.GameResult.DRAW, null, YESTERDAY);
        Move mate = evaluatedMove(game, "model.beta", 1, "BLACK", null);
        mate.evaluationMate = 2;
        mate.createdAt = YESTERDAY;
        mate.id = new UUID(0, 1);
        Move conflictingDuplicate = evaluatedMove(game, "model.beta", 1, "BLACK", 0);
        conflictingDuplicate.createdAt = tiedTimestamp ? YESTERDAY : YESTERDAY.plusSeconds(1);
        conflictingDuplicate.id = new UUID(0, 2);
        Move missed = evaluatedMove(game, "model.alpha", 2, "WHITE", 0);
        missed.createdAt = YESTERDAY;
        Move missedDuplicate = evaluatedMove(game, "model.alpha", 2, "WHITE", 0);
        missedDuplicate.createdAt = YESTERDAY.plusSeconds(1);
        var response = service(List.of(game), List.of(missedDuplicate, conflictingDuplicate, missed, mate), List.of())
                .getComparison(30, null, 0);
        var alpha = row(response, "model.alpha");
        assertEquals(1, alpha.analyzedMoves());
        assertEquals(1L, alpha.conversion().missedMates());
        assertEquals(1L, alpha.conversion().winningPositionsReached());
        assertEquals(1L, alpha.conversion().thrownAway());
    }

    @Test
    void conversionUsesOnlyFinishedNonByeModelSidesAndCountsMateZeroForItsMover() {
        Game human = finishedGame(null, "model.alpha", Game.GameResult.BLACK_WINS, Game.ResultReason.CHECKMATE, YESTERDAY);
        human.whiteParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
        Move mate = evaluatedMove(human, "model.alpha", 1, "BLACK", null);
        mate.evaluationMate = 0;
        Game active = unfinishedGame("model.active", "model.alpha", Game.GameStatus.IN_PROGRESS);
        Game bye = finishedGame("model.bye", null, Game.GameResult.WHITE_WINS, null, YESTERDAY);
        bye.blackParticipant = null;
        bye.blackPlayerName = "BYE";
        var response = service(List.of(human, active, bye), List.of(mate,
                evaluatedMove(active, "model.active", 1, "WHITE", 1500),
                evaluatedMove(bye, "model.bye", 1, "WHITE", 1500)), List.of()).getComparison(30, null, 0);
        assertEquals(List.of("model.alpha"), response.models().stream().map(AnalyticsModelComparisonRowDto::modelId).toList());
        var alpha = row(response, "model.alpha").conversion();
        assertEquals(1L, alpha.winningPositionsReached());
        assertEquals(1L, alpha.converted());
    }

    private MoveAttemptRepository.AttemptUsage usage(Game game, String model, Integer prompt,
                                                    Integer cached, Integer writes) {
        return new MoveAttemptRepository.AttemptUsage(game.id, model, prompt, cached, writes, null);
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
        service.openRouterService = new OpenRouterService() {
            @Override
            Map<String, BigDecimal> cacheReadSavingsSnapshot() {
                return Map.of("model.alpha", new BigDecimal("0.0000027"));
            }
        };
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
        return move(game, modelId, responseTimeMs, costUsd, retryCount, 1);
    }

    private Move move(Game game, String modelId, long responseTimeMs, String costUsd, int retryCount, int number) {
        Move move = new Move();
        move.id = UUID.randomUUID();
        move.game = game;
        move.color = Objects.equals(modelId, game.whiteModelId) ? "WHITE" : "BLACK";
        move.moveNumber = number;
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
        private LocalDateTime reasoningCutoff;
        private LocalDateTime costCutoff;

        private FakeMoveAttemptRepository(List<RejectedAttemptCost> costs) {
            this.costs = costs;
        }

        @Override
        public List<ReasoningAttempt> findReasoningAttempts(LocalDateTime cutoff, UUID tournamentId) {
            reasoningCutoff = cutoff;
            return List.of();
        }

        @Override
        public List<RejectedAttemptCost> findRejectedCostsInWindow(LocalDateTime cutoff, UUID tournamentId) {
            costCutoff = cutoff;
            return costs;
        }

        @Override
        public List<RejectedAttemptCost> findRejectedCostsByTournamentId(UUID tournamentId) {
            return costs;
        }

        private List<AttemptUsage> usage = List.of();

        @Override
        public List<AttemptUsage> findUsageInWindow(LocalDateTime cutoff, UUID tournamentId) {
            return usage;
        }

        private List<MoveAttemptRepository.ResignationTurnAttempt> resignationAttempts = List.of();

        @Override
        public List<MoveAttemptRepository.ResignationTurnAttempt> findResignationTurnAttemptsInWindow(
                LocalDateTime cutoff, UUID tournamentId) {
            return resignationAttempts;
        }
    }
}
