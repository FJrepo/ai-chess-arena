package dev.aichessarena.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aichessarena.dto.ChatMessageDto;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.repository.GameRepository;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.context.SmallRyeThreadContext;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@WebSocket(path = "/ws/games")
@ApplicationScoped
public class GameWebSocket {

    private static final Logger LOG = Logger.getLogger(GameWebSocket.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    GameRepository gameRepository;

    static final int TRY_AGAIN_LATER = 1013;
    static final long SUBSCRIBE_WINDOW_MS = 10_000L;

    /** Soft cap on simultaneous live connections; the app is a local, single-user tool. */
    @ConfigProperty(name = "game.websocket.max-connections", defaultValue = "256")
    int maxConnections = 256;

    /**
     * Subscribe messages allowed per connection within {@link #SUBSCRIBE_WINDOW_MS}. A connection that exceeds
     * it is closed; the frontend reconnects after a few seconds and resubscribes to the game it shows.
     */
    @ConfigProperty(name = "game.websocket.max-subscriptions-per-window", defaultValue = "30")
    int maxSubscriptionsPerWindow = 30;

    private final AtomicInteger admittedConnections = new AtomicInteger();

    private final Map<UUID, Set<WebSocketConnection>> gameSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, UUID> connectionGameMap = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionWindow> subscriptionWindows = new ConcurrentHashMap<>();

    private static final class SubscriptionWindow {
        private long startedAtMs;
        private int count;

        synchronized boolean tryAcquire(long nowMs, int limit) {
            if (nowMs - startedAtMs >= SUBSCRIBE_WINDOW_MS) {
                startedAtMs = nowMs;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        // Open callbacks of different connections run concurrently: reserve a slot atomically.
        if (admittedConnections.incrementAndGet() > maxConnections) {
            admittedConnections.decrementAndGet();
            LOG.warnf("Rejecting WebSocket %s: %d live connections already open", connection.id(), maxConnections);
            closeQuietly(connection, "Too many live connections");
            return;
        }
        subscriptionWindows.put(connection.id(), new SubscriptionWindow());
        LOG.debugf("WebSocket opened: %s", connection.id());
    }

    private void closeQuietly(WebSocketConnection connection, String reason) {
        outsideTransaction(() -> connection.close(new CloseReason(TRY_AGAIN_LATER, reason)).subscribe().with(
                ignored -> { },
                err -> LOG.debugf("Failed to close WebSocket %s: %s", connection.id(), err.getMessage())
        ));
    }

    /**
     * Context for the callbacks of a send or close. They run later, on the connection's event loop, and Quarkus
     * propagates every context into them by default, the JTA transaction included: a send made inside a transaction
     * (a move is announced in the transaction that records it) would make the event loop join that transaction. If
     * such a callback runs while the transaction commits, Narayana finds two threads in it and disassociates the
     * committing thread before the one-phase commit; Agroal then refuses to commit the enlisted connection
     * ("Enlisted connection used without active transaction") and the transaction rolls back. The callbacks only
     * log, so they get no context: the transaction is cleared, everything else is left as the event loop has it.
     */
    private static final class SendContext {
        static final SmallRyeThreadContext OUTSIDE_TRANSACTION = SmallRyeThreadContext.builder()
                .propagated(ThreadContext.NONE)
                .cleared(ThreadContext.TRANSACTION)
                .unchanged(ThreadContext.ALL_REMAINING)
                .build();
    }

    /** Starts a send or close whose callbacks never run inside the caller's transaction (see {@link SendContext}). */
    private static void outsideTransaction(Runnable send) {
        SmallRyeThreadContext.withThreadContext(SendContext.OUTSIDE_TRANSACTION, send);
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        if (subscriptionWindows.remove(connection.id()) != null) {
            admittedConnections.decrementAndGet();
        }
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

        SubscriptionWindow window = subscriptionWindows.get(connection.id());
        if (window == null) {
            LOG.debugf("Ignoring WebSocket message from connection %s that was not admitted", connection.id());
            return;
        }
        if ("subscribe".equals(type) && !window.tryAcquire(System.currentTimeMillis(), maxSubscriptionsPerWindow)) {
            // Close instead of silently dropping: the client reconnects and resubscribes to its current game.
            LOG.debugf("Closing WebSocket %s: too many subscriptions", connection.id());
            closeQuietly(connection, "Too many subscriptions; reconnect later");
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
            subscribe(connection, gameId, node.path("ack").asBoolean(false));
        } else {
            unsubscribe(connection, gameId);
        }
    }

    private void subscribe(WebSocketConnection connection, UUID gameId, boolean acknowledge) {
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
        if (!connection.isOpen()) {
            // The connection closed while subscribing; onClose may already have run, so undo here.
            connectionGameMap.remove(connection.id(), gameId);
            removeSubscription(gameId, connection);
            return;
        }
        if (acknowledge) {
            String ack = objectMapper.createObjectNode().put("type", "subscribed").put("gameId", gameId.toString())
                    .toString();
            outsideTransaction(() -> connection.sendText(ack).subscribe().with(ignored -> { },
                    err -> closeQuietly(connection, "Subscription acknowledgement failed")));
        }
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

    int openConnectionCount() {
        return admittedConnections.get();
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
                               long responseTimeMs, int retryCount, boolean isOverride) {
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
        msg.put("isOverride", isOverride);
        broadcast(gameId, msg.toString());
    }

    public void broadcastChat(UUID gameId, ChatMessageDto chat, String senderName) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "chat");
        msg.put("gameId", gameId.toString());
        msg.put("id", chat.id().toString());
        msg.put("createdAt", chat.createdAt().toString());
        msg.put("moveNumber", chat.moveNumber());
        msg.put("senderColor", chat.senderColor());
        msg.put("senderModel", chat.senderModel());
        msg.put("senderName", senderName);
        msg.put("message", chat.message());
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
        broadcastGameStatus(gameId, status, result, resultReason, totalMoves, totalCostUsd,
                activeColor, turnStartedAt, turnDeadlineAt, null);
    }

    public void broadcastGameStatus(UUID gameId, String status, String result,
                                     String resultReason, int totalMoves, BigDecimal totalCostUsd,
                                     String activeColor, Instant turnStartedAt, Instant turnDeadlineAt,
                                     String detail) {
        broadcastGameStatus(gameId, status, result, resultReason, totalMoves, totalCostUsd,
                activeColor, turnStartedAt, turnDeadlineAt, detail, null);
    }

    /**
     * {@code tournamentChatMode} is the tournament's configured chat mode, sent with the turn updates of model turns
     * only (not the effective mode of the side to move, which a legacy template can lower to OPTIONAL).
     */
    public void broadcastGameStatus(UUID gameId, String status, String result,
                                     String resultReason, int totalMoves, BigDecimal totalCostUsd,
                                     String activeColor, Instant turnStartedAt, Instant turnDeadlineAt,
                                     String detail, String tournamentChatMode) {
        broadcastGameStatus(gameId, status, result, resultReason, totalMoves, totalCostUsd, activeColor,
                turnStartedAt, turnDeadlineAt, detail, tournamentChatMode, null);
    }

    /** Turn metadata includes an explicit null tone so clients can clear a previous selection. */
    public void broadcastGameStatus(UUID gameId, String status, String result,
                                     String resultReason, int totalMoves, BigDecimal totalCostUsd,
                                     String activeColor, Instant turnStartedAt, Instant turnDeadlineAt,
                                     String detail, String tournamentChatMode, String tournamentBanterLevel) {
        ObjectNode msg = statusMessage(gameId, status, result, resultReason, totalMoves, totalCostUsd, activeColor,
                turnStartedAt, turnDeadlineAt, detail, tournamentChatMode, null);
        if (tournamentChatMode != null) msg.put("tournamentBanterLevel", tournamentBanterLevel);
        broadcast(gameId, msg.toString());
    }

    /**
     * A status change (start, pause, game over) with the game's {@code statusRevision} after it. Status changes are
     * announced after their commit and can therefore arrive out of order; clients ignore an update whose revision
     * is lower than one they already have.
     */
    public void broadcastStatusChange(UUID gameId, String status, String result, String resultReason,
                                      int totalMoves, BigDecimal totalCostUsd, String detail,
                                      long statusRevision, Instant resumeRequestedAt, RunCapacity capacity) {
        ObjectNode message = statusMessage(gameId, status, result, resultReason, totalMoves, totalCostUsd, null, null,
                null, detail, null, statusRevision);
        message.put("resumeRequestedAt", resumeRequestedAt == null ? null : resumeRequestedAt.toString());
        if (capacity != null) {
            message.putObject("runCapacity")
                    .put("runningGames", capacity.runningGames())
                    .put("maxConcurrentGames", capacity.maxConcurrentGames());
        }
        broadcast(gameId, message.toString());
    }

    ObjectNode statusMessage(UUID gameId, String status, String result, String resultReason, int totalMoves,
                             BigDecimal totalCostUsd, String activeColor, Instant turnStartedAt,
                             Instant turnDeadlineAt, String detail, String tournamentChatMode,
                             Long statusRevision) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "gameStatus");
        msg.put("gameId", gameId.toString());
        msg.put("status", status);
        if (result != null) msg.put("result", result);
        if (resultReason != null) msg.put("resultReason", resultReason);
        if (activeColor != null) msg.put("activeColor", activeColor);
        if (turnStartedAt != null) msg.put("turnStartedAt", turnStartedAt.toString());
        if (turnDeadlineAt != null) msg.put("turnDeadlineAt", turnDeadlineAt.toString());
        if (detail != null) msg.put("detail", detail);
        if (tournamentChatMode != null) msg.put("tournamentChatMode", tournamentChatMode);
        msg.put("totalMoves", totalMoves);
        msg.put("totalCostUsd", totalCostUsd != null ? totalCostUsd.doubleValue() : 0);
        if (statusRevision != null) msg.put("statusRevision", statusRevision);
        return msg;
    }

