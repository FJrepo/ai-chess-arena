package dev.aichessarena.service;

import dev.aichessarena.entity.MoveAttempt.Outcome;

/** Decides how to proceed from turn observations without reading the clock or writing game state. */
final class TurnPolicy {
    private TurnPolicy() {}

    enum Action {
        REQUEST,
        ACCEPT,
        RETRY_MODEL,
        RETRY_PROVIDER,
        PAUSE_PROVIDER,
        PAUSE_TRUNCATED,
        FORFEIT_TIMEOUT,
        FORFEIT_INVALID_MOVES
    }

    /** No new attempt has been sent. A null time budget denotes an untimed turn. */
    static Action beforeRequest(Long remainingNanos, boolean providerProblem) {
        if (remainingNanos == null || remainingNanos > 0) {
            return Action.REQUEST;
        }
        return providerProblem ? Action.PAUSE_PROVIDER : Action.FORFEIT_TIMEOUT;
    }

    /** Provider failures leave the model's attempts untouched. A null backoff means no provider retries remain. */
    static Action afterProviderFailure(Outcome outcome, Long remainingNanos, Long nextBackoffNanos) {
        if (outcome == Outcome.PROVIDER_RETRYABLE && nextBackoffNanos != null
                && (remainingNanos == null || remainingNanos > nextBackoffNanos)) {
            return Action.RETRY_PROVIDER;
        }
        return Action.PAUSE_PROVIDER;
    }

    /** The remaining attempt count excludes an invalid answer just received, even if its write fails. */
    static Action afterModelResponse(Outcome outcome, int attemptsAfterResponse, boolean providerProblem,
                                     boolean timedTurn) {
        return switch (outcome) {
            case ACCEPTED, RESIGNED -> Action.ACCEPT;
            case TRUNCATED -> Action.PAUSE_TRUNCATED;
            case TIMEOUT -> timedTurn && !providerProblem ? Action.FORFEIT_TIMEOUT : Action.PAUSE_PROVIDER;
            case EMPTY_RESPONSE, PARSE_FAILURE, RESIGN_NOT_ALLOWED, ILLEGAL_MOVE ->
                    attemptsAfterResponse > 0 ? Action.RETRY_MODEL : Action.FORFEIT_INVALID_MOVES;
            default -> throw new IllegalArgumentException("Expected a model response, got " + outcome);
        };
    }
}
