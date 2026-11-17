package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.repository.MoveAttemptRepository.ReasoningAttempt;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReasoningAnalyticsTest {

    @Test
    void sameEffortTimeoutKeepsTheLabelButAnotherPossibleEffortMakesItUnknown() {
        UUID gameId = UUID.randomUUID();
        ReasoningAttempt low = attempt(gameId, Outcome.ACCEPTED, ReasoningEffort.LOW, true);
        ReasoningAttempt lowTimeout = attempt(gameId, Outcome.TIMEOUT, ReasoningEffort.LOW, false);
        ReasoningAttempt highTimeout = attempt(gameId, Outcome.TIMEOUT, ReasoningEffort.HIGH, false);
        ReasoningAttempt highResponse = attempt(gameId, Outcome.TIMEOUT, ReasoningEffort.HIGH, true);

        assertEquals("low", effort(gameId, low, lowTimeout));
        assertEquals("unknown", effort(gameId, low, highTimeout));
        assertEquals("mixed", effort(gameId, low, highResponse));
        assertEquals("unknown", effort(gameId, highTimeout));
    }

    private static String effort(UUID gameId, ReasoningAttempt... attempts) {
        return ReasoningAnalytics.bySide(List.of(attempts), List.of())
                .get(ReasoningAnalytics.Side.of(gameId, "WHITE", "model")).effort();
    }

    private static ReasoningAttempt attempt(UUID gameId, Outcome outcome, ReasoningEffort effort, boolean hasResponse) {
        return new ReasoningAttempt(gameId, "WHITE", "model", 1, 1, outcome, effort, 4096, null, hasResponse);
    }
}
