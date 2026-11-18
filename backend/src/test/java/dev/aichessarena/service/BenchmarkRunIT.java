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
import dev.aichessarena.entity.BenchmarkDefinition.Network;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class BenchmarkRunIT {
    private static final String HASH = StockfishOpponentServiceTest.TEST_SHA256;
    private static final String NETWORK = StockfishOpponentServiceTest.NETWORK_NAME;
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
    @Inject MoveAttemptRepository attemptRows;
    @Inject EvaluationProbe evaluationProbe;
    @ConfigProperty(name = "quarkus.datasource.jdbc.url") String jdbcUrl;
    @ConfigProperty(name = "quarkus.datasource.username") String username;
    @ConfigProperty(name = "quarkus.datasource.password") String password;
    private final AtomicInteger schedules = new AtomicInteger();
    private final List<UUID> benchmarks = new ArrayList<>();

    @BeforeEach
    void useControlledScheduling() {
        QuarkusMock.installMockForType(new TournamentRunScheduler() {
            @Override void changed(UUID id) { schedules.incrementAndGet(); }
            @Override void tick() {}
        }, TournamentRunScheduler.class);
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override EngineFingerprint benchmarkFingerprint(List<Integer> strengths) {
                return new EngineFingerprint(HASH, List.of(new Network(NETWORK, HASH)));
            }
        }, StockfishOpponentService.class);
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public boolean isModelAllowed(String modelId) { return modelId.startsWith("fixture/") || modelId.startsWith("~fixture/"); }
        }, OpenRouterService.class);
    }

    @AfterEach
    void removeFixtures() {
        EvaluationProbe probe = ClientProxy.unwrap(evaluationProbe);
        probe.events.clear();
        probe.afterCommit = null;
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : benchmarks) {
                tournaments.findByIdForUpdate(id);
                games.delete("tournament.id", id);
                tournaments.deleteById(id);
            }
        });
    }

    @Test
    void requiresAClientRequestUuid() {
        var response = given().contentType("application/json").body(REQUEST).post("/api/benchmarks");
        if (response.statusCode() == 201) benchmarks.add(UUID.fromString(response.path("run.id")));
        response.then().statusCode(400);
    }

    @Test
    void concurrentCreationAndReplayReturnOnePaidRun() throws Exception {
        String body = withRequestId(REQUEST);
        Callable<io.restassured.response.Response> submit = () ->
                given().contentType("application/json").body(body).post("/api/benchmarks");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var responses = pool.invokeAll(List.of(submit, submit));
            var first = responses.getFirst().get();
            var second = responses.getLast().get();
            for (var response : List.of(first, second)) {
                if (response.statusCode() == 201) benchmarks.add(UUID.fromString(response.path("run.id")));
            }
            assertEquals(List.of(200, 201), List.of(first.statusCode(), second.statusCode()).stream().sorted().toList());
            assertEquals(first.<String>path("run.id"), second.<String>path("run.id"));
            QuarkusMock.installMockForType(new StockfishOpponentService() {
                @Override EngineFingerprint benchmarkFingerprint(List<Integer> strengths) throws IOException {
                    throw new IOException("Engine removed");
                }
            }, StockfishOpponentService.class);
            submit.call().then().statusCode(200).body("run.id", equalTo(first.<String>path("run.id")));
            assertEquals(1, schedules.get());
            assertEquals(32, gameIds(benchmarks.getFirst()).size());
        }
    }

    @Test
    void rerunOnlyAcceptsOneActualChangeOrAnExplicitEmptyChange() {
        UUID base = create(REQUEST);
        for (String change : List.of(
                "null", "[]", "{\"legalMoveHints\":false}", "{\"reasoningEffort\":\"extreme\"}",
                "{\"legalMoveHints\":true,\"chatMode\":\"OPTIONAL\"}", "{\"suite\":\"another\"}",
                "{\"modelId\":{\"from\":\"fixture/absent\",\"to\":\"fixture/second\"}}")) {
            rerun(base, withRequestId("{\"change\":" + change + "}")).then().statusCode(400);
        }
        assertEquals(1, schedules.get());
        UUID candidate = createRerun(base, "{}");
        assertEquals(32, gameIds(candidate).size());
        given().get("/api/benchmarks/{id}", candidate).then().statusCode(200)
                .body("definition.promptVersion", equalTo("v2-silent-no-hints"))
                .body("run.rerunOf", equalTo(base.toString()));
    }

    @Test
    void concurrentRerunReplaysOneCreationAndRejectsAConflictingIntent() throws Exception {
        UUID base = create(REQUEST);
        String body = withRequestId("{\"change\":{\"legalMoveHints\":true}}");
        Callable<io.restassured.response.Response> submit = () -> rerun(base, body);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var responses = pool.invokeAll(List.of(submit, submit));
            List<Integer> statuses = new ArrayList<>();
            for (var future : responses) {
                var response = future.get();
                statuses.add(response.statusCode());
                if (response.statusCode() == 201) benchmarks.add(UUID.fromString(response.path("run.id")));
                response.then().body("legalMoveHints", equalTo(true))
                        .body("definition.promptVersion", equalTo("v2-silent"));
            }
            assertEquals(List.of(200, 201), statuses.stream().sorted().toList());
            rerun(base, body.replace("\"legalMoveHints\":true", "\"chatMode\":\"OPTIONAL\""))
                    .then().statusCode(409);
            assertEquals(2, schedules.get());
        }
    }

    @Test
    void rerunFailsClosedOnStaleLimitsAndDifferentWeightsButReplaysAfterEngineChanges() {
        UUID base = create(REQUEST);
        rerun(base, withRequestId("""
                {"change":{},"expectedRunSettings":{"budgetUsd":"2.00","maxConcurrentGames":2}}
                """)).then().statusCode(409);
        String body = withRequestId("""
                {"change":{"chatMode":"MANDATORY"},"expectedRunSettings":{"budgetUsd":"1.00","maxConcurrentGames":2}}
                """);
        var created = rerun(base, body).then().statusCode(201);
        String candidate = created.extract().path("run.id");
        benchmarks.add(UUID.fromString(candidate));
        resumeWithHigherLimits(base);
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override EngineFingerprint benchmarkFingerprint(List<Integer> strengths) {
                return new EngineFingerprint(HASH, List.of(new Network(NETWORK, "different-weights")));
            }
        }, StockfishOpponentService.class);
        rerun(base, withRequestId("{\"change\":{}}")).then().statusCode(409);
        rerun(base, body).then().statusCode(200).body("run.id", equalTo(candidate));
        assertEquals(3, schedules.get());
    }

    @Test
    void rejectsStaleLimitsWhenTheSourceResumesDuringEngineVerification() throws Exception {
        UUID base = create(REQUEST);
        String body = withRequestId("""
                {"change":{},"expectedRunSettings":{"budgetUsd":"1.00","maxConcurrentGames":2}}
                """);
        var verifying = new CountDownLatch(1);
        var allowVerification = new CountDownLatch(1);
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override EngineFingerprint benchmarkFingerprint(List<Integer> strengths) {
                verifying.countDown();
                try {
                    assertTrue(allowVerification.await(6, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return new EngineFingerprint(HASH, List.of(new Network(NETWORK, HASH)));
            }
        }, StockfishOpponentService.class);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> rerun(base, body));
            try {
                assertTrue(verifying.await(6, TimeUnit.SECONDS));
                resumeWithHigherLimits(base);
            } finally {
                allowVerification.countDown();
            }
            var response = pending.get(6, TimeUnit.SECONDS);
            if (response.statusCode() == 201) benchmarks.add(UUID.fromString(response.path("run.id")));
            response.then().statusCode(409).body("error", containsString("source run limits changed"));
            assertEquals(2, schedules.get());
        }
    }

    private static void resumeWithHigherLimits(UUID base) {
        given().contentType("application/json").body("""
                {"budgetUsd":"2.00","maxConcurrentGames":3}
                """).post("/api/benchmarks/{id}/resume", base).then().statusCode(200);
    }

    @Test
    void comparesMatchedGamesAndAllSpend() {
        UUID base = create(REQUEST);
        UUID candidate = createRerun(base, "{\"modelId\":{\"from\":\"fixture/ladder\",\"to\":\"fixture/second\"}}");
        var before = gameIds(base);
        var after = gameIds(candidate);
        lifecycle.completeGame(before.getFirst(), new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
        String firstMatchup = "matchups.find { it.base.gameId == '" + before.getFirst() + "' }";
        compare(base, candidate).then().statusCode(200)
                .body(firstMatchup + ".outcomeChanged", equalTo(false))
                .body(firstMatchup + ".candidate.pairIncomplete", equalTo(true));
        lifecycle.markGameStarted(after.getFirst());
        lifecycle.forfeitInvalidMoves(after.getFirst(), "WHITE", 3);
        lifecycle.completeGame(after.get(1), new ChessService.GameEndInfo("DRAW", "STALEMATE"));
        attempts.record(new MoveAttemptService.AttemptRecord(before.getFirst(), 77, "WHITE", 1,
                "fixture/ladder", Outcome.PROVIDER_RETRYABLE, null, "provider failed", null));
        addTelemetry(before.getFirst(), "fixture/ladder", 0, 0);
        addTelemetry(after.getFirst(), "fixture/second", 1, 32);
        pay(before.getFirst(), "WHITE", 0.10);
        pay(after.getFirst(), "WHITE", 0.25);
        pay(after.get(3), "BLACK", 0.50);
        given().queryParam("base", base).queryParam("candidate", candidate).get("/api/benchmarks/compare").then()
                .statusCode(200).body("comparable", equalTo(true))
                .body("models[0].baseModelId", equalTo("fixture/ladder"))
                .body("models[0].candidateModelId", equalTo("fixture/second"))
                .body("models[0].scoreDelta", equalTo(-1.0f))
                .body("models[0].matchedGames", equalTo(1))
                .body("models[0].base.incompletePairs", equalTo(16))
                .body("models[0].candidate.incompletePairs", equalTo(15))
                .body("models[0].base.telemetry.firstTryLegalRate", equalTo(1.0f))
                .body("models[0].base.telemetry.reasoningTokensPerMove", equalTo(0.0f))
                .body("models[0].candidate.score.forfeits", equalTo(1))
                .body("models[0].candidate.telemetry.turnsSampled", equalTo(2))
                .body("models[0].candidate.telemetry.reasoningTokensPerMove", equalTo(32.0f))
                .body("matchups[0].candidate.forfeit", equalTo(true))
                .body("spendDeltaUsd", equalTo(0.65f))
                .body("matchups[0].base.gameId", equalTo(before.getFirst().toString()))
                .body("matchups[0].candidate.gameId", equalTo(after.getFirst().toString()))
                .body("matchups[0].spendDeltaUsd", equalTo(0.15f))
                .body("matchups.findAll { it.outcomeChanged }.size()", equalTo(1))
                .body("matchups.find { it.candidate.gameId == '" + after.get(1) + "' }.base.pairIncomplete",
                        equalTo(true));
        given().queryParam("base", "invalid").queryParam("candidate", candidate)
                .get("/api/benchmarks/compare").then().statusCode(400);
        given().queryParam("base", base, candidate).queryParam("candidate", candidate)
                .get("/api/benchmarks/compare").then().statusCode(400);
        given().queryParam("candidate", candidate).get("/api/benchmarks/compare").then().statusCode(400);
        given().queryParam("base", base).queryParam("candidate", base)
                .get("/api/benchmarks/compare").then().statusCode(400);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "suite | \"another-suite\" | Opening suite",
        "openings,0,moves | [\"d4\",\"d5\"] | Opening line ruy-lopez-closed",
        "openingSeed | 42 | Opening seed",
        "engineSha256 | \"another-engine\" | Stockfish executable SHA-256",
        "engineNetworks,0,sha256 | \"another-network\" | Stockfish NNUE weights",
        "searchNodes | 75000 | Search nodes",
        "threads | 2 | Threads",
        "hashMiB | 32 | Hash (MiB)",
        "targetStrengths | [1600,2000] | Stockfish levels",
        "modelColors | [\"WHITE\"] | Model colors",
        "promptVersion | \"v3-silent-no-hints\" | Prompt version"
    })
    void refusesIsolatedSetupMismatch(String key, String value, String label) {
        UUID base = create(REQUEST);
        UUID candidate = createRerun(base, "{}");
        QuarkusTransaction.requiringNew().run(() -> tournaments.getEntityManager().createNativeQuery("""
                UPDATE tournaments
                SET benchmark_definition = jsonb_set(benchmark_definition, cast(:key as text[]), cast(:value as jsonb))
                WHERE id = :id
                """).setParameter("key", "{" + key + "}").setParameter("value", value)
                .setParameter("id", candidate).executeUpdate());

        compare(base, candidate).then().statusCode(200)
                .body("comparable", equalTo(false))
                .body("differences.setting", equalTo(List.of(label)))
                .body("models.size()", equalTo(0)).body("matchups.size()", equalTo(0))
                .body("spendDeltaUsd", nullValue());
    }

    @Test
    void distinguishesAControlledRerunFromChangesToSeveralSettings() {
        UUID base = create(REQUEST);
        UUID rerun = createRerun(base, "{\"legalMoveHints\":true}");
        UUID independent = create(REQUEST.replace("\"legalMoveHints\":false", "\"legalMoveHints\":true")
                .replace("\"low\"", "\"high\""));
        compare(base, rerun).then().statusCode(200).body("comparable", equalTo(true))
                .body("comparisonType", equalTo("CONTROLLED_RERUN"));
        compare(rerun, base).then().statusCode(200).body("comparisonType", equalTo("CONTROLLED_RERUN"));
        compare(base, independent).then().statusCode(200).body("comparable", equalTo(true))
                .body("comparisonType", equalTo("MULTIPLE_SETTINGS")).body("models.size()", equalTo(1));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{} | 0",
        "{\"budgetUsd\":\"3.00\"} | 0",
        "{\"legalMoveHints\":true} | 2"
    })
    void operationalLimitsDoNotChangeComparability(String change, int settingChanges) {
        UUID base = create(REQUEST);
        UUID rerun = createRerun(base, change);
        compare(base, rerun).then().statusCode(200).body("comparisonType", equalTo("CONTROLLED_RERUN"));

        resumeWithHigherLimits(rerun);
        compare(base, rerun).then().statusCode(200)
                .body("comparable", equalTo(true))
                .body("comparisonType", equalTo("CONTROLLED_RERUN"))
                .body("settingChanges.size()", equalTo(settingChanges))
                .body("settingChanges.setting", not(hasItem("Current budget (USD)")))
                .body("settingChanges.setting", not(hasItem("Concurrent games")))
                .body("operationalLimitsNote", equalTo("Budget and concurrency are operational limits and are not compared"));
    }

    @Test
    void comparisonReportsSpendCheckPauseDetails() {
        UUID base = create(REQUEST);
        UUID candidate = createRerun(base, "{}");
        UUID paused = gameIds(base).getFirst();
        lifecycle.markGameStarted(paused);
        QuarkusTransaction.requiringNew().run(() -> games.findByIdForUpdate(paused).requestSentAt = Instant.now());
        lifecycle.pauseForInspection(paused, GameLifecycleService.DELIVERY_UNCERTAIN);

        String matchup = "matchups.find { it.base.gameId == '" + paused + "' }";
        compare(base, candidate).then().statusCode(200)
                .body(matchup + ".base.status", equalTo("PAUSED"))
                .body(matchup + ".base.statusDetail", containsString(GameLifecycleService.DELIVERY_UNCERTAIN))
                .body(matchup + ".base.uncertainRequest", equalTo(true))
                .body(matchup + ".candidate.uncertainRequest", equalTo(false))
                .body(matchup + ".base.points", nullValue());
    }

    @Test
    void excludesARetriedPlyFromFirstTryLegalityEvenWhenTheAttemptCounterRestarts() throws Exception {
        UUID base = create(REQUEST.replace("\"1.00\"", "\"0.01\""));
        UUID candidate = createRerun(base, "{}");
        UUID game = gameIds(base).getFirst();
        String legalMove = QuarkusTransaction.requiringNew().call(() ->
                new ChessService().getLegalMovesAsSan(conversations.initializeBoard(game, games.findById(game))).getFirst());
        var calls = new AtomicInteger();
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String modelId, List<ChatMsg> history, AnswerDeadline deadline,
                                              int maxOutputTokens, dev.aichessarena.entity.ReasoningEffort effort) {
                String move = calls.getAndIncrement() == 0 ? "Qh8" : legalMove;
                return new LlmResponse("{\"move\":\"" + move + "\"}", 10, 3, 0.01, 1, null, false, null);
            }
        }, OpenRouterService.class);
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", game).then().statusCode(200);
        awaitStatus(game, "PAUSED");
        given().get("/api/games/{id}", game).then().statusCode(200)
                .body("statusDetail", containsString("Budget"))
                .body("attempts[0].outcome", equalTo("ILLEGAL_MOVE"));
        given().contentType("application/json").body("{\"budgetUsd\":\"0.02\"}")
                .post("/api/benchmarks/{id}/resume", base).then().statusCode(200);
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", game).then().statusCode(200);
        awaitStatus(game, "PAUSED");
        given().get("/api/games/{id}", game).then().statusCode(200)
                .body("attempts.outcome", equalTo(List.of("ILLEGAL_MOVE", "ACCEPTED")))
                .body("attempts.attemptNumber", equalTo(List.of(1, 1)));
        compare(base, candidate).then().statusCode(200)
                .body("models[0].base.telemetry.turnsSampled", equalTo(1))
                .body("models[0].base.telemetry.firstTryMoves", equalTo(0))
                .body("models[0].base.telemetry.firstTryLegalRate", equalTo(0.0f));
    }

    private static io.restassured.response.Response compare(UUID base, UUID candidate) {
        return given().queryParam("base", base).queryParam("candidate", candidate).get("/api/benchmarks/compare");
    }

    @Test
    void upgradesAllV31PromptVariantsAndKeepsResultsSpendAndReruns() throws Exception {
        String database = "v33_benchmarks_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        List<UUID> migrated;
        try {
            migrateBenchmarks(url, "31");
            try (var connection = DriverManager.getConnection(url, username, password)) {
                seedV31Benchmarks(connection);
            }
            assertEquals(1, migrateBenchmarks(url, "32"));
            assertEquals(1, migrateBenchmarks(url, "33"));
            migrated = loadMigratedBenchmarks(url);
        } finally {
            try (var admin = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }

        List<String> versions = List.of("v2-chat-required-no-hints", "v2-chat-required", "v2-silent-no-hints",
                "v2-silent", "v2-no-hints", "v2");
        assertEquals(versions.size(), migrated.size());
        for (int variant = 0; variant < versions.size(); variant++) {
            UUID base = migrated.get(variant);
            String version = versions.get(variant);
            given().get("/api/benchmarks/{id}", base).then().statusCode(200)
                    .body("definition.promptVersion", equalTo(version))
                    .body("run.status", equalTo("PAUSED")).body("run.pauseReason", equalTo("Backend restarted"))
                    .body("models[0].overall.wins", equalTo(1)).body("spendUsd", equalTo(0.37f))
                    .body("models[0].pairs[0].games.status", equalTo(List.of("COMPLETED", "PAUSED")));
            given().get("/api/games/{id}", gameIds(base).getFirst()).then().statusCode(200)
                    .body("result", equalTo("WHITE_WINS")).body("resultReason", equalTo("CHECKMATE"))
                    .body("attempts[0].costUsd", equalTo(0.25f));
            given().contentType("application/json").body("{}").post("/api/benchmarks/{id}/resume", base).then()
                    .statusCode(200).body("run.status", equalTo("RUNNING")).body("spendUsd", equalTo(0.37f));
            UUID candidate = createRerun(base, "{}");
            given().get("/api/benchmarks/{id}", candidate).then().statusCode(200)
                    .body("definition.promptVersion", equalTo(version)).body("run.rerunOf", equalTo(base.toString()))
                    .body("models[0].overall.scoredGames", equalTo(0)).body("spendUsd", equalTo(0.0f));
            assertEquals(4, gameIds(candidate).size());
        }
    }

    private int migrateBenchmarks(String url, String target) {
        return Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").target(target).load().migrate().migrationsExecuted;
    }

    private static void seedV31Benchmarks(Connection connection) throws Exception {
        try (var insertRuns = connection.prepareStatement("""
                INSERT INTO tournaments (name, status, auto_run_status, chat_mode, legal_move_hints,
                                         budget_usd, client_request_id, benchmark_definition)
                SELECT 'V31 ' || mode || ' ' || hints, 'IN_PROGRESS', 'RUNNING', mode, hints,
                       1.00, gen_random_uuid(), cast(? as jsonb)
                FROM unnest(ARRAY['OFF', 'OPTIONAL', 'MANDATORY']) mode
                CROSS JOIN unnest(ARRAY[false, true]) hints
                """)) {
            insertRuns.setString(1, """
                    {"suite":"v31-fixture","openings":[{"id":"fixture","name":"Fixture","moves":["e4","e5"]}],
                     "modelIds":["fixture/ladder"],"targetStrengths":[1500,2000],"modelColors":["WHITE","BLACK"],
                     "engineSha256":"%s","engineNetworks":[{"name":"%s","sha256":"%s"}],
                     "searchNodes":50000,"threads":1,"hashMiB":16}
                    """.formatted(HASH, NETWORK, HASH));
            insertRuns.executeUpdate();
        }
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    WITH pairs AS MATERIALIZED (SELECT id, gen_random_uuid() AS pair_id FROM tournaments)
                    INSERT INTO games (tournament_id, benchmark_pair_id, opening_id, stockfish_elo, white_model_id,
                                       black_model_id, status, result, result_reason, total_cost_usd)
                    SELECT id, pair_id, 'fixture', 1500, 'fixture/ladder', null, 'COMPLETED', 'WHITE_WINS',
                           'CHECKMATE', 0.25 FROM pairs
                    UNION ALL
                    SELECT id, pair_id, 'fixture', 1500, null, 'fixture/ladder', 'IN_PROGRESS', null,
                           null, 0.12 FROM pairs;

                    INSERT INTO move_attempts (id, game_id, move_number, color, attempt_number, model_id, outcome, cost_usd)
                    SELECT gen_random_uuid(), id, 2, 'WHITE', 1, 'fixture/ladder', 'ACCEPTED', 0.25
                    FROM games WHERE status = 'COMPLETED'
                    UNION ALL
                    SELECT gen_random_uuid(), id, 2, 'BLACK', 1, 'fixture/ladder', 'ILLEGAL_MOVE', 0.12
                    FROM games WHERE status = 'IN_PROGRESS';
                    """);
        }
    }

    private List<UUID> loadMigratedBenchmarks(String upgradedUrl) throws Exception {
        try (var upgraded = DriverManager.getConnection(upgradedUrl, username, password);
             var destination = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = upgraded.createStatement()) {
            // Use the migrated rows in the running test app without changing its datasource.
            destination.setAutoCommit(false);
            for (String table : List.of("tournaments", "games", "move_attempts")) {
                try (var rows = statement.executeQuery("SELECT json_agg(t)::text FROM " + table + " t");
                     var insert = destination.prepareStatement("INSERT INTO " + table
                             + " SELECT * FROM json_populate_recordset(null::" + table + ", cast(? as json))")) {
                    assertTrue(rows.next());
                    insert.setString(1, rows.getString(1));
                    insert.executeUpdate();
                }
            }
            destination.commit();
            var ids = new ArrayList<UUID>();
            try (var rows = statement.executeQuery("SELECT id FROM tournaments ORDER BY chat_mode, legal_move_hints")) {
                while (rows.next()) ids.add(rows.getObject(1, UUID.class));
            }
            benchmarks.addAll(ids);
            return ids;
        }
    }

    private void addTelemetry(UUID gameId, String modelId, int retries, int reasoningTokens) {
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findByIdForUpdate(gameId);
            Move move = new Move();
            move.game = game;
            move.color = game.benchmarkModelColor();
            move.moveNumber = 77;
            move.san = "e4";
            move.fen = game.currentFen;
            move.modelId = modelId;
            move.retryCount = retries;
            move.persist();
            MoveAttempt accepted = new MoveAttempt();
            accepted.game = game;
            accepted.color = move.color;
            accepted.moveNumber = move.moveNumber;
            accepted.attemptNumber = retries + 1;
            accepted.modelId = modelId;
            accepted.outcome = Outcome.ACCEPTED;
            accepted.reasoningTokens = reasoningTokens;
            accepted.persist();
        });
    }

    private UUID createRerun(UUID base, String change) {
        var response = rerun(base, withRequestId("{\"change\":" + change + "}")).then().statusCode(201);
        UUID id = UUID.fromString(response.extract().path("run.id"));
        benchmarks.add(id);
        return id;
    }

    private static io.restassured.response.Response rerun(UUID base, String body) {
        return given().contentType("application/json").body(body).post("/api/benchmarks/{id}/rerun", base);
    }

    @Test
    void cancellationPreservesResultsAndLateChargesAndClearsQueuedResumes() {
        UUID id = create(REQUEST);
        var ids = gameIds(id);
        UUID completed = ids.getFirst();
        UUID running = ids.get(1);
        UUID queued = ids.get(2);
        lifecycle.completeGame(completed, new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
        lifecycle.markGameStarted(running);
        lifecycle.markGameStarted(queued);
        lifecycle.markGamePaused(queued);
        QuarkusTransaction.requiringNew().run(() -> games.findByIdForUpdate(queued).resumeRequestedAt = Instant.now());

        given().post("/api/tournaments/{id}/cancel", id).then().statusCode(404);
        given().post("/api/benchmarks/{id}/cancel", id).then().statusCode(200)
                .body("run.status", equalTo("CANCELLED")).body("models[0].overall.wins", equalTo(1))
                .body("models[0].pairs[0].games.status", equalTo(List.of("COMPLETED", "CANCELLED")));
        pay(running, "BLACK", 0.12);
        runs.schedule(id);
        given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("run.status", equalTo("CANCELLED")).body("spendUsd", equalTo(0.12f));
        given().get("/api/benchmarks").then().statusCode(200)
                .body("find { it.id == '" + id + "' }.status", equalTo("CANCELLED"));
        given().post("/api/benchmarks/{id}/cancel", id).then().statusCode(409);
        given().contentType("application/json").body("{}").post("/api/benchmarks/{id}/resume", id).then().statusCode(409);
        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(games.findByTournamentId(id).stream().allMatch(game -> game.resumeRequestedAt == null));
            assertEquals(1, games.findByTournamentId(id).stream().filter(game -> game.status == GameStatus.COMPLETED).count());
            assertEquals(0, games.countRunning(id));
        });
    }

    @Test
    void cancellationAtModelMateCommitKeepsTheScoredWinAndCharge() throws Exception {
        UUID runId = create(REQUEST);
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.tournament = tournaments.findByIdForUpdate(runId);
            game.benchmarkPairId = UUID.randomUUID();
            game.openingId = "mate-fixture";
            game.whiteModelId = "fixture/ladder";
            game.whitePlayerName = game.whiteModelId;
            game.blackPlayerName = "Stockfish";
            game.stockfishElo = 1500;
            OpeningBookService.apply(game, List.of("e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6"));
            games.persist(game);
            return game.id;
        });
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String modelId, List<ChatMsg> history, AnswerDeadline deadline,
                                              int maxOutputTokens, dev.aichessarena.entity.ReasoningEffort effort) {
                return new LlmResponse("{\"move\":\"Qxf7#\"}", 10, 3, 0.12, 1, null, false, null);
            }
        }, OpenRouterService.class);
        var cancellation = new CompletableFuture<io.restassured.response.Response>();
        ClientProxy.<EvaluationProbe>unwrap(evaluationProbe).afterCommit = event -> {
            if (event.gameId().equals(gameId)) {
                // Cancel in a separate request after the mate commits, before the model turn can continue.
                cancellation.complete(given().post("/api/benchmarks/{id}/cancel", runId));
            }
        };
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", gameId).then().statusCode(200);

        cancellation.get(6, TimeUnit.SECONDS).then().statusCode(200)
                .body("run.status", equalTo("CANCELLED"))
                .body("models[0].overall.wins", equalTo(1))
                .body("models[0].overall.scoredGames", equalTo(1))
                .body("spendUsd", equalTo(0.12f));
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline && QuarkusTransaction.requiringNew().call(
                () -> attemptRows.findByGameId(gameId).isEmpty())) {
            Thread.sleep(20);
        }
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("status", equalTo("COMPLETED")).body("result", equalTo("WHITE_WINS"))
                .body("resultReason", equalTo("CHECKMATE"))
                .body("moves.size()", equalTo(7)).body("moves[6].san", equalTo("Qxf7#"))
                .body("attempts[0].outcome", equalTo("ACCEPTED"));
    }

    @Test
    void storesEveryConcreteModelReportedForAMovingAlias() {
        String alias = "~fixture/ladder-latest";
        UUID id = create(REQUEST.replace("fixture/ladder", alias));
        UUID gameId = gameIds(id).getFirst();
        for (String resolved : List.of("fixture/ladder-v1", "fixture/ladder-v2", "fixture/ladder-v2", alias)) {
            attempts.record(new MoveAttemptService.AttemptRecord(gameId, 12, "WHITE", 1, alias,
                    Outcome.DISCARDED, "e4", null, new OpenRouterService.LlmResponse("e4", 10, 3, null, 1,
                    "{\"model\":\"" + resolved + "\"}", false, null)));
        }
        given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("models[0].modelId", equalTo(alias))
                .body("models[0].resolvedModelIds", equalTo(List.of("fixture/ladder-v1", "fixture/ladder-v2")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "OPTIONAL", "MANDATORY"})
    void gameSnapshotExposesTheConfiguredBenchmarkChatMode(String mode) {
        UUID id = create(REQUEST.replace("\"OFF\"", "\"" + mode + "\""));
        given().get("/api/games/{id}", gameIds(id).getFirst()).then().statusCode(200)
                .body("benchmarkChatMode", equalTo(mode)).body("turnChatMode", nullValue());
    }

    @Test
    void evaluatesOnlyTheFinalBookPlyOfEachBenchmarkGame() {
        ClientProxy.<EvaluationProbe>unwrap(evaluationProbe).events.clear();
        UUID id = create(REQUEST);
        QuarkusTransaction.requiringNew().run(() -> {
            var expected = games.findByTournamentId(id).stream().collect(Collectors.toMap(
                    game -> game.id, game -> game.moves.getLast().id));
            var delivered = ClientProxy.<EvaluationProbe>unwrap(evaluationProbe).events.stream().filter(event -> expected.containsKey(event.gameId()))
                    .collect(Collectors.toMap(MoveEvaluationRequested::gameId, MoveEvaluationRequested::moveId));
            assertEquals(expected, delivered);
        });
    }

    @Test
    void truncatedBenchmarkTurnExplainsHowToChangeFixedSettings() throws InterruptedException {
        UUID id = create(REQUEST);
        UUID gameId = gameIds(id).getFirst();
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String modelId, List<ChatMsg> history, AnswerDeadline deadline,
                                              int maxOutputTokens, dev.aichessarena.entity.ReasoningEffort effort) {
                return new LlmResponse(null, 10, 2048, 0.01, 1, null, false, null,
                        ProviderOutcome.OK, 200, "length", 2000).withOutputTokenLimits(2048, 8192);
            }
        }, OpenRouterService.class);
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", gameId).then().statusCode(200);
        awaitStatus(gameId, "PAUSED");
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("statusDetail", containsString("Start a new benchmark"))
                .body("statusDetail", not(containsString("and resume")))
                .body("attempts[0].outcome", equalTo("TRUNCATED"));
    }

    @Test
    void createsAndReloadsThirtyTwoGamesPerModelInColorReversedPairs() {
        UUID id = create(REQUEST.replace("[\"fixture/ladder\"]", "[\"fixture/ladder\",\"fixture/second\"]"));
        given().get("/api/benchmarks").then().statusCode(200).body("id", hasItem(id.toString()));
        var report = given().get("/api/benchmarks/{id}", id).then().statusCode(200)
                .body("definition.engineSha256", equalTo(HASH))
                .body("definition.engineNetworks[0].name", equalTo(NETWORK))
                .body("definition.engineNetworks[0].sha256", equalTo(HASH))
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
    void stockfishCompletesAThreefoldRepetitionWhenAnotherGameReachesTheBudget(@TempDir Path temporary)
            throws Exception {
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
        String handshake = StockfishOpponentServiceTest.HANDSHAKE.replace("uciok",
                "option name EvalFile type string default " + NETWORK + "\nuciok");
        var process = new StockfishServiceTest.FakeProcess(handshake + "Network saved successfully to " + NETWORK
                + "\nreadyok\nbestmove f6g8\n", false);
        Path installedBinary = Files.writeString(temporary.resolve("stockfish"), "abc");
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override Path engineBinary() { return installedBinary; }
            @Override Process startBenchmarkProcess(Path verifiedBinary) throws IOException {
                assertNotEquals(installedBinary, verifiedBinary);
                Files.writeString(verifiedBinary.resolveSibling(NETWORK), "abc");
                return process;
            }
        }, StockfishOpponentService.class);

        UUID paidGame = gameIds(runId).stream().filter(id -> !id.equals(gameId)).findFirst().orElseThrow();
        var appliedBudgetPause = new CompletableFuture<Void>();
        ClientProxy.<EvaluationProbe>unwrap(evaluationProbe).afterCommit = event -> {
            if (event.gameId().equals(gameId)) {
                // Force the other game's charge to land between the move commit and the next loop iteration.
                pay(paidGame, "WHITE", 1.01);
                appliedBudgetPause.complete(null);
            }
        };
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", gameId)
                .then().statusCode(200);
        appliedBudgetPause.get(6, TimeUnit.SECONDS);
        awaitStatus(gameId, "COMPLETED");
        given().get("/api/benchmarks/{id}", runId).then().statusCode(200)
                .body("run.status", equalTo("PAUSED")).body("run.pauseReason", containsString("Budget"))
                .body("spendUsd", equalTo(1.01f));
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("status", equalTo("COMPLETED")).body("resultReason", equalTo("REPETITION"))
                .body("moves.size()", equalTo(8)).body("moves[7].san", equalTo("Ng8"))
                .body("attempts.size()", equalTo(0));
        assertTrue(process.commands.toString(StandardCharsets.UTF_8)
                .contains("position startpos moves g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1\ngo nodes 50000"));
    }

    @Test
    void reportsScoredAndIncompletePairsAndKeepsAllBenchmarkSpendOutOfModelMetrics() {
        var baseline = given().get("/api/analytics/health?days=17").then().statusCode(200).extract();
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
                .body("practiceCostUsd", equalTo(0.0f)).body("models.size()", equalTo(0))
                .body("gamesCount", equalTo(0)).body("activeGamesCount", equalTo(0))
                .body("completedGamesCount", equalTo(0)).body("forfeitGamesCount", equalTo(0));
        var global = given().get("/api/analytics/health?days=18").then().statusCode(200).extract();
        for (String counter : List.of("gamesCount", "activeGamesCount", "completedGamesCount", "forfeitGamesCount")) {
            assertEquals(baseline.<Integer>path(counter), global.<Integer>path(counter), counter);
        }
        assertEquals(0.65f, global.<Float>path("benchmarkCostUsd").floatValue());

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
    void comparisonReasoningQueryExcludesFinishedBenchmarks() {
        UUID id = create(REQUEST);
        UUID gameId = gameIds(id).getFirst();
        lifecycle.completeGame(gameId, new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
        pay(gameId, "WHITE", 0.10);
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, attemptRows.findByGameId(gameId).size());
            LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
            assertTrue(attemptRows.findReasoningAttempts(cutoff, id, null).isEmpty());
            assertTrue(attemptRows.findReasoningAttempts(cutoff, null, null).stream()
                    .noneMatch(attempt -> attempt.gameId().equals(gameId)));
        });
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
        given().contentType("application/json").body(withRequestId(REQUEST.replace(original, substitute)))
                .post("/api/benchmarks").then().statusCode(400);
        assertEquals(before, QuarkusTransaction.requiringNew().call(tournaments::count).longValue());
    }

    @Test
    void missingEngineReturnsConflictBeforeAnyRunIsCreated() {
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override EngineFingerprint benchmarkFingerprint(List<Integer> strengths) throws IOException {
                throw new IOException("Test engine missing");
            }
        }, StockfishOpponentService.class);
        long before = QuarkusTransaction.requiringNew().call(tournaments::count);
        given().contentType("application/json").body(withRequestId(REQUEST)).post("/api/benchmarks").then().statusCode(409);
        assertEquals(before, QuarkusTransaction.requiringNew().call(tournaments::count).longValue());
    }

    private UUID create(String request) {
        var response = given().contentType("application/json").body(withRequestId(request)).post("/api/benchmarks")
                .then().statusCode(201);
        String id = response.extract().path("run.id");
        assertNotNull(id);
        UUID parsed = UUID.fromString(id);
        benchmarks.add(parsed);
        return parsed;
    }

    private static String withRequestId(String request) {
        return request.replaceFirst("\\{", "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",");
    }

    private static void awaitStatus(UUID gameId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        String status;
        do {
            status = given().get("/api/games/{id}", gameId).then().statusCode(200).extract().path("status");
            if (expected.equals(status)) return;
            Thread.sleep(20);
        } while (("IN_PROGRESS".equals(status) || "WAITING".equals(status)) && System.nanoTime() < deadline);
        assertEquals(expected, status);
    }

    @ApplicationScoped
    public static class EvaluationProbe {
        final List<MoveEvaluationRequested> events = new CopyOnWriteArrayList<>();
        volatile java.util.function.Consumer<MoveEvaluationRequested> afterCommit;

        void capture(@Observes(during = TransactionPhase.AFTER_SUCCESS) MoveEvaluationRequested event) {
            events.add(event);
            var action = afterCommit;
            if (action != null) action.accept(event);
        }
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
