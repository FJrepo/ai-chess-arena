package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Database-level guarantees for game state: runs against a real PostgreSQL via Dev Services. */
@QuarkusTest
class GameIntegrityIT {

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
    private static final String AFTER_D4 = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1";

    @Inject
    GameLifecycleService lifecycle;

    @Inject
    GameMoveService moves;

    @Inject
    GameRepository games;

    @Inject
    MoveRepository moveRepository;

    @Test
    void startIsOnlyAllowedFromWaitingOrPaused() {
        for (GameStatus status : GameStatus.values()) {
            UUID id = newGame(status);
            if (status == GameStatus.WAITING || status == GameStatus.PAUSED) {
                lifecycle.markGameStarted(id);
                assertEquals(GameStatus.IN_PROGRESS, statusOf(id), "from " + status);
            } else {
                WebApplicationException error = assertThrows(WebApplicationException.class,
                        () -> lifecycle.markGameStarted(id), "from " + status);
                assertEquals(409, error.getResponse().getStatus());
                assertEquals(status, statusOf(id));
            }
        }
    }

    @Test
    void concurrentStartsLetExactlyOneRequestWin() throws Exception {
        UUID id = newGame(GameStatus.WAITING);

        List<Throwable> outcomes = runConcurrently(
                () -> {
                    lifecycle.markGameStarted(id);
                    return null;
                },
                () -> {
                    lifecycle.markGameStarted(id);
                    return null;
                }
        );

        assertEquals(1, outcomes.stream().filter(o -> o == null).count());
        assertEquals(1, outcomes.stream().filter(o -> o instanceof WebApplicationException w
                && w.getResponse().getStatus() == 409).count());
        assertEquals(GameStatus.IN_PROGRESS, statusOf(id));
    }

    @Test
    void concurrentHumanMovesPersistExactlyOnePly() throws Exception {
        UUID id = newGame(GameStatus.IN_PROGRESS);

        List<Throwable> outcomes = runConcurrently(
                () -> {
                    moves.recordHumanMove(id, 1, "WHITE", "e4", AFTER_E4, "Human", null, START_FEN);
                    return null;
                },
                () -> {
                    moves.recordHumanMove(id, 1, "WHITE", "d4", AFTER_D4, "Human", null, START_FEN);
                    return null;
                }
        );

        assertEquals(1, outcomes.stream().filter(o -> o == null).count());
        assertEquals(1, outcomes.stream().filter(o -> o instanceof GameStateConflictException).count());
        List<Move> persisted = QuarkusTransaction.requiringNew().call(() -> moveRepository.findByGameId(id));
        assertEquals(1, persisted.size());
        String fen = QuarkusTransaction.requiringNew().call(() -> games.findById(id).currentFen);
        assertEquals(persisted.getFirst().fen, fen);
    }

    @Test
    void databaseRejectsADuplicatePly() {
        UUID id = newGame(GameStatus.IN_PROGRESS);
        insertMove(id, "e4", AFTER_E4);

        Exception error = assertThrows(Exception.class, () -> insertMove(id, "d4", AFTER_D4));
        assertTrue(rootMessage(error).contains("uq_moves_game_ply"), rootMessage(error));
    }

    private UUID newGame(GameStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.status = status;
            game.whitePlayerName = "White";
            game.blackPlayerName = "Black";
            games.persist(game);
            return game.id;
        });
    }

    private GameStatus statusOf(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> games.findById(id).status);
    }

    private void insertMove(UUID gameId, String san, String fen) {
        QuarkusTransaction.requiringNew().run(() -> {
            Move move = new Move();
            move.game = games.findById(gameId);
            move.moveNumber = 1;
            move.color = "WHITE";
            move.san = san;
            move.fen = fen;
            moveRepository.persistAndFlush(move);
        });
    }

    /** Runs the tasks at the same moment; returns null for success or the thrown exception, per task. */
    private static List<Throwable> runConcurrently(Callable<?>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.call();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        StringBuilder messages = new StringBuilder();
        while (current != null) {
            messages.append(current.getMessage()).append(" | ");
            current = current.getCause();
        }
        return messages.toString();
    }
}
