package dev.aichessarena.service;

import dev.aichessarena.entity.Game.PlayerType;
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
        FORFEIT_INVALID_MOVES,
        /** An interrupted game continues with a new loop: nothing of its current turn reached the provider. */
        RESUME,
        /** An interrupted game waits for its human player without a loop, as before the interruption. */
        AWAIT_HUMAN,
        /** A request may already have been charged without its answer being recorded: the operator checks first. */
        PAUSE_IN_FLIGHT,
        /** A model is to move but no OpenRouter key is configured. */
        PAUSE_DISCONNECTED,
        /** A request was sent but no usable answer arrived: it may be charged and is never retried automatically. */
        PAUSE_DELIVERY_UNCERTAIN,
        /** A paid answer could not be saved: no further request until the operator has checked spend. */
        PAUSE_UNRECORDED
    }

    /** No new attempt has been sent. A null time budget denotes an untimed turn. */
    static Action beforeRequest(Long remainingNanos, boolean providerProblem) {
        if (remainingNanos == null || remainingNanos > 0) {
            return Action.REQUEST;
        }
        return providerProblem ? Action.PAUSE_PROVIDER : Action.FORFEIT_TIMEOUT;
    }

    /**
     * Provider failures leave the model's attempts untouched. A request whose delivery is uncertain (sent, but the
     * connection broke or timed out before a usable answer) may already be charged and is never retried.
     */
    static Action afterProviderFailure(Outcome outcome, Long remainingNanos, Long nextBackoffNanos,
                                       boolean deliveryUncertain) {
        return deliveryUncertain ? Action.PAUSE_DELIVERY_UNCERTAIN
                : afterProviderFailure(outcome, remainingNanos, nextBackoffNanos);
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

    /** A retry needs the previous answer, and with it its cost, to be committed first. */
    static Action beforeRetry(Action planned, boolean answerRecorded) {
        boolean retry = planned == Action.RETRY_MODEL || planned == Action.RETRY_PROVIDER;
        return retry && !answerRecorded ? Action.PAUSE_UNRECORDED : planned;
    }

    /**
     * A game's loop ended without finishing its turn (backend restart or expired lease). Only a request recorded as
     * sent without a recorded answer may already have been charged; everything else was never sent and resumes. Engine
     * turns cost nothing and always resume; a human turn needs no loop.
     */
    static Action afterInterruption(boolean requestInFlight, PlayerType sideToMove, boolean providerConnected) {
        if (requestInFlight) {
            return Action.PAUSE_IN_FLIGHT;
        }
        return switch (sideToMove) {
            case HUMAN -> Action.AWAIT_HUMAN;
            case STOCKFISH -> Action.RESUME;
            case AI -> providerConnected ? Action.RESUME : Action.PAUSE_DISCONNECTED;
        };
    }
}
