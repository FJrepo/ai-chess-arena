package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.PlayerType;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Tournament.AutoRunStatus;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.GameLifecycleService.Interruption;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Durable runs (2.0): what happens to a game whose loop did not survive a backend restart. A "restart" here is
 * {@link GameRecovery#recover} for the test's own games, with the loop of the previous process played by a thread that
 * holds that process's ownership token.
 */
@QuarkusTest
class DurableRunsIT {
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    @Inject GameRecovery recovery;
    @Inject GameEngineService engine;
    @Inject GameLifecycleService lifecycle;
    @Inject GameMoveService gameMoves;
    @Inject GameTurnService turns;
    @Inject MoveAttemptService attempts;
    @Inject GameRunOwnership ownership;
    @Inject TournamentAutoRunService runs;
    @Inject TournamentRunScheduler scheduler;
    @Inject GameRepository games;
    @Inject MoveRepository moves;
    @Inject MoveAttemptRepository attemptRows;
    @Inject TournamentRepository tournaments;
    @Inject ChessService chess;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "quarkus.datasource.jdbc.url") String jdbcUrl;
    @ConfigProperty(name = "quarkus.datasource.username") String username;
    @ConfigProperty(name = "quarkus.datasource.password") String password;

    final List<UUID> seededTournaments = new ArrayList<>();
    final List<UUID> seededGames = new ArrayList<>();
    final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void noAsynchronousScheduling() {
        // Scheduling is driven explicitly; recovery itself never needs the scheduler.
        QuarkusMock.installMockForType(new TournamentRunScheduler() {
            @Override void changed(UUID id) {}
            @Override void tick() {}
        }, TournamentRunScheduler.class);
    }

    @AfterEach
    void stopSeededGames() throws InterruptedException {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : seededTournaments) {
                Tournament t = tournaments.findByIdForUpdate(id);
                if (t != null) t.autoRunStatus = AutoRunStatus.OFF;
            }
        });
        for (UUID id : seededGames) {
            engine.requestStop(id);
        }
        for (UUID id : seededGames) {
            awaitReleased(id);
        }
        // Leave nothing a later restart or safety tick in this test JVM would pick up.
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : seededGames) {
                Game game = games.findByIdForUpdate(id);
                if (game == null) continue;
                if (game.status == GameStatus.IN_PROGRESS) game.status = GameStatus.PAUSED;
                game.runOwner = null;
                game.runLeaseUntil = null;
                game.requestSentAt = null;
            }
        });
    }

    @Test
    void neverSentWorkResumesAfterARestartUnderANewOwner() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
        setRunState(gameId, oldOwner, Instant.now().plus(Duration.ofMinutes(10)), null);
        List<Game> seenDuringRequest = new ArrayList<>();
        answers(call -> {
            seenDuringRequest.add(read(gameId));
            if (call == 2) engine.pauseGame(gameId);
            return paid(call == 1 ? "e4" : "e5", 0.01);
        });

        recovery.recover(List.of(gameId), Interruption.RESTART);

        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        Game game = read(gameId);
        assertNull(game.statusDetail, "a user pause, not a recovery pause");
        assertEquals(2, requests.get());
        assertEquals(2, moveCount(gameId));
        assertEquals(List.of(Outcome.ACCEPTED, Outcome.ACCEPTED), outcomes(gameId));
        assertEquals(0, new BigDecimal("0.02").compareTo(game.totalCostUsd));
        assertNull(game.requestSentAt);
        assertNull(game.runOwner, "the loop released the game when it ended");
        // While the request was out it was recorded as sent, and the game belonged to the new loop.
        Game during = seenDuringRequest.getFirst();
        assertNotNull(during.requestSentAt);
        assertNotNull(during.runOwner);
        assertNotEquals(oldOwner, during.runOwner);
        assertTrue(during.runLeaseUntil.isAfter(Instant.now()));
    }

    @Test
    void anInFlightRequestPausesForInspectionAndItsLateAnswerRecordsNoCharge() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
        setRunState(gameId, oldOwner, Instant.now().plus(Duration.ofMinutes(10)), null);
        CountDownLatch thinking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        answers(call -> {
            thinking.countDown();
            await(answer);
            return paid("e4", 0.25);
        });

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // The previous process's loop, half way through its turn.
            Future<GameTurnService.TurnResult> oldLoop = pool.submit(() -> asWorker(gameId, oldOwner,
                    () -> ClientProxy.unwrap(turns).playTurn(turnRequest(gameId))));
            assertTrue(thinking.await(10, TimeUnit.SECONDS));
            assertNotNull(read(gameId).requestSentAt, "recorded as sent before the provider was called");

            recovery.recover(List.of(gameId), Interruption.RESTART);

            Game paused = read(gameId);
            assertEquals(GameStatus.PAUSED, paused.status);
            assertEquals("A model request (test/white, White, move 1) was in flight during a backend restart and may "
                    + "already have been charged. Check spend, then resume.", paused.statusDetail);
            assertNull(paused.runOwner);
            assertNotNull(paused.requestSentAt, "kept until the operator acknowledges it");
            assertFalse(engine.isRunning(gameId), "nothing was resumed");
            assertEquals(1, requests.get(), "the request is not sent again");

            answer.countDown();
            assertEquals(GameTurnService.Status.STOPPED, oldLoop.get(10, TimeUnit.SECONDS).status());
        }
        Game after = read(gameId);
        assertEquals(GameStatus.PAUSED, after.status);
        assertEquals(0, moveCount(gameId));
        assertEquals(List.of(), outcomes(gameId), "the late answer of the old loop records no attempt");
        assertEquals(0, BigDecimal.ZERO.compareTo(after.totalCostUsd), "and no charge");
        assertEquals(1, requests.get());
    }

    @Test
    void aStaleWorkerCannotCommitAMoveRecordAnAttemptOrDecideTheGame() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
        setRunState(gameId, oldOwner, Instant.now().plus(Duration.ofMinutes(10)), null);
        CountDownLatch thinking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        answers(call -> {
            if (call == 1) {
                thinking.countDown();
                await(answer);
            } else {
                engine.pauseGame(gameId);
            }
            return paid(call == 1 ? "d4" : "d5", 0.01);
        });

        recovery.recover(List.of(gameId), Interruption.RESTART);
        assertTrue(thinking.await(10, TimeUnit.SECONDS), "the resumed loop is waiting for its answer");
        UUID newOwner = read(gameId).runOwner;
        assertNotEquals(oldOwner, newOwner);

        String start = new Board().getFen();
        asWorker(gameId, oldOwner, () -> {
            var e4 = chess.validateAndApply(new Board(), "e4");
            assertThrows(GameStateConflictException.class, () -> gameMoves.recordModelMove(gameId, 1, "WHITE", "e4",
                    e4.fen(), "test/white", "White", "Old", paid("e4", 0.5), 0, "v", "h", start, ChatMode.OPTIONAL, null));
            assertThrows(GameStateConflictException.class, () -> gameMoves.recordStockfishMove(gameId, "WHITE", 1, e4,
                    start, read(gameId).statusRevision, () -> true, null));
            attempts.record(new MoveAttemptService.AttemptRecord(gameId, 1, "WHITE", 1, "test/white",
                    Outcome.ILLEGAL_MOVE, "Ke2", "Illegal", paid("Ke2", 0.5)));
            assertNull(ownership.markRequestSent(gameId));
            lifecycle.forfeitTimeout(gameId, "WHITE", 30);
            lifecycle.forfeitInvalidMoves(gameId, "WHITE", 3);
            lifecycle.pauseWithReason(gameId, "Stale pause");
            lifecycle.markGamePaused(gameId);
            lifecycle.completeGame(gameId, new ChessService.GameEndInfo("BLACK_WINS", "CHECKMATE"));
            assertFalse(runs.beforeRequest(gameId));
            return null;
        });
        Game untouched = read(gameId);
        assertEquals(GameStatus.IN_PROGRESS, untouched.status);
        assertEquals(newOwner, untouched.runOwner);
        assertNotNull(untouched.requestSentAt, "the new loop's request is still recorded as sent");
        assertEquals(0, moveCount(gameId));
        assertEquals(List.of(), outcomes(gameId));
        assertEquals(0, BigDecimal.ZERO.compareTo(untouched.totalCostUsd));

        // The owner itself still commits.
        answer.countDown();
        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        assertEquals(2, moveCount(gameId));
        assertEquals("d4", QuarkusTransaction.requiringNew().call(() -> moves.findByGameId(gameId).getFirst().san));
    }

    @Test
    void autoRunsContinueAndTheirConcurrencyLimitHoldsAfterResume() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.IN_PROGRESS, GameStatus.IN_PROGRESS, GameStatus.WAITING,
                GameStatus.PAUSED);
        configure(tournamentId, "{\"maxConcurrentGames\":2}");
        List<UUID> ids = gameIds(tournamentId);
        Instant queuedAt = queue(ids.get(3), Instant.now().minusSeconds(60));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                entered.countDown();
                await(release);
                concurrent.decrementAndGet();
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);

        recovery.recover(List.of(ids.get(0), ids.get(1)), Interruption.RESTART);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "both interrupted games resumed");
            runs.schedule(tournamentId);
            assertEquals(AutoRunStatus.RUNNING, runStatus(tournamentId));
            assertEquals(2, ids.stream().filter(engine::isRunning).count());
            assertEquals(GameStatus.WAITING, read(ids.get(2)).status, "no third game while two run");
            assertEquals(queuedAt, read(ids.get(3)).resumeRequestedAt, "the queued resume keeps its place");
            assertEquals(GameStatus.PAUSED, read(ids.get(3)).status);
        } finally {
            release.countDown();
        }
        awaitReleased(ids.get(0));
        awaitReleased(ids.get(1));
        assertEquals(2, maxConcurrent.get());
    }

    @Test
    void theAutoRunBudgetStillHoldsAfterResume() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.IN_PROGRESS);
        configure(tournamentId, "{\"maxConcurrentGames\":1,\"budgetUsd\":0.01}");
        UUID gameId = gameIds(tournamentId).getFirst();
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).totalCostUsd = new BigDecimal("0.01"));
        answers(call -> fail("no request once the budget is reached"));

        recovery.recover(List.of(gameId), Interruption.RESTART);

        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        assertEquals(AutoRunStatus.PAUSED, runStatus(tournamentId));
        assertEquals("Budget of $0.01 reached ($0.01 spent)", read(gameId).statusDetail);
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restartRecoveryAndAManualResumeNeverRunTwoLoops(boolean inFlight) throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger turnsPlayed = new AtomicInteger();
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                turnsPlayed.incrementAndGet();
                sleep(20);
                concurrent.decrementAndGet();
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);
        for (int round = 0; round < 10; round++) {
            UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
            setRunState(gameId, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10)),
                    inFlight ? Instant.now() : null);
            turnsPlayed.set(0);
            CountDownLatch go = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> restart = pool.submit(() -> {
                    await(go);
                    recovery.recover(List.of(gameId), Interruption.RESTART);
                });
                Future<Integer> resume = pool.submit(() -> {
                    await(go);
                    try {
                        engine.startGame(gameId, false);
                        return 200;
                    } catch (WebApplicationException e) {
                        return e.getResponse().getStatus();
                    }
                });
                go.countDown();
                restart.get(10, TimeUnit.SECONDS);
                int resumed = resume.get(10, TimeUnit.SECONDS);
                awaitReleased(gameId);
                Game game = read(gameId);
                assertEquals(GameStatus.PAUSED, game.status);
                if (inFlight) {
                    // Before or after recovery, an ordinary resume never sends the possibly charged request again.
                    assertEquals(409, resumed);
                    assertEquals(0, turnsPlayed.get());
                    assertNotNull(game.requestSentAt);
                    assertTrue(game.statusDetail.endsWith(GameLifecycleService.INSPECTION_SUFFIX), game.statusDetail);
                } else {
                    assertEquals(409, resumed, "the game never left IN_PROGRESS");
                    assertEquals(1, turnsPlayed.get());
                }
            }
        }
        assertEquals(inFlight ? 0 : 1, maxConcurrent.get(), "never two loops");
    }

    @Test
    void queuedResumesSurviveARestartInTheirOrder() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.IN_PROGRESS, GameStatus.PAUSED, GameStatus.PAUSED,
                GameStatus.PAUSED);
        configure(tournamentId, "{\"maxConcurrentGames\":1}");
        List<UUID> ids = gameIds(tournamentId);
        UUID interrupted = ids.get(0);
        UUID unansweredWhilePaused = ids.get(1);
        setRunState(interrupted, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10)), Instant.now());
        // Budget-paused while its answer was out, then queued: it must not start before the operator checks it.
        queue(unansweredWhilePaused, Instant.now().minusSeconds(120));
        setRunState(unansweredWhilePaused, null, null, Instant.now().minusSeconds(130));
        Instant first = queue(ids.get(2), Instant.now().minusSeconds(60));
        Instant second = queue(ids.get(3), Instant.now().minusSeconds(30));
        List<UUID> started = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                synchronized (started) {
                    started.add(request.gameId());
                }
                await(release);
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);

        recovery.recover(List.of(interrupted, unansweredWhilePaused), Interruption.RESTART);
        try {
            for (UUID paused : List.of(interrupted, unansweredWhilePaused)) {
                Game game = read(paused);
                assertEquals(GameStatus.PAUSED, game.status);
                assertNull(game.resumeRequestedAt);
                assertTrue(game.statusDetail.startsWith("A model request (test/white, White, move 1) was in flight"));
            }
            assertEquals(first, read(ids.get(2)).resumeRequestedAt);
            assertEquals(second, read(ids.get(3)).resumeRequestedAt);
            assertEquals(AutoRunStatus.RUNNING, runStatus(tournamentId));

            runs.schedule(tournamentId);
            awaitGame(ids.get(2), g -> g.status == GameStatus.IN_PROGRESS);
            assertNull(read(ids.get(2)).resumeRequestedAt);
            assertEquals(second, read(ids.get(3)).resumeRequestedAt, "the later request still waits");
            assertEquals(GameStatus.PAUSED, read(ids.get(3)).status);
            assertEquals(GameStatus.PAUSED, read(interrupted).status);
            assertEquals(GameStatus.PAUSED, read(unansweredWhilePaused).status);
        } finally {
            release.countDown();
        }
        awaitReleased(ids.get(2));
        assertEquals(List.of(ids.get(2)), started);
    }

    @Test
    void aQueuedResumeWaitsBehindAResumedGame() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.IN_PROGRESS, GameStatus.PAUSED);
        configure(tournamentId, "{\"maxConcurrentGames\":1}");
        List<UUID> ids = gameIds(tournamentId);
        Instant queuedAt = queue(ids.get(1), Instant.now().minusSeconds(60));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                entered.countDown();
                await(release);
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);

        recovery.recover(List.of(ids.get(0)), Interruption.RESTART);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            runs.schedule(tournamentId);
            assertEquals(queuedAt, read(ids.get(1)).resumeRequestedAt);
            assertEquals(GameStatus.PAUSED, read(ids.get(1)).status);
        } finally {
            release.countDown();
        }
        awaitReleased(ids.get(0));
    }

    @Test
    void practiceGamesFollowTheSamePolicy() throws Exception {
        UUID inFlight = modelPractice(AFTER_E4);
        setRunState(inFlight, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10)), Instant.now());
        UUID humanToMove = modelPractice(new Board().getFen());
        setRunState(humanToMove, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10)), null);
        UUID modelToMove = modelPractice(AFTER_E4);
        setRunState(modelToMove, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10)), null);
        // Free answers: other suites total practice spend across all games.
        answers(call -> paid("e5", 0.0));

        recovery.recover(List.of(inFlight, humanToMove, modelToMove), Interruption.RESTART);

        Game paused = read(inFlight);
        assertEquals(GameStatus.PAUSED, paused.status);
        assertTrue(paused.statusDetail.startsWith("A model request (test/black, Black, move 1) was in flight"));
        Game waiting = read(humanToMove);
        assertEquals(GameStatus.IN_PROGRESS, waiting.status, "a human turn keeps waiting");
        assertNull(waiting.statusDetail);
        assertNull(waiting.runOwner);
        assertFalse(engine.isRunning(humanToMove));
        // The model answers, then the loop waits for the human again.
        awaitGame(modelToMove, g -> g.runOwner == null && g.status == GameStatus.IN_PROGRESS && moveCountOf(g) == 1);
        assertEquals(1, requests.get());
        assertEquals(List.of(Outcome.ACCEPTED), outcomes(modelToMove));
    }

    @Test
    void stockfishTurnsAlwaysResumeAndCostNothing() throws Exception {
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override Process startProcess() {
                return new StockfishServiceTest.FakeProcess(StockfishOpponentServiceTest.HANDSHAKE + "bestmove e2e4\n", true);
            }
        }, StockfishOpponentService.class);
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game g = new Game();
            g.practiceKind = Game.PracticeKind.HUMAN_STOCKFISH;
            g.stockfishElo = 1500;
            g.whitePracticeControl = PlayerType.STOCKFISH;
            g.blackPracticeControl = PlayerType.HUMAN;
            g.whitePlayerName = "Stockfish";
            g.blackPlayerName = "You";
            g.status = GameStatus.IN_PROGRESS;
            g.startedAt = java.time.LocalDateTime.now();
            g.runOwner = UUID.randomUUID();
            g.runLeaseUntil = Instant.now().plusSeconds(600);
            games.persist(g);
            return g.id;
        });
        seededGames.add(gameId);
        answers(call -> fail("an engine turn never calls OpenRouter"));

        recovery.recover(List.of(gameId), Interruption.RESTART);

        awaitGame(gameId, g -> g.runOwner == null && moveCountOf(g) == 1);
        Game game = read(gameId);
        assertEquals(GameStatus.IN_PROGRESS, game.status);
        assertEquals(0, BigDecimal.ZERO.compareTo(game.totalCostUsd));
        assertEquals(List.of(), outcomes(gameId));
    }

    @Test
    void anExpiredLeaseIsRecoveredOnlyWithoutALiveLoop() throws Exception {
        UUID expired = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
        setRunState(expired, UUID.randomUUID(), Instant.now().minusSeconds(1), Instant.now().minusSeconds(900));
        UUID leased = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
        UUID leaseOwner = UUID.randomUUID();
        setRunState(leased, leaseOwner, Instant.now().plusSeconds(600), Instant.now());
        UUID local = tournamentGame(AutoRunStatus.OFF, GameStatus.IN_PROGRESS);
        setRunState(local, UUID.randomUUID(), Instant.now().minusSeconds(1), Instant.now());
        GameEngineService.GameLoop localLoop = ClientProxy.unwrap(engine).tryClaimLoop(local);
        assertNotNull(localLoop);
        try {
            assertTrue(lifecycle.gamesWithExpiredLease().containsAll(List.of(expired, local)));
            assertFalse(lifecycle.gamesWithExpiredLease().contains(leased));

            recovery.recover(List.of(expired, leased, local), Interruption.EXPIRED_LEASE);

            assertEquals("A model request (test/white, White, move 1) was in flight when its game loop stopped and "
                    + "may already have been charged. Check spend, then resume.", read(expired).statusDetail);
            assertEquals(GameStatus.IN_PROGRESS, read(leased).status, "a valid lease is respected");
            assertEquals(leaseOwner, read(leased).runOwner);
            assertEquals(GameStatus.IN_PROGRESS, read(local).status, "a loop of this process is never replaced");
        } finally {
            ClientProxy.unwrap(engine).releaseLoop(local, localLoop);
        }
    }

    @Test
    void theUpgradeToV32PausesWhatThePreviousVersionLeftRunning() throws Exception {
        String database = "v32_runs_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "30").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tournaments (id, name, status, auto_run_status) VALUES
                          ('00000000-0000-0000-0000-0000000000a1', 'Running', 'IN_PROGRESS', 'RUNNING');
                        INSERT INTO games (id, tournament_id, status, status_revision, turn_started_at) VALUES
                          ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1',
                           'IN_PROGRESS', 4, NOW()),
                          ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000000a1',
                           'WAITING', 0, NULL);
                        """);
            }
            flyway(url, "latest").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("""
                        SELECT status, status_detail, status_revision, turn_started_at, run_owner, request_sent_at
                        FROM games ORDER BY id""")) {
                    assertTrue(rows.next());
                    assertEquals("PAUSED", rows.getString(1));
                    assertEquals("Paused by the upgrade to durable runs: a model request may have been in flight. "
                            + "Check spend, then resume.", rows.getString(2));
                    assertEquals(5, rows.getLong(3));
                    assertNull(rows.getObject(4));
                    assertNull(rows.getObject(5));
                    assertNull(rows.getObject(6));
                    assertTrue(rows.next());
                    assertEquals("WAITING", rows.getString(1));
                    assertEquals(0, rows.getLong(3));
                }
                try (ResultSet run = statement.executeQuery(
                        "SELECT auto_run_status, auto_run_pause_reason FROM tournaments")) {
                    assertTrue(run.next());
                    assertEquals("PAUSED", run.getString(1));
                    assertEquals("Backend restarted", run.getString(2));
                }
                assertThrows(java.sql.SQLException.class, () -> statement.execute(
                        "UPDATE games SET run_owner = gen_random_uuid() WHERE status = 'WAITING'"),
                        "an owner always has a lease");
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .target(target).load();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI", "STOCKFISH"})
    void aGameDecidedByItsLastCommittedMoveCompletesOnRecovery(String winner) throws Exception {
        // The winning move was committed, but the backend stopped before the loop could finish the game.
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game g = new Game();
            boolean engine = "STOCKFISH".equals(winner);
            g.practiceKind = engine ? Game.PracticeKind.HUMAN_STOCKFISH : Game.PracticeKind.FROM_GAME;
            g.stockfishElo = engine ? 1500 : null;
            g.whitePracticeControl = PlayerType.HUMAN;
            g.blackPracticeControl = PlayerType.valueOf(winner);
            g.whitePlayerName = "You";
            g.blackPlayerName = engine ? "Stockfish" : "Model";
            g.blackModelId = engine ? null : "test/black";
            g.status = GameStatus.IN_PROGRESS;
            g.startedAt = java.time.LocalDateTime.now();
            g.runOwner = UUID.randomUUID();
            g.runLeaseUntil = Instant.now().plusSeconds(600);
            games.persist(g);
            Board board = new Board();
            for (String san : List.of("f3", "e5", "g4", "Qh4#")) {
                var move = new dev.aichessarena.entity.Move();
                move.game = g;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                var played = chess.validateAndApply(board, san);
                assertTrue(played.valid(), san);
                move.san = played.san();
                move.fen = played.fen();
                moves.persist(move);
            }
            g.currentFen = board.getFen();
            return g.id;
        });
        seededGames.add(gameId);
        answers(call -> fail("a decided game sends nothing"));

        recovery.recover(List.of(gameId), Interruption.RESTART);

        Game game = read(gameId);
        assertEquals(GameStatus.COMPLETED, game.status);
        assertEquals(Game.GameResult.BLACK_WINS, game.result);
        assertEquals(Game.ResultReason.CHECKMATE, game.resultReason);
        assertNull(game.runOwner);
        assertFalse(engine.isRunning(gameId));
        assertEquals(0, requests.get());
    }

    @Test
    void aRequestWhoseDeliveryIsUncertainPausesForInspectionAndIsNeverRetried() throws Exception {
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.WAITING);
        List<Instant> markers = new ArrayList<>();
        answers(call -> {
            markers.add(read(gameId).requestSentAt);
            return LlmResponse.providerFailure(OpenRouterService.ProviderOutcome.RETRYABLE, 0,
                    "The connection to OpenRouter broke after the request was sent", 5, null).withDeliveryUncertain();
        });

        engine.startGame(gameId);

        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        Game game = read(gameId);
        assertEquals("A model request (test/white, White, move 1) lost its connection after it was sent and may "
                + "already have been charged. Check spend, then resume.", game.statusDetail);
        assertEquals(1, requests.get(), "never retried automatically");
        assertNotNull(markers.getFirst());
        assertNotNull(game.requestSentAt, "kept until the operator acknowledges it");
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE), outcomes(gameId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anAnswerThatCannotBeSavedIsNeverFollowedByAnotherRequest(boolean illegalAnswer) throws Exception {
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.WAITING);
        QuarkusMock.installMockForType(new MoveAttemptService() {
            @Override public void record(AttemptRecord attempt) {
                throw new IllegalStateException("Attempt transaction rolled back");
            }
        }, MoveAttemptService.class);
        answers(call -> illegalAnswer ? paid("Ke2", 0.02)
                : LlmResponse.providerFailure(OpenRouterService.ProviderOutcome.RETRYABLE, 503, "OpenRouter server error",
                        5, null));

        engine.startGame(gameId);

        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        Game game = read(gameId);
        assertEquals("A model request (test/white, White, move 1) was answered, but the answer could not be saved, so "
                + "its cost may be missing. Check spend, then resume.", game.statusDetail);
        assertEquals(1, requests.get(), "no retry before the answer is saved");
        assertNotNull(game.requestSentAt, "kept until the operator acknowledges it");
    }

    @Test
    void everyPostIsAdmittedAgainstFreshStateOwnershipBudgetAndUnresolvedRequests() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.IN_PROGRESS);
        configure(tournamentId, "{\"maxConcurrentGames\":1,\"budgetUsd\":0.01}");
        UUID gameId = gameIds(tournamentId).getFirst();
        UUID owner = UUID.randomUUID();
        setRunState(gameId, owner, Instant.now().plusSeconds(600), null);

        Instant first = asWorker(gameId, owner, () -> ownership.markRequestSent(gameId));
        assertNotNull(first);
        assertEquals(first, read(gameId).requestSentAt);
        assertNull(asWorker(gameId, owner, () -> ownership.markRequestSent(gameId)),
                "an earlier request has no recorded answer");
        Game paused = read(gameId);
        assertEquals(GameStatus.PAUSED, paused.status);
        assertEquals("A model request (test/white, White, move 1) has no recorded answer and may already have been "
                + "charged. Check spend, then resume.", paused.statusDetail);
        assertEquals(first, paused.requestSentAt, "only the operator's acknowledgement clears it");

        // Another loop or a stale one is refused; so is a request beyond the budget, which pauses the run.
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).status = GameStatus.IN_PROGRESS);
        setRunState(gameId, owner, Instant.now().plusSeconds(600), null);
        assertNull(asWorker(gameId, UUID.randomUUID(), () -> ownership.markRequestSent(gameId)));
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).totalCostUsd = new BigDecimal("0.01"));
        assertNull(asWorker(gameId, owner, () -> ownership.markRequestSent(gameId)));
        assertEquals(AutoRunStatus.PAUSED, runStatus(tournamentId));
        assertNull(read(gameId).requestSentAt);
    }

    @Test
    void thePlainFallbackOfATurnIsAdmittedLikeItsFirstRequest() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.WAITING);
        configure(tournamentId, "{\"maxConcurrentGames\":1,\"budgetUsd\":0.01}");
        UUID gameId = gameIds(tournamentId).getFirst();
        List<Boolean> fallbackAdmitted = new ArrayList<>();
        List<BigDecimal> spendAtAdmission = new ArrayList<>();
        answers(call -> {
            // As OpenRouterService does: the marked request was rejected for cache control, with billable usage.
            LlmResponse rejection = new LlmResponse(null, 900, 0, 0.01, 5,
                    "{\"error\":{\"message\":\"cache_control is unsupported\"}}", false,
                    "OpenRouter rejected the request (HTTP 400): cache_control is unsupported",
                    OpenRouterService.ProviderOutcome.RETRYABLE, 400, null, null);
            boolean admitted = OpenRouterService.SEND_GATE.get().admitAnotherRequest(rejection);
            fallbackAdmitted.add(admitted);
            spendAtAdmission.add(read(gameId).totalCostUsd);
            return admitted ? paid("e4", 0.0) : rejection;
        });

        runs.schedule(tournamentId);

        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        assertEquals(List.of(false), fallbackAdmitted, "the recorded rejection's spend reaches the budget");
        assertEquals(0, new BigDecimal("0.01").compareTo(spendAtAdmission.getFirst()));
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE), outcomes(gameId), "recorded once, before the fallback");
        assertEquals(AutoRunStatus.PAUSED, runStatus(tournamentId));
        assertEquals("Budget of $0.01 reached ($0.01 spent)", read(gameId).statusDetail);
        assertEquals(1, requests.get());
    }

    @Test
    void aCommittedHumanMoveHandsTheGameToTheNextLoopInTheSameTransaction() throws Exception {
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game g = new Game();
            g.practiceKind = Game.PracticeKind.FROM_GAME;
            g.whitePracticeControl = PlayerType.HUMAN;
            g.blackPracticeControl = PlayerType.AI;
            g.whitePlayerName = "You";
            g.blackPlayerName = "Model";
            g.blackModelId = "test/black";
            g.status = GameStatus.IN_PROGRESS;
            g.startedAt = java.time.LocalDateTime.now();
            games.persist(g);
            return g.id;
        });
        seededGames.add(gameId);
        GameMoveService real = ClientProxy.unwrap(gameMoves);
        // The backend loses the loop right after the human move commits (e.g. a database outage at that moment).
        QuarkusMock.installMockForType(new GameMoveService() {
            @Override public void recordHumanMove(UUID id, int number, String color, String san, String fen,
                                                  String player, String message, String expectedFen, UUID nextOwner) {
                QuarkusTransaction.requiringNew().run(() -> real.recordHumanMove(id, number, color, san, fen, player,
                        message, expectedFen, nextOwner));
                throw new IllegalStateException("Connection lost after the commit");
            }
        }, GameMoveService.class);
        answers(call -> paid("e5", 0.0));

        assertThrows(IllegalStateException.class,
                () -> engine.submitHumanMove(gameId, "e4", null, null, null, null));
        QuarkusMock.installMockForType(real, GameMoveService.class);
        Game handedOver = read(gameId);
        assertEquals(1, moveCount(gameId));
        assertEquals(GameStatus.IN_PROGRESS, handedOver.status);
        assertNotNull(handedOver.runOwner, "the model's turn has an owner from the move's commit on");
        assertNotNull(handedOver.runLeaseUntil);
        assertFalse(engine.isRunning(gameId));

        // Once that lease expires, the safety tick resumes the model's turn.
        setRunState(gameId, handedOver.runOwner, Instant.now().minusSeconds(1), null);
        assertTrue(lifecycle.gamesWithExpiredLease().contains(gameId));
        recovery.recover(List.of(gameId), Interruption.EXPIRED_LEASE);
        awaitGame(gameId, g -> g.runOwner == null && moveCountOf(g) == 2);
        assertEquals(1, requests.get());
    }

    @Test
    void onlyTheOperatorsAcknowledgementResumesAGameWithAnUnresolvedRequest() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.PAUSED);
        configure(tournamentId, "{\"maxConcurrentGames\":1}");
        UUID gameId = gameIds(tournamentId).getFirst();
        Instant sentAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        // As left when the inspection pause itself could not be committed: the generic loop-failure reason.
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findById(gameId);
            game.statusDetail = "The game loop stopped unexpectedly (IllegalStateException); resume to retry";
            game.requestSentAt = sentAt;
        });
        queue(gameId, Instant.now().minusSeconds(10));
        AtomicInteger turnsPlayed = new AtomicInteger();
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                turnsPlayed.incrementAndGet();
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);
        String held = "A model request (test/white, White, move 1) has no recorded answer and may already have been "
                + "charged. Check spend, then resume.";

        // The scheduler's queued resume: refused and withdrawn.
        runs.schedule(tournamentId);
        Game game = read(gameId);
        assertEquals(GameStatus.PAUSED, game.status);
        assertNull(game.resumeRequestedAt);
        assertEquals(held, game.statusDetail);
        assertEquals(sentAt, game.requestSentAt);
        // An ordinary resume, queued or not.
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", gameId)
                .then().statusCode(409).body("error", org.hamcrest.Matchers.equalTo(
                        GameEngineService.UNRESOLVED_START_CONFLICT));
        given().contentType("application/json").body("{}").queryParam("queueIfFull", false)
                .post("/api/games/{id}/start", gameId).then().statusCode(409);
        // Restart recovery and the safety tick.
        recovery.recover(List.of(gameId), Interruption.RESTART);
        recovery.recover(List.of(gameId), Interruption.EXPIRED_LEASE);
        assertEquals(held, read(gameId).statusDetail);
        assertEquals(sentAt, read(gameId).requestSentAt);
        assertEquals(0, turnsPlayed.get());
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("uncertainRequest", org.hamcrest.Matchers.equalTo(true));
        // Fail-closed parameter.
        given().contentType("application/json").body("{}").queryParam("acknowledgeUncertainRequest", "true")
                .post("/api/games/{id}/start", gameId).then().statusCode(400);
        given().contentType("application/json").body("{}")
                .queryParam("acknowledgeUncertainRequest", sentAt.toString(), sentAt.toString())
                .post("/api/games/{id}/start", gameId).then().statusCode(400);

        given().contentType("application/json").body("{}").queryParam("acknowledgeUncertainRequest", sentAt.toString())
                .post("/api/games/{id}/start", gameId).then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("IN_PROGRESS"))
                .body("uncertainRequest", org.hamcrest.Matchers.equalTo(false));
        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        assertEquals(1, turnsPlayed.get());
        assertNull(read(gameId).requestSentAt);
        // Nothing left to acknowledge.
        given().contentType("application/json").body("{}").queryParam("acknowledgeUncertainRequest", sentAt.toString())
                .post("/api/games/{id}/start", gameId).then().statusCode(409);
    }

    @Test
    void anAcknowledgementOnlyClearsTheRequestItNames() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.PAUSED);
        UUID gameId = gameIds(tournamentId).getFirst();
        Instant seen = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant newer = seen.plusSeconds(30);
        setRunState(gameId, null, null, seen);
        String shown = given().get("/api/games/{id}", gameId).then().statusCode(200)
                .extract().path("uncertainRequestSentAt");
        assertEquals(seen, Instant.parse(shown));
        // Meanwhile (another tab acknowledged it, the game ran on) a newer request became unresolved.
        setRunState(gameId, null, null, newer);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);

        given().contentType("application/json").body("{}").queryParam("acknowledgeUncertainRequest", shown)
                .post("/api/games/{id}/start", gameId).then().statusCode(409)
                .body("error", org.hamcrest.Matchers.containsString("has changed"));
        assertEquals(newer, read(gameId).requestSentAt, "the newer request stays unresolved");
        assertEquals(GameStatus.PAUSED, read(gameId).status);

        given().contentType("application/json").body("{}").queryParam("acknowledgeUncertainRequest", newer.toString())
                .post("/api/games/{id}/start", gameId).then().statusCode(200);
        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        assertNull(read(gameId).requestSentAt);
    }

    @Test
    void aStartedAnswerThatMissesTheMoveTimeNeverForfeits() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.WAITING);
        QuarkusTransaction.requiringNew().run(() -> tournaments.findById(tournamentId).moveTimeoutSeconds = 2);
        UUID gameId = gameIds(tournamentId).getFirst();
        AtomicInteger posts = new AtomicInteger();
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> serveRaw(socket, posts, "200 headers then stall"));
            installRouter(socket);

            engine.startGame(gameId);

            awaitGame(gameId, g -> g.status != GameStatus.IN_PROGRESS);
            awaitReleased(gameId);
        }
        Game game = read(gameId);
        assertEquals(GameStatus.PAUSED, game.status, "never a timeout forfeit once the answer had started");
        assertNull(game.result);
        assertEquals("A model request (test/white, White, move 1) lost its connection after it was sent and may "
                + "already have been charged. Check spend, then resume.", game.statusDetail);
        assertEquals(1, posts.get());
    }

    @Test
    void aLoopStoppedByARunPauseStillHoldsItsUnresolvedRequest() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.WAITING);
        configure(tournamentId, "{\"maxConcurrentGames\":1}");
        UUID gameId = gameIds(tournamentId).getFirst();
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                assertNotNull(ownership.markRequestSent(request.gameId()));
                // The run is paused while the request is out; then saving the (discarded) answer fails.
                runs.pause(tournamentId);
                return TurnResult.stopped();
            }
        }, GameTurnService.class);

        runs.schedule(tournamentId);

        awaitGame(gameId, g -> g.statusDetail != null && g.statusDetail.endsWith(GameLifecycleService.INSPECTION_SUFFIX));
        awaitReleased(gameId);
        Game game = read(gameId);
        assertEquals(GameStatus.PAUSED, game.status);
        assertEquals("A model request (test/white, White, move 1) has no recorded answer and may already have been "
                + "charged. Check spend, then resume.", game.statusDetail);
        assertNotNull(game.requestSentAt);
    }

    @Test
    void aLoopThatCannotCommitItsFinalStateKeepsItsLeaseForTheSafetyTick() throws Exception {
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.WAITING);
        CountDownLatch sent = new CountDownLatch(1);
        CountDownLatch failNow = new CountDownLatch(1);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                assertNotNull(ownership.markRequestSent(request.gameId()));
                sent.countDown();
                await(failNow);
                throw new IllegalStateException("Lost after sending");
            }
        }, GameTurnService.class);
        GameLifecycleService real = ClientProxy.unwrap(lifecycle);

        engine.startGame(gameId);
        assertTrue(sent.await(10, TimeUnit.SECONDS));
        // The database is unreachable for the loop's pause and hold.
        QuarkusMock.installMockForType(new GameLifecycleService() {
            @Override public void pauseEndingLoop(UUID id, String detail) {
                throw new IllegalStateException("Database unavailable");
            }

            @Override public void markGamePaused(UUID id) {
                throw new IllegalStateException("Database unavailable");
            }
        }, GameLifecycleService.class);
        failNow.countDown();
        awaitReleased(gameId);
        QuarkusMock.installMockForType(real, GameLifecycleService.class);

        Game stuck = read(gameId);
        assertEquals(GameStatus.IN_PROGRESS, stuck.status);
        assertNotNull(stuck.requestSentAt);
        assertNotNull(stuck.runOwner, "the lease is kept so the safety tick can recover the game");
        setRunState(gameId, stuck.runOwner, Instant.now().minusSeconds(1), stuck.requestSentAt);
        assertTrue(lifecycle.gamesWithExpiredLease().contains(gameId));
        recovery.recover(List.of(gameId), Interruption.EXPIRED_LEASE);
        Game held = read(gameId);
        assertEquals(GameStatus.PAUSED, held.status);
        assertEquals("A model request (test/white, White, move 1) was in flight when its game loop stopped and may "
                + "already have been charged. Check spend, then resume.", held.statusDetail);
    }

    @Test
    void theSafetyTickFinalizesADecidedGamePausedBeforeItsLeaseExpired() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.PAUSED);
        UUID gameId = gameIds(tournamentId).getFirst();
        QuarkusTransaction.requiringNew().run(() -> {
            Game g = games.findById(gameId);
            g.statusDetail = null;
            Board board = new Board();
            for (String san : List.of("f3", "e5", "g4", "Qh4#")) {
                var move = new dev.aichessarena.entity.Move();
                move.game = g;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                var played = chess.validateAndApply(board, san);
                move.san = played.san();
                move.fen = played.fen();
                moves.persist(move);
            }
            g.currentFen = board.getFen();
        });
        // Its completion rolled back and the operator paused it; the lease of that human move has now expired.
        setRunState(gameId, UUID.randomUUID(), Instant.now().minusSeconds(1), null);
        assertTrue(lifecycle.gamesWithExpiredLease().contains(gameId));

        recovery.recover(List.of(gameId), Interruption.EXPIRED_LEASE);

        Game game = read(gameId);
        assertEquals(GameStatus.COMPLETED, game.status);
        assertEquals(Game.ResultReason.CHECKMATE, game.resultReason);
        assertNull(game.runOwner);
    }

    @Test
    void aLoopFailingWithAnUnresolvedRequestCommitsTheInspectionPause() throws Exception {
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.WAITING);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                assertNotNull(ownership.markRequestSent(request.gameId()));
                throw new IllegalStateException("Lost after sending");
            }
        }, GameTurnService.class);

        engine.startGame(gameId);

        awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
        awaitReleased(gameId);
        Game game = read(gameId);
        assertEquals("A model request (test/white, White, move 1) has no recorded answer and may already have been "
                + "charged. Check spend, then resume.", game.statusDetail);
        assertNotNull(game.requestSentAt);
        WebApplicationException refused = assertThrows(WebApplicationException.class, () -> engine.startGame(gameId));
        assertEquals(409, refused.getResponse().getStatus());
        assertFalse(engine.isRunning(gameId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"200 malformed", "200 no choices", "200 choice without message", "200 error object",
            "200 choice error", "200 finish reason error", "200 trailing value", "503 html", "503 trailing garbage",
            "503 error without code", "408 error document", "418 error document", "503 headers then drop",
            "drop after request"})
    void everyAnswerThatDoesNotProveNothingWasChargedPausesAfterExactlyOnePost(String answer) throws Exception {
        UUID gameId = tournamentGame(AutoRunStatus.OFF, GameStatus.WAITING);
        AtomicInteger posts = new AtomicInteger();
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> serveRaw(socket, posts, answer));
            installRouter(socket);

            engine.startGame(gameId);

            awaitGame(gameId, g -> g.status == GameStatus.PAUSED);
            awaitReleased(gameId);
        }
        Game game = read(gameId);
        assertEquals(1, posts.get(), answer);
        assertEquals("A model request (test/white, White, move 1) lost its connection after it was sent and may "
                + "already have been charged. Check spend, then resume.", game.statusDetail, answer);
        assertNotNull(game.requestSentAt);
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE), outcomes(gameId));
    }

    private void installRouter(java.net.ServerSocket socket) {
        OpenRouterService router = new OpenRouterService();
        router.baseUrl = "http://127.0.0.1:" + socket.getLocalPort();
        router.keyStore = dev.aichessarena.config.OpenRouterKeyStoreFixture.keys("test-key",
                java.nio.file.Path.of("target/test-keys"));
        router.objectMapper = new ObjectMapper();
        router.defaultTemperature = 0.7;
        router.defaultMaxTokens = 100;
        QuarkusMock.installMockForType(router, OpenRouterService.class);
    }

    private static void serveRaw(java.net.ServerSocket socket, AtomicInteger posts, String answer) {
        while (!socket.isClosed()) {
            try (var connection = socket.accept()) {
                var in = connection.getInputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    int b = in.read();
                    if (b < 0) break;
                    head.append((char) b);
                }
                int length = 0;
                for (String line : head.toString().split("\r\n")) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(15).trim());
                    }
                }
                in.readNBytes(length);
                var out = connection.getOutputStream();
                if (!head.toString().startsWith("POST /chat/completions")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    continue;
                }
                posts.incrementAndGet();
                String document = "{\"error\":{\"code\":503,\"message\":\"Something happened\"}}";
                String usage = ",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"cost\":0.001}}";
                String raw = switch (answer) {
                    case "200 malformed" -> response(200, "<html>gateway</html>");
                    case "200 no choices" -> response(200, "{\"choices\":[]}");
                    case "200 choice without message" -> response(200, "{\"choices\":[{}]}");
                    case "200 error object" -> response(200, "{\"error\":{\"message\":\"Provider returned error\"}}");
                    case "503 html" -> response(503, "<html>bad gateway</html>");
                    case "408 error document" -> response(408, document);
                    case "418 error document" -> response(418, document);
                    case "200 choice error" -> response(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"mo\"},"
                            + "\"error\":{\"code\":502,\"message\":\"Upstream stopped\"}}]" + usage);
                    case "200 finish reason error" -> response(200, "{\"choices\":[{\"message\":{\"content\":\"e4\"},"
                            + "\"finish_reason\":\"error\"}]" + usage);
                    case "200 trailing value" -> response(200,
                            "{\"choices\":[{\"message\":{\"content\":\"{\\\"move\\\":\\\"e4\\\"}\"}}]} {}");
                    case "503 trailing garbage" -> response(503, document + " garbage");
                    case "503 error without code" -> response(503, "{\"error\":{\"message\":\"Something happened\"}}");
                    case "503 headers then drop" -> "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json"
                            + "\r\nContent-Length: 200\r\n\r\n{\"error\":";
                    case "200 headers then stall" -> "HTTP/1.1 200 OK\r\nContent-Type: application/json"
                            + "\r\nContent-Length: 500\r\n\r\n{\"choices\":";
                    default -> "";
                };
                out.write(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                if (answer.endsWith("stall")) {
                    // Keep the connection open until the client gives up.
                    while (in.read() >= 0) {
                        // discard
                    }
                }
            } catch (java.io.IOException e) {
                // closed by the test
            }
        }
    }

    private static String response(int status, String body) {
        return "HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
                .formatted(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, body);
    }

    @Test
    void restartFinalizesAGameABudgetPauseLeftWithADecidingMove() throws Exception {
        UUID tournamentId = seedTournament(GameStatus.PAUSED);
        UUID gameId = gameIds(tournamentId).getFirst();
        QuarkusTransaction.requiringNew().run(() -> {
            Game g = games.findById(gameId);
            g.statusDetail = "Budget of $0.01 reached ($0.01 spent)";
            Board board = new Board();
            for (String san : List.of("f3", "e5", "g4", "Qh4#")) {
                var move = new dev.aichessarena.entity.Move();
                move.game = g;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                var played = chess.validateAndApply(board, san);
                move.san = played.san();
                move.fen = played.fen();
                moves.persist(move);
            }
            g.currentFen = board.getFen();
        });
        assertTrue(lifecycle.gamesInterruptedByRestart().contains(gameId));

        recovery.recover(List.of(gameId), Interruption.RESTART);

        Game game = read(gameId);
        assertEquals(GameStatus.COMPLETED, game.status);
        assertEquals(Game.GameResult.BLACK_WINS, game.result);
        assertEquals(Game.ResultReason.CHECKMATE, game.resultReason);
    }

    @Test
    void aHumanMoveWhoseCompletionRollsBackKeepsTheLeaseForRecovery() throws Exception {
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game g = new Game();
            g.practiceKind = Game.PracticeKind.FROM_GAME;
            g.whitePracticeControl = PlayerType.HUMAN;
            g.blackPracticeControl = PlayerType.AI;
            g.whitePlayerName = "You";
            g.blackPlayerName = "Model";
            g.blackModelId = "test/black";
            g.status = GameStatus.IN_PROGRESS;
            g.startedAt = java.time.LocalDateTime.now();
            games.persist(g);
            Board board = new Board();
            for (String san : List.of("e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6")) {
                var move = new dev.aichessarena.entity.Move();
                move.game = g;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                var played = chess.validateAndApply(board, san);
                move.san = played.san();
                move.fen = played.fen();
                moves.persist(move);
            }
            g.currentFen = board.getFen();
            return g.id;
        });
        seededGames.add(gameId);
        GameLifecycleService real = ClientProxy.unwrap(lifecycle);
        QuarkusMock.installMockForType(new GameLifecycleService() {
            @Override public void completeGame(UUID id, ChessService.GameEndInfo endInfo) {
                throw new IllegalStateException("Completion rolled back");
            }
        }, GameLifecycleService.class);
        answers(call -> fail("the game is decided"));

        assertThrows(IllegalStateException.class,
                () -> engine.submitHumanMove(gameId, "Qxf7", null, null, null, null));
        QuarkusMock.installMockForType(real, GameLifecycleService.class);
        Game mated = read(gameId);
        assertEquals(GameStatus.IN_PROGRESS, mated.status);
        assertEquals(7, moveCount(gameId));
        assertNotNull(mated.runOwner, "the durable lease survives the failed completion");
        assertFalse(engine.isRunning(gameId));

        setRunState(gameId, mated.runOwner, Instant.now().minusSeconds(1), null);
        recovery.recover(List.of(gameId), Interruption.EXPIRED_LEASE);
        Game game = read(gameId);
        assertEquals(GameStatus.COMPLETED, game.status);
        assertEquals(Game.GameResult.WHITE_WINS, game.result);
        assertEquals(Game.ResultReason.CHECKMATE, game.resultReason);
    }

    // --- fixtures -------------------------------------------------------------------------------------------------

    @FunctionalInterface
    interface Answer {
        LlmResponse answer(int call);
    }

    private void answers(Answer script) {
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline deadline,
                                              int maxOutputTokens, ReasoningEffort effort) {
                return script.answer(requests.incrementAndGet());
            }
        }, OpenRouterService.class);
    }

    private static LlmResponse paid(String move, double cost) {
        String body = "{\"move\":\"" + move + "\"}";
        return new LlmResponse(body, 10, 5, cost, 1, body, false, null);
    }

    private <T> T asWorker(UUID gameId, UUID token, java.util.concurrent.Callable<T> work) throws Exception {
        return ScopedValue.where(GameRunOwnership.CURRENT, new GameRunOwnership.Owner(gameId, token))
                .call(work::call);
    }

    private GameTurnService.TurnRequest turnRequest(UUID gameId) {
        Board board = new Board();
        return new GameTurnService.TurnRequest(gameId, null, board.getFen(), board, "WHITE", 1,
                List.of("e4", "d4"), "test/white", "White", "Black", new ArrayList<>(), null, null,
                true, 3, null, "test", "hash", BigDecimal.ZERO, () -> true, 128, ChatMode.OFF, ChatMode.OFF);
    }

    private UUID tournamentGame(AutoRunStatus run, GameStatus status) {
        UUID tournamentId = seedTournament(status);
        if (run == AutoRunStatus.RUNNING) configure(tournamentId, "{}");
        return gameIds(tournamentId).getFirst();
    }

    private UUID seedTournament(GameStatus... statuses) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament t = new Tournament();
            t.name = "Durable runs";
            t.status = TournamentStatus.IN_PROGRESS;
            t.chatMode = ChatMode.OFF;
            tournaments.persist(t);
            TournamentParticipant white = participant(t, "White", "test/white", 0);
            TournamentParticipant black = participant(t, "Black", "test/black", 1);
            for (int i = 0; i < statuses.length; i++) {
                Game g = new Game();
                g.tournament = t;
                g.whiteParticipant = white;
                g.blackParticipant = black;
                g.whiteModelId = white.modelId;
                g.blackModelId = black.modelId;
                g.whitePlayerName = white.playerName;
                g.blackPlayerName = black.playerName;
                g.status = statuses[i];
                if (statuses[i] != GameStatus.WAITING) g.startedAt = java.time.LocalDateTime.now();
                g.bracketRound = "Semifinal";
                g.bracketPosition = i;
                games.persist(g);
            }
            return t.id;
        });
        seededTournaments.add(id);
        seededGames.addAll(gameIds(id));
        return id;
    }

    private TournamentParticipant participant(Tournament t, String name, String model, int seed) {
        TournamentParticipant p = new TournamentParticipant();
        p.tournament = t;
        p.playerName = name;
        p.modelId = model;
        p.seed = seed;
        p.persist();
        return p;
    }

    private UUID modelPractice(String fen) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Game g = new Game();
            g.practiceKind = Game.PracticeKind.FROM_GAME;
            g.whitePracticeControl = PlayerType.HUMAN;
            g.blackPracticeControl = PlayerType.AI;
            g.whitePlayerName = "You";
            g.blackPlayerName = "Model";
            g.blackModelId = "test/black";
            g.status = GameStatus.IN_PROGRESS;
            g.startedAt = java.time.LocalDateTime.now();
            g.currentFen = fen;
            games.persist(g);
            return g.id;
        });
        seededGames.add(id);
        return id;
    }

    private void configure(UUID id, String body) {
        try {
            runs.configure(id, json.readTree(body), true);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void setRunState(UUID gameId, UUID owner, Instant leaseUntil, Instant requestSentAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findById(gameId);
            game.runOwner = owner;
            game.runLeaseUntil = leaseUntil;
            game.requestSentAt = requestSentAt;
        });
    }

    private Instant queue(UUID gameId, Instant at) {
        Instant stored = at.truncatedTo(ChronoUnit.MICROS);
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).resumeRequestedAt = stored);
        return stored;
    }

    private Game read(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Game game = games.findById(id);
            games.getEntityManager().detach(game);
            return game;
        });
    }

    private int moveCount(UUID gameId) {
        return QuarkusTransaction.requiringNew().call(() -> moves.findByGameId(gameId).size());
    }

    private int moveCountOf(Game game) {
        return moveCount(game.id);
    }

    private List<Outcome> outcomes(UUID gameId) {
        return QuarkusTransaction.requiringNew().call(() -> attemptRows.list("game.id", gameId).stream()
                .map(a -> a.outcome).toList());
    }

    private AutoRunStatus runStatus(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).autoRunStatus);
    }

    private List<UUID> gameIds(UUID tournamentId) {
        return QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(tournamentId).stream()
                .sorted(java.util.Comparator.comparing(g -> g.bracketPosition)).map(g -> g.id).toList());
    }

    private void awaitGame(UUID id, Predicate<Game> check) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.test(read(id))) return;
            Thread.sleep(20);
        }
        fail("game " + id + " did not reach the expected state: " + read(id).status);
    }

    private void awaitReleased(UUID gameId) throws InterruptedException {
        GameEngineService real = ClientProxy.unwrap(engine);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            GameEngineService.GameLoop claim = real.tryClaimLoop(gameId);
            if (claim != null) {
                real.releaseLoop(gameId, claim);
                return;
            }
            Thread.sleep(10);
        }
        fail("game loop did not release " + gameId);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
