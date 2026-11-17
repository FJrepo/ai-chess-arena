package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    private static final String INSIDE = "inside the sender's transaction";

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
    ObjectMapper json;

    @Inject
    TransactionManager transactionManager;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    private final ExecutorService eventLoop = Executors.newSingleThreadExecutor();
    private final List<String> callbackTransactions = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> moveAndChat = Collections.synchronizedList(new ArrayList<>());
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

    /**
     * Exercise the transport directly inside an open transaction. Even a live send initiated there must let
     * the move commit while the send finishes on the event loop.
     */
    @Test
    void aMoveCommitsWhileALiveUpdateIsStillBeingSent() {
        UUID gameId = game(GameStatus.IN_PROGRESS);
        gameWebSocket.onMessage(viewer, "{\"type\":\"subscribe\",\"gameId\":\"%s\"}".formatted(gameId));

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                gameWebSocket.broadcastRetry(gameId, "WHITE", 1, "Retry");
                gameMoveService.recordModelMove(gameId, 1, "WHITE", "e4", AFTER_E4, "test/white", "White", null,
                        new LlmResponse("{\"move\":\"e4\"}", 10, 5, 0.001, 1, "{}", false, null), 1, "v2", "hash",
                        START, ChatMode.OFF);
            });
        } finally {
            releaseCallback.countDown();
        }

        assertFalse(callbackTransactions.isEmpty(), "the live update was not sent");
        assertFalse(callbackTransactions.contains(INSIDE), "a send callback ran inside a transaction");
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> moveRepository.count("game.id", gameId)));
        assertEquals(AFTER_E4, QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(gameId).currentFen));
    }

    enum MoveSource { MODEL, HUMAN, OVERRIDE }

    @ParameterizedTest
    @EnumSource(MoveSource.class)
    void onlyCommittedMovesReachTheViewerWithChatFirst(MoveSource source) throws Exception {
        UUID id = game(source == MoveSource.OVERRIDE ? GameStatus.PAUSED : GameStatus.IN_PROGRESS);
        gameWebSocket.onMessage(viewer, "{\"type\":\"subscribe\",\"gameId\":\"%s\"}".formatted(id));
        Runnable play = switch (source) {
            case MODEL -> () -> gameMoveService.recordModelMove(id, 1, "WHITE", "e4", AFTER_E4, "test/white",
                    "White", "Hello", new LlmResponse("e4", 10, 5, null, 1, "{}", false, null),
                    0, "v2", "hash", START, ChatMode.OPTIONAL);
            case HUMAN -> () -> gameMoveService.recordHumanMove(id, 1, "WHITE", "e4", AFTER_E4, "White", "Hello", START);
            case OVERRIDE -> () -> gameMoveService.applyOverrideMove(id, "e4", START);
        };

        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            play.run();
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> moveRepository.count("game.id", id)));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> chatRepository.count("game.id", id)));
        assertTrue(moveAndChat.isEmpty(), () -> "rolled-back updates reached the viewer: " + moveAndChat);

        QuarkusTransaction.requiringNew().run(() -> {
            play.run();
            assertTrue(moveAndChat.isEmpty(), "the outer transaction is still open");
        });
        assertEquals(source == MoveSource.OVERRIDE ? List.of("move") : List.of("chat", "move"),
                moveAndChat.stream().map(event -> event.path("type").asText()).toList());
        if (source != MoveSource.OVERRIDE) {
            var chat = moveAndChat.getFirst();
            UUID storedId = QuarkusTransaction.requiringNew().call(() -> chatRepository.findByGameId(id).getFirst().id);
            assertEquals("Hello", chat.path("message").asText());
            assertEquals(storedId.toString(), chat.path("id").asText());
        }
        var move = moveAndChat.getLast();
        assertEquals("1. e4", move.path("pgn").asText());
        assertEquals(1, move.path("moveNumber").asInt());
        assertEquals("WHITE", move.path("color").asText());
        assertEquals(7, move.path("statusRevision").asLong(-1));
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> moveRepository.count("game.id", id)));
        assertEquals(source == MoveSource.OVERRIDE ? 0L : 1L,
                QuarkusTransaction.requiringNew().call(() -> chatRepository.count("game.id", id)));
        releaseCallback.countDown();
        // Wait for the single-threaded viewer to finish the committed sends before checking their context.
        eventLoop.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertFalse(callbackTransactions.contains(INSIDE), "a committed send carried the transaction");
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
                        var message = json.readTree(args[0].toString());
                        switch (message.path("type").asText()) {
                            case "chat", "move" -> moveAndChat.add(message);
                        }
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
            boolean inside = transaction != null;
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

    private UUID game(GameStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.whitePlayerName = "White";
            game.whiteModelId = "test/white";
            game.blackPlayerName = "Black";
            game.blackModelId = "test/black";
            game.status = status;
            game.statusRevision = 7;
            gameRepository.persist(game);
            return game.id;
        });
    }
}
