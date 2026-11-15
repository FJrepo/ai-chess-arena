package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import dev.aichessarena.service.OpenRouterService.ProviderOutcome;
import dev.aichessarena.websocket.GameWebSocket;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameTurnServiceTest {

    private static final LlmResponse VALID_MOVE =
            new LlmResponse("{\"move\":\"e4\",\"message\":\"Hi\"}", 10, 6, null, 400, "{}", false, null);

    @Test
    void timedOutModelResponseForfeitsTheTurn() {
        Fixture fixture = new Fixture(new LlmResponse(null, 0, 0, null, 0, null, true, "timeout"));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.isTerminal());
        assertEquals("WHITE", fixture.lifecycle.lastForfeitColor);
        assertEquals(30, fixture.lifecycle.lastTimeoutSeconds);
        assertNull(fixture.lifecycle.pauseDetail);
    }

    @Test
    void successfulTurnRecordsMoveAndReturnsLastMessage() {
        Fixture fixture = new Fixture(VALID_MOVE);
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();

        GameTurnService.TurnResult result = fixture.service.playTurn(request(history, 3, 30));

        assertTrue(result.moveRecorded());
        assertFalse(result.isTerminal());
        assertEquals("e4", result.moveSan());
        assertEquals("Hi", result.message());
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertEquals("Hi", fixture.moves.lastRecordedMessage);
        assertEquals(new Board().getFen(), fixture.moves.lastExpectedFen);
        assertTrue(history.stream().anyMatch(msg -> "assistant".equals(msg.role()) && msg.content().contains("\"move\":\"e4\"")));
    }

    @Test
    void fatalProviderFailurePausesWithoutForfeitingOrRetrying() {
        Fixture fixture = new Fixture(failure(ProviderOutcome.FATAL, 401, "OpenRouter rejected the API key (HTTP 401)"));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertEquals("OpenRouter rejected the API key (HTTP 401)", fixture.lifecycle.pauseDetail);
        assertEquals(1, fixture.openRouter.calls);
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
        assertEquals(0, fixture.sleptMs.size());
    }

    @Test
    void retryableProviderFailuresDoNotUseUpModelAttempts() {
        Fixture fixture = new Fixture(
                failure(ProviderOutcome.RETRYABLE, 429, "OpenRouter rate limit reached (HTTP 429)"),
                failure(ProviderOutcome.RETRYABLE, 503, "OpenRouter server error (HTTP 503)"),
                VALID_MOVE
        );

        // Only one model attempt is allowed: the two provider failures must not consume it.
        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 1, 30));

        assertTrue(result.moveRecorded());
        assertEquals(0, fixture.moves.lastRetryCount);
        assertEquals(3, fixture.openRouter.calls);
        assertEquals(List.of(10L, 20L), fixture.sleptMs);
        assertEquals(2, fixture.websocket.retryReasons.size());
        assertTrue(fixture.websocket.retryReasons.getFirst().contains("provider retry 1 of 3"));
        assertNull(fixture.lifecycle.pauseDetail);
    }

    @Test
    void persistentRetryableFailurePausesAfterProviderRetriesAreExhausted() {
        LlmResponse outage = failure(ProviderOutcome.RETRYABLE, 0, "Could not reach OpenRouter: Connection refused");
        Fixture fixture = new Fixture(outage, outage, outage, outage, outage);

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertEquals("Could not reach OpenRouter: Connection refused", fixture.lifecycle.pauseDetail);
        assertEquals(4, fixture.openRouter.calls);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void providerRetryThatCannotFinishBeforeTheDeadlinePausesInsteadOfForfeiting() {
        Fixture fixture = new Fixture(failure(ProviderOutcome.RETRYABLE, 502, "OpenRouter server error (HTTP 502)"));
        fixture.service.providerRetryBackoffMs = new long[] {60_000L};

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertEquals("OpenRouter server error (HTTP 502)", fixture.lifecycle.pauseDetail);
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void timeoutAfterAnOutageInTheSameTurnPausesInsteadOfForfeiting() {
        Fixture fixture = new Fixture(
                failure(ProviderOutcome.RETRYABLE, 503, "OpenRouter server error (HTTP 503)"),
                new LlmResponse(null, 0, 0, null, 0, null, true, "Request timed out")
        );

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertTrue(fixture.lifecycle.pauseDetail.contains("OpenRouter server error (HTTP 503)"));
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void pauseRequestDuringProviderBackoffStopsTheTurn() {
        Fixture fixture = new Fixture(failure(ProviderOutcome.RETRYABLE, 429, "OpenRouter rate limit reached (HTTP 429)"));
        boolean[] running = {true};
        fixture.service.sleeper = ms -> running[0] = false;

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30, () -> running[0]));

        assertEquals(GameTurnService.Status.STOPPED, result.status());
        assertEquals(1, fixture.openRouter.calls);
        assertNull(fixture.lifecycle.pauseDetail);
    }

    @Test
    void positionConflictWhileRecordingStopsTheTurnWithoutForfeit() {
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.moves.conflict = new GameStateConflictException("Position changed while the model was thinking");

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertEquals(GameTurnService.Status.STOPPED, result.status());
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
    }

    private static LlmResponse failure(ProviderOutcome outcome, int status, String detail) {
        return LlmResponse.providerFailure(outcome, status, detail, 5, null);
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history,
                                                       int maxRetries, int moveTimeoutSeconds) {
        return request(history, maxRetries, moveTimeoutSeconds, () -> true);
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history,
                                                       int maxRetries, int moveTimeoutSeconds,
                                                       java.util.function.BooleanSupplier shouldContinue) {
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
                maxRetries,
                moveTimeoutSeconds,
                "v1",
                "hash-1",
                BigDecimal.ZERO,
                shouldContinue
        );
    }

    private static final class Fixture {
        final GameTurnService service = new GameTurnService();
        final RecordingLifecycleService lifecycle = new RecordingLifecycleService();
        final RecordingGameMoveService moves = new RecordingGameMoveService();
        final RecordingGameWebSocket websocket = new RecordingGameWebSocket();
        final ScriptedOpenRouterService openRouter;
        final List<Long> sleptMs = new ArrayList<>();

        Fixture(LlmResponse... responses) {
            openRouter = new ScriptedOpenRouterService(responses);
            ResponseParserService parser = new ResponseParserService();
            parser.objectMapper = new ObjectMapper();

            service.chessService = new ChessService();
            service.openRouterService = openRouter;
            service.promptService = new PromptService();
            service.responseParserService = parser;
            service.moveRepository = new CountingMoveRepository();
            service.gameWebSocket = websocket;
            service.gameLifecycleService = lifecycle;
            service.gameMoveService = moves;
            service.providerRetryBackoffMs = new long[] {10L, 20L, 40L};
            service.sleeper = sleptMs::add;
        }
    }

    /** Returns the scripted responses in order and repeats the last one once exhausted. */
    private static final class ScriptedOpenRouterService extends OpenRouterService {
        private final Deque<LlmResponse> responses;
        private LlmResponse last;
        private int calls;

        private ScriptedOpenRouterService(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration requestTimeout) {
            calls++;
            if (!responses.isEmpty()) {
                last = responses.removeFirst();
            }
            return last;
        }
    }

    private static final class RecordingLifecycleService extends GameLifecycleService {
        private String lastForfeitColor;
        private Integer lastTimeoutSeconds;
        private boolean invalidMovesForfeited;
        private String pauseDetail;

        @Override
        public void forfeitTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
            lastForfeitColor = forfeitColor;
            lastTimeoutSeconds = moveTimeoutSeconds;
        }

        @Override
        public void forfeitInvalidMoves(UUID gameId, String forfeitColor, int maxRetries) {
            invalidMovesForfeited = true;
        }

        @Override
        public void pauseForProviderFailure(UUID gameId, String detail) {
            pauseDetail = detail;
        }
    }

    private static final class RecordingGameMoveService extends GameMoveService {
        private String lastRecordedSan;
        private String lastRecordedMessage;
        private String lastExpectedFen;
        private int lastRetryCount = -1;
        private GameStateConflictException conflict;

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
                LlmResponse llmResponse,
                int retryCount,
                String promptVersion,
                String promptHash,
                String expectedFen
        ) {
            if (conflict != null) {
                throw conflict;
            }
            lastRecordedSan = san;
            lastRecordedMessage = message;
            lastExpectedFen = expectedFen;
            lastRetryCount = retryCount;
        }
    }

    private static final class CountingMoveRepository extends MoveRepository {
        @Override
        public long count(String query, Object... params) {
            return 0;
        }
    }

    private static final class RecordingGameWebSocket extends GameWebSocket {
        private final List<String> retryReasons = new ArrayList<>();

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

        @Override
        public void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
            retryReasons.add(reason);
        }
    }
}
