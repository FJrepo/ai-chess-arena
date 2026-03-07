package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameMoveServiceTest {

    @Test
    void recordModelMoveFlushesBeforeQueuingEvaluationAndBroadcastsChat() {
        Game game = new Game();
        game.id = UUID.randomUUID();

        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        TrackingChatMessageRepository chatRepository = new TrackingChatMessageRepository();
        TrackingMoveEvaluationEvent moveEvaluationEvents = new TrackingMoveEvaluationEvent();
        RecordingGameWebSocket websocket = new RecordingGameWebSocket();

        GameMoveService service = new GameMoveService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = moveRepository;
        service.chatMessageRepository = chatRepository;
        service.costTrackingService = new CostTrackingService();
        service.moveEvaluationEvents = moveEvaluationEvents;
        service.gameWebSocket = websocket;

        service.recordModelMove(
                game.id,
                1,
                "WHITE",
                "e4",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                "model-a",
                "Model A",
                "Good luck",
                new OpenRouterService.LlmResponse("e4", 12, 8, null, 1500L, "{}", false, null),
                0,
                "v1",
                "hash-1"
        );

        assertTrue(moveRepository.persistAndFlushCalled);
        assertEquals(1, moveRepository.persistedMoves.size());
        assertNotNull(moveEvaluationEvents.lastRequest);
        assertEquals(game.id, moveEvaluationEvents.lastRequest.gameId());
        assertEquals("WHITE", moveEvaluationEvents.lastRequest.color());
        assertEquals(1, chatRepository.persistedMessages.size());
        assertEquals("Good luck", chatRepository.persistedMessages.getFirst().message);
        assertEquals("Model A", websocket.lastChatSenderName);
        assertEquals("e4", websocket.lastMoveSan);
        assertTrue(game.pgn.contains("1. e4"));
    }

    private static final class FakeGameRepository extends GameRepository {
        private final Map<UUID, Game> games;

        private FakeGameRepository(Map<UUID, Game> games) {
            this.games = new HashMap<>(games);
        }

        @Override
        public Game findById(UUID id) {
            return games.get(id);
        }

        @Override
        public void persist(Game entity) {
            games.put(entity.id, entity);
        }
    }

    private static final class TrackingMoveRepository extends MoveRepository {
        private final List<Move> persistedMoves = new ArrayList<>();
        private boolean persistAndFlushCalled;

        @Override
        public void persistAndFlush(Move entity) {
            persistAndFlushCalled = true;
            entity.id = UUID.randomUUID();
            persistedMoves.add(entity);
        }

        @Override
        public void persist(Move entity) {
            persistedMoves.add(entity);
        }
    }

    private static final class TrackingChatMessageRepository extends ChatMessageRepository {
        private final List<ChatMessage> persistedMessages = new ArrayList<>();

        @Override
        public void persist(ChatMessage entity) {
            persistedMessages.add(entity);
        }
    }

    private static final class TrackingMoveEvaluationEvent implements Event<MoveEvaluationRequested> {
        private MoveEvaluationRequested lastRequest;

        @Override
        public void fire(MoveEvaluationRequested event) {
            lastRequest = event;
        }

        @Override
        public Event<MoveEvaluationRequested> select(Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends MoveEvaluationRequested> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends MoveEvaluationRequested> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletionStage<MoveEvaluationRequested> fireAsync(MoveEvaluationRequested event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletionStage<MoveEvaluationRequested> fireAsync(
                MoveEvaluationRequested event,
                jakarta.enterprise.event.NotificationOptions options
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingGameWebSocket extends GameWebSocket {
        private String lastChatSenderName;
        private String lastMoveSan;

        @Override
        public void broadcastChat(
                UUID gameId,
                int moveNumber,
                String senderColor,
                String senderModel,
                String senderName,
                String message
        ) {
            lastChatSenderName = senderName;
        }

        @Override
        public void broadcastMove(
                UUID gameId,
                int moveNumber,
                String color,
                String san,
                String fen,
                String pgn,
                String modelId,
                long responseTimeMs,
                int retryCount
        ) {
            lastMoveSan = san;
        }
    }
}
