package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Live updates are sent after their transaction commits, against a real PostgreSQL and real transactions: a viewer
 * that reacts to an update must already read the state it announces, and an update that arrives late carries an
 * older status revision than the one that overtook it.
 */
@QuarkusTest
class LiveUpdateOrderIT {

    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameMoveService gameMoveService;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    /**
     * Regression: a pause commits, but its update is delayed until a resume has committed and been announced. The
     * late PAUSED update must carry an older revision than the IN_PROGRESS one (and the stored game), so a viewer
     * keeps showing the game as running.
     */
    @Test
    void aPauseUpdateThatArrivesAfterTheResumeCarriesTheOlderRevision() throws Exception {
        UUID gameId = runningGame();
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch resumeDelivered = new CountDownLatch(1);
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override
            public void broadcastStatusChange(UUID id, String status, String result, String resultReason,
                                              int totalMoves, BigDecimal totalCostUsd, String detail,
                                              long statusRevision, Instant resumeRequestedAt,
                                              RunCapacity capacity, Instant uncertainRequestSentAt) {
                if ("PAUSED".equals(status)) {
                    try {
                        resumeDelivered.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                delivered.add(status + "@" + statusRevision);
                if ("IN_PROGRESS".equals(status)) {
                    resumeDelivered.countDown();
                }
            }
        }, GameWebSocket.class);

        CompletableFuture<Void> pause = CompletableFuture.runAsync(() -> gameLifecycleService.markGamePaused(gameId));
        awaitStatus(gameId, GameStatus.PAUSED);
        gameLifecycleService.markGameStarted(gameId);
        pause.get(15, TimeUnit.SECONDS);

        assertEquals(List.of("IN_PROGRESS@2", "PAUSED@1"), delivered);
        Number stored = given().when().get("/api/games/{id}", gameId.toString())
                .then().statusCode(200)
                .extract().path("statusRevision");
        assertEquals(2L, stored.longValue());
        assertEquals("IN_PROGRESS", given().when().get("/api/games/{id}", gameId.toString())
                .then().extract().path("status"));
    }

    /**
     * Regression: the evaluation update went out inside its transaction, so a viewer that reloaded the game on it
     * (to show a resignation's winning chance) could read the move without its evaluation.
     */
    @Test
    void aGameReadWhenTheEvaluationIsAnnouncedAlreadyHasIt() {
        UUID gameId = runningGame();
        UUID moveId = QuarkusTransaction.requiringNew().call(() -> {
            Move move = new Move();
            move.game = gameRepository.findById(gameId);
            move.moveNumber = 1;
            move.color = "WHITE";
            move.san = "e4";
            move.fen = AFTER_E4;
            moveRepository.persist(move);
            return move.id;
        });
        List<Object> seenAtBroadcast = Collections.synchronizedList(new ArrayList<>());
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override
            public void broadcastEvaluation(UUID id, int moveNumber, String color, Integer evaluationCp,
                                            Integer evaluationMate, List<PlyQualityDto> qualities,
                                            GameQualitySummaryDto qualitySummary) {
                seenAtBroadcast.add(evaluationCp);
                seenAtBroadcast.add(given().when().get("/api/games/{id}", id.toString())
                        .then().statusCode(200)
                        .extract().path("moves[0].evaluationCp"));
            }
        }, GameWebSocket.class);

        gameMoveService.updateMoveEvaluation(moveId, new StockfishService.EvalResult(35, null));

        assertEquals(List.of(35, 35), seenAtBroadcast);
    }

    /** A status change inside an outer transaction is announced with the outer commit, and never on rollback. */
    @Test
    void aJoinedChangeIsAnnouncedWithTheOuterCommitAndARolledBackOneNever() {
        UUID committed = runningGame();
        UUID rolledBack = runningGame();
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override
            public void broadcastStatusChange(UUID id, String status, String result, String resultReason,
                                              int totalMoves, BigDecimal totalCostUsd, String detail,
                                              long statusRevision, Instant resumeRequestedAt,
                                              RunCapacity capacity, Instant uncertainRequestSentAt) {
                delivered.add(id.equals(committed) ? "committed " + status : "rolled back " + status);
            }
        }, GameWebSocket.class);

        QuarkusTransaction.requiringNew().run(() -> {
            gameLifecycleService.pauseWithReason(committed, "Operator check");
            assertEquals(List.of(), delivered, "nothing before the outer commit");
        });
        assertEquals(List.of("committed PAUSED"), delivered);

        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            gameLifecycleService.markGamePaused(rolledBack);
            throw new IllegalStateException("rolled back");
        }));
        assertEquals(List.of("committed PAUSED"), delivered);
        assertEquals(GameStatus.IN_PROGRESS,
                QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(rolledBack).status));
    }

    private UUID runningGame() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.whitePlayerName = "White";
            game.whiteModelId = "test/white";
            game.blackPlayerName = "Black";
            game.blackModelId = "test/black";
            game.status = GameStatus.IN_PROGRESS;
            gameRepository.persist(game);
            return game.id;
        });
    }

    private void awaitStatus(UUID gameId, GameStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        GameStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            status = QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(gameId).status);
            if (status == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(false, "game did not reach " + expected + " (last status " + status + ")");
    }
}
