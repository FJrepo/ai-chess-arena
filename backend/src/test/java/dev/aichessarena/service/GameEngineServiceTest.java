package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import jakarta.ws.rs.WebApplicationException;
import java.lang.reflect.Field;
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

        assertThrows(WebApplicationException.class, () -> service.overrideMove(game.id, "e4"));
    }

    @Test
    void overrideMoveRejectsWhenLoopStillMarkedRunning() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.PAUSED;

        GameEngineService service = new GameEngineService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        markGameRunning(service, game.id);

        assertThrows(WebApplicationException.class, () -> service.overrideMove(game.id, "e4"));
    }

    @Test
    void pauseGameImmediatelyMarksWaitingHumanGamePaused() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;

        RecordingGameLifecycleService lifecycleService = new RecordingGameLifecycleService();
        GameEngineService service = new GameEngineService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        lifecycleService.gameRepository = service.gameRepository;
        service.gameLifecycleService = lifecycleService;

        service.pauseGame(game.id);

        assertEquals(game.id, lifecycleService.pausedGameId);
        assertEquals(GameStatus.PAUSED, game.status);
        assertFalse(service.isRunning(game.id));
    }

    @Test
    void tryMarkGameRunningRejectsExistingPauseRequestMarker() {
        GameEngineService service = new GameEngineService();
        UUID gameId = UUID.randomUUID();
        markGamePaused(service, gameId);

        assertFalse(service.tryMarkGameRunning(gameId));
        assertFalse(service.isRunning(gameId));
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

    @SuppressWarnings("unchecked")
    private void markGamePaused(GameEngineService service, UUID gameId) {
        try {
            Field runningGamesField = GameEngineService.class.getDeclaredField("runningGames");
            runningGamesField.setAccessible(true);
            ((Map<UUID, Boolean>) runningGamesField.get(service)).put(gameId, false);
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

    private static final class RecordingGameLifecycleService extends GameLifecycleService {
        private UUID pausedGameId;

        @Override
        public void markGamePaused(UUID gameId) {
            Game game = gameRepository.findById(gameId);
            game.status = GameStatus.PAUSED;
            pausedGameId = gameId;
        }
    }
}
