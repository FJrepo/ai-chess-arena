package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.ChatMode;
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

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    @Test
    void recordModelMoveFlushesBeforeQueuingEvaluationAndBroadcastsChat() {
        Game game = game(Game.GameStatus.IN_PROGRESS, START_FEN);

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
                "hash-1",
                START_FEN,
                ChatMode.MANDATORY
        );

        assertTrue(moveRepository.persistAndFlushCalled);
        assertEquals(1, moveRepository.persistedMoves.size());
        assertEquals(ChatMode.MANDATORY, moveRepository.persistedMoves.getFirst().chatMode);
        assertNotNull(moveEvaluationEvents.lastRequest);
        assertEquals(game.id, moveEvaluationEvents.lastRequest.gameId());
        assertEquals("WHITE", moveEvaluationEvents.lastRequest.color());
        assertEquals(1, chatRepository.persistedMessages.size());
        assertEquals("Good luck", chatRepository.persistedMessages.getFirst().message);
        assertEquals("Model A", websocket.lastChatSenderName);
        assertEquals("e4", websocket.lastMoveSan);
        assertFalse(websocket.lastMoveOverride);
        assertTrue(game.pgn.contains("1. e4"));
        assertEquals(AFTER_E4, game.currentFen);
    }

    @Test
    void recordModelMoveRejectsAPositionThatChangedMeanwhile() {
        Game game = game(Game.GameStatus.IN_PROGRESS, AFTER_E4);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        GameMoveService service = service(game, moveRepository);

        assertThrows(GameStateConflictException.class, () -> service.recordModelMove(
                game.id, 1, "WHITE", "d4", "fen", "model-a", "Model A", null,
                new OpenRouterService.LlmResponse("d4", 1, 1, null, 1L, "{}", false, null),
                0, "v1", "hash-1", START_FEN, ChatMode.OPTIONAL
        ));
        assertTrue(moveRepository.persistedMoves.isEmpty());
    }

    @Test
    void aModelMoveWithoutAMessageIsRecordedWithItsChatModeAndNoChatMessage() {
        Game game = game(Game.GameStatus.IN_PROGRESS, START_FEN);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        TrackingChatMessageRepository chatRepository = new TrackingChatMessageRepository();
        GameMoveService service = service(game, moveRepository);
        service.chatMessageRepository = chatRepository;

        service.recordModelMove(
                game.id, 1, "WHITE", "e4", AFTER_E4, "model-a", "Model A", null,
                new OpenRouterService.LlmResponse("e4", 1, 1, null, 1L, "{}", false, null),
                0, "v2-chat-required", "hash-1", START_FEN, ChatMode.MANDATORY
        );

        assertEquals(ChatMode.MANDATORY, moveRepository.persistedMoves.getFirst().chatMode);
        assertTrue(chatRepository.persistedMessages.isEmpty());
    }

    @Test
    void recordHumanMoveRejectsStalePositionAndFinishedGames() {
        Game game = game(Game.GameStatus.IN_PROGRESS, AFTER_E4);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        GameMoveService service = service(game, moveRepository);

        assertThrows(GameStateConflictException.class, () -> service.recordHumanMove(
                game.id, 1, "WHITE", "d4", "fen", "Human", null, START_FEN));

        game.status = Game.GameStatus.COMPLETED;
        assertThrows(GameStateConflictException.class, () -> service.recordHumanMove(
                game.id, 1, "BLACK", "e5", "fen", "Human", null, AFTER_E4));
        assertTrue(moveRepository.persistedMoves.isEmpty());
    }

    @Test
    void overrideRequiresAPausedGameAtTheExpectedPosition() {
        Game game = game(Game.GameStatus.IN_PROGRESS, START_FEN);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        GameMoveService service = service(game, moveRepository);

        assertThrows(GameStateConflictException.class, () -> service.applyOverrideMove(game.id, "e4", START_FEN));

        game.status = Game.GameStatus.PAUSED;
        assertThrows(GameStateConflictException.class, () -> service.applyOverrideMove(game.id, "e4", AFTER_E4));

        service.applyOverrideMove(game.id, "e4", START_FEN);
        assertEquals(1, moveRepository.persistedMoves.size());
        assertEquals(AFTER_E4.split(" ")[0], game.currentFen.split(" ")[0]);
    }

    @Test
    void overrideMoveIsQueuedForEvaluationAndBroadcastAsOverride() {
        Game game = game(Game.GameStatus.PAUSED, START_FEN);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        GameMoveService service = service(game, moveRepository);
        TrackingMoveEvaluationEvent moveEvaluationEvents = (TrackingMoveEvaluationEvent) service.moveEvaluationEvents;
        RecordingGameWebSocket websocket = (RecordingGameWebSocket) service.gameWebSocket;

        service.applyOverrideMove(game.id, "e4", START_FEN);

        Move move = moveRepository.persistedMoves.getFirst();
        assertTrue(move.isOverride);
        assertTrue(moveRepository.persistAndFlushCalled);
        assertNotNull(moveEvaluationEvents.lastRequest);
        assertEquals(move.id, moveEvaluationEvents.lastRequest.moveId());
        assertEquals(game.id, moveEvaluationEvents.lastRequest.gameId());
        assertEquals(1, moveEvaluationEvents.lastRequest.moveNumber());
        assertEquals("WHITE", moveEvaluationEvents.lastRequest.color());
        assertEquals(move.fen, moveEvaluationEvents.lastRequest.fen());
        assertEquals("e4", websocket.lastMoveSan);
        assertTrue(websocket.lastMoveOverride);
    }

    @Test
    void humanMoveIsBroadcastAsARegularMove() {
        Game game = game(Game.GameStatus.IN_PROGRESS, START_FEN);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        GameMoveService service = service(game, moveRepository);
        RecordingGameWebSocket websocket = (RecordingGameWebSocket) service.gameWebSocket;
        websocket.lastMoveOverride = true;

        service.recordHumanMove(game.id, 1, "WHITE", "e4", AFTER_E4, "Human", null, START_FEN);

        assertEquals("e4", websocket.lastMoveSan);
        assertFalse(websocket.lastMoveOverride);
        assertNotNull(((TrackingMoveEvaluationEvent) service.moveEvaluationEvents).lastRequest);
    }

    @Test
    void evaluationUpdateBroadcastsTheQualitiesItDecidesAndTheSummary() {
        Game game = game(Game.GameStatus.IN_PROGRESS, START_FEN);
        Move white = storedMove(game, 1, "WHITE", -280);
        Move black = storedMove(game, 1, "BLACK", null);
        Move nextWhite = storedMove(game, 2, "WHITE", -250);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        moveRepository.gameMoves.addAll(List.of(white, black, nextWhite));
        GameMoveService service = service(game, moveRepository);
        RecordingGameWebSocket websocket = (RecordingGameWebSocket) service.gameWebSocket;

        // Black's evaluation arrives last (out of order): it decides Black's move and White's next one.
        service.updateMoveEvaluation(black.id, new StockfishService.EvalResult(338, null));

        assertEquals(338, black.evaluationCp);
        assertEquals(1, websocket.lastEvaluationMoveNumber);
        assertEquals(2, websocket.lastQualities.size());
        PlyQualityDto blackQuality = websocket.lastQualities.get(0);
        assertEquals("BLACK", blackQuality.color());
        assertEquals("BLUNDER", blackQuality.quality().judgement());
        assertEquals(618, blackQuality.quality().centipawnLoss());
        PlyQualityDto whiteQuality = websocket.lastQualities.get(1);
        assertEquals(2, whiteQuality.moveNumber());
        assertEquals("WHITE", whiteQuality.color());
        assertEquals("BLUNDER", whiteQuality.quality().judgement(), "+3.38 to -2.50 for White");
        assertEquals(1, websocket.lastSummary.black().blunders());
        assertEquals(2, websocket.lastSummary.white().blunders(), "1.?? to -2.80, then 2.??");
        assertEquals(2, websocket.lastSummary.white().analyzedMoves());
    }

    @Test
    void evaluationUpdateWithoutAScoreSendsNoQuality() {
        Game game = game(Game.GameStatus.IN_PROGRESS, START_FEN);
        Move white = storedMove(game, 1, "WHITE", null);
        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        moveRepository.gameMoves.add(white);
        GameMoveService service = service(game, moveRepository);
        RecordingGameWebSocket websocket = (RecordingGameWebSocket) service.gameWebSocket;

        // Stockfish unavailable: the result carries no score.
        service.updateMoveEvaluation(white.id, new StockfishService.EvalResult(null, null));

        assertEquals(1, websocket.lastQualities.size());
        assertNull(websocket.lastQualities.get(0).quality());
        assertEquals(0, websocket.lastSummary.white().analyzedMoves());
    }

    @Test
    void samePositionIgnoresMoveClocks() {
        assertTrue(GameMoveService.samePosition(START_FEN, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 3 7"));
        assertFalse(GameMoveService.samePosition(START_FEN, AFTER_E4));
        assertFalse(GameMoveService.samePosition(START_FEN, null));
    }

    private static Game game(Game.GameStatus status, String fen) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = status;
        game.currentFen = fen;
        return game;
    }

    private static Move storedMove(Game game, int number, String color, Integer cp) {
        Move move = new Move();
        move.id = UUID.randomUUID();
        move.game = game;
        move.moveNumber = number;
        move.color = color;
        move.san = "x";
        move.fen = "fen";
        move.evaluationCp = cp;
        return move;
    }

    private static GameMoveService service(Game game, TrackingMoveRepository moveRepository) {
        GameMoveService service = new GameMoveService();
        service.chessService = new ChessService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = moveRepository;
        service.chatMessageRepository = new TrackingChatMessageRepository();
        service.costTrackingService = new CostTrackingService();
        service.moveEvaluationEvents = new TrackingMoveEvaluationEvent();
        service.gameWebSocket = new RecordingGameWebSocket();
        return service;
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
        public Game findByIdForUpdate(UUID id) {
            return games.get(id);
        }

        @Override
        public void persist(Game entity) {
            games.put(entity.id, entity);
        }
    }

    private static final class TrackingMoveRepository extends MoveRepository {
        private final List<Move> persistedMoves = new ArrayList<>();
        private final List<Move> gameMoves = new ArrayList<>();
        private boolean persistAndFlushCalled;

        @Override
        public Move findById(UUID id) {
            return gameMoves.stream().filter(move -> id.equals(move.id)).findFirst().orElse(null);
        }

        @Override
        public List<Move> findByGameId(UUID gameId) {
            return gameMoves.stream().filter(move -> gameId.equals(move.game.id)).toList();
        }

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
        private int lastEvaluationMoveNumber;
        private List<PlyQualityDto> lastQualities;
        private GameQualitySummaryDto lastSummary;
        private String lastChatSenderName;
        private String lastMoveSan;
        private boolean lastMoveOverride;

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
                int retryCount,
                boolean isOverride
        ) {
            lastMoveSan = san;
            lastMoveOverride = isOverride;
        }

        @Override
        public void broadcastEvaluation(UUID gameId, int moveNumber, String color, Integer evaluationCp,
                                        Integer evaluationMate, List<PlyQualityDto> qualities,
                                        GameQualitySummaryDto qualitySummary) {
            lastEvaluationMoveNumber = moveNumber;
            lastQualities = qualities;
            lastSummary = qualitySummary;
        }
    }
}
