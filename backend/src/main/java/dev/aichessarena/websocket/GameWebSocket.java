package dev.aichessarena.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aichessarena.repository.GameRepository;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/games")
@ApplicationScoped
public class GameWebSocket {

    private static final Logger LOG = Logger.getLogger(GameWebSocket.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    GameRepository gameRepository;

    private final Map<UUID, Set<WebSocketConnection>> gameSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, UUID> connectionGameMap = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        LOG.debugf("WebSocket opened: %s", connection.id());
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        UUID gameId = connectionGameMap.remove(connection.id());
        if (gameId != null) {
            removeSubscription(gameId, connection);
        }
    }

    @OnTextMessage
    @ActivateRequestContext
    public void onMessage(WebSocketConnection connection, String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            LOG.debugf("Ignoring malformed WebSocket message from %s", connection.id());
            return;
        }

        String type = node.path("type").asText("");
        if (!"subscribe".equals(type) && !"unsubscribe".equals(type)) {
            LOG.debugf("Ignoring unsupported WebSocket message type from %s", connection.id());
            return;
        }

        UUID gameId;
        try {
            gameId = UUID.fromString(node.path("gameId").asText(""));
        } catch (IllegalArgumentException e) {
            LOG.debugf("Ignoring WebSocket message with invalid game ID from %s", connection.id());
            return;
        }

        if ("subscribe".equals(type)) {
            subscribe(connection, gameId);
        } else {
            unsubscribe(connection, gameId);
        }
    }

    private void subscribe(WebSocketConnection connection, UUID gameId) {
        if (gameRepository.findById(gameId) == null) {
            LOG.tracef("Ignoring subscription from %s for missing game %s", connection.id(), gameId);
            return;
        }

        UUID previousGameId = connectionGameMap.put(connection.id(), gameId);
        if (previousGameId != null && !previousGameId.equals(gameId)) {
            removeSubscription(previousGameId, connection);
        }

        gameSubscriptions.compute(gameId, (ignored, subscribers) -> {
            Set<WebSocketConnection> currentSubscribers = subscribers;
            if (currentSubscribers == null) {
                currentSubscribers = ConcurrentHashMap.newKeySet();
            }
            currentSubscribers.add(connection);
            return currentSubscribers;
        });
        LOG.debugf("Connection %s subscribed to game %s", connection.id(), gameId);
    }

    private void unsubscribe(WebSocketConnection connection, UUID gameId) {
        if (connectionGameMap.remove(connection.id(), gameId)) {
            removeSubscription(gameId, connection);
            LOG.debugf("Connection %s unsubscribed from game %s", connection.id(), gameId);
        }
    }

    private void removeSubscription(UUID gameId, WebSocketConnection connection) {
        gameSubscriptions.computeIfPresent(gameId, (ignored, subscribers) -> {
            subscribers.remove(connection);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    int trackedGameCount() {
        return gameSubscriptions.size();
    }

    int trackedConnectionCount() {
        return connectionGameMap.size();
    }

    int subscriberCount(UUID gameId) {
        Set<WebSocketConnection> subscribers = gameSubscriptions.get(gameId);
        return subscribers != null ? subscribers.size() : 0;
    }

    UUID subscribedGameId(String connectionId) {
        return connectionGameMap.get(connectionId);
    }

    public void broadcastMove(UUID gameId, int moveNumber, String color, String san,
                               String fen, String pgn, String modelId,
                               long responseTimeMs, int retryCount) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "move");
        msg.put("gameId", gameId.toString());
        msg.put("moveNumber", moveNumber);
        msg.put("color", color);
        msg.put("san", san);
        msg.put("fen", fen);
        msg.put("pgn", pgn);
        if (modelId != null) {
            msg.put("modelId", modelId);
        } else {
            msg.putNull("modelId");
        }
        msg.put("responseTimeMs", responseTimeMs);
        msg.put("retryCount", retryCount);
        broadcast(gameId, msg.toString());
    }

    public void broadcastChat(UUID gameId, int moveNumber, String senderColor,
                               String senderModel, String senderName, String message) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "chat");
        msg.put("gameId", gameId.toString());
        msg.put("moveNumber", moveNumber);
        msg.put("senderColor", senderColor);
        if (senderModel != null) {
            msg.put("senderModel", senderModel);
        } else {
            msg.putNull("senderModel");
        }
        msg.put("senderName", senderName);
        msg.put("message", message);
        broadcast(gameId, msg.toString());
    }

    public void broadcastGameStatus(UUID gameId, String status, String result,
                                     String resultReason, int totalMoves, BigDecimal totalCostUsd) {
        broadcastGameStatus(gameId, status, result, resultReason, totalMoves, totalCostUsd,
                null, null, null);
    }

    public void broadcastGameStatus(UUID gameId, String status, String result,
                                     String resultReason, int totalMoves, BigDecimal totalCostUsd,
                                     String activeColor, Instant turnStartedAt, Instant turnDeadlineAt) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "gameStatus");
        msg.put("gameId", gameId.toString());
        msg.put("status", status);
        if (result != null) msg.put("result", result);
        if (resultReason != null) msg.put("resultReason", resultReason);
        if (activeColor != null) msg.put("activeColor", activeColor);
        if (turnStartedAt != null) msg.put("turnStartedAt", turnStartedAt.toString());
        if (turnDeadlineAt != null) msg.put("turnDeadlineAt", turnDeadlineAt.toString());
        msg.put("totalMoves", totalMoves);
        msg.put("totalCostUsd", totalCostUsd != null ? totalCostUsd.doubleValue() : 0);
        broadcast(gameId, msg.toString());
    }

    public void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "retry");
        msg.put("gameId", gameId.toString());
        msg.put("color", color);
        msg.put("attemptNumber", attemptNumber);
        msg.put("reason", reason);
        broadcast(gameId, msg.toString());
    }

    public void broadcastForfeit(UUID gameId, String forfeitColor, String reason) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "forfeit");
        msg.put("gameId", gameId.toString());
        msg.put("forfeitColor", forfeitColor);
        msg.put("reason", reason);
        broadcast(gameId, msg.toString());
    }

    public void broadcastEvaluation(UUID gameId, int moveNumber, String color, Integer evaluationCp, Integer evaluationMate) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "evaluationUpdate");
        msg.put("gameId", gameId.toString());
        msg.put("moveNumber", moveNumber);
        msg.put("color", color);
        if (evaluationCp != null) msg.put("evaluationCp", evaluationCp);
        if (evaluationMate != null) msg.put("evaluationMate", evaluationMate);
        broadcast(gameId, msg.toString());
    }

    private void broadcast(UUID gameId, String message) {
        Set<WebSocketConnection> subs = gameSubscriptions.get(gameId);
        if (subs == null) return;
        for (WebSocketConnection conn : subs) {
            conn.sendText(message).subscribe().with(
                    ignored -> {},
                    err -> LOG.warnf("Failed to send WebSocket message: %s", err.getMessage())
            );
        }
    }
}
