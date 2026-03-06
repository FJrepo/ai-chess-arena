package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.dto.OverrideMoveRequest;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.GameEngineService;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameResourceTest {

    @Test
    void overrideMoveReturnsNotFoundForMissingGame() {
        GameResource resource = new GameResource();
        resource.gameRepository = new FakeGameRepository(Map.of());
        resource.moveRepository = new MoveRepository();
        resource.gameEngineService = new RecordingGameEngineService();

        Response response = resource.overrideMove(UUID.randomUUID(), new OverrideMoveRequest("e4"));

        assertEquals(404, response.getStatus());
    }

    private static final class FakeGameRepository extends GameRepository {
        private final Map<UUID, Game> games;

        private FakeGameRepository(Map<UUID, Game> games) {
            this.games = games;
        }

        @Override
        public Game findById(UUID id) {
            return games.get(id);
        }
    }

    private static final class RecordingGameEngineService extends GameEngineService {
        @Override
        public void overrideMove(UUID gameId, String san) {
        }
    }
}
