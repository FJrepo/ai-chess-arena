package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.dto.HumanMoveRequest;
import dev.aichessarena.dto.MoveAttemptDto;
import dev.aichessarena.dto.OverrideMoveRequest;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.GameEngineService;
import jakarta.ws.rs.core.Response;
import java.util.List;
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

        Response response = resource.startGame(game.id, false);

        assertEquals(200, response.getStatus());
        assertEquals(game.id, engine.startedGameId);
    }

    @Test
    void queueIfFullDefaultsOnlyWhenAbsentAndRejectsEmptyOrRepeatedValues() {
        assertEquals(true, GameResource.parseQueueIfFull(null));
        assertEquals(true, GameResource.parseQueueIfFull(List.of("true")));
        assertEquals(false, GameResource.parseQueueIfFull(List.of("false")));
        for (List<String> invalid : List.of(List.of(""), List.of(" "), List.of("TRUE"), List.of("1"))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GameResource.parseQueueIfFull(invalid));
            assertEquals("queueIfFull must be true or false", error.getMessage());
        }
        IllegalArgumentException repeated = assertThrows(IllegalArgumentException.class,
                () -> GameResource.parseQueueIfFull(List.of("true", "true")));
        assertEquals("queueIfFull must be given at most once", repeated.getMessage());
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

    @Test
    void attemptsReturnNotFoundForMissingGame() {
        GameResource resource = new GameResource();
        resource.gameRepository = new FakeGameRepository(Map.of());
        resource.moveAttemptRepository = new FakeMoveAttemptRepository(List.of());

        assertEquals(404, resource.getAttempts(UUID.randomUUID()).getStatus());
    }

    @Test
    void attemptsAreMappedToDtos() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        MoveAttempt illegal = new MoveAttempt();
        illegal.id = UUID.randomUUID();
        illegal.moveNumber = 4;
        illegal.color = "BLACK";
        illegal.attemptNumber = 1;
        illegal.outcome = MoveAttempt.Outcome.ILLEGAL_MOVE;
        illegal.proposedMove = "Qxh7";
        GameResource resource = new GameResource();
        resource.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        resource.moveAttemptRepository = new FakeMoveAttemptRepository(List.of(illegal));

        Response response = resource.getAttempts(game.id);

        assertEquals(200, response.getStatus());
        List<?> body = (List<?>) response.getEntity();
        MoveAttemptDto dto = (MoveAttemptDto) body.getFirst();
        assertEquals("ILLEGAL_MOVE", dto.outcome());
        assertEquals("Qxh7", dto.proposedMove());
        assertEquals(4, dto.moveNumber());
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

    private static final class FakeMoveAttemptRepository extends MoveAttemptRepository {
        private final List<MoveAttempt> attempts;

        private FakeMoveAttemptRepository(List<MoveAttempt> attempts) {
            this.attempts = attempts;
        }

        @Override
        public List<MoveAttempt> findByGameId(UUID gameId) {
            return attempts;
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
        public dev.aichessarena.dto.GameDto startGame(UUID gameId) {
            startedGameId = gameId;
            return null;
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
