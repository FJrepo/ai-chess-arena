package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.entity.Game.PlayerType;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.service.TurnPolicy.Action;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnPolicyTest {

    @Test
    void anUnsentRetryAtTheDeadlineKeepsProviderAttribution() {
        assertEquals(Action.PAUSE_PROVIDER, TurnPolicy.beforeRequest(0L, true));
        assertEquals(Action.FORFEIT_TIMEOUT, TurnPolicy.beforeRequest(0L, false));
        assertEquals(Action.REQUEST, TurnPolicy.beforeRequest(1L, true));
        assertEquals(Action.REQUEST, TurnPolicy.beforeRequest(null, true));
    }

    @Test
    void retryableProviderFailuresRequireBothRetryCapacityAndTimeBeyondTheBackoff() {
        for (long backoff = 0; backoff <= 10; backoff++) {
            assertEquals(Action.PAUSE_PROVIDER, TurnPolicy.afterProviderFailure(Outcome.PROVIDER_RETRYABLE, backoff, backoff));
            assertEquals(Action.RETRY_PROVIDER, TurnPolicy.afterProviderFailure(Outcome.PROVIDER_RETRYABLE, backoff + 1, backoff));
            assertEquals(Action.RETRY_PROVIDER, TurnPolicy.afterProviderFailure(Outcome.PROVIDER_RETRYABLE, null, backoff));
            assertEquals(Action.PAUSE_PROVIDER, TurnPolicy.afterProviderFailure(Outcome.PROVIDER_FATAL, null, backoff));
        }
        assertEquals(Action.PAUSE_PROVIDER, TurnPolicy.afterProviderFailure(Outcome.PROVIDER_RETRYABLE, null, null));
    }

    @Test
    void insertingProviderRetriesDoesNotChangeHowManyModelAnswersCanBeRejected() {
        List<Outcome> invalid = List.of(Outcome.EMPTY_RESPONSE, Outcome.PARSE_FAILURE, Outcome.ILLEGAL_MOVE,
                Outcome.RESIGN_NOT_ALLOWED);
        // Place a provider failure before any subset of the four invalid model answers.
        for (int mask = 0; mask < (1 << invalid.size()); mask++) {
            for (int limit = 1; limit <= invalid.size(); limit++) {
                boolean providerProblem = false;
                int attemptsLeft = limit;
                for (int i = 0; i < limit; i++) {
                    if ((mask & (1 << i)) != 0) {
                        providerProblem = true;
                        assertEquals(Action.RETRY_PROVIDER, TurnPolicy.afterProviderFailure(Outcome.PROVIDER_RETRYABLE, 2L, 1L));
                        assertEquals(Action.PAUSE_PROVIDER, TurnPolicy.afterModelResponse(Outcome.TIMEOUT, attemptsLeft, true, true));
                    }
                    attemptsLeft--;
                    assertEquals(attemptsLeft > 0 ? Action.RETRY_MODEL : Action.FORFEIT_INVALID_MOVES,
                            TurnPolicy.afterModelResponse(invalid.get(i), attemptsLeft, providerProblem, true));
                }
            }
        }
    }

    @Test
    void answersRetainTheirClassificationAcrossProviderHistoriesAndTimeLimits() {
        for (boolean providerProblem : List.of(false, true)) {
            for (boolean timedTurn : List.of(false, true)) {
                assertEquals(Action.ACCEPT, TurnPolicy.afterModelResponse(Outcome.ACCEPTED, 0, providerProblem, timedTurn));
                assertEquals(Action.ACCEPT, TurnPolicy.afterModelResponse(Outcome.RESIGNED, 0, providerProblem, timedTurn));
                assertEquals(Action.PAUSE_TRUNCATED,
                        TurnPolicy.afterModelResponse(Outcome.TRUNCATED, 0, providerProblem, timedTurn));
                Action timeout = TurnPolicy.afterModelResponse(Outcome.TIMEOUT, 0, providerProblem, timedTurn);
                assertEquals(timedTurn && !providerProblem ? Action.FORFEIT_TIMEOUT : Action.PAUSE_PROVIDER, timeout);
            }
        }
    }

    @Test
    void anInterruptedRequestPausesForInspectionAndNeverSentWorkResumes() {
        for (PlayerType side : PlayerType.values()) {
            for (boolean connected : List.of(false, true)) {
                assertEquals(Action.PAUSE_IN_FLIGHT, TurnPolicy.afterInterruption(true, side, connected));
            }
        }
        assertEquals(Action.RESUME, TurnPolicy.afterInterruption(false, PlayerType.AI, true));
        assertEquals(Action.PAUSE_DISCONNECTED, TurnPolicy.afterInterruption(false, PlayerType.AI, false));
        // Engine turns cost nothing and need no OpenRouter connection.
        assertEquals(Action.RESUME, TurnPolicy.afterInterruption(false, PlayerType.STOCKFISH, false));
        assertEquals(Action.RESUME, TurnPolicy.afterInterruption(false, PlayerType.STOCKFISH, true));
        assertEquals(Action.AWAIT_HUMAN, TurnPolicy.afterInterruption(false, PlayerType.HUMAN, false));
        assertEquals(Action.AWAIT_HUMAN, TurnPolicy.afterInterruption(false, PlayerType.HUMAN, true));
    }
}
