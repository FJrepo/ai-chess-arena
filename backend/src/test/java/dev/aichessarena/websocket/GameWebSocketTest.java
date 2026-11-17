package dev.aichessarena.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.MoveQualityDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.dto.SideQualityDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
    private final java.util.Set<String> closedConnections = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    void statusChangesCarryTheStatusRevisionAndTurnUpdatesDoNot() throws Exception {
        UUID gameId = UUID.randomUUID();

        JsonNode change = new ObjectMapper().readTree(endpoint.statusMessage(gameId, "PAUSED", null, null, 4,
                java.math.BigDecimal.ONE, null, null, null, "Rate limited", null, 7L).toString());
        JsonNode turn = new ObjectMapper().readTree(endpoint.statusMessage(gameId, "IN_PROGRESS", null, null, 4,
                java.math.BigDecimal.ONE, "WHITE", null, null, null, "OPTIONAL", null).toString());

        assertEquals("gameStatus", change.get("type").asText());
        assertEquals(7L, change.get("statusRevision").asLong());
        assertEquals("Rate limited", change.get("detail").asText());
        assertFalse(turn.has("statusRevision"));
    }

    @Test
    void statusChangesCarryQueuedResumeAndExplicitlyClearIt() throws Exception {
        UUID game = gameRepository.addGame();
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        endpoint.onMessage(recordingConnection("spectator", sent), subscribeMessage(game));
        Instant requestedAt = Instant.parse("2026-11-17T20:00:00Z");

        endpoint.broadcastStatusChange(game, "PAUSED", null, null, 4, BigDecimal.ZERO,
                "Answer cut off", 7, requestedAt, new RunCapacity(3, 3));
        endpoint.broadcastStatusChange(game, "IN_PROGRESS", null, null, 4, BigDecimal.ZERO,
                null, 8, null, null);

        var queued = endpoint.objectMapper.readTree(sent.get(sent.size() - 2));
        var resumed = endpoint.objectMapper.readTree(sent.getLast());
        assertEquals(requestedAt.toString(), queued.get("resumeRequestedAt").asText());
        assertEquals(3, queued.at("/runCapacity/runningGames").asInt());
        assertEquals(3, queued.at("/runCapacity/maxConcurrentGames").asInt());
        assertTrue(resumed.get("resumeRequestedAt").isNull());
        assertFalse(resumed.has("runCapacity"));
    }

    @Test
    void onlyTheRegisteredSubscriberReceivesItsAcknowledgement() throws Exception {
        UUID game = gameRepository.addGame();
        List<String> first = new java.util.ArrayList<>();
        List<String> second = new java.util.ArrayList<>();
        String request = "{\"type\":\"subscribe\",\"ack\":true,\"gameId\":\"%s\"}";
        endpoint.onMessage(recordingConnection("first", first), request.formatted(game));
        WebSocketConnection joining = recordingConnection("second", second);
        endpoint.onMessage(joining, request.formatted(UUID.randomUUID()));
        assertTrue(second.isEmpty());
        endpoint.onMessage(joining, request.formatted(game));

        assertEquals(1, first.size());
        assertEquals(first, second);
        JsonNode ack = endpoint.objectMapper.readTree(second.getFirst());
        assertEquals("subscribed", ack.path("type").asText());
        assertEquals(game.toString(), ack.path("gameId").asText());
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

    @Test
    void tooManySubscriptionsCloseTheConnectionSoTheClientResubscribes() {
        endpoint.maxSubscriptionsPerWindow = 3;
        WebSocketConnection connection = connection("connection-1");
        UUID[] games = {gameRepository.addGame(), gameRepository.addGame(), gameRepository.addGame()};

        for (UUID game : games) {
            endpoint.onMessage(connection, subscribeMessage(game));
            endpoint.onMessage(connection, unsubscribeMessage(game)); // unsubscribes are not limited
        }
        assertFalse(closedConnections.contains("connection-1"));

        endpoint.onMessage(connection, subscribeMessage(gameRepository.addGame()));

        assertTrue(closedConnections.contains("connection-1"));
        assertNull(endpoint.subscribedGameId(connection.id()));
    }

    @Test
    void concurrentOpensNeverExceedTheConnectionCap() throws Exception {
        endpoint.maxConnections = 5;
        int attempts = 40;
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String id = "connection-" + i;
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                connection(id);
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }

        assertEquals(5, endpoint.openConnectionCount());
        assertEquals(attempts - 5, closedConnections.size());
    }

    @Test
    void closingAnAdmittedConnectionFreesItsSlot() {
        endpoint.maxConnections = 1;
        WebSocketConnection first = connection("connection-1");
        endpoint.onClose(first);

        connection("connection-2");

        assertEquals(1, endpoint.openConnectionCount());
        assertFalse(closedConnections.contains("connection-2"));
    }

    @Test
    void connectionsBeyondTheCapAreClosedAndIgnored() {
        endpoint.maxConnections = 2;
        connection("connection-1");
        connection("connection-2");
        WebSocketConnection rejected = connection("connection-3");

        endpoint.onMessage(rejected, subscribeMessage(gameRepository.addGame()));

        assertTrue(closedConnections.contains("connection-3"));
        assertEquals(2, endpoint.openConnectionCount());
        assertEquals(0, endpoint.trackedConnectionCount());

        endpoint.onClose(rejected);
        assertEquals(2, endpoint.openConnectionCount());
    }

    @Test
    void subscriptionFromAConnectionThatClosedMeanwhileLeavesNoState() {
        UUID gameId = gameRepository.addGame();
        WebSocketConnection connection = connection("connection-1");
        closedConnections.add("connection-1");

        endpoint.onMessage(connection, subscribeMessage(gameId));

        assertEquals(0, endpoint.trackedGameCount());
        assertEquals(0, endpoint.trackedConnectionCount());
    }

    @Test
    void moveBroadcastsCarryTheOverrideFlag() throws Exception {
        UUID gameId = gameRepository.addGame();
        java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        WebSocketConnection spectator = recordingConnection("spectator", sent);
        endpoint.onMessage(spectator, subscribeMessage(gameId));

        endpoint.broadcastMove(gameId, 1, "WHITE", "e4", "fen-1", "1. e4", "model-a", 120, 0, false);
        endpoint.broadcastMove(gameId, 1, "BLACK", "e5", "fen-2", "1. e4 e5", "admin-override", 0, 0, true);

        ObjectMapper mapper = new ObjectMapper();
        java.util.List<com.fasterxml.jackson.databind.JsonNode> moves = new java.util.ArrayList<>();
        for (String message : sent) {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(message);
            if ("move".equals(node.path("type").asText())) {
                moves.add(node);
            }
        }
        assertEquals(2, moves.size());
        assertTrue(moves.get(0).has("isOverride"));
        assertFalse(moves.get(0).get("isOverride").asBoolean());
        assertTrue(moves.get(1).get("isOverride").asBoolean());
        assertEquals("admin-override", moves.get(1).get("modelId").asText());
    }

    @Test
    void turnStatusUpdatesCarryTheChatModeAndOtherStatusUpdatesDoNot() throws Exception {
        UUID gameId = gameRepository.addGame();
        java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        WebSocketConnection spectator = recordingConnection("spectator", sent);
        endpoint.onMessage(spectator, subscribeMessage(gameId));

        java.time.Instant now = java.time.Instant.now();
        endpoint.broadcastGameStatus(gameId, "IN_PROGRESS", null, null, 3, java.math.BigDecimal.ZERO,
                "WHITE", now, null, null, "MANDATORY", "SPICY");
        endpoint.broadcastGameStatus(gameId, "IN_PROGRESS", null, null, 3, java.math.BigDecimal.ZERO,
                "BLACK", now, null, null, "MANDATORY", null);
        endpoint.broadcastGameStatus(gameId, "PAUSED", null, null, 3, java.math.BigDecimal.ZERO);

        ObjectMapper mapper = new ObjectMapper();
        java.util.List<com.fasterxml.jackson.databind.JsonNode> statuses = new java.util.ArrayList<>();
        for (String message : sent) {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(message);
            if ("gameStatus".equals(node.path("type").asText())) {
                statuses.add(node);
            }
        }
        assertEquals(3, statuses.size());
        assertEquals("MANDATORY", statuses.get(0).get("tournamentChatMode").asText());
        assertEquals("SPICY", statuses.get(0).get("tournamentBanterLevel").asText());
        assertTrue(statuses.get(1).get("tournamentBanterLevel").isNull());
        assertFalse(statuses.get(2).has("tournamentChatMode"));
        assertFalse(statuses.get(2).has("tournamentBanterLevel"));
    }

    /** An open connection that records every text frame sent to it. */
    private WebSocketConnection recordingConnection(String id, java.util.List<String> sent) {
        WebSocketConnection connection = (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class<?>[]{WebSocketConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "WebSocketConnection[" + id + "]";
                    case "isOpen" -> true;
                    case "sendText" -> {
                        sent.add(String.valueOf(args[0]));
                        yield io.smallrye.mutiny.Uni.createFrom().voidItem();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        endpoint.onOpen(connection);
        return connection;
    }

    private String subscribeMessage(UUID gameId) {
        return "{\"type\":\"subscribe\",\"gameId\":\"%s\"}".formatted(gameId);
    }

    private String unsubscribeMessage(UUID gameId) {
        return "{\"type\":\"unsubscribe\",\"gameId\":\"%s\"}".formatted(gameId);
    }

    @Test
    void evaluationMessageCarriesMoveQualitiesAndSummary() {
        UUID gameId = UUID.randomUUID();
        MoveQualityDto blunder = new MoveQualityDto("BLUNDER", 618, 0.7372, 0.2236, null);
        GameQualitySummaryDto summary = new GameQualitySummaryDto(
                new SideQualityDto(35, 41, 2, 1, 0),
                new SideQualityDto(35, 87, 3, 2, 1)
        );

        JsonNode msg = endpoint.evaluationMessage(gameId, 35, "BLACK", 338, null,
                List.of(new PlyQualityDto(35, "BLACK", blunder), new PlyQualityDto(36, "WHITE", null)), summary);

        assertEquals("evaluationUpdate", msg.path("type").asText());
        assertEquals(338, msg.path("evaluationCp").asInt());
        assertFalse(msg.has("evaluationMate"));
        JsonNode first = msg.path("qualities").get(0);
        assertEquals(35, first.path("moveNumber").asInt());
        assertEquals("BLACK", first.path("color").asText());
        assertEquals("BLUNDER", first.path("quality").path("judgement").asText());
        assertEquals(618, first.path("quality").path("centipawnLoss").asInt());
        assertEquals(0.7372, first.path("quality").path("winChanceBefore").asDouble());
        assertTrue(first.path("quality").path("excluded").isNull());
        assertTrue(msg.path("qualities").get(1).path("quality").isNull());
        assertEquals(87, msg.path("qualitySummary").path("black").path("averageCentipawnLoss").asInt());
        assertEquals(1, msg.path("qualitySummary").path("black").path("blunders").asInt());
        assertEquals(35, msg.path("qualitySummary").path("white").path("analyzedMoves").asInt());
    }

    @Test
    void evaluationMessageWithoutQualitiesHasAnEmptyListAndNoSummary() {
        JsonNode msg = endpoint.evaluationMessage(UUID.randomUUID(), 1, "WHITE", null, null, null, null);

        assertTrue(msg.path("qualities").isArray());
        assertEquals(0, msg.path("qualities").size());
        assertFalse(msg.has("qualitySummary"));
    }

    private WebSocketConnection connection(String id) {
        return connection(id, null, null);
    }

    /** Creates a fake connection and opens it on the endpoint, like the runtime does. */
    private WebSocketConnection connection(
            String id, CountDownLatch hashCodeStarted, CountDownLatch allowHashCode) {
        WebSocketConnection connection = (WebSocketConnection) Proxy.newProxyInstance(
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
                    case "isOpen" -> !closedConnections.contains(id);
                    case "close" -> {
                        closedConnections.add(id);
                        yield io.smallrye.mutiny.Uni.createFrom().voidItem();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        endpoint.onOpen(connection);
        return connection;
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
