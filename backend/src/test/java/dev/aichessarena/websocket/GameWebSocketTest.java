package dev.aichessarena.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWebSocketTest {

    private GameWebSocket endpoint;
    private FakeGameRepository gameRepository;

    @BeforeEach
    void setUp() {
        gameRepository = new FakeGameRepository();
        endpoint = new GameWebSocket();
        endpoint.objectMapper = new ObjectMapper();
        endpoint.gameRepository = gameRepository;
    }

    @Test
    void messageCallbackActivatesRequestContextForRepositoryLookup() throws Exception {
        assertTrue(GameWebSocket.class
                .getMethod("onMessage", WebSocketConnection.class, String.class)
                .isAnnotationPresent(ActivateRequestContext.class));
    }

    @Test
    void missingGamesDoNotCreateSubscriptionState() {
        WebSocketConnection connection = connection("connection-1");

        for (int i = 0; i < 100; i++) {
            endpoint.onMessage(connection, subscribeMessage(UUID.randomUUID()));
        }

        assertEquals(0, endpoint.trackedGameCount());
        assertEquals(0, endpoint.trackedConnectionCount());
        assertNull(endpoint.subscribedGameId(connection.id()));
    }

    @Test
    void resubscribeMovesConnectionAndRemovesEmptyPreviousGame() {
        UUID firstGameId = gameRepository.addGame();
        UUID secondGameId = gameRepository.addGame();
        WebSocketConnection connection = connection("connection-1");

        endpoint.onMessage(connection, subscribeMessage(firstGameId));
        endpoint.onMessage(connection, subscribeMessage(secondGameId));
        endpoint.onMessage(connection, subscribeMessage(secondGameId));

        assertEquals(0, endpoint.subscriberCount(firstGameId));
        assertEquals(1, endpoint.subscriberCount(secondGameId));
        assertEquals(1, endpoint.trackedGameCount());
        assertEquals(1, endpoint.trackedConnectionCount());
        assertEquals(secondGameId, endpoint.subscribedGameId(connection.id()));
    }

    @Test
    void unsubscribeOnlyRemovesTheCurrentGame() {
        UUID currentGameId = gameRepository.addGame();
        UUID otherGameId = gameRepository.addGame();
        WebSocketConnection connection = connection("connection-1");

        endpoint.onMessage(connection, subscribeMessage(currentGameId));
        endpoint.onMessage(connection, unsubscribeMessage(otherGameId));

        assertEquals(1, endpoint.subscriberCount(currentGameId));
        assertEquals(currentGameId, endpoint.subscribedGameId(connection.id()));

        endpoint.onMessage(connection, unsubscribeMessage(currentGameId));

        assertEquals(0, endpoint.trackedGameCount());
        assertEquals(0, endpoint.trackedConnectionCount());
    }

    @Test
    void closeRemovesOnlyThatConnectionAndEventuallyRemovesTheGame() {
        UUID gameId = gameRepository.addGame();
        WebSocketConnection firstConnection = connection("connection-1");
        WebSocketConnection secondConnection = connection("connection-2");

        endpoint.onMessage(firstConnection, subscribeMessage(gameId));
        endpoint.onMessage(secondConnection, subscribeMessage(gameId));

        endpoint.onClose(firstConnection);

        assertEquals(1, endpoint.subscriberCount(gameId));
        assertEquals(1, endpoint.trackedGameCount());
        assertEquals(1, endpoint.trackedConnectionCount());

        endpoint.onClose(secondConnection);

        assertEquals(0, endpoint.trackedGameCount());
        assertEquals(0, endpoint.trackedConnectionCount());
    }

    @Test
    void concurrentLastDisconnectDoesNotDetachNewSubscription() throws Exception {
        UUID gameId = gameRepository.addGame();
        WebSocketConnection oldConnection = connection("connection-old");
        CountDownLatch addStarted = new CountDownLatch(1);
        CountDownLatch allowAdd = new CountDownLatch(1);
        WebSocketConnection newConnection = connection("connection-new", addStarted, allowAdd);
        endpoint.onMessage(oldConnection, subscribeMessage(gameId));

        Thread subscriber = new Thread(
                () -> endpoint.onMessage(newConnection, subscribeMessage(gameId)));
        subscriber.start();
        assertTrue(addStarted.await(2, TimeUnit.SECONDS));

        Thread closer = new Thread(() -> endpoint.onClose(oldConnection));
        closer.start();
        waitUntilBlockedOrFinished(closer);
        allowAdd.countDown();
        subscriber.join(2_000);
        closer.join(2_000);

        assertFalse(subscriber.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(1, endpoint.trackedGameCount());
        assertEquals(1, endpoint.trackedConnectionCount());
        assertEquals(1, endpoint.subscriberCount(gameId));
        assertEquals(gameId, endpoint.subscribedGameId(newConnection.id()));
    }

    @Test
    void malformedAndUnsupportedMessagesDoNotCreateState() {
        WebSocketConnection connection = connection("connection-1");

        endpoint.onMessage(connection, "not-json");
        endpoint.onMessage(connection, "{\"type\":\"subscribe\",\"gameId\":\"not-a-uuid\"}");
        endpoint.onMessage(connection, "{\"type\":\"unknown\"}");

        assertEquals(0, endpoint.trackedGameCount());
        assertEquals(0, endpoint.trackedConnectionCount());
    }

    @Test
    void repositoryFailuresAreNotMisclassifiedAsInvalidClientMessages() {
        UUID gameId = UUID.randomUUID();
        WebSocketConnection connection = connection("connection-1");
        endpoint.gameRepository = new GameRepository() {
            @Override
            public Game findById(UUID id) {
                throw new IllegalStateException("database unavailable");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> endpoint.onMessage(connection, subscribeMessage(gameId)));
        assertEquals(0, endpoint.trackedGameCount());
        assertEquals(0, endpoint.trackedConnectionCount());
    }

    private String subscribeMessage(UUID gameId) {
        return "{\"type\":\"subscribe\",\"gameId\":\"%s\"}".formatted(gameId);
    }

    private String unsubscribeMessage(UUID gameId) {
        return "{\"type\":\"unsubscribe\",\"gameId\":\"%s\"}".formatted(gameId);
    }

    private WebSocketConnection connection(String id) {
        return connection(id, null, null);
    }

    private WebSocketConnection connection(
            String id, CountDownLatch hashCodeStarted, CountDownLatch allowHashCode) {
        return (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class<?>[]{WebSocketConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "hashCode" -> {
                        if (hashCodeStarted != null) {
                            hashCodeStarted.countDown();
                            assertTrue(allowHashCode.await(2, TimeUnit.SECONDS));
                        }
                        yield System.identityHashCode(proxy);
                    }
                    case "equals" -> proxy == args[0];
                    case "toString" -> "WebSocketConnection[" + id + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private void waitUntilBlockedOrFinished(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive()
                && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static final class FakeGameRepository extends GameRepository {
        private final Map<UUID, Game> games = new HashMap<>();

        UUID addGame() {
            UUID gameId = UUID.randomUUID();
            Game game = new Game();
            game.id = gameId;
            games.put(gameId, game);
            return gameId;
        }

        @Override
        public Game findById(UUID id) {
            return games.get(id);
        }
    }
}
