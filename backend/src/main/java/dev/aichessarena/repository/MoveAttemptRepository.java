package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class MoveAttemptRepository implements PanacheRepositoryBase<MoveAttempt, UUID> {

    private static final String REJECTED_COST_SELECT = "select a.game.id, a.modelId, sum(a.costUsd), a.color "
            + "from MoveAttempt a where a.outcome <> :accepted and a.costUsd is not null and a.modelId is not null";

    /**
     * The game's attempts in play order: by ply (White before Black), then by time. Attempt numbers restart at 1
     * when a paused game resumes, so they only break ties between attempts stored at the same instant.
     */
    public List<MoveAttempt> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by moveNumber, color desc, createdAt, attemptNumber", gameId);
    }

    /** Request telemetry in the comparison window, without loading response bodies. */
    public List<ReasoningAttempt> findReasoningAttempts(LocalDateTime cutoff, UUID tournamentId,
                                                        Boolean legalMoveHints) {
        String condition = GameRepository.hintsJoin(legalMoveHints)
                + " where g.status in :reasoningStatuses and g.completedAt >= :cutoff and g.benchmarkPairId is null"
                + GameRepository.tournamentPredicate("g", tournamentId)
                + GameRepository.hintsPredicate(legalMoveHints);
        var parameters = Parameters.with("cutoff", cutoff)
                .and("reasoningStatuses", List.of(Game.GameStatus.COMPLETED, Game.GameStatus.FORFEIT));
        if (tournamentId != null) {
            parameters.and("tournamentId", tournamentId);
        }
        return reasoningAttempts(condition, GameRepository.hintsParameters(parameters, legalMoveHints));
    }

    public List<ReasoningAttempt> findRatedReasoningAttempts(boolean includeForfeits, Boolean legalMoveHints) {
        return reasoningAttempts(LeaderboardRepository.rankedGameWhere(includeForfeits, legalMoveHints),
                LeaderboardRepository.rankedGameParameters(includeForfeits, legalMoveHints));
    }

    private List<ReasoningAttempt> reasoningAttempts(String gameCondition, Parameters parameters) {
        var query = getEntityManager().createQuery(
                "select g.id, a.color, a.modelId, a.moveNumber, a.attemptNumber, a.outcome, "
                        + "a.reasoningEffort, a.effectiveMaxOutputTokens, a.reasoningTokens, "
                        + "case when a.rawResponse is not null or a.promptTokens is not null then true else false end "
                        + "from MoveAttempt a join a.game g" + gameCondition + " and a.modelId is not null"
                        + " order by a.createdAt, a.id", Object[].class);
        parameters.map().forEach(query::setParameter);
        return query.getResultList().stream()
                .map(row -> new ReasoningAttempt((UUID) row[0], (String) row[1], (String) row[2],
                        (Integer) row[3], (Integer) row[4], (Outcome) row[5], (ReasoningEffort) row[6],
                        (Integer) row[7], (Integer) row[8], (Boolean) row[9]))
                .toList();
    }

    public record ReasoningAttempt(UUID gameId, String color, String modelId, int moveNumber, int attemptNumber,
                                   Outcome outcome, ReasoningEffort effort, Integer requestTokenLimit,
                                   Integer reasoningTokens, boolean hasResponse) {
        public boolean responseEvidence() {
            // Some provider and deadline failures happen locally, before a request can be transmitted.
            return hasResponse || switch (outcome) {
                case PROVIDER_RETRYABLE, PROVIDER_FATAL, TIMEOUT -> false;
                default -> true;
            };
        }
    }

    public List<ResolvedModel> findResolvedBenchmarkModels(UUID runId) {
        return getEntityManager().createQuery("""
                select distinct a.modelId, a.resolvedModelId from MoveAttempt a
                where a.game.tournament.id = :id and a.resolvedModelId is not null
                order by a.modelId, a.resolvedModelId
                """, ResolvedModel.class).setParameter("id", runId).getResultList();
    }

    public record ResolvedModel(String alias, String modelId) {}

    /** Lightweight usage projection for every outcome, without loading raw responses. */
    public List<AttemptUsage> findUsageInWindow(LocalDateTime cutoff, UUID tournamentId) {
        TypedQuery<Object[]> query = getEntityManager().createQuery(
                "select a.game.id, a.modelId, a.promptTokens, a.cachedPromptTokens, a.cacheWriteTokens, a.color "
                        + "from MoveAttempt a where (a.promptTokens is not null or a.completionTokens is not null "
                        + "or a.costUsd is not null or a.cachedPromptTokens is not null or a.cacheWriteTokens is not null) "
                        + "and " + GameRepository.windowPredicate("a.game")
                        + GameRepository.tournamentPredicate("a.game", tournamentId), Object[].class);
        for (Map.Entry<String, Object> parameter : GameRepository.windowParameters(cutoff, tournamentId).map()
                .entrySet()) {
            query.setParameter(parameter.getKey(), parameter.getValue());
        }
        return query.getResultList().stream()
                .map(row -> new AttemptUsage((UUID) row[0], (String) row[1], (Integer) row[2],
                        (Integer) row[3], (Integer) row[4], (String) row[5]))
                .toList();
    }

    public record AttemptUsage(UUID gameId, String modelId, Integer promptTokens,
                               Integer cachedPromptTokens, Integer cacheWriteTokens, String color) {
    }

    /**
     * Cost of the attempts that did not become a move, per game and model, for the games in an analytics window
     * (see {@link GameRepository#findInWindow}). Accepted attempts are left out: their cost is on the move.
     */
    public List<RejectedAttemptCost> findRejectedCostsInWindow(LocalDateTime cutoff, UUID tournamentId) {
        return rejectedCosts(
                " and " + GameRepository.windowPredicate("a.game")
                        + GameRepository.tournamentPredicate("a.game", tournamentId),
                GameRepository.windowParameters(cutoff, tournamentId)
        );
    }

    /** Cost of the attempts that did not become a move, per game and model, for one tournament. */
    public List<RejectedAttemptCost> findRejectedCostsByTournamentId(UUID tournamentId) {
        return rejectedCosts(" and a.game.tournament.id = :tournamentId", Parameters.with("tournamentId", tournamentId));
    }

    private List<RejectedAttemptCost> rejectedCosts(String condition, Parameters parameters) {
        TypedQuery<Object[]> query = getEntityManager().createQuery(
                REJECTED_COST_SELECT + condition + " group by a.game.id, a.modelId, a.color",
                Object[].class
        );
        query.setParameter("accepted", Outcome.ACCEPTED);
        for (Map.Entry<String, Object> parameter : parameters.map().entrySet()) {
            query.setParameter(parameter.getKey(), parameter.getValue());
        }
        return query.getResultList().stream()
                .map(row -> new RejectedAttemptCost((UUID) row[0], (String) row[1], (BigDecimal) row[2], (String) row[3]))
                .toList();
    }

    /**
     * The attempts that matter for a resigned turn, for the games in an analytics window that ended by resignation:
     * the RESIGNED attempts and the model's own failures (see {@link Outcome#isModelFailure()}). A resigned turn has
     * no move, so its failed attempts are not counted as retries anywhere else.
     */
    public List<ResignationTurnAttempt> findResignationTurnAttemptsInWindow(LocalDateTime cutoff, UUID tournamentId) {
        List<Outcome> outcomes = new java.util.ArrayList<>();
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.RESIGNED || outcome.isModelFailure()) {
                outcomes.add(outcome);
            }
        }
        TypedQuery<Object[]> query = getEntityManager().createQuery(
                "select a.game.id, a.moveNumber, a.color, a.modelId, a.outcome from MoveAttempt a "
                        + "where a.game.resultReason = :resignation and a.outcome in :outcomes and "
                        + GameRepository.windowPredicate("a.game")
                        + GameRepository.tournamentPredicate("a.game", tournamentId),
                Object[].class
        );
        query.setParameter("resignation", Game.ResultReason.RESIGNATION);
        query.setParameter("outcomes", outcomes);
        for (Map.Entry<String, Object> parameter : GameRepository.windowParameters(cutoff, tournamentId).map()
                .entrySet()) {
            query.setParameter(parameter.getKey(), parameter.getValue());
        }
        return query.getResultList().stream()
                .map(row -> new ResignationTurnAttempt((UUID) row[0], (Integer) row[1], (String) row[2],
                        (String) row[3], (Outcome) row[4]))
                .toList();
    }

    /** One attempt of a ply in a game that ended by resignation (see {@link #findResignationTurnAttemptsInWindow}). */
    public record ResignationTurnAttempt(UUID gameId, int moveNumber, String color, String modelId, Outcome outcome) {
    }

    /** Summed cost of one model's rejected attempts in one game. */
    public record RejectedAttemptCost(UUID gameId, String modelId, BigDecimal costUsd, String color) {
        public RejectedAttemptCost(UUID gameId, String modelId, BigDecimal costUsd) {
            this(gameId, modelId, costUsd, null);
        }
    }
}
