package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameConversationServiceTest {

    @Test
    void initializeConversationUsesParticipantInstructionsOverTournamentSharedInstructions() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.whitePlayerName = "White";
        game.whiteModelId = "white-model";
        game.blackPlayerName = "Black";
        game.blackModelId = "black-model";
        game.tournament = new Tournament();
        game.tournament.sharedCustomInstructions = "Play patiently.";

        TournamentParticipant whiteParticipant = new TournamentParticipant();
        whiteParticipant.customInstructions = "Force tactical play.";
        game.whiteParticipant = whiteParticipant;

        GameConversationService service = baseService(Map.of(game.id, game), List.of());

        GameConversationService.ConversationState state = service.initializeConversation(game.id);

        assertTrue(state.whiteHistory().getFirst().content().contains("Force tactical play."));
        assertTrue(state.blackHistory().getFirst().content().contains("Play patiently."));
        assertEquals("v2+custom-instructions", state.whitePrompt().version());
        assertEquals("v2+custom-instructions", state.blackPrompt().version());
    }

    @Test
    void initializeConversationReplaysMovesIntoBoardState() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.whitePlayerName = "White";
        game.whiteModelId = "white-model";
        game.blackPlayerName = "Black";
        game.blackModelId = "black-model";

        Move firstMove = new Move();
        firstMove.san = "e4";
        Move secondMove = new Move();
        secondMove.san = "e5";

        GameConversationService service = baseService(Map.of(game.id, game), List.of(firstMove, secondMove));

        GameConversationService.ConversationState state = service.initializeConversation(game.id);

        assertEquals("WHITE", service.chessService.getSideToMove(state.board()));
        assertEquals(2, service.chessService.getMoveNumber(state.board()));
    }

    private static GameConversationService baseService(Map<UUID, Game> games, List<Move> moves) {
        GameConversationService service = new GameConversationService();
        service.chessService = new ChessService();
        service.promptService = new PromptService();
        service.gameRepository = new FakeGameRepository(games);
        service.moveRepository = new FakeMoveRepository(moves);
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
    }

    private static final class FakeMoveRepository extends MoveRepository {
        private final List<Move> moves;

        private FakeMoveRepository(List<Move> moves) {
            this.moves = moves;
        }

        @Override
        public List<Move> findByGameId(UUID gameId) {
            return moves;
        }
    }
}
