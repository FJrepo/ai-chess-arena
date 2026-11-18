package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@QuarkusTest
class BenchmarkRunIT {
    private static final String HASH = "1234".repeat(16);
    private static final String REQUEST = """
            {"name":"Ladder fixture","modelIds":["fixture/ladder"],"targetStrengths":[1500,2000],
             "legalMoveHints":false,"reasoningEffort":"low","chatMode":"OFF",
             "runSettings":{"budgetUsd":"1.00","maxConcurrentGames":2}}
            """;

    @Inject GameRepository games;
    @Inject TournamentRepository tournaments;
    @Inject GameConversationService conversations;
    @Inject TournamentAutoRunService runs;
    @Inject GameLifecycleService lifecycle;
    @Inject MoveAttemptService attempts;
    @Inject GameEngineService engine;
    private final List<UUID> benchmarks = new ArrayList<>();

    @BeforeEach
    void useControlledScheduling() {
        QuarkusMock.installMockForType(new TournamentRunScheduler() {
            @Override void changed(UUID id) {}
            @Override void tick() {}
        }, TournamentRunScheduler.class);
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override String benchmarkEngineSha256() { return HASH; }
        }, StockfishOpponentService.class);
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public boolean isModelAllowed(String modelId) { return modelId.startsWith("fixture/"); }
        }, OpenRouterService.class);
    }

    @AfterEach
    void removeFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : benchmarks) {
                tournaments.findByIdForUpdate(id);
                games.delete("tournament.id", id);
                tournaments.deleteById(id);
            }
        });
    }

    @Test
    void createsAndReloadsThirtyTwoGamesPerModelInColorReversedPairs() {
        UUID id = create(REQUEST.replace("[\"fixture/ladder\"]", "[\"fixture/ladder\",\"fixture/second\"]"));
        given().get("/api/benchmarks").then().statusCode(200).body("id", hasItem(id.toString()));
        var report = given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("definition.engineSha256", equalTo(HASH))
                .body("definition.searchNodes", equalTo(50000))
                .body("definition.openings.size()", equalTo(8))
                .body("models.size()", equalTo(2))
                .body("models[0].pairs.size()", equalTo(16))
                .body("models[0].incompletePairs", equalTo(16))
                .body("models[1].incompletePairs", equalTo(16));
        String gameId = report.extract().path("models[0].pairs[0].games[0].id");
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("benchmarkRunId", equalTo(id.toString())).body("tournamentId", nullValue())
                .body("whiteControlType", equalTo("AI")).body("blackControlType", equalTo("STOCKFISH"))
                .body("whiteReasoningEffort", equalTo("low")).body("blackReasoningEffort", nullValue());
        given().get("/api/tournaments").then().statusCode(200).body("id", not(hasItem(id.toString())));
        given().get("/api/tournaments/{id}", id).then().statusCode(404);
        given().get("/api/tournaments/{id}/export.json", id).then().statusCode(404);
        given().contentType("application/json").body("{}").post("/api/tournaments/{id}/run", id).then().statusCode(404);
        given().delete("/api/tournaments/{id}", id).then().statusCode(404);

        QuarkusTransaction.requiringNew().run(() -> {
            Tournament run = tournaments.findById(id);
            var openings = run.benchmarkDefinition.openings();
            assertTrue(openings.stream().filter(opening -> opening.moves().size() >= 26).count() >= 4);
            var pairs = games.findByTournamentId(id).stream().collect(Collectors.groupingBy(game -> game.benchmarkPairId));
            assertEquals(32, pairs.size());
            for (List<Game> pair : pairs.values()) {
                assertEquals(2, pair.size());
                Game first = pair.getFirst();
                Game second = pair.getLast();
                assertEquals(first.benchmarkModelId(), second.benchmarkModelId());
                assertEquals(first.stockfishElo, second.stockfishElo);
                assertEquals(first.openingId, second.openingId);
                assertNotEquals(first.benchmarkModelColor(), second.benchmarkModelColor());
                var opening = openings.stream().filter(line -> line.id().equals(first.openingId)).findFirst().orElseThrow();
                for (Game game : pair) {
                    assertNull(game.bracketRound);
                    assertNull(game.bracketPosition);
                    assertTrue(game.moves.stream().allMatch(move -> move.isBook && move.costUsd == null));
                    assertEquals(opening.moves(), game.moves.stream().map(move -> move.san).toList());
                    var board = conversations.initializeBoard(game.id, game);
                    assertEquals(first.currentFen, board.getFen());
                    assertEquals(opening.moves().size(), board.getBackup().size());
                }
            }
        });
    }

    @Test
    void stockfishCompletesAThreefoldRepetitionUsingTheFullOpeningHistory() throws InterruptedException {
        UUID runId = create(REQUEST);
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Tournament run = tournaments.findByIdForUpdate(runId);
            Game game = new Game();
            game.tournament = run;
            game.benchmarkPairId = UUID.randomUUID();
            game.openingId = "repetition-fixture";
            game.whiteModelId = "fixture/ladder";
            game.whitePlayerName = game.whiteModelId;
            game.blackPlayerName = "Stockfish";
            game.stockfishElo = 1500;
            OpeningBookService.apply(game, List.of("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1"));
            games.persist(game);
            return game.id;
        });
        var process = new StockfishServiceTest.FakeProcess(
                StockfishOpponentServiceTest.HANDSHAKE + "bestmove f6g8\n", false);
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override Process startBenchmarkProcess(String expectedSha256) {
                assertEquals(HASH, expectedSha256);
                return process;
            }
        }, StockfishOpponentService.class);

        given().contentType("application/json").body("{}").post("/api/games/{id}/start", gameId)
                .then().statusCode(200);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (engine.isRunning(gameId) && System.nanoTime() < deadline) Thread.sleep(20);
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("status", equalTo("COMPLETED")).body("resultReason", equalTo("REPETITION"))
                .body("moves.size()", equalTo(8)).body("moves[7].san", equalTo("Ng8"))
                .body("attempts.size()", equalTo(0));
        assertTrue(process.commands.toString(StandardCharsets.UTF_8)
                .contains("position startpos moves g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1\ngo nodes 50000"));
    }

    @Test
    void reportsScoredAndIncompletePairsAndKeepsAllBenchmarkSpendOutOfModelMetrics() {
        UUID id = create(REQUEST);
        List<UUID> ordered = gameIds(id);
        UUID first = ordered.getFirst();
        UUID second = ordered.get(1);
        UUID third = ordered.get(2);
        UUID fourth = ordered.get(3);
        lifecycle.completeGame(first, new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
        lifecycle.completeGame(second, new ChessService.GameEndInfo("DRAW", "REPETITION"));
        lifecycle.markGameStarted(third);
        lifecycle.forfeitInvalidMoves(third, "WHITE", 3);
        lifecycle.cancelGame(fourth);
        pay(first, "WHITE", 0.10);
        pay(third, "WHITE", 0.20);
        pay(fourth, "BLACK", 0.30);
        pay(ordered.get(4), "WHITE", 0.05);

        given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("spendUsd", equalTo(0.65f))
                .body("models[0].spendUsd", equalTo(0.65f))
                .body("models[0].overall.wins", equalTo(1))
                .body("models[0].overall.draws", equalTo(1))
                .body("models[0].overall.losses", equalTo(1))
                .body("models[0].overall.forfeits", equalTo(1))
                .body("models[0].overall.points", equalTo(1.5f))
                .body("models[0].overall.scoredGames", equalTo(3))
                .body("models[0].incompletePairs", equalTo(15))
                .body("models[0].splits[0].score.wins", equalTo(1))
                .body("models[0].splits[1].score.draws", equalTo(1))
                .body("models[0].splits[2].score.losses", equalTo(1))
                .body("models[0].splits[3].score.scoredGames", equalTo(0))
                .body("models[0].pairs[0].complete", equalTo(true))
                .body("models[0].pairs[1].complete", equalTo(false))
                .body("models[0].pairs[1].games[1].status", equalTo("CANCELLED"));
        given().get("/api/analytics/health?tournamentId=" + id).then().statusCode(200)
                .body("benchmarkCostUsd", equalTo(0.65f)).body("costTotalUsd", equalTo(0.65f))
                .body("practiceCostUsd", equalTo(0.0f)).body("models.size()", equalTo(0));
        given().get("/api/analytics/comparison?tournamentId=" + id).then().statusCode(200)
                .body("models.size()", equalTo(0));
        given().get("/api/analytics/reliability?tournamentId=" + id).then().statusCode(200)
                .body("models.size()", equalTo(0));
        given().get("/api/analytics/leaderboard").then().statusCode(200)
                .body("models.modelId", not(hasItem("fixture/ladder")));

        ordered.stream().skip(4).forEach(lifecycle::cancelGame);
        runs.schedule(id);
        given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("run.status", equalTo("FINISHED")).body("models[0].incompletePairs", equalTo(15));
        assertEquals(32, gameIds(id).size());
    }

    @Test
    void usesExistingConcurrencyAndBudgetStopsAndCanRaiseTheCap() {
        UUID id = create(REQUEST);
        GameEngineService realEngine = ClientProxy.unwrap(engine);
        QuarkusMock.installMockForType(new GameEngineService() {
            @Override public GameDto startScheduledGame(UUID gameId) {
                return lifecycle.markGameStarted(gameId, true, false, () -> true);
            }
        }, GameEngineService.class);
        List<UUID> ordered = gameIds(id);
        runs.schedule(id);
        assertEquals(2L, QuarkusTransaction.requiringNew().call(() -> games.countRunning(id)));
        pay(ordered.getFirst(), "WHITE", 1.01);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> games.countRunning(id)));
        given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("run.status", equalTo("PAUSED")).body("run.pauseReason", containsString("Budget"))
                .body("models[0].pairs[0].games.status", equalTo(List.of("PAUSED", "PAUSED")));
        given().contentType("application/json").body("{\"budgetUsd\":null}")
                .post("/api/benchmarks/{id}/resume", id).then().statusCode(400);
        given().contentType("application/json").body("{\"budgetUsd\":\"2.00\",\"maxConcurrentGames\":1}")
                .post("/api/benchmarks/{id}/resume", id).then().statusCode(200)
                .body("budgetUsd", equalTo("2.00")).body("run.status", equalTo("RUNNING"));
        runs.schedule(id);
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, games.countRunning(id));
            assertEquals(GameStatus.IN_PROGRESS, games.findById(ordered.get(2)).status);
            assertEquals(new BigDecimal("1.01000000"), games.findById(ordered.getFirst()).totalCostUsd);
        });
        given().post("/api/benchmarks/{id}/pause", id).then().statusCode(200).body("run.status", equalTo("PAUSED"));
        QuarkusMock.installMockForType(realEngine, GameEngineService.class);
        given().contentType("application/json").body("{\"move\":\"e4\"}")
                .post("/api/games/{id}/override-move", ordered.getFirst()).then().statusCode(409);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"targetStrengths\":[1500,2000] | \"targetStrengths\":[1500,1500]",
        "\"targetStrengths\":[1500,2000] | \"targetStrengths\":[1500.5,2000]",
        "\"targetStrengths\":[1500,2000] | \"targetStrengths\":[1319,2000]",
        "\"modelIds\":[\"fixture/ladder\"] | \"modelIds\":[\"fixture/ladder\",\"fixture/ladder\"]",
        "\"modelIds\":[\"fixture/ladder\"] | \"modelIds\":[]",
        "\"modelIds\":[\"fixture/ladder\"] | \"modelIds\":[123]",
        "\"legalMoveHints\":false | \"legalMoveHints\":\"false\"",
        "\"reasoningEffort\":\"low\" | \"reasoningEffort\":\"extreme\"",
        "\"chatMode\":\"OFF\" | \"chatMode\":false",
        "\"budgetUsd\":\"1.00\" | \"budgetUsd\":0",
        "\"budgetUsd\":\"1.00\" | \"budgetUsd\":0.001",
        "\"budgetUsd\":\"1.00\" | \"budgetUsd\":1.00000000000000001",
        "\"budgetUsd\":\"1.00\" | \"budgetUsd\":2,\"budgetUsd\":1",
        "\"budgetUsd\":\"1.00\" | \"budgetUsd\":null",
        "\"maxConcurrentGames\":2 | \"maxConcurrentGames\":9",
        "\"maxConcurrentGames\":2 | \"maxConcurrentGames\":\"2\"",
        "\"name\":\"Ladder fixture\" | \"name\":\"Ladder fixture\",\"variant\":\"chess960\""
    })
    void rejectsInvalidSettingsWithoutPersisting(String original, String substitute) {
        long before = QuarkusTransaction.requiringNew().call(tournaments::count);
        given().contentType("application/json").body(REQUEST.replace(original, substitute))
                .post("/api/benchmarks").then().statusCode(400);
        assertEquals(before, QuarkusTransaction.requiringNew().call(tournaments::count).longValue());
    }

    @Test
    void missingEngineReturnsConflictBeforeAnyRunIsCreated() {
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override String benchmarkEngineSha256() throws IOException { throw new IOException("Test engine missing"); }
        }, StockfishOpponentService.class);
        long before = QuarkusTransaction.requiringNew().call(tournaments::count);
        given().contentType("application/json").body(REQUEST).post("/api/benchmarks").then().statusCode(409);
        assertEquals(before, QuarkusTransaction.requiringNew().call(tournaments::count).longValue());
    }

    private UUID create(String request) {
        var response = given().contentType("application/json").body(request).post("/api/benchmarks")
                .then().statusCode(201);
        String id = response.extract().path("run.id");
        assertNotNull(id);
        UUID parsed = UUID.fromString(id);
        benchmarks.add(parsed);
        return parsed;
    }

    private List<UUID> gameIds(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(id).stream()
                .sorted(BenchmarkRunService.pairOrder()).map(game -> game.id).toList());
    }

    private void pay(UUID id, String color, double cost) {
        attempts.record(new MoveAttemptService.AttemptRecord(id, 12, color, 1, "fixture/ladder",
                Outcome.DISCARDED, "e4", null,
                new OpenRouterService.LlmResponse("e4", 10, 3, cost, 1, null, false, null)));
    }
}
