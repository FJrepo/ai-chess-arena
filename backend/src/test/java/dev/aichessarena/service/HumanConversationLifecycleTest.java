package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bhlangonijr.chesslib.Board;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real loop, human submission, turn service and HTTP builder; only persistence and scheduling are in-memory. */
class HumanConversationLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void twoHumanContinuationsKeepThePreviousModelExchangesAndReloadTheBoard() throws Exception {
        Fixture f = new Fixture();
        f.engine.startGame(f.game.id);
        f.human("e5");
        f.human("Nc6");

        assertEquals(List.of(2, 4, 6), f.messageCounts());
        JsonNode first = f.requests.getFirst().path("messages");
        JsonNode second = f.requests.get(1).path("messages");
        JsonNode third = f.requests.get(2).path("messages");
        assertEquals(first.get(0), second.get(0));
        assertEquals(first.get(1), second.get(1));
        assertEquals("{ \"move\": \"e4\" }\n", second.get(2).path("content").asText());
        assertTrue(second.get(3).path("content").asText().contains("e5"));
        for (int i = 0; i < second.size(); i++) assertEquals(second.get(i), third.get(i));
        assertEquals(List.of("e4", "e5", "Nf3", "Nc6", "Bc4"), f.moves.stream().map(m -> m.san).toList());
        assertTrue(third.get(5).path("content").asText().contains("Nc6"));
        assertFalse(f.engine.isRunning(f.game.id));
    }

    @Test
    void editingSharedInstructionsAppliesNextTurnAndResetsThePrefixOnlyOnce() throws Exception {
        Fixture f = new Fixture();
        f.engine.startGame(f.game.id);
        f.human("e5");
        f.game.tournament.sharedCustomInstructions = "Prefer quiet development.";
        f.human("Nc6");
        f.human("Nf6");

        assertEquals(List.of(2, 4, 2, 4), f.messageCounts());
        JsonNode changed = f.requests.get(2).path("messages");
        assertTrue(changed.get(0).path("content").asText().contains("Prefer quiet development."));
        assertEquals(changed.get(0), f.requests.get(3).path("messages").get(0));
        assertEquals(f.promptHashes.get(0), f.promptHashes.get(1));
        assertFalse(f.promptHashes.get(1).equals(f.promptHashes.get(2)));
        assertEquals(f.promptHashes.get(2), f.promptHashes.get(3));
    }

    @Test
    void pauseResumeDiscardsTheWaitingConversationThenGrowsAgain() throws Exception {
        Fixture f = new Fixture();
        f.engine.startGame(f.game.id);
        f.engine.pauseGame(f.game.id);
        assertNoWaitingConversation(f.engine);
        f.engine.startGame(f.game.id);
        f.human("e5");
        f.human("Nc6");
        assertEquals(List.of(2, 2, 4), f.messageCounts());
    }

    @Test
    void aNewBackendInstanceStartsFreshAtTheCommittedBoard() throws Exception {
        Fixture f = new Fixture();
        f.engine.startGame(f.game.id);
        f.engine = f.buildEngine();
        f.human("e5");
        f.human("Nc6");
        assertEquals(List.of(2, 2, 4), f.messageCounts());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationOrCompletionReleasesAHumanWait(boolean cancelled) throws Exception {
        Fixture f = new Fixture();
        f.engine.startGame(f.game.id);
        if (cancelled) {
            f.game.status = Game.GameStatus.CANCELLED;
            f.engine.requestStop(f.game.id);
        } else {
            Board mate = new Board();
            mate.loadFromFen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
            f.engine.endGame(f.game.id, mate);
        }
        assertNoWaitingConversation(f.engine);
    }

    private static void assertNoWaitingConversation(GameEngineService engine) throws Exception {
        var cache = GameEngineService.class.getDeclaredField("humanConversations");
        cache.setAccessible(true);
        assertTrue(((Map<?, ?>) cache.get(engine)).isEmpty(), "finished or paused games must release retained history");
    }

    private final class Fixture {
        private final Game game = new Game();
        private final List<Move> moves = new ArrayList<>();
        private final List<JsonNode> requests = new ArrayList<>();
        private final List<String> promptHashes = new ArrayList<>();
        private final ChessService chess = new ChessService();
        private final OpenRouterService router = new OpenRouterService();
        private final GameRepository games = new GameRepository() {
            @Override
            public Game findById(UUID id) { return game; }

            @Override
            public void detachAll() { }
        };
        private final MoveRepository moveRepo = new MoveRepository() {
            @Override
            public List<Move> findByGameId(UUID id) { return List.copyOf(moves); }

            @Override
            public long count(String query, Object... params) { return moves.size(); }
        };
        private final GameLifecycleService lifecycle = new GameLifecycleService() {
            @Override
            public GameDto markGameStarted(UUID id) {
                game.status = Game.GameStatus.IN_PROGRESS;
                return null;
            }

            @Override
            public void markGamePaused(UUID id) { game.status = Game.GameStatus.PAUSED; }

            @Override
            public boolean adjudicateIfEligible(UUID id, String fen) { return false; }

            @Override
            public void completeGame(UUID id, ChessService.GameEndInfo info) { game.status = Game.GameStatus.COMPLETED; }
        };
        private final GameMoveService recorder = new GameMoveService() {
            @Override
            public void recordModelMove(UUID id, int number, String color, String san, String fen,
                                        String model, String player, String message, OpenRouterService.LlmResponse response,
                                        int retries, String version, String hash, String expectedFen, ChatMode mode) {
                assertEquals(game.currentFen, expectedFen);
                promptHashes.add(hash);
                save(number, color, san, fen);
            }

            @Override
            public void recordHumanMove(UUID id, int number, String color, String san, String fen,
                                        String player, String message, String expectedFen) {
                assertEquals(game.currentFen, expectedFen);
                save(number, color, san, fen);
            }
        };
        private GameEngineService engine;

        private Fixture() throws Exception {
            game.id = UUID.randomUUID();
            game.currentFen = new Board().getFen();
            game.whiteModelId = "openai/test-model";
            game.whitePlayerName = "Model";
            game.blackPlayerName = "Human";
            game.blackParticipant = new TournamentParticipant();
            game.blackParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
            game.tournament = new Tournament();
            game.tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                String san = List.of("e4", "Nf3", "Bc4", "d3").get(requests.size() - 1);
                String answer = "{ \"move\": \"" + san + "\" }\n";
                var body = JSON.createObjectNode();
                body.putArray("choices").addObject().putObject("message").put("content", answer);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                exchange.close();
            });
            server.start();
            router.objectMapper = JSON;
            router.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            router.apiKey = "test-key";
            engine = buildEngine();
        }

        private GameEngineService buildEngine() {
            // Execute synchronously, keeping the production start/continue/claim paths and real loop intact.
            GameEngineService service = new GameEngineService() {
                @Override
                void spawnGameLoop(UUID id, GameLoop loop, String san, String message) {
                    try { runGameLoop(id, loop, san, message); }
                    finally { releaseLoop(id, loop); }
                }
            };
            service.chessService = chess;
            service.gameRepository = games;
            service.moveRepository = moveRepo;
            service.chatMessageRepository = new ChatMessageRepository() {
                @Override
                public ChatMessage findByPly(UUID id, int number, String color) { return null; }
            };
            service.gameLifecycleService = lifecycle;
            service.gameMoveService = recorder;
            var conversation = new GameConversationService();
            conversation.chessService = chess;
            conversation.promptService = new PromptService();
            conversation.gameRepository = games;
            conversation.moveRepository = moveRepo;
            service.gameConversationService = conversation;
            var turn = new GameTurnService();
            turn.chessService = chess;
            turn.openRouterService = router;
            turn.promptService = conversation.promptService;
            turn.responseParserService = new ResponseParserService();
            turn.responseParserService.objectMapper = JSON;
            turn.moveRepository = moveRepo;
            turn.gameLifecycleService = lifecycle;
            turn.gameMoveService = recorder;
            turn.moveAttemptService = new MoveAttemptService() {
                @Override
                public void record(AttemptRecord record) { }
            };
            turn.gameWebSocket = new GameWebSocket() {
                @Override
                public void broadcastGameStatus(UUID id, String status, String result, String reason, int count,
                                                BigDecimal cost, String color, Instant start, Instant deadline,
                                                String detail, String mode) { }
            };
            service.gameTurnService = turn;
            return service;
        }

        private void save(int number, String color, String san, String fen) {
            Move move = new Move();
            move.moveNumber = number;
            move.color = color;
            move.san = san;
            moves.add(move);
            game.currentFen = fen;
            game.pgn = moves.stream().map(m -> m.san).collect(java.util.stream.Collectors.joining(" "));
        }

        private void human(String san) { engine.submitHumanMove(game.id, san, null, null, null, null); }

        private List<Integer> messageCounts() { return requests.stream().map(r -> r.path("messages").size()).toList(); }
    }
}
