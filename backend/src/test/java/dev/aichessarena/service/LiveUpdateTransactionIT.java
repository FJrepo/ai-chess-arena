package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import dev.aichessarena.websocket.GameWebSocket;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live updates are sent without blocking: the send finishes later on another thread (the Vert.x event loop in
 * production), where its callbacks run. Context propagation must not take the sender's transaction along. A thread
 * that runs inside the game loop's transaction while that transaction commits makes Narayana disassociate the
 * committing thread before its one-phase commit; Agroal then refuses to commit the enlisted connection and the move
 * is rolled back ("Enlisted connection used without active transaction", the game pauses).
 */
@QuarkusTest
class LiveUpdateTransactionIT {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
    private static final String INSIDE = "inside an active transaction";

    @Inject
    GameMoveService gameMoveService;

    @Inject
    GameWebSocket gameWebSocket;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    ChatMessageRepository chatRepository;

    @Inject
    ObjectMapper mapper;

    @Inject
    TransactionManager transactionManager;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    private final ExecutorService eventLoop = Executors.newSingleThreadExecutor();
    private final List<JsonNode> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> callbackTransactions = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch callbackStarted = new CountDownLatch(1);
    private final CountDownLatch releaseCallback = new CountDownLatch(1);
    private WebSocketConnection viewer;

    @BeforeEach
    void connectViewer() {
        viewer = slowViewer("viewer-" + UUID.randomUUID());
        gameWebSocket.onOpen(viewer);
    }

    @AfterEach
    void disconnectViewer() {
        releaseCallback.countDown();
        gameWebSocket.onClose(viewer);
        eventLoop.shutdownNow();
    }

    @Test
    void broadcastsCommittedMovesAndStoredChatIdsOutsideTheTransaction() throws Exception {
        UUID gameId = runningGame();
        gameWebSocket.onMessage(viewer, "{\"type\":\"subscribe\",\"gameId\":\"%s\"}".formatted(gameId));

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                gameMoveService.recordModelMove(gameId, 1, "WHITE", "e4", AFTER_E4, "test/white", "White", "Hello",
                        new LlmResponse("{\"move\":\"e4\"}", 10, 5, 0.001, 1, "{}", false, null), 0, "v2", "hash",
                        START, ChatMode.OPTIONAL);
                assertTrue(frames.isEmpty(), "a GET cannot see the move or chat until this transaction commits");
            });
        } finally {
            releaseCallback.countDown();
        }

        UUID storedId = QuarkusTransaction.requiringNew().call(() -> chatRepository.findByGameId(gameId).getFirst().id);
        JsonNode chat = frames.stream().filter(f -> "chat".equals(f.path("type").asText())).findFirst().orElseThrow();
        assertEquals(storedId.toString(), chat.path("id").asText());
        assertEquals("Hello", chat.path("message").asText());
        assertTrue(frames.stream().anyMatch(f -> "move".equals(f.path("type").asText())));
        assertTrue(callbackStarted.await(10, TimeUnit.SECONDS));
        assertFalse(callbackTransactions.contains(INSIDE), "a send callback ran inside a transaction");
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> moveRepository.count("game.id", gameId)));
        assertEquals(AFTER_E4, QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(gameId).currentFen));
    }

    /**
     * A viewer whose sends finish on another thread. A send made in a transaction finishes while that transaction
     * commits: right before the commit, and the commit waits until the callback runs. A callback inside a transaction
     * stays there until the test releases it, as a busy event loop would.
     */
    private WebSocketConnection slowViewer(String id) {
        return (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class<?>[]{WebSocketConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "WebSocketConnection[" + id + "]";
                    case "isOpen" -> true;
                    case "sendText" -> {
                        frames.add(mapper.readTree(args[0].toString()));
                        yield slowSend();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Uni<Void> slowSend() throws SystemException {
        CompletableFuture<Void> written = new CompletableFuture<>();
        // Built here, like the real send's pipeline: its callbacks get the sender's propagated context.
        Uni<Void> send = Uni.createFrom().completionStage(written).onItem().invoke(this::onWritten);
        if (transactionRegistry.getTransactionStatus() != Status.STATUS_ACTIVE) {
            eventLoop.execute(() -> written.complete(null));
            return send;
        }
        transactionRegistry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                eventLoop.execute(() -> written.complete(null));
                try {
                    assertTrue(callbackStarted.await(10, TimeUnit.SECONDS), "the send callback did not run");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void afterCompletion(int status) {
                // Nothing to do.
            }
        });
        return send;
    }

    private void onWritten() {
        try {
            Transaction transaction = transactionManager.getTransaction();
            boolean inside = transaction != null && transaction.getStatus() == Status.STATUS_ACTIVE;
            callbackTransactions.add(inside ? INSIDE : "no transaction");
            callbackStarted.countDown();
            if (inside) {
                releaseCallback.await(10, TimeUnit.SECONDS);
            }
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
}
