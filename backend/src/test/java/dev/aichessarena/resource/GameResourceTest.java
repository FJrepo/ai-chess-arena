package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.dto.HumanMoveRequest;
import dev.aichessarena.dto.OverrideMoveRequest;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.TournamentParticipant;
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

    @Test
    void startGameAllowsHumanParticipantWithoutModelId() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.whitePlayerName = "Human";
        game.whiteParticipant = humanParticipant("Human");
        game.blackPlayerName = "Model";
        game.blackModelId = "model-a";
        game.blackParticipant = aiParticipant("Model", "model-a");

        RecordingGameEngineService engine = new RecordingGameEngineService();
        GameResource resource = new GameResource();
        resource.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        resource.moveRepository = new MoveRepository();
        resource.gameEngineService = engine;

        Response response = resource.startGame(game.id);

        assertEquals(200, response.getStatus());
        assertEquals(game.id, engine.startedGameId);
    }

    @Test
    void submitHumanMoveDelegatesToEngine() {
        Game game = new Game();
        game.id = UUID.randomUUID();

        RecordingGameEngineService engine = new RecordingGameEngineService();
        GameResource resource = new GameResource();
        resource.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        resource.moveRepository = new MoveRepository();
        resource.gameEngineService = engine;

        Response response = resource.submitHumanMove(game.id, new HumanMoveRequest("e4", null, null, null, "gl hf"));

        assertEquals(200, response.getStatus());
        assertEquals(game.id, engine.humanMoveGameId);
        assertEquals("e4", engine.humanMoveSan);
        assertEquals("gl hf", engine.humanMoveMessage);
    }

    @Test
    void submitHumanMoveReturnsNotFoundForMissingGame() {
        GameResource resource = new GameResource();
        resource.gameRepository = new FakeGameRepository(Map.of());
        resource.moveRepository = new MoveRepository();
        resource.gameEngineService = new RecordingGameEngineService();

        Response response = resource.submitHumanMove(UUID.randomUUID(), new HumanMoveRequest("e4", null, null, null, null));

        assertEquals(404, response.getStatus());
    }

    private TournamentParticipant humanParticipant(String name) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.HUMAN;
        return participant;
    }

    private TournamentParticipant aiParticipant(String name, String modelId) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.AI;
        participant.modelId = modelId;
        return participant;
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
        private UUID startedGameId;
        private UUID humanMoveGameId;
        private String humanMoveSan;
        private String humanMoveFrom;
        private String humanMoveTo;
        private String humanMovePromotion;
        private String humanMoveMessage;

        @Override
        public void startGame(UUID gameId) {
            startedGameId = gameId;
        }

        @Override
        public void overrideMove(UUID gameId, String san) {
        }

        @Override
        public void submitHumanMove(UUID gameId, String san, String from, String to, String promotion, String message) {
            humanMoveGameId = gameId;
            humanMoveSan = san;
            humanMoveFrom = from;
            humanMoveTo = to;
            humanMovePromotion = promotion;
            humanMoveMessage = message;
        }
    }
}
