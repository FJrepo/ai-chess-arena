package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.ChatMode;
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
    void chatOffUsesTheSilentPromptForBothSides() {
        Game game = tournamentGame(ChatMode.OFF);

        GameConversationService.ConversationState state =
                baseService(Map.of(game.id, game), List.of()).initializeConversation(game.id);

        assertEquals("v2-silent", state.whitePrompt().version());
        assertEquals("v2-silent", state.blackPrompt().version());
        assertEquals(ChatMode.OFF, state.whitePrompt().chatMode());
        assertEquals(ChatMode.OFF, state.chatMode());
        assertTrue(state.whiteHistory().getFirst().content().contains(PromptService.SILENT_MESSAGE_RULE));
    }

    @Test
    void mandatoryChatUsesTheChatRequiredPromptForBothSides() {
        Game game = tournamentGame(ChatMode.MANDATORY);

        GameConversationService.ConversationState state =
                baseService(Map.of(game.id, game), List.of()).initializeConversation(game.id);

        assertEquals("v2-chat-required", state.whitePrompt().version());
        assertEquals("v2-chat-required", state.blackPrompt().version());
        assertEquals(ChatMode.MANDATORY, state.blackPrompt().chatMode());
        assertTrue(state.blackHistory().getFirst().content().contains(PromptService.REQUIRED_MESSAGE_RULE));
    }

    @Test
    void aLegacyTemplateCannotRequireAMessageSoMandatoryCountsAsOptionalForThatSide() {
        Game game = tournamentGame(ChatMode.MANDATORY);
        TournamentParticipant white = new TournamentParticipant();
        white.customSystemPrompt = "Play as %s against %s (%s). Answer with JSON.";
        game.whiteParticipant = white;

        GameConversationService.ConversationState state =
                baseService(Map.of(game.id, game), List.of()).initializeConversation(game.id);

        assertEquals("legacy-custom-template", state.whitePrompt().version());
        assertEquals(ChatMode.OPTIONAL, state.whitePrompt().chatMode());
        assertEquals(ChatMode.MANDATORY, state.blackPrompt().chatMode());
        assertEquals(ChatMode.MANDATORY, state.chatMode());
    }

    @Test
    void refreshingAfterAModeChangeClearsExchangesUnderOldInstructions() {
        Game game = tournamentGame(ChatMode.OPTIONAL);
        GameConversationService service = baseService(Map.of(game.id, game), List.of());
        GameConversationService.ConversationState state = service.initializeConversation(game.id);
        state.whiteHistory().add(new OpenRouterService.ChatMsg("user", "turn 1"));
        state.whiteHistory().add(new OpenRouterService.ChatMsg("assistant", "{\"move\":\"e4\"}"));
        String originalSystem = state.whiteHistory().getFirst().content();

        game.tournament.chatMode = ChatMode.MANDATORY;
        GameConversationService.ConversationState refreshed = service.refreshSystemPrompts(game, state);

        assertEquals(ChatMode.MANDATORY, refreshed.chatMode());
        assertEquals("v2-chat-required", refreshed.whitePrompt().version());
        assertEquals(1, refreshed.whiteHistory().size());
        assertEquals("system", refreshed.whiteHistory().getFirst().role());
        assertTrue(refreshed.whiteHistory().getFirst().content().contains(PromptService.REQUIRED_MESSAGE_RULE));
        assertNotEquals(originalSystem, refreshed.whiteHistory().getFirst().content(), "settings change resets the prefix");
        assertEquals(1, refreshed.blackHistory().size());
    }

    @Test
    void sharedInstructionChangesResetOnlySidesWithoutAnOverrideAndOnlyOnce() {
        Game game = tournamentGame(ChatMode.OPTIONAL);
        game.whiteParticipant = new TournamentParticipant();
        game.whiteParticipant.customInstructions = "Keep this override.";
        GameConversationService service = baseService(Map.of(game.id, game), List.of());
        var state = service.initializeConversation(game.id);
        var prior = new OpenRouterService.ChatMsg("assistant", "prior exchange");
        state.whiteHistory().add(prior);
        state.blackHistory().add(prior);
        game.tournament.sharedCustomInstructions = "New shared instructions.";

        var updated = service.refreshSystemPrompts(game, state);
        assertEquals(state.whitePrompt(), updated.whitePrompt());
        assertSame(prior, updated.whiteHistory().getLast());
        assertNotEquals(state.blackPrompt().hash(), updated.blackPrompt().hash());
        assertEquals(1, updated.blackHistory().size());
        assertTrue(updated.blackHistory().getFirst().content().contains("New shared instructions."));
        updated.blackHistory().add(prior);
        assertSame(prior, service.refreshSystemPrompts(game, updated).blackHistory().getLast());

        game.whiteParticipant.customInstructions = "New override.";
        var override = service.refreshSystemPrompts(game, updated);
        assertEquals(1, override.whiteHistory().size());
        assertTrue(override.whiteHistory().getFirst().content().contains("New override."));
        assertSame(prior, override.blackHistory().getLast());

        game.whiteParticipant.customSystemPrompt = "New template for %s against %s (%s).";
        var template = service.refreshSystemPrompts(game, override);
        assertNotEquals(override.whitePrompt().hash(), template.whitePrompt().hash());
        assertEquals("legacy-custom-template", template.whitePrompt().version());
        assertTrue(template.whiteHistory().getFirst().content().startsWith("New template"));
    }

    @Test
    void unchangedSettingsKeepSystemPromptBytesIdenticalWhenRebuilt() {
        Game game = tournamentGame(ChatMode.OPTIONAL);
        GameConversationService service = baseService(Map.of(game.id, game), List.of());
        var state = service.initializeConversation(game.id);
        String white = state.whiteHistory().getFirst().content();
        String black = state.blackHistory().getFirst().content();
        var whitePrompt = state.whitePrompt();
        for (int turn = 0; turn < 20; turn++) {
            state = service.refreshSystemPrompts(game, state);
            assertEquals(white, state.whiteHistory().getFirst().content());
            assertEquals(black, state.blackHistory().getFirst().content());
            assertEquals(whitePrompt, state.whitePrompt());
        }
    }

    private static Game tournamentGame(ChatMode chatMode) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.whitePlayerName = "White";
        game.whiteModelId = "white-model";
        game.blackPlayerName = "Black";
        game.blackModelId = "black-model";
        game.tournament = new Tournament();
        game.tournament.chatMode = chatMode;
        return game;
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
