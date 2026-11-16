package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Persists every model attempt. Failed attempts also add their cost to the game total (accepted moves are
 * costed when the move is recorded), so paid but rejected answers are no longer invisible.
 */
@ApplicationScoped
public class MoveAttemptService {

    private static final Logger LOG = Logger.getLogger(MoveAttemptService.class);
    static final int MAX_RAW_RESPONSE_CHARS = 20_000;
    static final int MAX_ERROR_CHARS = 500;
    static final int MAX_PROPOSED_MOVE_CHARS = 64;
    static final int MAX_FINISH_REASON_CHARS = 32;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    public record AttemptRecord(
            UUID gameId,
            int moveNumber,
            String color,
            int attemptNumber,
            String modelId,
            Outcome outcome,
            String proposedMove,
            String errorMessage,
            OpenRouterService.LlmResponse response
    ) {}

    /** Records the attempt in its own transaction; failures to record never interrupt the game. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(AttemptRecord attempt) {
        try {
            Game game = attempt.outcome().isCostedElsewhere()
                    ? gameRepository.findById(attempt.gameId())
                    : gameRepository.findByIdForUpdate(attempt.gameId());
            if (game == null) {
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
            OpenRouterService.LlmResponse response = attempt.response();
            if (response != null) {
                row.promptTokens = response.promptTokens() > 0 ? response.promptTokens() : null;
                row.completionTokens = response.completionTokens() > 0 ? response.completionTokens() : null;
                row.reasoningTokens = response.reasoningTokens();
                row.costUsd = response.costUsd() != null ? BigDecimal.valueOf(response.costUsd()) : null;
                row.responseTimeMs = response.responseTimeMs() > 0 ? response.responseTimeMs() : null;
                row.finishReason = truncate(response.finishReason(), MAX_FINISH_REASON_CHARS);
                row.rawResponse = truncate(response.rawResponse(), MAX_RAW_RESPONSE_CHARS);
            }
            moveAttemptRepository.persist(row);

            // Accepted moves are costed by GameMoveService.recordModelMove and resignations by
            // GameLifecycleService.resign; count only the other answers here.
            if (!attempt.outcome().isCostedElsewhere() && row.costUsd != null) {
                game.totalCostUsd = game.totalCostUsd.add(row.costUsd);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not record %s attempt for game %s", attempt.outcome(), attempt.gameId());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
