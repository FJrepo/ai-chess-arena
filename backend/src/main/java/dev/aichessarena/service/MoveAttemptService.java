package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persists every model attempt. Failed attempts also add their cost to the game total (accepted moves are
 * costed when the move is recorded), so paid but rejected answers are no longer invisible.
 */
@ApplicationScoped
public class MoveAttemptService {

    static final int MAX_RAW_RESPONSE_CHARS = 20_000;
    static final int MAX_ERROR_CHARS = 500;
    static final int MAX_PROPOSED_MOVE_CHARS = 64;
    static final int MAX_FINISH_REASON_CHARS = 32;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    @Inject
    GameWebSocket gameWebSocket;

    @Inject
    LiveGameUpdates liveUpdates;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    @Inject
    TournamentAutoRunService autoRuns;

    public record AttemptRecord(
            UUID gameId,
            int moveNumber,
            String color,
            int attemptNumber,
            String modelId,
            Outcome outcome,
            String proposedMove,
            String errorMessage,
            OpenRouterService.LlmResponse response,
            // The reasoning effort sent with the request; null when none was sent.
            ReasoningEffort reasoningEffort,
            Long turnStatusRevision,
            Integer configurationRevision
    ) {
        public AttemptRecord(UUID gameId, int moveNumber, String color, int attemptNumber, String modelId,
                             Outcome outcome, String proposedMove, String errorMessage,
                             OpenRouterService.LlmResponse response, ReasoningEffort reasoningEffort, Long turnStatusRevision) {
            this(gameId, moveNumber, color, attemptNumber, modelId, outcome, proposedMove, errorMessage, response,
                    reasoningEffort, turnStatusRevision, null);
        }

        public AttemptRecord(UUID gameId, int moveNumber, String color, int attemptNumber, String modelId,
                             Outcome outcome, String proposedMove, String errorMessage,
                             OpenRouterService.LlmResponse response, ReasoningEffort reasoningEffort) {
            this(gameId, moveNumber, color, attemptNumber, modelId, outcome, proposedMove, errorMessage, response,
                    reasoningEffort, null);
        }

        /** An attempt sent without a reasoning effort. */
        public AttemptRecord(UUID gameId, int moveNumber, String color, int attemptNumber, String modelId,
                             Outcome outcome, String proposedMove, String errorMessage,
                             OpenRouterService.LlmResponse response) {
            this(gameId, moveNumber, color, attemptNumber, modelId, outcome, proposedMove, errorMessage, response,
                    null);
        }
    }

    /**
     * Records the attempt and its cost in its own transaction. A failure propagates once the transaction has rolled
     * back: the caller decides whether the game can go on without it (never with another request; see
     * {@link TurnPolicy#beforeRetry}).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(AttemptRecord attempt) {
        Game game = gameRepository.findByIdForUpdate(attempt.gameId());
        if (game == null || GameRunOwnership.staleWorker(game)) {
            return;
        }
        MoveAttempt row = new MoveAttempt();
        row.game = game;
        row.moveNumber = attempt.moveNumber();
        row.color = attempt.color();
        row.attemptNumber = attempt.attemptNumber();
        row.modelId = attempt.modelId();
        row.outcome = attempt.outcome();
        row.proposedMove = truncate(attempt.proposedMove(), MAX_PROPOSED_MOVE_CHARS);
        row.errorMessage = truncate(attempt.errorMessage(), MAX_ERROR_CHARS);
        row.reasoningEffort = attempt.reasoningEffort();
        row.turnStatusRevision = attempt.turnStatusRevision();
        row.configurationRevision = attempt.configurationRevision();
        OpenRouterService.LlmResponse response = attempt.response();
        if (response != null) {
            row.promptTokens = response.promptTokensReported() ? response.promptTokens() : null;
            row.cachedPromptTokens = response.cachedPromptTokens();
            row.cacheWriteTokens = response.cacheWriteTokens();
            row.completionTokens = response.completionTokens() > 0 ? response.completionTokens() : null;
            row.reasoningTokens = response.reasoningTokens();
            row.effectiveMaxOutputTokens = response.effectiveMaxOutputTokens();
            row.modelMaxCompletionTokens = response.modelMaxCompletionTokens();
            row.costUsd = response.costUsd() != null ? BigDecimal.valueOf(response.costUsd()) : null;
            row.responseTimeMs = response.responseTimeMs() > 0 ? response.responseTimeMs() : null;
            row.finishReason = truncate(response.finishReason(), MAX_FINISH_REASON_CHARS);
            row.rawResponse = truncate(response.rawResponse(), MAX_RAW_RESPONSE_CHARS);
        }
        moveAttemptRepository.persist(row);
        // The answer to the request in flight is recorded with its cost, in this transaction. A request whose
        // delivery is uncertain stays unresolved until the game is paused for inspection.
        if (response == null || !response.deliveryUncertain()) {
            GameRunOwnership.answerRecorded(game);
        }

        // Accepted moves are costed by GameMoveService.recordModelMove and resignations by
        // GameLifecycleService.resign; count only the other answers here.
        if (!attempt.outcome().isCostedElsewhere() && row.costUsd != null) {
            game.totalCostUsd = game.totalCostUsd.add(row.costUsd);
        }
        if (autoRuns != null) autoRuns.afterAttempt(game);
        // A paid answer can arrive after cancellation's status update. Invalidate only after the
        // attempt and its cost are visible, without inventing another status change/revision.
        if (liveUpdates != null) {
            liveUpdates.changed(game, liveUpdates.entityChange("committedAttempt", game.id, "attempt",
                    DtoMapper.toDto(row)));
        }
        AfterCommit.run(transactionRegistry, () -> gameWebSocket.broadcastAttemptsUpdated(attempt.gameId()));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
