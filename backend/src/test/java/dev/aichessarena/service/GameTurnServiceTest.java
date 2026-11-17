package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameTurnServiceTest {

    private static final int MAX_OUTPUT_TOKENS = 2048;

    private static final LlmResponse VALID_MOVE =
            new LlmResponse("{\"move\":\"e4\",\"message\":\"Hi\"}", 10, 6, null, 400, "{}", false, null);

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void legacyAttemptLimitStillGivesTheModelOneAttempt(int limit) {
        Fixture fixture = new Fixture(VALID_MOVE);

        assertTrue(fixture.service.playTurn(request(new ArrayList<>(), limit, 30)).moveRecorded());
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(List.of(Outcome.ACCEPTED), fixture.attempts.outcomes());
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hintsSettingAppliesToEveryModelRequestAndLegalityIsStillEnforced(boolean hints) {
        Fixture fixture = new Fixture(answer(null), answer("%%%"), answer("{\"resign\":true}"),
                answer("{\"move\":\"Ke2\"}"), VALID_MOVE);
        Board board = new Board();
        List<String> legalMoves = hints ? List.of("a3", "h3") : null;
        var request = new GameTurnService.TurnRequest(UUID.randomUUID(), null, board.getFen(), board, "WHITE", 1,
                legalMoves, "model-a", "Model A", "Model B", new ArrayList<>(), null, null, true, 5, 30,
                "v2", "hash-1", BigDecimal.ZERO, () -> true, MAX_OUTPUT_TOKENS, ChatMode.OPTIONAL, ChatMode.OPTIONAL);

        assertTrue(fixture.service.playTurn(request).moveRecorded());
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertEquals(List.of(Outcome.EMPTY_RESPONSE, Outcome.PARSE_FAILURE, Outcome.RESIGN_NOT_ALLOWED,
                Outcome.ILLEGAL_MOVE, Outcome.ACCEPTED), fixture.attempts.outcomes());
        assertEquals(5, fixture.openRouter.sentMessages.size());
        for (var messages : fixture.openRouter.sentMessages) {
            assertEquals("user", messages.getLast().role());
            assertTrue(messages.getLast().content().contains(request.fen()));
            assertEquals(hints, messages.getLast().content().contains("Legal moves: a3, h3"));
            assertEquals(hints, messages.stream().anyMatch(message -> message.content().contains("Legal moves:")));
        }
    }

    @Test
    void legacyAttemptLimitForfeitsOnlyAfterOneInvalidAnswer() {
        Fixture fixture = new Fixture(answer("{\"move\":\"Ke2\"}"), VALID_MOVE);

        assertTrue(fixture.service.playTurn(request(new ArrayList<>(), 0, 30)).isTerminal());
        assertEquals(List.of(Outcome.ILLEGAL_MOVE), fixture.attempts.outcomes());
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(1, fixture.lifecycle.forfeitAttempts);
    }

    @Test
    void legacyLimitOfTwentyOneStopsAfterTwentyInvalidAnswers() {
        Fixture fixture = new Fixture(answer("{\"move\":\"Ke2\"}"));

        assertTrue(fixture.service.playTurn(request(new ArrayList<>(), 21, 30)).isTerminal());
        assertEquals(20, fixture.openRouter.calls);
        assertEquals(20, fixture.attempts.recorded.size());
        assertEquals(20, fixture.lifecycle.forfeitAttempts);
    }

    @Test
    void plainTextProposalKeepsItsMateSuffixInTheAttempt() {
        Fixture fixture = new Fixture(answer("My move is e4#."));

        assertTrue(fixture.service.playTurn(request(new ArrayList<>(), 3, 30)).moveRecorded());
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertEquals("e4#", fixture.attempts.recorded.getFirst().proposedMove());
    }

    @Test
    void aTimingCommitCannotUseUpTheModelsFirstAnswerBudget() {
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.service.turnTiming = new GameTurnTiming() {
            @Override
            TurnTimes record(GameTurnService.TurnRequest request) {
                TurnTimes stored = TurnTimes.start(request.moveTimeoutSeconds());
                fixture.openRouter.clockNanos[0] += Duration.ofSeconds(12).toNanos();
                return stored;
            }
        };

        assertTrue(fixture.service.playTurn(request(new ArrayList<>(), 3, 10)).moveRecorded());
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(List.of(10000L), fixture.openRouter.remainingMsAtCall);
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void timedOutModelResponseForfeitsTheTurn() {
        Fixture fixture = new Fixture(new LlmResponse(null, 0, 0, null, 0, null, true, "timeout"));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.isTerminal());
        assertEquals("WHITE", fixture.lifecycle.lastForfeitColor);
        assertEquals(30, fixture.lifecycle.lastTimeoutSeconds);
        assertNull(fixture.lifecycle.pauseDetail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"e4", "e4#", "e4+"})
    void successfulTurnRecordsCanonicalSanAndKeepsTheProposedMove(String proposed) {
        String content = "{\"move\":\"" + proposed + "\",\"message\":\"Hi\"}";
        Fixture fixture = new Fixture(answer(content));
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();

        GameTurnService.TurnResult result = fixture.service.playTurn(request(history, 3, 30));

        assertTrue(result.moveRecorded());
        assertFalse(result.isTerminal());
        assertEquals("e4", result.moveSan());
        assertEquals("Hi", result.message());
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertEquals("Hi", fixture.moves.lastRecordedMessage);
        assertEquals(new Board().getFen(), fixture.moves.lastExpectedFen);
        assertTrue(history.stream().anyMatch(msg -> "assistant".equals(msg.role()) && content.equals(msg.content())));
        assertEquals(proposed, fixture.attempts.recorded.getFirst().proposedMove());
    }

    @Test
    void missingKeyPausesWithoutForfeitOrNetworkCall() {
        OpenRouterService router = new OpenRouterService();
        LlmResponse missingKey = router.chat("fixture/model", List.of());
        Fixture fixture = new Fixture(missingKey);
        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));
        assertTrue(result.pausedGame());
        assertTrue(fixture.lifecycle.pauseDetail.contains("Connect OpenRouter"));
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
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
        // The backoff waits are taken from the same turn deadline.
        assertEquals(List.of(30_000L, 29_990L, 29_970L), fixture.openRouter.remainingMsAtCall);
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
                VALID_MOVE
        );
        fixture.openRouter.callDurationsMs.addAll(List.of(29_000L, 1_000L));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertTrue(fixture.lifecycle.pauseDetail.contains("OpenRouter server error (HTTP 503)"));
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE, Outcome.PROVIDER_RETRYABLE), fixture.attempts.outcomes());
        assertEquals(fixture.lifecycle.pauseDetail, fixture.attempts.recorded.getLast().errorMessage());
        assertTrue(fixture.attempts.outcomes().stream().noneMatch(Outcome::isModelFailure));
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
        attempts.gameWebSocket = new GameWebSocket() {
            @Override
            public void broadcastAttemptsUpdated(UUID gameId) {
                // No viewer in this turn unit test.
            }
        };
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
        assertTrue(fixture.lifecycle.pauseDetail.endsWith(
                "answer was cut off at the 2048-token output limit (1800 of them reasoning). Raise the limit or "
                        + "lower the reasoning effort and resume."),
                fixture.lifecycle.pauseDetail);
        assertFalse(fixture.lifecycle.pauseDetail.endsWith(".."), fixture.lifecycle.pauseDetail);
        // User-facing: no API route, field name or pointer to the page the user is already on.
        assertFalse(fixture.lifecycle.pauseDetail.contains("/api/"), fixture.lifecycle.pauseDetail);
        assertFalse(fixture.lifecycle.pauseDetail.contains("maxOutputTokens"), fixture.lifecycle.pauseDetail);
        assertFalse(fixture.lifecycle.pauseDetail.contains("game view"), fixture.lifecycle.pauseDetail);
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(List.of(Outcome.TRUNCATED), fixture.attempts.outcomes());
        assertNull(fixture.moves.lastRecordedSan);
    }

    @Test
    void clampedTruncationReportsTheModelMaximumAndSuggestsLessReasoning() {
        assertModelMaximumPause(16384, true);
    }

    @Test
    void truncationAtAnEqualTournamentAndModelCapAlsoCannotBeFixedByRaisingTheLimit() {
        assertModelMaximumPause(8192, true);
    }

    @Test
    void modelWithoutEffortSelectionExplainsThatItCannotFinishWithinItsMaximum() {
        assertModelMaximumPause(16384, false);
    }

    private void assertModelMaximumPause(int configured, boolean offersEffort) {
        Fixture fixture = new Fixture(truncated(null, 8000).withOutputTokenLimits(8192, 8192));
        fixture.openRouter.offersEffort = offersEffort;
        var r = request(new ArrayList<>(), 1, 30);
        var request = new GameTurnService.TurnRequest(r.gameId(), r.pgn(), r.fen(), r.board(), r.sideToMove(),
                r.moveNumber(), r.legalMoves(), r.modelId(), r.playerName(), r.opponentName(), r.history(),
                r.lastMoveSan(), r.lastMessage(), r.isFirstMove(), r.maxRetries(), r.moveTimeoutSeconds(),
                r.promptVersion(), r.promptHash(), r.totalCostUsd(), r.shouldContinue(), configured,
                r.chatMode(), r.tournamentChatMode(), r.resignationAllowed(), r.reasoningEffort());

        assertTrue(fixture.service.playTurn(request).pausedGame());

        String detail = fixture.lifecycle.pauseDetail;
        assertTrue(detail.contains("cut off at the model's maximum of 8,192 output tokens"), detail);
        assertTrue(detail.contains("Raising the tournament limit cannot help."), detail);
        assertTrue(detail.endsWith(offersEffort ? "Lower the reasoning effort and resume."
                : "This model cannot finish within its own output maximum."), detail);
        var recorded = fixture.attempts.recorded.getFirst();
        assertEquals(Outcome.TRUNCATED, recorded.outcome());
        assertEquals(detail, recorded.errorMessage());
        assertEquals(8192, recorded.response().effectiveMaxOutputTokens());
        assertEquals(8192, recorded.response().modelMaxCompletionTokens());
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void truncationBelowTheModelMaximumKeepsTheExistingRecoveryHint() {
        Fixture fixture = new Fixture(truncated(null, 0).withOutputTokenLimits(2048, 8192));

        fixture.service.playTurn(request(new ArrayList<>(), 1, 30));

        assertTrue(fixture.lifecycle.pauseDetail.endsWith(
                "answer was cut off at the 2048-token output limit. Raise the limit and resume."),
                fixture.lifecycle.pauseDetail);
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
    void chatOffDropsTheModelsMessage() {
        Fixture fixture = new Fixture(VALID_MOVE);

        GameTurnService.TurnResult result =
                fixture.service.playTurn(request(new ArrayList<>(), 3, 30, () -> true, ChatMode.OFF));

        assertTrue(result.moveRecorded());
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertNull(fixture.moves.lastRecordedMessage);
        assertEquals(ChatMode.OFF, fixture.moves.lastChatMode);
        assertNull(result.message());
    }

    @Test
    void optionalChatKeepsTheMessageAndRecordsTheMode() {
        Fixture fixture = new Fixture(VALID_MOVE);

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertEquals("Hi", fixture.moves.lastRecordedMessage);
        assertEquals(ChatMode.OPTIONAL, fixture.moves.lastChatMode);
        assertEquals("Hi", result.message());
    }

    @Test
    void mandatoryChatWithoutAMessageAcceptsTheMoveOnTheFirstAttemptAndRecordsItAsSilent() {
        for (String answer : new String[] {
                "{\"move\":\"e4\"}", "{\"move\":\"e4\",\"message\":\"\"}", "{\"move\":\"e4\",\"message\":\"   \"}",
                "{\"move\":\"e4\",\"message\":null}"}) {
            Fixture fixture = new Fixture(answer(answer));

            GameTurnService.TurnResult result =
                    fixture.service.playTurn(request(new ArrayList<>(), 3, 30, () -> true, ChatMode.MANDATORY));

            assertTrue(result.moveRecorded(), answer);
            assertEquals("e4", fixture.moves.lastRecordedSan);
            assertEquals(0, fixture.moves.lastRetryCount, "no retry for a missing message: " + answer);
            assertNull(fixture.moves.lastRecordedMessage, answer);
            assertEquals(ChatMode.MANDATORY, fixture.moves.lastChatMode);
            assertEquals(List.of(Outcome.ACCEPTED), fixture.attempts.outcomes());
            assertEquals(1, fixture.openRouter.sentMessages.size(), "one model call only");
            assertTrue(fixture.websocket.retryReasons.isEmpty());
            assertFalse(fixture.lifecycle.invalidMovesForfeited);
        }
    }

    @Test
    void mandatoryChatAsksForAMessageInTheTurnPrompt() {
        Fixture fixture = new Fixture(VALID_MOVE);

        fixture.service.playTurn(request(new ArrayList<>(), 3, 30, () -> true, ChatMode.MANDATORY));

        String turnPrompt = fixture.openRouter.sentMessages.getFirst().getLast().content();
        assertTrue(turnPrompt.endsWith(PromptService.REQUIRED_MESSAGE_ANSWER_LINE));
        assertEquals("Hi", fixture.moves.lastRecordedMessage);
    }

    @Test
    void theTurnStartUpdateCarriesTheConfiguredTournamentModeNotTheSidesEffectiveOne() {
        // The request's tournament mode is MANDATORY; this side has a legacy template, so its effective mode is
        // OPTIONAL. The live label shows the configured mode, the move records the effective one.
        Fixture fixture = new Fixture(VALID_MOVE);

        fixture.service.playTurn(request(new ArrayList<>(), 3, 30, () -> true, ChatMode.OPTIONAL));

        assertTrue(fixture.websocket.turnTimingBroadcast);
        assertEquals("MANDATORY", fixture.websocket.lastTurnChatMode);
        assertEquals(ChatMode.OPTIONAL, fixture.moves.lastChatMode);
    }

    @Test
    void anOverfullConversationReanchorsBeforeTheCurrentTurn() {
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();
        history.add(new OpenRouterService.ChatMsg("system", "rules"));
        for (int i = 0; i < 20; i++) {
            history.add(new OpenRouterService.ChatMsg("user", "turn " + i, true));
            history.add(new OpenRouterService.ChatMsg("assistant", "answer " + i));
        }
        Fixture fixture = new Fixture(VALID_MOVE);

        fixture.service.playTurn(request(history, 3, 30));

        List<OpenRouterService.ChatMsg> sent = fixture.openRouter.sentMessages.getFirst();
        assertEquals(1 + ConversationWindow.DEFAULT_RETAINED_MESSAGES + 1, sent.size());
        assertEquals("rules", sent.getFirst().content());
        assertEquals("turn 18", sent.get(1).content());
        assertEquals(7, history.size(), "only the anchored chunk and accepted answer are kept locally");
    }

    @Test
    void disablingCachingRestoresTheSixPriorMessageSlidingWindow() {
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();
        history.add(new OpenRouterService.ChatMsg("system", "rules"));
        for (int i = 0; i < 20; i++) {
            history.add(new OpenRouterService.ChatMsg("user", "turn " + i, true));
            history.add(new OpenRouterService.ChatMsg("assistant", "answer " + i));
        }
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.service.promptCaching = false;

        fixture.service.playTurn(request(history, 3, 30));

        List<OpenRouterService.ChatMsg> sent = fixture.openRouter.sentMessages.getFirst();
        assertEquals(1 + ConversationWindow.LEGACY_PRIOR_MESSAGES + 1, sent.size());
        assertEquals("turn 17", sent.get(1).content());
        assertEquals(9, history.size(), "the retained history is bounded as well as the outbound request");
    }

    @Test
    void retriesAndTheNextTurnOnlyAppendToTheOriginalPrefix() {
        Fixture fixture = new Fixture(answer("not JSON"), answer("{\"move\":\"Ke2\"}"), VALID_MOVE);
        fixture.service.maxPriorMessages = 8;
        fixture.service.retainedMessages = 2;
        List<OpenRouterService.ChatMsg> history = new ArrayList<>(List.of(
                new OpenRouterService.ChatMsg("system", "fixed rules")));

        fixture.service.playTurn(request(history, 3, 30));
        List<OpenRouterService.ChatMsg> first = fixture.openRouter.sentMessages.get(0);
        List<OpenRouterService.ChatMsg> retry = fixture.openRouter.sentMessages.get(1);
        List<OpenRouterService.ChatMsg> lastRetry = fixture.openRouter.sentMessages.get(2);
        assertEquals(first, retry.subList(0, first.size()));
        assertEquals(retry, lastRetry.subList(0, retry.size()));
        assertTrue(lastRetry.get(1).startsTurn());
        assertFalse(lastRetry.get(3).startsTurn(), "a retry must not move the cache boundary");

        fixture.service.playTurn(request(history, 3, 30));
        List<OpenRouterService.ChatMsg> next = fixture.openRouter.sentMessages.get(3);
        assertEquals(lastRetry, next.subList(0, lastRetry.size()));
        fixture.service.playTurn(request(history, 3, 30));
        List<OpenRouterService.ChatMsg> atLimit = fixture.openRouter.sentMessages.get(4);
        assertEquals(10, atLimit.size(), "system + eight prior messages + current turn");
        fixture.service.playTurn(request(history, 3, 30));
        assertEquals(4, fixture.openRouter.sentMessages.get(5).size(), "custom retained budget is applied");
    }

    @Test
    void slowModelWithAMoveTimeLimitForfeitsOnTime() {
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.openRouter.modelAnswerMs = 45_000L;

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.isTerminal());
        assertEquals("WHITE", fixture.lifecycle.lastForfeitColor);
        assertEquals(30, fixture.lifecycle.lastTimeoutSeconds);
        assertNull(fixture.moves.lastRecordedSan);
        assertEquals(List.of(Outcome.TIMEOUT), fixture.attempts.outcomes());
        // The whole remaining turn time is the deadline for the complete answer.
        assertEquals(List.of(30_000L), fixture.openRouter.remainingMsAtCall);
        assertEquals(Duration.ofSeconds(30),
                Duration.between(fixture.websocket.lastTurnStartedAt, fixture.websocket.lastTurnDeadlineAt));
    }

    @Test
    void aRetryNearExpiryGetsOnlyTheTimeLeftOfTheSameTurnDeadline() {
        // The first answer is illegal and arrives 20 ms before the deadline; the retry must not get extra time.
        Fixture fixture = new Fixture(answer("{\"move\":\"Ke2\"}"), VALID_MOVE);
        fixture.openRouter.callDurationsMs.add(29_980L);
        fixture.openRouter.callDurationsMs.add(50L);

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertEquals(java.util.Arrays.asList(30_000L, 20L), fixture.openRouter.remainingMsAtCall);
        assertEquals(fixture.openRouter.answerDeadlines.get(0), fixture.openRouter.answerDeadlines.get(1),
                "every attempt shares the one turn deadline");
        assertTrue(result.isTerminal());
        assertEquals(30, fixture.lifecycle.lastTimeoutSeconds);
        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.TIMEOUT), fixture.attempts.outcomes());
    }

    @Test
    void aProviderBackoffThatRunsPastTheDeadlinePausesWithoutAnotherRequest() {
        // 29 s pass in a failing request; the 500 ms backoff fits in the remaining second but takes twice as long
        // (a busy host), so the turn deadline passes while waiting.
        Fixture fixture = new Fixture(failure(ProviderOutcome.RETRYABLE, 503, "OpenRouter server error (HTTP 503)"),
                VALID_MOVE);
        fixture.openRouter.callDurationsMs.add(29_000L);
        fixture.service.providerRetryBackoffMs = new long[] {500L};
        fixture.service.sleeper = ms -> {
            fixture.sleptMs.add(ms);
            fixture.openRouter.clockNanos[0] += 2 * ms * 1_000_000L;
        };

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertEquals("OpenRouter server error (HTTP 503)", fixture.lifecycle.pauseDetail);
        assertEquals(500L, fixture.sleptMs.stream().mapToLong(Long::longValue).sum());
        assertEquals(1, fixture.openRouter.calls);
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void aProviderBackoffLongerThanTheTimeLeftPausesWithoutWaiting() {
        Fixture fixture = new Fixture(failure(ProviderOutcome.RETRYABLE, 503, "OpenRouter server error (HTTP 503)"),
                VALID_MOVE);
        fixture.openRouter.callDurationsMs.add(29_800L);
        fixture.service.providerRetryBackoffMs = new long[] {500L};

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertEquals(List.of(), fixture.sleptMs);
        assertEquals(1, fixture.openRouter.calls);
    }

    @Test
    void aDeadlinePassedDuringAnAttemptForfeitsWithoutSendingAnotherRequest() {
        Fixture fixture = new Fixture(answer("{\"move\":\"Ke2\"}"), VALID_MOVE);
        // The illegal answer arrives exactly at the deadline: no time is left for a retry.
        fixture.openRouter.callDurationsMs.add(30_000L);

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.isTerminal());
        assertEquals("WHITE", fixture.lifecycle.lastForfeitColor);
        assertEquals(1, fixture.openRouter.calls);
        assertEquals(List.of(Outcome.ILLEGAL_MOVE), fixture.attempts.outcomes());
        assertNull(fixture.moves.lastRecordedSan);
    }

    @Test
    void withoutAMoveTimeLimitASlowModelIsNotForfeitedAndNoDeadlineIsSent() {
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.openRouter.modelAnswerMs = 45_000L;

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, null));

        assertTrue(result.moveRecorded(), String.valueOf(result.status()));
        assertEquals("e4", fixture.moves.lastRecordedSan);
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertNull(fixture.lifecycle.pauseDetail);
        assertEquals(java.util.Collections.singletonList(null), fixture.openRouter.answerDeadlines);
        assertTrue(fixture.websocket.turnTimingBroadcast);
        assertNull(fixture.websocket.lastTurnDeadlineAt);
    }

    @Test
    void slowModelAfterAProviderFailureInTheSameTurnPausesInsteadOfForfeiting() {
        Fixture fixture = new Fixture(
                failure(ProviderOutcome.RETRYABLE, 503, "OpenRouter server error (HTTP 503)"),
                VALID_MOVE
        );
        fixture.openRouter.modelAnswerMs = 45_000L;

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertTrue(result.pausedGame());
        assertTrue(fixture.lifecycle.pauseDetail.contains("OpenRouter server error (HTTP 503)"),
                fixture.lifecycle.pauseDetail);
        assertNull(fixture.lifecycle.lastForfeitColor);
    }

    @Test
    void withoutAMoveTimeLimitProviderRetriesAreNotCutShortByADeadline() {
        Fixture fixture = new Fixture(
                failure(ProviderOutcome.RETRYABLE, 0, "OpenRouter request did not finish within the safety limit"),
                VALID_MOVE
        );
        fixture.service.providerRetryBackoffMs = new long[] {60_000L};

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, null));

        assertTrue(result.moveRecorded(), String.valueOf(result.status()));
        assertEquals(60_000L, fixture.sleptMs.stream().mapToLong(Long::longValue).sum());
        assertNull(fixture.lifecycle.pauseDetail);
    }

    @Test
    void withoutAMoveTimeLimitARepeatedSafetyCapPausesInsteadOfForfeiting() {
        LlmResponse hung = failure(ProviderOutcome.RETRYABLE, 0,
                "OpenRouter request did not finish within the safety limit of 10 min");
        Fixture fixture = new Fixture(hung, hung, hung, hung);

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, null));

        assertTrue(result.pausedGame());
        assertEquals("OpenRouter request did not finish within the safety limit of 10 min",
                fixture.lifecycle.pauseDetail);
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertEquals(4, fixture.openRouter.calls);
    }

    @Test
    void withoutAMoveTimeLimitAnUnexpectedTimeoutPausesInsteadOfForfeiting() {
        Fixture fixture = new Fixture(new LlmResponse(null, 0, 0, null, 0, null, true, "timeout"));

        GameTurnService.TurnResult result = fixture.service.playTurn(request(new ArrayList<>(), 3, null));

        assertTrue(result.pausedGame());
        assertNull(fixture.lifecycle.lastForfeitColor);
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE), fixture.attempts.outcomes());
    }

    @Test
    void anAllowedResignationEndsTheGameWithLastWordsAndIgnoresAMoveSentAlong() {
        LlmResponse resigning = answer("{\"resign\":true,\"move\":\"Nf6\",\"message\":\"Well played.\"}");
        Fixture fixture = new Fixture(resigning);
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();

        GameTurnService.TurnRequest request = fixture.resignationRequest(history, 3, 10, true, ChatMode.OPTIONAL);
        GameTurnService.TurnResult result = fixture.service.playTurn(request);

        assertTrue(result.isTerminal());
        assertNull(fixture.moves.lastRecordedSan, "the move sent along is ignored");
        GameLifecycleService.Resignation resignation = fixture.lifecycle.resignation;
        assertEquals("BLACK", resignation.color());
        assertEquals(10, resignation.moveNumber());
        assertEquals("model-b", resignation.modelId());
        assertEquals("Model B", resignation.playerName());
        assertEquals("Well played.", resignation.message());
        assertEquals(0, new BigDecimal("0.001").compareTo(resignation.costUsd()));
        assertEquals(request.fen(), resignation.expectedFen());
        assertEquals(List.of(Outcome.RESIGNED), fixture.attempts.outcomes());
        MoveAttemptService.AttemptRecord recorded = fixture.attempts.recorded.getFirst();
        assertEquals("Nf6", recorded.proposedMove(), "the ignored move is kept for the record");
        assertEquals(resigning, recorded.response(), "tokens and cost are recorded with the attempt");
        assertEquals(1, fixture.openRouter.calls);
        assertTrue(history.getLast().content().contains("\"resign\":true"));
        // The turn prompt told the model it may resign.
        assertTrue(fixture.openRouter.sentMessages.getFirst().getLast().content()
                .contains(PromptService.MAY_RESIGN_LINE));
    }

    @Test
    void lastWordsAreDroppedWithChatOff() {
        Fixture fixture = new Fixture(answer("{\"resign\":true,\"message\":\"Bye.\"}"));

        fixture.service.playTurn(fixture.resignationRequest(new ArrayList<>(), 3, 12, true, ChatMode.OFF));

        assertNull(fixture.lifecycle.resignation.message());
        assertEquals(List.of(Outcome.RESIGNED), fixture.attempts.outcomes());
    }

    @Test
    void aResignationBeforeMoveTenIsRetriedLikeAnInvalidMove() {
        Fixture fixture = new Fixture(answer("{\"resign\":true,\"message\":\"I give up\"}"), answer("{\"move\":\"Nf6\"}"));
        List<OpenRouterService.ChatMsg> history = new ArrayList<>();

        GameTurnService.TurnResult result = fixture.service.playTurn(
                fixture.resignationRequest(history, 3, 9, true, ChatMode.OPTIONAL));

        assertTrue(result.moveRecorded());
        assertNull(fixture.lifecycle.resignation);
        assertEquals("Nf6", fixture.moves.lastRecordedSan);
        assertEquals(1, fixture.moves.lastRetryCount, "the rejected resignation counts as a retry");
        assertNull(fixture.moves.lastRecordedMessage, "the message of a rejected answer is not kept");
        assertEquals(List.of(Outcome.RESIGN_NOT_ALLOWED, Outcome.ACCEPTED), fixture.attempts.outcomes());
        assertEquals("Resigning is not allowed before your own move 10 (book moves do not count); send a legal move.",
                fixture.attempts.recorded.getFirst().errorMessage());
        assertEquals(List.of("RESIGN_NOT_ALLOWED"), fixture.websocket.retryOutcomes);
        String retryPrompt = fixture.openRouter.sentMessages.get(1).getLast().content();
        assertTrue(retryPrompt.contains("Resigning is not allowed before your own move 10 (book moves do not count); send a legal move."), retryPrompt);
        assertTrue(retryPrompt.contains("Attempt 2 of 3"), retryPrompt);
        assertFalse(fixture.openRouter.sentMessages.getFirst().getLast().content().contains("You may resign"));
    }

    @Test
    void aResignationThatIsNotOfferedIsRetriedAndForfeitsWhenRepeated() {
        Fixture fixture = new Fixture(answer("{\"resign\":true}"));

        GameTurnService.TurnResult result = fixture.service.playTurn(
                fixture.resignationRequest(new ArrayList<>(), 2, 30, false, ChatMode.MANDATORY));

        assertTrue(result.isTerminal());
        assertNull(fixture.lifecycle.resignation);
        assertTrue(fixture.lifecycle.invalidMovesForfeited, "repeated attempts use up the attempts like illegal moves");
        assertEquals(List.of(Outcome.RESIGN_NOT_ALLOWED, Outcome.RESIGN_NOT_ALLOWED), fixture.attempts.outcomes());
        assertEquals("Resigning is not allowed in this game; send a legal move.",
                fixture.attempts.recorded.getFirst().errorMessage());
        assertFalse(fixture.openRouter.sentMessages.getFirst().getLast().content().contains("resign"));
    }

    @Test
    void aLegalMoveSentWithAResignationThatIsNotAllowedIsPlayed() {
        Fixture fixture = new Fixture(answer("{\"resign\":true,\"move\":\"Bc5\",\"message\":\"Just kidding\"}"));

        GameTurnService.TurnResult result = fixture.service.playTurn(
                fixture.resignationRequest(new ArrayList<>(), 3, 5, true, ChatMode.OPTIONAL));

        assertTrue(result.moveRecorded());
        assertEquals("Bc5", fixture.moves.lastRecordedSan);
        assertEquals("Just kidding", fixture.moves.lastRecordedMessage);
        assertEquals(0, fixture.moves.lastRetryCount);
        assertNull(fixture.lifecycle.resignation);
        assertEquals(List.of(Outcome.ACCEPTED), fixture.attempts.outcomes());
    }

    @Test
    void anIllegalMoveSentWithAResignationThatIsNotAllowedIsAnIllegalMove() {
        Fixture fixture = new Fixture(answer("{\"resign\":true,\"move\":\"Qxf7\"}"), answer("{\"move\":\"Nf6\"}"));

        fixture.service.playTurn(fixture.resignationRequest(new ArrayList<>(), 3, 4, false, ChatMode.OPTIONAL));

        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.ACCEPTED), fixture.attempts.outcomes());
        assertEquals("Nf6", fixture.moves.lastRecordedSan);
    }

    @Test
    void aResignationAfterEarlierInvalidAttemptsKeepsThemOnRecord() {
        Fixture fixture = new Fixture(answer("{\"move\":\"Qxf7\"}"), answer("{\"resign\":true}"));

        GameTurnService.TurnResult result = fixture.service.playTurn(
                fixture.resignationRequest(new ArrayList<>(), 3, 15, true, ChatMode.OPTIONAL));

        assertTrue(result.isTerminal());
        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.RESIGNED), fixture.attempts.outcomes());
        assertEquals(2, fixture.attempts.recorded.getLast().attemptNumber());
    }

    @Test
    void aResignationIntoAChangedGameIsDiscardedWithoutEndingIt() {
        Fixture fixture = new Fixture(answer("{\"resign\":true}"));
        fixture.lifecycle.resignConflict = new GameStateConflictException("Game is no longer in progress");

        GameTurnService.TurnResult result = fixture.service.playTurn(
                fixture.resignationRequest(new ArrayList<>(), 3, 20, true, ChatMode.OPTIONAL));

        assertEquals(GameTurnService.Status.STOPPED, result.status());
        assertEquals(List.of(Outcome.DISCARDED), fixture.attempts.outcomes());
        assertEquals("Game is no longer in progress", fixture.attempts.recorded.getFirst().errorMessage());
    }

    @Test
    void aCutOffResignationThatIsNotAllowedPausesLikeOtherCutOffAnswers() {
        Fixture fixture = new Fixture(truncated("{\"resign\":true}", null));

        GameTurnService.TurnResult result = fixture.service.playTurn(
                fixture.resignationRequest(new ArrayList<>(), 3, 3, true, ChatMode.OPTIONAL));

        assertTrue(result.pausedGame());
        assertEquals(List.of(Outcome.TRUNCATED), fixture.attempts.outcomes());
        assertFalse(fixture.lifecycle.invalidMovesForfeited);
    }

    private static LlmResponse answer(String content) {
        return new LlmResponse(content, 10, 6, 0.001, 400, "{}", false, null);
    }

    private static LlmResponse truncated(String content, Integer reasoningTokens) {
        return new LlmResponse(content, 10, MAX_OUTPUT_TOKENS, 0.002, 400, "{}", false, null,
                ProviderOutcome.OK, 200, "length", reasoningTokens);
    }

    @Test
    void theRequestedEffortIsSentAndRecordedOnEveryAttempt() {
        Fixture fixture = new Fixture(
                new LlmResponse("{\"move\":\"Ke2\"}", 10, 6, null, 400, "{}", false, null),
                VALID_MOVE);

        GameTurnService.TurnResult result = fixture.service.playTurn(
                withEffort(request(new ArrayList<>(), 3, 30), ReasoningEffort.LOW));

        assertTrue(result.moveRecorded());
        assertEquals(List.of(ReasoningEffort.LOW, ReasoningEffort.LOW), fixture.openRouter.efforts);
        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.ACCEPTED), fixture.attempts.outcomes());
        assertEquals(List.of(ReasoningEffort.LOW, ReasoningEffort.LOW),
                fixture.attempts.recorded.stream().map(MoveAttemptService.AttemptRecord::reasoningEffort).toList());
    }

    @Test
    void theEffortSentIsTheMappedOneAndNoneIsRecordedWithoutALevel() {
        Fixture fixture = new Fixture(VALID_MOVE);
        fixture.openRouter.effortMapping = requested -> requested == ReasoningEffort.MEDIUM ? ReasoningEffort.LOW : null;

        fixture.service.playTurn(withEffort(request(new ArrayList<>(), 3, 30), ReasoningEffort.MEDIUM));
        fixture.service.playTurn(request(new ArrayList<>(), 3, 30));

        assertEquals(java.util.Arrays.asList(ReasoningEffort.LOW, null), fixture.openRouter.efforts);
        assertEquals(java.util.Arrays.asList(ReasoningEffort.LOW, null),
                fixture.attempts.recorded.stream().map(MoveAttemptService.AttemptRecord::reasoningEffort).toList());
    }

    @Test
    void aRejectedRequestWithAnEffortPausesWithAHintToChangeIt() {
        Fixture fixture = new Fixture(failure(ProviderOutcome.FATAL, 400,
                "OpenRouter rejected the request (HTTP 400): Invalid reasoning effort"));

        GameTurnService.TurnResult result = fixture.service.playTurn(
                withEffort(request(new ArrayList<>(), 3, 30), ReasoningEffort.MINIMAL));

        assertTrue(result.pausedGame());
        assertEquals("OpenRouter rejected the request (HTTP 400): Invalid reasoning effort. The request asked for "
                + "reasoning effort \"minimal\"; if the model does not accept it, choose another level for Model A "
                + "(or Model default for the tournament) and resume.", fixture.lifecycle.pauseDetail);
        assertEquals(List.of(Outcome.PROVIDER_FATAL), fixture.attempts.outcomes());
        assertEquals(ReasoningEffort.MINIMAL, fixture.attempts.recorded.getFirst().reasoningEffort());
    }

    @Test
    void otherFailuresAndRequestsWithoutAnEffortKeepTheirMessage() {
        Fixture withoutEffort = new Fixture(failure(ProviderOutcome.FATAL, 400, "OpenRouter rejected the request (HTTP 400)"));
        withoutEffort.service.playTurn(request(new ArrayList<>(), 3, 30));
        assertEquals("OpenRouter rejected the request (HTTP 400)", withoutEffort.lifecycle.pauseDetail);

        Fixture keyProblem = new Fixture(failure(ProviderOutcome.FATAL, 401, "OpenRouter rejected the API key (HTTP 401)"));
        keyProblem.service.playTurn(withEffort(request(new ArrayList<>(), 3, 30), ReasoningEffort.LOW));
        assertEquals("OpenRouter rejected the API key (HTTP 401)", keyProblem.lifecycle.pauseDetail);
    }

    @Test
    void aCutOffAnswerWithoutReasoningOnlySuggestsRaisingTheLimit() {
        Fixture fixture = new Fixture(truncated(null, 0));

        fixture.service.playTurn(request(new ArrayList<>(), 1, 30));

        assertTrue(fixture.lifecycle.pauseDetail.endsWith(
                "answer was cut off at the 2048-token output limit. Raise the limit and resume."),
                fixture.lifecycle.pauseDetail);
    }

    private static GameTurnService.TurnRequest withEffort(GameTurnService.TurnRequest r, ReasoningEffort effort) {
        return new GameTurnService.TurnRequest(r.gameId(), r.pgn(), r.fen(), r.board(), r.sideToMove(), r.moveNumber(),
                r.legalMoves(), r.modelId(), r.playerName(), r.opponentName(), r.history(), r.lastMoveSan(),
                r.lastMessage(), r.isFirstMove(), r.maxRetries(), r.moveTimeoutSeconds(), r.promptVersion(),
                r.promptHash(), r.totalCostUsd(), r.shouldContinue(), r.maxOutputTokens(), r.chatMode(),
                r.tournamentChatMode(), r.resignationAllowed(), effort);
    }

    private static LlmResponse failure(ProviderOutcome outcome, int status, String detail) {
        return LlmResponse.providerFailure(outcome, status, detail, 5, null);
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history,
                                                       int maxRetries, Integer moveTimeoutSeconds) {
        return request(history, maxRetries, moveTimeoutSeconds, () -> true);
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history,
                                                       int maxRetries, Integer moveTimeoutSeconds,
                                                       java.util.function.BooleanSupplier shouldContinue) {
        return request(history, maxRetries, moveTimeoutSeconds, shouldContinue, ChatMode.OPTIONAL);
    }

    private static GameTurnService.TurnRequest request(List<OpenRouterService.ChatMsg> history,
                                                       int maxRetries, Integer moveTimeoutSeconds,
                                                       java.util.function.BooleanSupplier shouldContinue,
                                                       ChatMode chatMode) {
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
                chatMode,
                ChatMode.MANDATORY
        );
    }

    private static final class Fixture {
        /**
         * Black's turn at {@code moveNumber} in an Italian-game position, with resignation offered or not. White's last move
         * was Bc4, so Black has legal moves like Nf6 and Bc5.
         */
        private GameTurnService.TurnRequest resignationRequest(List<OpenRouterService.ChatMsg> history,
                                                                      int maxRetries, int moveNumber,
                                                                      boolean resignationAllowed, ChatMode chatMode) {
            ((CountingMoveRepository) service.moveRepository).nonBookMoves = moveNumber - 1;
            Board board = new Board();
            board.loadFromFen("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 " + moveNumber);
            ChessService chess = new ChessService();
            return new GameTurnService.TurnRequest(
                    UUID.randomUUID(),
                    "1. e4 e5 2. Nf3 Nc6 3. Bc4",
                    board.getFen(),
                    board,
                    "BLACK",
                    moveNumber,
                    chess.getLegalMovesAsSan(board),
                    "model-b",
                    "Model B",
                    "Model A",
                    history,
                    "Bc4",
                    "Your move.",
                    false,
                    maxRetries,
                    null,
                    resignationAllowed ? "v2-resign" : "v2",
                    "hash-1",
                    BigDecimal.ZERO,
                    () -> true,
                    MAX_OUTPUT_TOKENS,
                    chatMode,
                    chatMode,
                    resignationAllowed
            );
        }

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
            // Backoff waits pass on the same clock as the model calls, so they count against the turn deadline.
            service.sleeper = ms -> {
                sleptMs.add(ms);
                openRouter.clockNanos[0] += ms * 1_000_000L;
            };
            service.nanoClock = () -> openRouter.clockNanos[0];
        }
    }

    /** Returns the scripted responses in order and repeats the last one once exhausted. */
    private static final class ScriptedOpenRouterService extends OpenRouterService {
        private final Deque<LlmResponse> responses;
        private LlmResponse last;
        private int calls;
        private boolean offersEffort = true;

        @Override
        boolean offersReasoningEffort(String modelId) {
            return offersEffort;
        }


        private ScriptedOpenRouterService(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        private final List<Integer> maxOutputTokens = new ArrayList<>();
        /** The reasoning effort of every call. */
        private final List<ReasoningEffort> efforts = new ArrayList<>();
        /** Stands in for the catalog lookup; by default every level is sent as requested. */
        private java.util.function.UnaryOperator<ReasoningEffort> effortMapping = requested -> requested;
        private final List<List<ChatMsg>> sentMessages = new ArrayList<>();
        private final List<AnswerDeadline> answerDeadlines = new ArrayList<>();
        /** Time left until the deadline when each call started, in ms (null without a deadline). */
        private final List<Long> remainingMsAtCall = new ArrayList<>();
        /** How long the model needs for a complete answer; like the real service, a later deadline times out. */
        private long modelAnswerMs;
        /** Per-call answer times in ms, used before {@link #modelAnswerMs}. */
        private final Deque<Long> callDurationsMs = new ArrayDeque<>();
        /** The test's monotonic clock, advanced by the simulated answer time. */
        private final long[] clockNanos = {1_000_000_000L};

        @Override
        public ReasoningEffort resolveReasoningEffort(String modelId, ReasoningEffort requested) {
            return requested == null ? null : effortMapping.apply(requested);
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration requestTimeout) {
            throw new AssertionError("The game must pass the tournament's output-token limit");
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                                int maxOutputTokens, ReasoningEffort reasoningEffort) {
            calls++;
            this.maxOutputTokens.add(maxOutputTokens);
            efforts.add(reasoningEffort);
            sentMessages.add(List.copyOf(messages));
            answerDeadlines.add(answerDeadline);
            Long remainingMs = answerDeadline == null
                    ? null : answerDeadline.remainingNanos(clockNanos[0]) / 1_000_000L;
            remainingMsAtCall.add(remainingMs);
            if (!responses.isEmpty()) {
                last = responses.removeFirst();
            }
            long answerMs = callDurationsMs.isEmpty() ? modelAnswerMs : callDurationsMs.removeFirst();
            if (last.outcome() == ProviderOutcome.OK && remainingMs != null && answerMs > remainingMs) {
                clockNanos[0] += remainingMs * 1_000_000L;
                return new LlmResponse(null, 0, 0, null, remainingMs, null, true,
                        "No complete answer within the move time");
            }
            clockNanos[0] += answerMs * 1_000_000L;
            return last;
        }
    }

    private static final class RecordingLifecycleService extends GameLifecycleService {
        private String lastForfeitColor;
        private Integer lastTimeoutSeconds;
        private boolean invalidMovesForfeited;
        private Integer forfeitAttempts;
        private String pauseDetail;

        @Override
        public boolean adjudicateIfEligible(UUID gameId, String expectedFen) {
            return false;
        }

        @Override
        public void forfeitTimeout(UUID gameId, String forfeitColor, int moveTimeoutSeconds) {
            lastForfeitColor = forfeitColor;
            lastTimeoutSeconds = moveTimeoutSeconds;
        }

        @Override
        public void forfeitInvalidMoves(UUID gameId, String forfeitColor, int maxRetries) {
            invalidMovesForfeited = true;
            forfeitAttempts = maxRetries;
        }

        @Override
        public void pauseForProviderFailure(UUID gameId, String detail) {
            pauseDetail = detail;
        }

        @Override
        public void pauseWithReason(UUID gameId, String detail) {
            pauseDetail = detail;
        }

        private Resignation resignation;
        private GameStateConflictException resignConflict;

        @Override
        public void resign(Resignation resignation) {
            if (resignConflict != null) {
                throw resignConflict;
            }
            this.resignation = resignation;
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
        private ChatMode lastChatMode;
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
                String expectedFen,
                ChatMode chatMode
        ) {
            if (conflict != null) {
                throw conflict;
            }
            lastRecordedSan = san;
            lastRecordedMessage = message;
            lastChatMode = chatMode;
            lastExpectedFen = expectedFen;
            lastRetryCount = retryCount;
        }
    }

    private static final class CountingMoveRepository extends MoveRepository {
        private long nonBookMoves;

        @Override
        public long countNonBookByGameIdAndColor(UUID gameId, String color) {
            return nonBookMoves;
        }

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
                java.time.Instant turnDeadlineAt,
                String detail,
                String chatMode,
                String banterLevel
        ) {
            if (turnStartedAt != null) {
                turnTimingBroadcast = true;
                lastTurnStartedAt = turnStartedAt;
                lastTurnDeadlineAt = turnDeadlineAt;
                lastTurnChatMode = chatMode;
            }
        }

        private boolean turnTimingBroadcast;
        private String lastTurnChatMode;
        private java.time.Instant lastTurnStartedAt;
        private java.time.Instant lastTurnDeadlineAt;

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
