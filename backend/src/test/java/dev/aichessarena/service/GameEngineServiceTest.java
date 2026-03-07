package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
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
}
