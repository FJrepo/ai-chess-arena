package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.ws.rs.WebApplicationException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameEngineServiceTest {

    @Test
    void overrideMoveRejectsWhenGameIsNotPaused() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;

        GameEngineService service = new GameEngineService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = new MoveRepository();
        service.chessService = new ChessService();

        assertThrows(WebApplicationException.class, () -> service.overrideMove(game.id, "e4"));
    }

    @Test
    void overrideMoveRejectsWhenLoopStillMarkedRunning() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.PAUSED;

        GameEngineService service = new GameEngineService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = new MoveRepository();
        service.chessService = new ChessService();
        markGameRunning(service, game.id);

        assertThrows(WebApplicationException.class, () -> service.overrideMove(game.id, "e4"));
    }

    @Test
    void persistMoveFlushesBeforeQueuingEvaluation() {
        Game game = new Game();
        game.id = UUID.randomUUID();

        TrackingMoveRepository moveRepository = new TrackingMoveRepository();
        TrackingMoveEvaluationEvent moveEvaluationEvents = new TrackingMoveEvaluationEvent();

        GameEngineService service = new GameEngineService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = moveRepository;
        service.costTrackingService = new CostTrackingService();
        service.moveEvaluationEvents = moveEvaluationEvents;

        service.persistMove(
                game.id,
                1,
                "WHITE",
                "e4",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                "model-a",
                new OpenRouterService.LlmResponse("e4", 12, 8, null, 1500L, "{}", false, null),
                0,
                "v1",
                "hash-1"
        );

        assertTrue(moveRepository.persistAndFlushCalled);
        assertEquals(1, moveRepository.persistedMoves.size());
        assertNotNull(moveEvaluationEvents.lastRequest);
        assertEquals(game.id, moveEvaluationEvents.lastRequest.gameId());
        assertEquals(1, moveEvaluationEvents.lastRequest.moveNumber());
        assertEquals("WHITE", moveEvaluationEvents.lastRequest.color());

        Move persistedMove = moveRepository.persistedMoves.getFirst();
        assertNotNull(persistedMove.id);
        assertEquals(game.id, persistedMove.game.id);
        assertEquals(1, persistedMove.moveNumber);
        assertEquals("WHITE", persistedMove.color);
        assertEquals("e4", persistedMove.san);
        assertEquals(persistedMove.id, moveEvaluationEvents.lastRequest.moveId());
    }

    @SuppressWarnings("unchecked")
    private void markGameRunning(GameEngineService service, UUID gameId) {
        try {
            Field runningGamesField = GameEngineService.class.getDeclaredField("runningGames");
            runningGamesField.setAccessible(true);
            ((Map<UUID, Boolean>) runningGamesField.get(service)).put(gameId, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to seed runningGames test state", e);
        }
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
}
