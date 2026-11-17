package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Tournament.AutoRunStatus;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.websocket.GameWebSocket;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class TournamentAutoRunIT {
    @Inject TournamentAutoRunService runs;
    @Inject TournamentRepository tournaments;
    @Inject GameRepository games;
    @Inject MoveRepository moves;
    @Inject ChessService chess;
    @Inject GameMoveService gameMoves;
    @Inject GameLifecycleService lifecycle;
    @Inject GameEngineService engine;
    @Inject GameTurnService turns;
    @Inject MoveAttemptService attempts;
    @Inject GameRecovery recovery;
    @Inject TournamentRunScheduler scheduler;
    @Inject TournamentService tournamentService;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "quarkus.datasource.jdbc.url") String jdbcUrl;
    @ConfigProperty(name = "quarkus.datasource.username") String username;
    @ConfigProperty(name = "quarkus.datasource.password") String password;
    GameEngineService realEngine;
    TournamentRunScheduler realScheduler;
    final List<UUID> seeded = new ArrayList<>();
    final List<UUID> starts = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void deterministicScheduling() {
        realEngine = ClientProxy.unwrap(engine);
        realScheduler = ClientProxy.unwrap(scheduler);
        // Exercise the real transactional start path without model calls or asynchronous notifications.
        QuarkusMock.installMockForType(new TournamentRunScheduler() {
            @Override void changed(UUID id) {}
            @Override void tick() {}
        }, TournamentRunScheduler.class);
        QuarkusMock.installMockForType(new GameEngineService() {
            @Override public GameDto startWaitingGame(UUID id) {
                GameDto result = lifecycle.markGameStarted(id, true);
                starts.add(id);
                return result;
            }
            @Override public GameDto startGame(UUID id) {
                GameDto result = lifecycle.markGameStarted(id);
                starts.add(id);
                return result;
            }
            @Override public void requestStop(UUID id) {}
        }, GameEngineService.class);
    }

    @AfterEach
    void disableSeededRuns() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : seeded) {
                Tournament t = tournaments.findByIdForUpdate(id);
                if (t != null) t.autoRunStatus = AutoRunStatus.OFF;
            }
        });
    }

    @Test
    void actualEngineClaimsOnlyOneLoopAcrossConcurrentEventsAndManualStarts() throws Exception {
        UUID id = seed(GameStatus.WAITING, GameStatus.WAITING, GameStatus.WAITING);
        List<UUID> ids = gameIds(id);
        configure(id, "{\"maxConcurrentGames\":2}", true);
        QuarkusMock.installMockForType(realEngine, GameEngineService.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                entered.countDown();
                await(release);
                lifecycle.pauseWithReason(request.gameId(), "Test turn finished");
                return TurnResult.paused();
            }
        }, GameTurnService.class);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                int n = i;
                tasks.add(pool.submit(() -> {
                    await(go);
                    try {
                        if (n % 2 == 0) runs.schedule(id);
                        else engine.startGame(ids.get(n % ids.size()));
                    } catch (WebApplicationException e) { assertEquals(409, e.getResponse().getStatus()); }
                }));
            }
            go.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(2, ids.stream().filter(engine::isRunning).count());
            assertEquals(2, ids.stream().filter(g -> status(g) == GameStatus.IN_PROGRESS).count());
        } finally {
            release.countDown();
            for (UUID gameId : ids) awaitReleased(gameId);
        }
    }

    @Test
    void rollbackReleasesReservedLoopsAndNeverExecutesATurn() {
        UUID id = seed(GameStatus.WAITING);
        UUID gameId = gameIds(id).getFirst();
        configure(id, "{}", true);
        QuarkusMock.installMockForType(realEngine, GameEngineService.class);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                fail("a rolled-back start must never launch a turn");
                return TurnResult.stopped();
            }
        }, GameTurnService.class);
        assertThrows(io.quarkus.narayana.jta.QuarkusTransactionException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    runs.schedule(id);
                    assertTrue(engine.isRunning(gameId), "claim reserved until commit");
                    QuarkusTransaction.setRollbackOnly();
                }));
        assertEquals(GameStatus.WAITING, status(gameId));
        assertFalse(engine.isRunning(gameId));
        GameEngineService.GameLoop claim = realEngine.tryClaimLoop(gameId);
        assertNotNull(claim);
        realEngine.releaseLoop(gameId, claim);
    }

    @Test
    void schedulerNotificationAndSafetyTickBothUsePersistedState() {
        UUID id = seed(GameStatus.WAITING, GameStatus.WAITING, GameStatus.WAITING);
        configure(id, "{\"maxConcurrentGames\":1}", true);
        realScheduler.recoveryComplete();
        realScheduler.onChange(new TournamentRunScheduler.RunChanged(id));
        assertEquals(List.of(gameIds(id).getFirst()), starts);
        realScheduler.tick();
        assertEquals(1, starts.size());
        lifecycle.pauseWithReason(starts.getFirst(), "Test attention");
        realScheduler.tick();
        assertEquals(2, starts.size());
    }

    private void awaitReleased(UUID gameId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            GameEngineService.GameLoop claim = realEngine.tryClaimLoop(gameId);
            if (claim != null) {
                realEngine.releaseLoop(gameId, claim);
                return;
            }
            Thread.sleep(10);
        }
        fail("test loop did not release its claim");
    }

    @Test
    void schedulerStartsInBracketOrderAndFillsFreedSlotsWithoutStartingPausedUnknownOrHumanGames() {
        UUID id = seed(GameStatus.WAITING, GameStatus.WAITING, GameStatus.WAITING, GameStatus.PAUSED,
                GameStatus.WAITING, GameStatus.WAITING);
        List<UUID> ids = gameIds(id);
        QuarkusTransaction.requiringNew().run(() -> {
            games.findById(ids.get(0)).bracketRound = "Final"; // later round, despite lower position
            games.findById(ids.get(4)).blackParticipant = null;
            TournamentParticipant human = new TournamentParticipant();
            human.tournament = tournaments.findById(id);
            human.playerName = "Human";
            human.controlType = TournamentParticipant.ControlType.HUMAN;
            human.persist();
            games.findById(ids.get(5)).whiteParticipant = human;
        });
        configure(id, "{}", true);
        runs.schedule(id);
        assertEquals(List.of(ids.get(1), ids.get(2)), starts);
        runs.schedule(id);
        assertEquals(2, starts.size());
        lifecycle.pauseWithReason(ids.get(1), "Needs attention");
        runs.schedule(id);
        assertEquals(List.of(ids.get(1), ids.get(2), ids.get(0)), starts);
        assertEquals(GameStatus.PAUSED, status(ids.get(3)));
        assertEquals(GameStatus.WAITING, status(ids.get(4)));
        assertEquals(GameStatus.WAITING, status(ids.get(5)));
    }

    @Test
    void seriesAndBracketAdvancementSupplyNewReadyGamesUntilRunFinishes() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament t = new Tournament();
            t.name = "Auto-run series";
            t.matchupBestOf = 3;
            tournaments.persist(t);
            for (int i = 0; i < 4; i++) participant(t, "Player " + i, i);
            return t.id;
        });
        seeded.add(id);
        tournamentService.generateBracket(id, dev.aichessarena.entity.BracketDrawMode.ORDER);
        configure(id, "{\"maxConcurrentGames\":1}", true);
        for (int i = 0; i < 6; i++) {
            runs.schedule(id);
            UUID next = starts.getLast();
            var snapshot = snapshot(next);
            assertEquals(i < 4 ? "Semifinal" : "Final", snapshot.bracketRound());
            assertEquals(i % 2 + 1, snapshot.seriesGameNumber());
            lifecycle.completeGame(next, new ChessService.GameEndInfo(i % 2 == 0 ? "WHITE_WINS" : "BLACK_WINS", "CHECKMATE"));
        }
        runs.schedule(id);
        assertEquals(6, starts.size());
        assertEquals(AutoRunStatus.FINISHED, runStatus(id));
        assertEquals(TournamentStatus.COMPLETED,
                QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).status));
    }

    @Test
    void loweringBudgetImmediatelyPausesAndLateAnswersStillIncreaseSpend() {
        UUID id = seed(GameStatus.IN_PROGRESS, GameStatus.IN_PROGRESS);
        configure(id, "{\"budgetUsd\":2}", true);
        UUID gameId = gameIds(id).getFirst();
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).totalCostUsd = BigDecimal.ONE);
        configure(id, "{\"budgetUsd\":0.50}", false);
        assertBudgetPause(id, gameIds(id), "Budget of $0.50 reached ($1.00 spent)");
        attempts.record(new MoveAttemptService.AttemptRecord(gameId, 1, "WHITE", 1, "test/model", Outcome.DISCARDED,
                null, null, new OpenRouterService.LlmResponse("late", 1, 1, 0.25, 1, "late", false, null)));
        assertEquals(0, new BigDecimal("1.25").compareTo(snapshot(gameId).totalCostUsd()));
        configure(id, "{}", true);
        assertEquals(AutoRunStatus.PAUSED, runStatus(id), "resuming below spend re-pauses immediately");
        assertEquals("Budget of $0.50 reached ($1.25 spent)", runReason(id));
    }

    @Test
    void cachedRunWriterCannotUndoCommittedCancellation() throws Exception {
        UUID id = seed(GameStatus.WAITING);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            QuarkusTransaction.requiringNew().run(() -> {
                assertEquals(TournamentStatus.IN_PROGRESS, tournaments.findById(id).status);
                try { pool.submit(() -> tournamentService.cancelTournament(id)).get(20, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
                WebApplicationException conflict = assertThrows(WebApplicationException.class,
                        () -> configure(id, "{}", true));
                assertEquals(409, conflict.getResponse().getStatus());
                QuarkusTransaction.setRollbackOnly();
            });
        } catch (io.quarkus.narayana.jta.QuarkusTransactionException expected) {
            // The rejected nested write marks the outer transaction rollback-only.
        }
        runs.schedule(id);
        assertEquals(AutoRunStatus.OFF, runStatus(id));
        assertEquals(GameStatus.CANCELLED, status(gameIds(id).getFirst()));
        assertTrue(starts.isEmpty());
    }

    @Test
    void simultaneousEventsAndManualStartsNeverDoubleStartOrExceedCap() throws Exception {
        UUID id = seed(GameStatus.WAITING, GameStatus.WAITING, GameStatus.WAITING, GameStatus.WAITING);
        configure(id, "{\"maxConcurrentGames\":2}", true);
        List<UUID> ids = gameIds(id);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                tasks.add(pool.submit(() -> {
                    await(go);
                    try {
                        if (n % 2 == 0) runs.schedule(id);
                        else engine.startGame(ids.get(n % ids.size()));
                    } catch (WebApplicationException e) {
                        assertEquals(409, e.getResponse().getStatus());
                    }
                }));
            }
            go.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertEquals(2, starts.size());
        assertEquals(2, starts.stream().distinct().count());
        assertEquals(2, gameIds(id).stream().filter(g -> status(g) == GameStatus.IN_PROGRESS).count());
    }

    @Test
    void pauseAndLoweredConcurrencyUseLifecycleAndResumeLeavesPausedGamesForAttention() {
        UUID id = seed(GameStatus.WAITING, GameStatus.WAITING, GameStatus.WAITING);
        List<UUID> ids = gameIds(id);
        configure(id, "{\"maxConcurrentGames\":3}", true);
        runs.schedule(id);
        configure(id, "{\"maxConcurrentGames\":1}", false);
        assertEquals(GameStatus.IN_PROGRESS, status(ids.getFirst()));
        assertEquals(GameStatus.PAUSED, status(ids.get(1)));
        assertEquals(2, snapshot(ids.get(1)).statusRevision());
        assertEquals("Concurrency limit reduced to 1", snapshot(ids.get(1)).statusDetail());
        runs.pause(id);
        assertEquals(GameStatus.PAUSED, status(ids.getFirst()));
        assertEquals("Paused by user", snapshot(ids.getFirst()).statusDetail());
        assertEquals(409, assertThrows(WebApplicationException.class, () -> engine.startGame(ids.getFirst()))
                .getResponse().getStatus());
        configure(id, "{}", true);
        runs.schedule(id);
        assertEquals(3, starts.size(), "paused games never auto-resume");
        engine.startGame(ids.getFirst());
        assertEquals(GameStatus.IN_PROGRESS, status(ids.getFirst()));
    }

    @Test
    void budgetAtTurnStartPausesAllRunningGamesAndRaisingThenResumingAllowsManualContinuation() {
        UUID id = seed(GameStatus.IN_PROGRESS, GameStatus.IN_PROGRESS, GameStatus.WAITING);
        List<UUID> ids = gameIds(id);
        configure(id, "{\"budgetUsd\":1.00}", true);
        QuarkusTransaction.requiringNew().run(() -> games.findById(ids.getFirst()).totalCostUsd = BigDecimal.ONE);
        assertFalse(runs.beforeRequest(ids.getFirst()));
        assertBudgetPause(id, ids.subList(0, 2), "Budget of $1.00 reached ($1.00 spent)");
        runs.schedule(id);
        assertTrue(starts.isEmpty());
        configure(id, "{\"budgetUsd\":2.00}", false);
        assertEquals(AutoRunStatus.PAUSED, runStatus(id));
        configure(id, "{}", true);
        engine.startGame(ids.getFirst());
        assertTrue(runs.beforeRequest(ids.getFirst()));
        runs.schedule(id);
        assertEquals(GameStatus.IN_PROGRESS, status(ids.get(2)));
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"ILLEGAL_MOVE", "PROVIDER_RETRYABLE", "TRUNCATED", "DISCARDED", "ACCEPTED"})
    void eachRecordedAttemptChecksAggregateSpendIncludingFailedAndAlreadyCostedAnswers(Outcome outcome) {
        UUID id = seed(GameStatus.IN_PROGRESS, GameStatus.IN_PROGRESS, GameStatus.WAITING);
        List<UUID> ids = gameIds(id);
        configure(id, "{\"budgetUsd\":1.00}", true);
        QuarkusTransaction.requiringNew().run(() -> {
            games.findById(ids.getFirst()).totalCostUsd = new BigDecimal(outcome.isCostedElsewhere() ? "0.65" : "0.40");
            games.findById(ids.get(1)).totalCostUsd = new BigDecimal("0.35");
        });
        attempts.record(new MoveAttemptService.AttemptRecord(ids.getFirst(), 1, "WHITE", 1, "test/model", outcome,
                "bad", null, new OpenRouterService.LlmResponse("bad", 1, 1, 0.25, 1, "bad", false, null)));
        assertBudgetPause(id, ids.subList(0, 2), "Budget of $1.00 reached ($1.00 spent)");
        assertEquals(GameStatus.WAITING, status(ids.get(2)));
    }

    @Test
    void realTurnDoesNotCallProviderWhenBudgetIsReached() {
        UUID id = seed(GameStatus.IN_PROGRESS);
        UUID gameId = gameIds(id).getFirst();
        configure(id, "{\"budgetUsd\":0.01}", true);
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).totalCostUsd = new BigDecimal("0.01"));
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String model, List<ChatMsg> history, AnswerDeadline deadline,
                                              int tokens, dev.aichessarena.entity.ReasoningEffort effort) {
                fail("budget check must prevent the model request");
                return null;
            }
        }, OpenRouterService.class);
        var result = turns.playTurn(turnRequest(gameId));
        assertTrue(result.pausedGame());
        assertEquals(AutoRunStatus.PAUSED, runStatus(id));
    }

    @Test
    void realRetryStopsAfterPaidInvalidAttemptReachesBudget() {
        UUID id = seed(GameStatus.IN_PROGRESS, GameStatus.IN_PROGRESS);
        UUID gameId = gameIds(id).getFirst();
        configure(id, "{\"budgetUsd\":0.01}", true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String model, List<ChatMsg> history, AnswerDeadline deadline,
                                              int tokens, dev.aichessarena.entity.ReasoningEffort effort) {
                assertEquals(1, calls.incrementAndGet(), "no second request after cap");
                return new LlmResponse("{\"move\":\"e5\"}", 1, 1, 0.01, 1, "bad", false, null);
            }
        }, OpenRouterService.class);
        assertTrue(turns.playTurn(turnRequest(gameId)).pausedGame());
        assertBudgetPause(id, gameIds(id), "Budget of $0.01 reached ($0.01 spent)");
    }

    @ParameterizedTest
    @CsvSource({
        "1, 7k/5Q2/6K1/8/8/8/8/8 w - - 0 1, Qg7#, CHECKMATE, WHITE_WINS",
        "3, 7k/5Q2/6K1/8/8/8/8/8 w - - 0 1, Qg7#, CHECKMATE, WHITE_WINS",
        "1, 7k/8/5KQ1/8/8/8/8/8 w - - 0 1, Qf7, STALEMATE, DRAW",
        "1, 7k/8/8/8/8/3r4/2B5/K7 w - - 0 1, Bxd3, INSUFFICIENT_MATERIAL, DRAW",
        "1, 7k/8/8/8/8/8/R7/K7 w - - 99 1, Ra3, FIFTY_MOVE, DRAW"
    })
    void terminalMoveAtBudgetCapCompletesAndAdvancesBeforePausingRemainingGames(
            int bestOf, String fen, String san, String reason, String result) {
        UUID id = budgetBracket(bestOf);
        UUID gameId = starts.getFirst();
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).currentFen = fen);
        finishAtCap(id, gameId, "{\"move\":\"" + san + "\"}", reason, result, bestOf);
    }

    @Test
    void repetitionAtBudgetCapUsesTheBoardsMoveHistoryAndAdvances() {
        UUID id = budgetBracket(1);
        UUID gameId = starts.getFirst();
        preparePlies(gameId, List.of("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1"));
        finishAtCap(id, gameId, "{\"move\":\"Ng8\"}", "REPETITION", "DRAW", 1);
    }

    @Test
    void resignationAtBudgetCapCompletesAndAdvancesBeforePausingRemainingGames() {
        UUID id = budgetBracket(1);
        UUID gameId = starts.getFirst();
        preparePlies(gameId, List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O",
                "Be7", "Re1", "b5", "Bb3", "d6", "c3", "O-O", "h3", "Nb8"));
        QuarkusTransaction.requiringNew().run(() -> tournaments.findById(id).resignationAllowed = true);
        finishAtCap(id, gameId, "{\"resign\":true}", "RESIGNATION", "BLACK_WINS", 1);
    }

    @Test
    void storedAdjudicationAtBudgetCapCompletesAndAdvancesBeforePausingRemainingGames() {
        UUID id = budgetBracket(1);
        UUID gameId = starts.getFirst();
        preparePlies(gameId, List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O"));
        QuarkusTransaction.requiringNew().run(() -> tournaments.findById(id).adjudicationEnabled = true);
        QuarkusMock.installMockForType(new StockfishService() {
            @Override public java.util.concurrent.CompletableFuture<EvalResult> evaluate(String fen, int depth) {
                return new java.util.concurrent.CompletableFuture<>();
            }
        }, StockfishService.class);
        GameMoveService realMoves = ClientProxy.unwrap(gameMoves);
        QuarkusMock.installMockForType(new GameMoveService() {
            @Override public void recordModelMove(UUID changedId, int moveNumber, String color, String san,
                    String fen, String model, String player, String message, OpenRouterService.LlmResponse response,
                    int retries, String promptVersion, String promptHash, String expectedFen,
                    dev.aichessarena.entity.ChatMode chatMode) {
                realMoves.recordModelMove(changedId, moveNumber, color, san, fen, model, player, message, response,
                        retries, promptVersion, promptHash, expectedFen, chatMode);
                // Model an evaluation already committed when the move returns. Never wait for the
                // asynchronous engine future: unavailable evaluations must not delay a budget pause.
                QuarkusTransaction.requiringNew().run(() -> moves.findByGameId(changedId).getLast().evaluationCp = 1230);
            }
        }, GameMoveService.class);
        finishAtCap(id, gameId, "{\"move\":\"Be7\"}", "ADJUDICATION", "WHITE_WINS", 1);
    }

    @Test
    void nonterminalMoveAtBudgetCapPausesWithoutWaitingForAdjudicationEvaluations() {
        UUID id = budgetBracket(1);
        UUID gameId = starts.getFirst();
        preparePlies(gameId, List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O"));
        QuarkusTransaction.requiringNew().run(() -> tournaments.findById(id).adjudicationEnabled = true);
        QuarkusMock.installMockForType(new StockfishService() {
            @Override public java.util.concurrent.CompletableFuture<EvalResult> evaluate(String fen, int depth) {
                return new java.util.concurrent.CompletableFuture<>();
            }
        }, StockfishService.class);
        paidAnswer("{\"move\":\"Be7\"}");
        runLoop(gameId);
        assertBudgetPause(id, starts, "Budget of $0.01 reached ($0.01 spent)");
        assertNull(snapshot(gameId).result());
        assertNull(snapshot(gameId).advancedParticipantId());
        assertEquals(3, gameIds(id).size());
    }

    @Test
    void cancellationAfterRecordingAMatingMoveStillPreventsCompletionAndAdvancement() {
        UUID id = budgetBracket(1);
        UUID gameId = starts.getFirst();
        QuarkusTransaction.requiringNew().run(() ->
                games.findById(gameId).currentFen = "7k/5Q2/6K1/8/8/8/8/8 w - - 0 1");
        QuarkusMock.installMockForType(new ChessService() {
            @Override public boolean isGameOver(Board board) {
                boolean terminal = super.isGameOver(board);
                if (terminal) tournamentService.cancelTournament(id);
                return terminal;
            }
        }, ChessService.class);
        paidAnswer("{\"move\":\"Qg7#\"}");
        runLoop(gameId);
        GameDto finished = snapshot(gameId);
        assertEquals("CANCELLED", finished.status());
        assertNull(finished.result());
        assertNull(finished.advancedParticipantId());
        assertEquals(3, gameIds(id).size(), "no new series game");
        assertTrue(QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(id).stream()
                .filter(g -> "Final".equals(g.bracketRound))
                .allMatch(g -> g.whiteParticipant == null && g.blackParticipant == null)));
        assertEquals(AutoRunStatus.OFF, runStatus(id));
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> moves.findByGameId(gameId).size()));
    }

    private UUID budgetBracket(int bestOf) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament t = new Tournament();
            t.name = "Terminal answer at budget cap";
            t.matchupBestOf = bestOf;
            tournaments.persist(t);
            for (int i = 0; i < 4; i++) participant(t, "Player " + i, i);
            return t.id;
        });
        seeded.add(id);
        tournamentService.generateBracket(id, dev.aichessarena.entity.BracketDrawMode.ORDER);
        configure(id, "{\"maxConcurrentGames\":2,\"budgetUsd\":0.01}", true);
        runs.schedule(id);
        assertEquals(2, starts.size());
        return id;
    }

    private void preparePlies(UUID gameId, List<String> sans) {
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findById(gameId);
            Board board = new Board();
            for (String san : sans) {
                var move = new dev.aichessarena.entity.Move();
                move.game = game;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                move.san = san;
                var applied = chess.validateAndApply(board, san);
                assertTrue(applied.valid(), san);
                move.fen = applied.fen();
                move.evaluationCp = 1230;
                moves.persist(move);
            }
            game.currentFen = board.getFen();
        });
    }

    private void paidAnswer(String content) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String model, List<ChatMsg> history, AnswerDeadline deadline,
                                              int tokens, dev.aichessarena.entity.ReasoningEffort effort) {
                assertEquals(1, calls.incrementAndGet(), "no second request after the terminal answer");
                return new LlmResponse(content, 1, 1, 0.01, 1, content, false, null);
            }
        }, OpenRouterService.class);
    }

    private void runLoop(UUID gameId) {
        QuarkusMock.installMockForType(realEngine, GameEngineService.class);
        GameEngineService.GameLoop loop = realEngine.tryClaimLoop(gameId);
        assertNotNull(loop);
        try {
            realEngine.runGameLoop(gameId, loop, null, null);
        } finally {
            realEngine.releaseLoop(gameId, loop);
        }
    }

    private void finishAtCap(UUID id, UUID gameId, String answer, String reason, String result, int bestOf) {
        List<String> announcements = new ArrayList<>();
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override public void broadcastGameStatus(UUID changedId, String status, String announcedResult,
                    String announcedReason, int totalMoves, BigDecimal cost, String activeColor,
                    java.time.Instant startedAt, java.time.Instant deadlineAt, String detail, String chatMode,
                    String banterLevel) {}

            @Override public void broadcastMove(UUID changedId, int moveNumber, String color, String san,
                    String fen, String pgn, String model, long responseTimeMs, int retries, boolean override) {}

            @Override public void broadcastAttemptsUpdated(UUID changedId) {}

            @Override public void broadcastEvaluation(UUID changedId, int moveNumber, String color, Integer cp,
                    Integer mate, List<dev.aichessarena.dto.PlyQualityDto> qualities,
                    dev.aichessarena.dto.GameQualitySummaryDto qualitySummary) {}

            @Override public void broadcastStatusChange(UUID changedId, String status, String announcedResult,
                    String announcedReason, int totalMoves, BigDecimal cost, String detail, long revision) {
                // Separate transactions must see completed state and progression before any budget pause.
                GameDto stored = snapshot(gameId);
                assertEquals("COMPLETED", stored.status());
                assertEquals(result, stored.result());
                assertEquals(reason, stored.resultReason());
                assertProgression(id, gameId, bestOf);
                assertEquals(status, status(changedId).name());
                if (changedId.equals(gameId)) {
                    assertEquals(AutoRunStatus.RUNNING, runStatus(id));
                    assertEquals("COMPLETED", status);
                    assertEquals(stored.statusRevision(), revision);
                }
                announcements.add(status);
            }
        }, GameWebSocket.class);
        paidAnswer(answer);
        runLoop(gameId);
        assertEquals(List.of("COMPLETED", "PAUSED"), announcements);
        assertNull(snapshot(gameId).statusDetail());
        assertEquals(0, new BigDecimal("0.01").compareTo(snapshot(gameId).totalCostUsd()));
        assertBudgetPause(id, List.of(starts.get(1)), "Budget of $0.01 reached ($0.01 spent)");
        runs.schedule(id);
        assertEquals(2, starts.size(), "remaining waiting games must not start");
    }

    private void assertProgression(UUID id, UUID gameId, int bestOf) {
        assertTrue(QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(id).stream().anyMatch(g ->
                g.status == GameStatus.WAITING && (bestOf == 1
                        ? "Final".equals(g.bracketRound) && (g.whiteParticipant != null || g.blackParticipant != null)
                        : g.seriesId.equals(games.findById(gameId).seriesId) && g.seriesGameNumber == 2))));
    }

    @Test
    void manualGamesWithoutARunIgnoreStoredBudget() {
        UUID id = seed(GameStatus.IN_PROGRESS);
        configure(id, "{\"budgetUsd\":0.01}", false);
        UUID gameId = gameIds(id).getFirst();
        QuarkusTransaction.requiringNew().run(() -> games.findById(gameId).totalCostUsd = BigDecimal.TEN);
        assertTrue(runs.beforeRequest(gameId));
        assertEquals(AutoRunStatus.OFF, runStatus(id));
    }

    @Test
    void restartPausesRunningRunsAndGamesAndDoesNotResumeWaitingGames() {
        UUID id = seed(GameStatus.IN_PROGRESS, GameStatus.WAITING);
        configure(id, "{}", true);
        recovery.onStart(null);
        assertEquals(AutoRunStatus.PAUSED, runStatus(id));
        assertEquals("Backend restarted", runReason(id));
        assertEquals(GameStatus.PAUSED, status(gameIds(id).getFirst()));
        runs.schedule(id);
        assertTrue(starts.isEmpty());
    }

    @Test
    void cancellationStopsRunAndCompletionFinishesIt() {
        UUID id = seed(GameStatus.WAITING, GameStatus.IN_PROGRESS);
        configure(id, "{}", true);
        tournamentService.cancelTournament(id);
        runs.schedule(id);
        assertEquals(AutoRunStatus.OFF, runStatus(id));
        assertTrue(gameIds(id).stream().allMatch(g -> status(g) == GameStatus.CANCELLED));
        assertTrue(starts.isEmpty());
        UUID finished = seed(GameStatus.COMPLETED);
        configure(finished, "{}", true);
        runs.schedule(finished);
        assertEquals(AutoRunStatus.FINISHED, runStatus(finished));
    }

    @ParameterizedTest
    @EnumSource(TournamentStatus.class)
    void apiStatusMatrix(TournamentStatus status) {
        UUID id = seed(GameStatus.WAITING);
        QuarkusTransaction.requiringNew().run(() -> tournaments.findById(id).status = status);
        int expected = status == TournamentStatus.IN_PROGRESS ? 200 : 409;
        given().contentType("application/json").body("{}").post("/api/tournaments/{id}/run", id).then().statusCode(expected);
        given().contentType("application/json").body("{}").put("/api/tournaments/{id}/run", id).then().statusCode(expected);
        given().post("/api/tournaments/{id}/run/pause", id).then().statusCode(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"maxConcurrentGames\":0}", "{\"maxConcurrentGames\":9}",
            "{\"maxConcurrentGames\":1.5}", "{\"maxConcurrentGames\":null}", "{\"maxConcurrentGames\":\"2\"}",
            "{\"maxConcurrentGames\":2147483648}", "{\"budgetUsd\":0}", "{\"budgetUsd\":-1}",
            "{\"budgetUsd\":0.001}", "{\"budgetUsd\":\"1.00\"}", "{\"budgetUsd\":1e20}", "{\"unexpected\":1}"})
    void apiRejectsInvalidSettingsAtomically(String body) {
        UUID id = seed(GameStatus.WAITING);
        for (String method : List.of("POST", "PUT")) {
            given().contentType("application/json").body(body).request(method, "/api/tournaments/{id}/run", id)
                    .then().statusCode(400);
        }
        assertEquals(AutoRunStatus.OFF, runStatus(id));
    }

    @Test
    void apiDefaultsEditsOmissionsRemovalAndMissingTournament() {
        UUID id = seed(GameStatus.WAITING);
        given().get("/api/tournaments/{id}", id).then().body("autoRunStatus", equalTo("OFF"))
                .body("maxConcurrentGames", equalTo(2)).body("budgetUsd", nullValue());
        given().contentType("application/json").body("{\"maxConcurrentGames\":8,\"budgetUsd\":5.00}")
                .post("/api/tournaments/{id}/run", id).then().statusCode(200).body("autoRunStatus", equalTo("RUNNING"));
        given().post("/api/tournaments/{id}/run/pause", id).then().statusCode(200).body("autoRunStatus", equalTo("PAUSED"));
        given().contentType("application/json").body("{}").put("/api/tournaments/{id}/run", id).then()
                .statusCode(200).body("maxConcurrentGames", equalTo(8)).body("budgetUsd", equalTo(5.00f))
                .body("autoRunStatus", equalTo("PAUSED"));
        given().contentType("application/json").body("{\"budgetUsd\":null,\"maxConcurrentGames\":1}")
                .post("/api/tournaments/{id}/run", id).then().statusCode(200).body("budgetUsd", nullValue());
        UUID missing = UUID.randomUUID();
        given().contentType("application/json").body("{}").post("/api/tournaments/{id}/run", missing).then().statusCode(404);
        given().contentType("application/json").body("{}").put("/api/tournaments/{id}/run", missing).then().statusCode(404);
        given().post("/api/tournaments/{id}/run/pause", missing).then().statusCode(404);
    }

    @Test
    void v20MigrationPreservesHistoryAndAddsDefaultsAndConstraints() throws Exception {
        String database = "autorun_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(jdbcUrl, username, password); var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            Flyway.configure().dataSource(url, username, password).target("19").load().migrate();
            try (var db = DriverManager.getConnection(url, username, password); var sql = db.createStatement()) {
                sql.execute("INSERT INTO tournaments(name) VALUES ('Existing')");
                sql.execute("INSERT INTO games(tournament_id,status,total_cost_usd,pgn) "
                        + "SELECT id,'COMPLETED',1.23456789,'1. e4' FROM tournaments");
            }
            Flyway.configure().dataSource(url, username, password).target("20").load().migrate();
            try (var db = DriverManager.getConnection(url, username, password); var sql = db.createStatement()) {
                sql.execute("INSERT INTO tournaments(name) VALUES ('New')");
                try (var rows = sql.executeQuery("SELECT auto_run_status,max_concurrent_games,budget_usd,auto_run_pause_reason FROM tournaments")) {
                    int count = 0;
                    while (rows.next()) {
                        assertEquals("OFF", rows.getString(1));
                        assertEquals(2, rows.getInt(2));
                        assertNull(rows.getBigDecimal(3));
                        assertNull(rows.getString(4));
                        count++;
                    }
                    assertEquals(2, count);
                }
                try (var rows = sql.executeQuery("SELECT status,total_cost_usd,pgn FROM games")) {
                    assertTrue(rows.next());
                    assertEquals("COMPLETED", rows.getString(1));
                    assertEquals(new BigDecimal("1.23456789"), rows.getBigDecimal(2));
                    assertEquals("1. e4", rows.getString(3));
                }
                for (String setting : List.of("auto_run_status = 'UNKNOWN'", "auto_run_status = NULL",
                        "max_concurrent_games = 0", "max_concurrent_games = 9", "max_concurrent_games = NULL", "budget_usd = 0")) {
                    assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE tournaments SET " + setting));
                }
            }
        } finally {
            try (var admin = DriverManager.getConnection(jdbcUrl, username, password); var sql = admin.createStatement()) {
                sql.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private GameTurnService.TurnRequest turnRequest(UUID gameId) {
        var board = new com.github.bhlangonijr.chesslib.Board();
        return new GameTurnService.TurnRequest(gameId, null, board.getFen(), board, "WHITE", 1,
                List.of("e4", "d4"), "test/model", "White", "Black", new ArrayList<>(), null, null,
                true, 3, null, "test", "hash", BigDecimal.ZERO, () -> true, 128,
                dev.aichessarena.entity.ChatMode.OFF, dev.aichessarena.entity.ChatMode.OFF);
    }

    private void assertBudgetPause(UUID id, List<UUID> paused, String reason) {
        assertEquals(AutoRunStatus.PAUSED, runStatus(id));
        assertEquals(reason, runReason(id));
        for (UUID game : paused) {
            assertEquals(GameStatus.PAUSED, status(game));
            assertEquals(reason, snapshot(game).statusDetail());
        }
    }

    private void configure(UUID id, String body, boolean resume) {
        try { runs.configure(id, json.readTree(body), resume); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
    }
    private AutoRunStatus runStatus(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).autoRunStatus);
    }
    private String runReason(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).autoRunPauseReason);
    }
    private GameStatus status(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> games.findById(id).status);
    }
    private GameDto snapshot(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> dev.aichessarena.dto.DtoMapper.toDto(games.findById(id), false));
    }
    private List<UUID> gameIds(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(id).stream()
                .sorted(java.util.Comparator.comparing(g -> g.bracketPosition)).map(g -> g.id).toList());
    }
    private UUID seed(GameStatus... statuses) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament t = new Tournament();
            t.name = "Auto-run tests";
            t.status = TournamentStatus.IN_PROGRESS;
            tournaments.persist(t);
            TournamentParticipant white = participant(t, "White", 0);
            TournamentParticipant black = participant(t, "Black", 1);
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
                g.bracketRound = "Semifinal";
                g.bracketPosition = i;
                games.persist(g);
            }
            return t.id;
        });
        seeded.add(id);
        return id;
    }
    private TournamentParticipant participant(Tournament t, String name, int seed) {
        TournamentParticipant p = new TournamentParticipant();
        p.tournament = t;
        p.playerName = name;
        p.modelId = "test/model";
        p.seed = seed;
        p.persist();
        return p;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
}
