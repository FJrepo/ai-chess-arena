package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import jakarta.ws.rs.WebApplicationException;
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
        service.tryClaimLoop(game.id);

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
    void pauseOnlyFlagsARunningLoopAndRepeatedPausesKeepItsClaim() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;
        RecordingGameLifecycleService lifecycleService = new RecordingGameLifecycleService();
        GameEngineService service = new GameEngineService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        lifecycleService.gameRepository = service.gameRepository;
        service.gameLifecycleService = lifecycleService;
        GameEngineService.GameLoop loop = service.tryClaimLoop(game.id);

        service.pauseGame(game.id);
        service.pauseGame(game.id);

        assertFalse(loop.shouldContinue());
        assertFalse(service.isRunning(game.id));
        assertNull(lifecycleService.pausedGameId, "the loop pauses itself after its turn");
        assertNull(service.tryClaimLoop(game.id), "a resume must not start a second loop while the first one runs");
        assertThrows(WebApplicationException.class, () -> service.startGame(game.id));
    }

    @Test
    void pauseAlsoStopsALoopClaimedWhileTheGameWasBeingPaused() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;
        GameEngineService service = new GameEngineService();
        GameEngineService.GameLoop[] lateLoop = new GameEngineService.GameLoop[1];
        RecordingGameLifecycleService lifecycleService = new RecordingGameLifecycleService() {
            @Override
            public void markGamePaused(UUID gameId) {
                super.markGamePaused(gameId);
                // A concurrent human move continues the game right now.
                lateLoop[0] = service.tryClaimLoop(gameId);
            }
        };
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        lifecycleService.gameRepository = service.gameRepository;
        service.gameLifecycleService = lifecycleService;

        service.pauseGame(game.id);

        assertNotNull(lateLoop[0]);
        assertFalse(lateLoop[0].shouldContinue());
    }

    @Test
    void aFinishedLoopCannotReleaseItsSuccessorsClaim() {
        GameEngineService service = new GameEngineService();
        UUID gameId = UUID.randomUUID();
        GameEngineService.GameLoop first = service.tryClaimLoop(gameId);
        service.releaseLoop(gameId, first);
        GameEngineService.GameLoop second = service.tryClaimLoop(gameId);
        assertNotNull(second);

        service.releaseLoop(gameId, first);

        assertTrue(service.isRunning(gameId));
        assertNull(service.tryClaimLoop(gameId));
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

    private static class RecordingGameLifecycleService extends GameLifecycleService {
        private UUID pausedGameId;

        @Override
        public void markGamePaused(UUID gameId) {
            Game game = gameRepository.findById(gameId);
            game.status = GameStatus.PAUSED;
            pausedGameId = gameId;
        }
    }
}
