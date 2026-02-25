package dev.aichessarena.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
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
            Set<WebSocketConnection> subs = gameSubscriptions.get(gameId);
            if (subs != null) {
                subs.remove(connection);
            }
        }
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        try {
            var node = objectMapper.readTree(message);
            String type = node.get("type").asText();

            if ("subscribe".equals(type)) {
                UUID gameId = UUID.fromString(node.get("gameId").asText());
                gameSubscriptions.computeIfAbsent(gameId, ignored -> ConcurrentHashMap.newKeySet())
                        .add(connection);
                connectionGameMap.put(connection.id(), gameId);
                LOG.debugf("Connection %s subscribed to game %s", connection.id(), gameId);
            } else if ("unsubscribe".equals(type)) {
                UUID gameId = UUID.fromString(node.get("gameId").asText());
                Set<WebSocketConnection> subs = gameSubscriptions.get(gameId);
                if (subs != null) {
                    subs.remove(connection);
                }
                connectionGameMap.remove(connection.id());
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse WebSocket message", e);
        }
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
        msg.put("modelId", modelId);
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
        msg.put("senderModel", senderModel);
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
