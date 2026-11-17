package dev.aichessarena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Collects immutable changes made while the game row is locked, with one revision per game and transaction. */
@ApplicationScoped
public class LiveGameUpdates {

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    GameWebSocket socket;

    @Inject
    ObjectMapper mapper;

    private static final Object RESOURCE_KEY = new Object();

    private static final class Changes {
        final List<ObjectNode> messages = new ArrayList<>();
        final long revision;
        ObjectNode state;

        Changes(long revision) {
            this.revision = revision;
        }
    }

    public ObjectNode change(String type, UUID id) {
        return mapper.createObjectNode().put("type", type).put("gameId", id.toString());
    }

    public ObjectNode entityChange(String type, UUID gameId, String field, Object value) {
        ObjectNode message = change(type, gameId);
        message.set(field, mapper.valueToTree(value));
        return message;
    }

    public ObjectNode statusChange(UUID gameId, String status, String result, String resultReason, int totalMoves,
                                   BigDecimal totalCostUsd, String detail, long statusRevision,
                                   Instant resumeRequestedAt, RunCapacity capacity) {
        return GameWebSocket.statusChangeMessage(mapper, gameId, status, result, resultReason, totalMoves,
                totalCostUsd, detail, statusRevision, resumeRequestedAt, capacity);
    }

    public ObjectNode evaluationChange(UUID gameId, UUID moveId, int moveNumber, String color,
                                       Integer evaluationCp, Integer evaluationMate,
                                       List<PlyQualityDto> qualities, GameQualitySummaryDto qualitySummary) {
        return GameWebSocket.evaluationMessage(mapper, gameId, moveNumber, color, evaluationCp,
                evaluationMate, qualities, qualitySummary).put("moveId", moveId.toString());
    }

    public void changed(Game game, ObjectNode... messages) {
        @SuppressWarnings("unchecked")
        Map<UUID, Changes> pending = (Map<UUID, Changes>) registry.getResource(RESOURCE_KEY);
        if (pending == null) {
            pending = new HashMap<>();
            registry.putResource(RESOURCE_KEY, pending);
        }
        UUID gameId = game.id;
        Changes changes = pending.computeIfAbsent(gameId, ignored -> {
            Changes created = new Changes(++game.liveRevision);
            AfterCommit.run(registry, () -> {
                created.messages.add(created.state);
                socket.broadcastRevision(gameId, created.revision, created.messages);
            });
            return created;
        });
        changes.messages.addAll(List.of(messages));
        changes.state = change("gameState", gameId)
                .put("totalCostUsd", game.totalCostUsd)
                .put("activeColor", game.activeColor())
                .put("turnStartedAt", asText(game.turnStartedAt))
                .put("turnDeadlineAt", asText(game.turnDeadlineAt))
                .put("turnChatMode", game.turnChatMode == null ? null : game.turnChatMode.name())
                .put("turnBanterLevel", game.turnBanterLevel == null ? null : game.turnBanterLevel.name());
    }

    private static String asText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