    /** Invalidates attempts and game spend after an attempt commits; carries no status or status revision. */
    public void broadcastAttemptsUpdated(UUID gameId) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "attemptsUpdated");
        msg.put("gameId", gameId.toString());
        broadcast(gameId, msg.toString());
    }

    public void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
        broadcastRetry(gameId, color, attemptNumber, reason, null, null);
    }

    /** {@code outcome} is a {@code MoveAttempt.Outcome} name; {@code proposedMove} is the rejected move, if any. */
    public void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason,
                               String outcome, String proposedMove) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "retry");
        msg.put("gameId", gameId.toString());
        msg.put("color", color);
        msg.put("attemptNumber", attemptNumber);
        msg.put("reason", reason);
        if (outcome != null) msg.put("outcome", outcome);
        if (proposedMove != null) msg.put("proposedMove", proposedMove);
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

    /**
     * A move's new evaluation, plus the qualities it decides: the evaluated move's own and the next move's (whose
     * "before" it is), and the game's updated per-side quality summary.
     */
    public void broadcastEvaluation(UUID gameId, int moveNumber, String color, Integer evaluationCp,
                                    Integer evaluationMate, List<PlyQualityDto> qualities,
                                    GameQualitySummaryDto qualitySummary) {
        broadcast(gameId, evaluationMessage(gameId, moveNumber, color, evaluationCp, evaluationMate, qualities,
                qualitySummary).toString());
    }

    ObjectNode evaluationMessage(UUID gameId, int moveNumber, String color, Integer evaluationCp,
                                 Integer evaluationMate, List<PlyQualityDto> qualities,
                                 GameQualitySummaryDto qualitySummary) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "evaluationUpdate");
        msg.put("gameId", gameId.toString());
        msg.put("moveNumber", moveNumber);
        msg.put("color", color);
        if (evaluationCp != null) msg.put("evaluationCp", evaluationCp);
        if (evaluationMate != null) msg.put("evaluationMate", evaluationMate);
        msg.set("qualities", objectMapper.valueToTree(qualities != null ? qualities : List.of()));
        if (qualitySummary != null) msg.set("qualitySummary", objectMapper.valueToTree(qualitySummary));
        return msg;
    }

    private void broadcast(UUID gameId, String message) {
        Set<WebSocketConnection> subs = gameSubscriptions.get(gameId);
        if (subs == null) return;
        outsideTransaction(() -> {
            for (WebSocketConnection conn : subs) {
                conn.sendText(message).subscribe().with(
                        ignored -> {},
                        err -> LOG.warnf("Failed to send WebSocket message: %s", err.getMessage())
                );
            }
        });
    }
}
