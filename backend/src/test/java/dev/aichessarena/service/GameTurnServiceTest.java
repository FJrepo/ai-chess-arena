package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameTurnServiceTest {

    @Test
    void timedOutModelResponseForfeitsTheTurn() {
        RecordingLifecycleService lifecycleService = new RecordingLifecycleService();

        GameTurnService service = new GameTurnService();
        service.chessService = new ChessService();
        service.openRouterService = new StubOpenRouterService(
                new OpenRouterService.LlmResponse(null, 0, 0, null, 0, null, true, "timeout")
        );
        service.promptService = new PromptService();
        service.responseParserService = parser();
        service.moveRepository = new CountingMoveRepository();
        service.gameWebSocket = new RecordingGameWebSocket();
        service.gameLifecycleService = lifecycleService;
        service.gameMoveService = new RecordingGameMoveService();

        GameTurnService.TurnResult result = service.playTurn(request(new ArrayList<>()));

        assertTrue(result.isTerminal());
        assertEquals("WHITE", lifecycleService.lastForfeitColor);
        assertEquals(30, lifecycleService.lastTimeoutSeconds);
    }

    @Test
    void successfulTurnRecordsMoveAndReturnsLastMessage() {
        RecordingLifecycleService lifecycleService = new RecordingLifecycleService();
        RecordingGameMoveService moveService = new RecordingGameMoveService();
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();

        GameTurnService service = new GameTurnService();
        service.chessService = new ChessService();
        service.openRouterService = new StubOpenRouterService(
                new OpenRouterService.LlmResponse("{\"move\":\"e4\",\"message\":\"Hi\"}", 10, 6, null, 400, "{}", false, null)
        );
        service.promptService = new PromptService();
        service.responseParserService = parser();
        service.moveRepository = new CountingMoveRepository();
        service.gameWebSocket = new RecordingGameWebSocket();
        service.gameLifecycleService = lifecycleService;
        service.gameMoveService = moveService;

        GameTurnService.TurnResult result = service.playTurn(request(history));

        assertTrue(result.moveRecorded());
        assertFalse(result.isTerminal());
        assertEquals("e4", result.moveSan());
        assertEquals("Hi", result.message());
        assertEquals("e4", moveService.lastRecordedSan);
        assertEquals("Hi", moveService.lastRecordedMessage);
        assertTrue(history.stream().anyMatch(msg -> "assistant".equals(msg.role()) && msg.content().contains("\"move\":\"e4\"")));
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history) {
        Board board = new Board();
        return new GameTurnService.TurnRequest(
                UUID.randomUUID(),
                null,
                board.getFen(),
                board,
                "WHITE",
                1,
                List.of("e4", "d4", "Nf3"),
                "model-a",
                "Model A",
                "Model B",
                history,
                null,
                null,
                true,
                3,
                30,
                "v1",
                "hash-1",
                BigDecimal.ZERO,
                () -> true
        );
    }

    private static ResponseParserService parser() {
        ResponseParserService parser = new ResponseParserService();
        parser.objectMapper = new ObjectMapper();
        return parser;
    }

    private static final class StubOpenRouterService extends OpenRouterService {
        private final LlmResponse response;

        private StubOpenRouterService(LlmResponse response) {
            this.response = response;
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration requestTimeout) {
            return response;
        }
    }

    private static final class RecordingLifecycleService extends GameLifecycleService {
        private String lastForfeitColor;
        private Integer lastTimeoutSeconds;

        @Override
        public void forfeitTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
            lastForfeitColor = forfeitColor;
            lastTimeoutSeconds = moveTimeoutSeconds;
        }
    }

    private static final class RecordingGameMoveService extends GameMoveService {
        private String lastRecordedSan;
        private String lastRecordedMessage;

        @Override
        public void recordModelMove(
                UUID gameId,
                int moveNumber,
                String color,
                String san,
                String fen,
                String modelId,
                String playerName,
                String message,
                OpenRouterService.LlmResponse llmResponse,
                int retryCount,
                String promptVersion,
                String promptHash
        ) {
            lastRecordedSan = san;
            lastRecordedMessage = message;
        }
    }

    private static final class CountingMoveRepository extends MoveRepository {
        @Override
        public long count(String query, Object... params) {
            return 0;
        }
    }

    private static final class RecordingGameWebSocket extends GameWebSocket {
        @Override
        public void broadcastGameStatus(
                UUID gameId,
                String status,
                String result,
                String resultReason,
                int totalMoves,
                BigDecimal totalCostUsd,
                String activeColor,
                java.time.Instant turnStartedAt,
                java.time.Instant turnDeadlineAt
        ) {
        }
    }
}
