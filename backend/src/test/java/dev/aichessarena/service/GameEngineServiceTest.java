package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Test
    void aTurnOfAnUntimedTournamentRunsWithoutATimeLimit() {
        GameTurnService.TurnRequest request = runOneTurnFor(tournamentWithTimeout(null));

        assertNull(request.moveTimeoutSeconds());
        assertEquals("model-white", request.modelId());
        assertEquals(2, request.maxRetries());
    }

    @Test
    void aTurnOfATimedTournamentUsesItsLimitAndAGameWithoutATournamentUsesSixtySeconds() {
        assertEquals(45, runOneTurnFor(tournamentWithTimeout(45)).moveTimeoutSeconds());
        assertEquals(60, runOneTurnFor(null).moveTimeoutSeconds());
    }

    @Test
    void aTurnUsesTheChatModeOfItsPromptAndAChangedModeRebuildsThePromptsFirst() {
        // The conversation was built for OPTIONAL (see runOneTurnFor); the tournament is MANDATORY by now.
        Tournament tournament = tournamentWithTimeout(45);
        tournament.chatMode = ChatMode.MANDATORY;

        GameTurnService.TurnRequest request = runOneTurnFor(tournament);

        assertEquals(ChatMode.MANDATORY, request.chatMode());
        assertEquals("v2-chat-required", request.promptVersion());
        assertTrue(request.history().getFirst().content().contains(PromptService.REQUIRED_MESSAGE_RULE));

        GameTurnService.TurnRequest unchanged = runOneTurnFor(tournamentWithTimeout(45));
        assertEquals(ChatMode.OPTIONAL, unchanged.chatMode());
        assertEquals("v1", unchanged.promptVersion(), "the mode did not change, so the prompt is kept");
    }

    /** Runs the real game loop for one turn of a fresh game and returns the request it passed to the turn. */
    private static GameTurnService.TurnRequest runOneTurnFor(Tournament tournament) {
        return runTurnsFor(tournament, null, 1).getFirst();
    }

    @Test
    void aMixedTemplateGameKeepsTheConfiguredModeForTheLiveLabelOnEveryTurn() {
        // MANDATORY tournament; White has a legacy template, which cannot ask for a message.
        Tournament tournament = tournamentWithTimeout(45);
        tournament.chatMode = ChatMode.MANDATORY;
        TournamentParticipant white = new TournamentParticipant();
        white.customSystemPrompt = "Play as %s against %s (%s). Answer with JSON.";

        List<GameTurnService.TurnRequest> turns = runTurnsFor(tournament, white, 4);

        assertEquals(List.of("WHITE", "BLACK", "WHITE", "BLACK"),
                turns.stream().map(GameTurnService.TurnRequest::sideToMove).toList());
        assertEquals(List.of(ChatMode.OPTIONAL, ChatMode.MANDATORY, ChatMode.OPTIONAL, ChatMode.MANDATORY),
                turns.stream().map(GameTurnService.TurnRequest::chatMode).toList(), "effective mode per side");
        assertEquals(List.of(ChatMode.MANDATORY, ChatMode.MANDATORY, ChatMode.MANDATORY, ChatMode.MANDATORY),
                turns.stream().map(GameTurnService.TurnRequest::tournamentChatMode).toList(),
                "configured mode for the live label");
    }

    /**
     * Runs the real game loop for {@code turns} turns of a fresh game (each turn plays the first legal move) and
     * returns the requests it passed to the turns.
     */
    private static List<GameTurnService.TurnRequest> runTurnsFor(Tournament tournament,
                                                                 TournamentParticipant whiteParticipant, int turns) {
        Game game = new Game();
        game.whiteParticipant = whiteParticipant;
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;
        game.tournament = tournament;
        game.whitePlayerName = "White";
        game.whiteModelId = "model-white";
        game.blackPlayerName = "Black";
        game.blackModelId = "model-black";
        game.totalCostUsd = BigDecimal.ZERO;

        GameEngineService service = new GameEngineService();
        service.chessService = new ChessService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.gameConversationService = new GameConversationService() {
            @Override
            public ConversationState initializeConversation(UUID gameId) {
                List<OpenRouterService.ChatMsg> white = new ArrayList<>();
                white.add(new OpenRouterService.ChatMsg("system", "rules v1"));
                List<OpenRouterService.ChatMsg> black = new ArrayList<>();
                black.add(new OpenRouterService.ChatMsg("system", "rules v1"));
                return new ConversationState(new Board(), white, black,
                        new PromptContext("v1", "hash-w"), new PromptContext("v1", "hash-b"));
            }
        };
        service.gameConversationService.promptService = new PromptService();
        List<GameTurnService.TurnRequest> requests = new ArrayList<>();
        service.gameTurnService = new GameTurnService() {
            @Override
            public TurnResult playTurn(TurnRequest request) {
                requests.add(request);
                if (requests.size() >= turns) {
                    return TurnResult.terminal();
                }
                String san = request.legalMoves().getFirst();
                chessService.validateAndApply(request.board(), san);
                return TurnResult.recorded(san, null);
            }
        };
        service.gameTurnService.chessService = service.chessService;

        service.runGameLoop(game.id, new GameEngineService.GameLoop(), null, null);

        assertEquals(turns, requests.size());
        return requests;
    }

    private static Tournament tournamentWithTimeout(Integer moveTimeoutSeconds) {
        Tournament tournament = new Tournament();
        tournament.moveTimeoutSeconds = moveTimeoutSeconds;
        tournament.maxRetries = 2;
        return tournament;
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
        public ChatMode findChatMode(UUID gameId) {
            Game game = games.get(gameId);
            return game.tournament != null ? game.tournament.chatMode : ChatMode.OPTIONAL;
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
