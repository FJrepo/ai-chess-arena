package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.entity.MoveAttempt.Outcome;
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

    private static final int MAX_OUTPUT_TOKENS = 2048;

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
    void positionConflictWhileRecordingStopsTheTurnWithoutForfeitAndKeepsTheDiscardedAnswer() {
        LlmResponse paidAnswer = new LlmResponse("{\"move\":\"e4\"}", 100, 8, 0.0035, 400, "{\"raw\":1}", false, null);
        Fixture fixture = new Fixture(paidAnswer);
        fixture.moves.conflict = new GameStateConflictException("Position changed while the model was thinking");
        // Real attempt service over in-memory repositories: checks the stored row and the cost bookkeeping.
        dev.aichessarena.entity.Game game = new dev.aichessarena.entity.Game();
        game.totalCostUsd = new BigDecimal("0.01");
        List<dev.aichessarena.entity.MoveAttempt> stored = new ArrayList<>();
        MoveAttemptService attempts = new MoveAttemptService();
        attempts.gameRepository = new dev.aichessarena.repository.GameRepository() {
            @Override
            public dev.aichessarena.entity.Game findById(UUID id) {
                return game;
            }

            @Override
            public dev.aichessarena.entity.Game findByIdForUpdate(UUID id) {
                return game;
            }
        };
        attempts.moveAttemptRepository = new dev.aichessarena.repository.MoveAttemptRepository() {
            @Override
            public void persist(dev.aichessarena.entity.MoveAttempt entity) {
                stored.add(entity);
            }
        };
        fixture.service.moveAttemptService = attempts;

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertEquals(GameTurnService.Status.STOPPED, result.status());
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
        assertNull(fixture.moves.lastRecordedSan, "the move itself was not recorded");
        assertEquals(1, stored.size());
        dev.aichessarena.entity.MoveAttempt discarded = stored.getFirst();
        assertEquals(Outcome.DISCARDED, discarded.outcome);
        assertEquals("e4", discarded.proposedMove);
        assertEquals("Position changed while the model was thinking", discarded.errorMessage);
        assertEquals("{\"raw\":1}", discarded.rawResponse);
        // Added exactly once: 0.01 + 0.0035.
        assertEquals(0, new BigDecimal("0.0135").compareTo(game.totalCostUsd), game.totalCostUsd.toPlainString());
    }

    @Test
    void truncatedAnswerWithoutAMovePausesWithAHintInsteadOfForfeiting() {
        Fixture fixture = new Fixture(truncated(null, 1800));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 1, 30));

        assertTrue(result.pausedGame());
        assertTrue(fixture.lifecycle.pauseDetail.contains("2048-token output limit"), fixture.lifecycle.pauseDetail);
        assertTrue(fixture.lifecycle.pauseDetail.contains("1800 of them reasoning"), fixture.lifecycle.pauseDetail);
        assertTrue(fixture.lifecycle.pauseDetail.contains("Max output tokens per answer"));
        assertTrue(fixture.lifecycle.pauseDetail.contains("'Save and resume' in the game view"));
        assertTrue(fixture.lifecycle.pauseDetail.contains("PUT /api/tournaments/{id}"));
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(List.of(Outcome.TRUNCATED), fixture.attempts.outcomes());
        assertNull(fixture.moves.lastRecordedSan);
    }

    @Test
    void truncatedUnparseableAnswerAlsoPauses() {
        Fixture fixture = new Fixture(truncated("{\"move\": \"e", null));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(List.of(Outcome.TRUNCATED), fixture.attempts.outcomes());
        assertFalse(fixture.lifecycle.pauseDetail.contains("reasoning"));
    }

    @Test
    void truncatedAnswerWithALegalMoveIsAccepted() {
        Fixture fixture = new Fixture(truncated("{\"move\":\"e4\",\"message\":\"I was going to say", 50));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        // The parser recovers the move from the cut-off JSON; a usable legal move is played.
        assertTrue(result.moveRecorded(), String.valueOf(result.status()));
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertNull(fixture.lifecycle.pauseDetail);
        assertEquals(List.of(Outcome.ACCEPTED), fixture.attempts.outcomes());
    }

    @Test
    void tournamentOutputTokenLimitIsSentWithEveryCall() {
        Fixture fixture = new Fixture(answer("{\"move\":\"Ke2\"}"), VALID_MOVE);

        fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertEquals(List.of(MAX_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS), fixture.openRouter.maxOutputTokens);
    }

    @Test
    void eachBranchRecordsExactlyOneAttemptWithItsOutcome() {
        Fixture fixture = new Fixture(
                failure(ProviderOutcome.RETRYABLE, 429, "OpenRouter rate limit reached (HTTP 429)"),
                answer(null),
                answer("not json at all"),
                answer("{\"move\":\"Qxh7\"}"),
                VALID_MOVE
        );

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 4, 30));

        assertTrue(result.moveRecorded());
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE, Outcome.EMPTY_RESPONSE, Outcome.PARSE_FAILURE,
                Outcome.ILLEGAL_MOVE, Outcome.ACCEPTED), fixture.attempts.outcomes());
        assertEquals(List.of(1, 1, 2, 3, 4),
                fixture.attempts.recorded.stream().map(MoveAttemptService.AttemptRecord::attemptNumber).toList());
        MoveAttemptService.AttemptRecord illegal = fixture.attempts.recorded.get(3);
        assertEquals("Qxh7", illegal.proposedMove());
        assertEquals("WHITE", illegal.color());
        assertEquals(1, illegal.moveNumber());
        assertEquals("model-a", illegal.modelId());
        assertEquals("e4", fixture.attempts.recorded.get(4).proposedMove());
        assertEquals(java.util.Arrays.asList(Outcome.EMPTY_RESPONSE.name(), Outcome.PARSE_FAILURE.name(),
                Outcome.ILLEGAL_MOVE.name()), fixture.websocket.retryOutcomes);
        assertEquals(java.util.Arrays.asList(null, null, "Qxh7"), fixture.websocket.proposedMoves);
    }

    @Test
    void timeoutAndFatalProviderFailureAreRecorded() {
        Fixture timeout = new Fixture(new LlmResponse(null, 0, 0, null, 0, null, true, "timeout"));
        timeout.service.playTurn(request(new ArrayList<>(), 3, 30));
        assertEquals(List.of(Outcome.TIMEOUT), timeout.attempts.outcomes());

        Fixture fatal = new Fixture(failure(ProviderOutcome.FATAL, 401, "OpenRouter rejected the API key (HTTP 401)"));
        fatal.service.playTurn(request(new ArrayList<>(), 3, 30));
        assertEquals(List.of(Outcome.PROVIDER_FATAL), fatal.attempts.outcomes());
        assertEquals("OpenRouter rejected the API key (HTTP 401)", fatal.attempts.recorded.getFirst().errorMessage());
    }

    @Test
    void exhaustedIllegalMovesForfeitAndRecordEveryAttempt() {
        Fixture fixture = new Fixture(answer("{\"move\":\"Ke2\"}"));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 2, 30));

        assertTrue(result.isTerminal());
        assertTrue(fixture.lifecycle.invalidMovesForfeited);
        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.ILLEGAL_MOVE), fixture.attempts.outcomes());
    }

    @Test
    void attemptRecordingFailureDoesNotInterruptTheTurn() {
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.service.moveAttemptService = new MoveAttemptService() {
            @Override
            public void record(AttemptRecord attempt) {
                throw new IllegalStateException("database unavailable");
            }
        };

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.moveRecorded());
    }

    @Test
    void disabledTrashTalkDropsTheModelsMessage() {
        Fixture fixture = new Fixture(VALID_MOVE);

        GameTurnService.TurnResult result =
                fixture.service.playTurn(request(new ArrayList<>(), 3, 30, () -> true, false));

        assertTrue(result.moveRecorded());
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertNull(fixture.moves.lastRecordedMessage);
        assertNull(result.message());
    }

    @Test
    void onlyTheRecentConversationIsSentToTheModel() {
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();
        history.add(new OpenRouterService.ChatMsg("system", "rules"));
        for (int i = 0; i < 20; i++) {
            history.add(new OpenRouterService.ChatMsg("user", "turn " + i));
            history.add(new OpenRouterService.ChatMsg("assistant", "answer " + i));
        }
        Fixture fixture = new Fixture(VALID_MOVE);

        fixture.service.playTurn(request(history, 3, 30));

        List<OpenRouterService.ChatMsg> sent = fixture.openRouter.sentMessages.getFirst();
        assertEquals(1 + ConversationWindow.DEFAULT_PRIOR_MESSAGES + 1, sent.size());
        assertEquals("rules", sent.getFirst().content());
        assertEquals("turn 17", sent.get(1).content());
        assertEquals(43, history.size(), "the full history is kept locally");
    }

    private static LlmResponse answer(String content) {
        return new LlmResponse(content, 10, 6, 0.001, 400, "{}", false, null);
    }

    private static LlmResponse truncated(String content, Integer reasoningTokens) {
        return new LlmResponse(content, 10, MAX_OUTPUT_TOKENS, 0.002, 400, "{}", false, null,
                ProviderOutcome.OK, 200, "length", reasoningTokens);
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
        return request(history, maxRetries, moveTimeoutSeconds, shouldContinue, true);
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history,
                                                       int maxRetries, int moveTimeoutSeconds,
                                                       java.util.function.BooleanSupplier shouldContinue,
                                                       boolean trashTalkEnabled) {
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
                shouldContinue,
                MAX_OUTPUT_TOKENS,
                trashTalkEnabled
        );
    }

    private static final class Fixture {
        final GameTurnService service = new GameTurnService();
        final RecordingLifecycleService lifecycle = new RecordingLifecycleService();
        final RecordingGameMoveService moves = new RecordingGameMoveService();
        final RecordingGameWebSocket websocket = new RecordingGameWebSocket();
        final RecordingMoveAttemptService attempts = new RecordingMoveAttemptService();
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
            service.moveAttemptService = attempts;
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

        private final List<Integer> maxOutputTokens = new ArrayList<>();
        private final List<List<ChatMsg>> sentMessages = new ArrayList<>();

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration requestTimeout) {
            throw new AssertionError("The game must pass the tournament's output-token limit");
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration requestTimeout,
                                int maxOutputTokens) {
            calls++;
            this.maxOutputTokens.add(maxOutputTokens);
            sentMessages.add(List.copyOf(messages));
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

        @Override
        public void pauseWithReason(UUID gameId, String detail) {
            pauseDetail = detail;
        }
    }

    private static final class RecordingMoveAttemptService extends MoveAttemptService {
        private final List<AttemptRecord> recorded = new ArrayList<>();

        @Override
        public void record(AttemptRecord attempt) {
            recorded.add(attempt);
        }

        List<Outcome> outcomes() {
            return recorded.stream().map(AttemptRecord::outcome).toList();
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

        private final List<String> retryOutcomes = new ArrayList<>();
        private final List<String> proposedMoves = new ArrayList<>();

        @Override
        public void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
            retryReasons.add(reason);
        }

        @Override
        public void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason,
                                   String outcome, String proposedMove) {
            retryReasons.add(reason);
            retryOutcomes.add(outcome);
            proposedMoves.add(proposedMove);
        }
    }
}
