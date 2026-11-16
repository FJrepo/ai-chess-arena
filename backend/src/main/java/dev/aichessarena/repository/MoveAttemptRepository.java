package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
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

    private static final String REJECTED_COST_SELECT = "select a.game.id, a.modelId, sum(a.costUsd) "
            + "from MoveAttempt a where a.outcome <> :accepted and a.costUsd is not null and a.modelId is not null";

    /**
     * The game's attempts in play order: by ply (White before Black), then by time. Attempt numbers restart at 1
     * when a paused game resumes, so they only break ties between attempts stored at the same instant.
     */
    public List<MoveAttempt> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by moveNumber, color desc, createdAt, attemptNumber", gameId);
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
                REJECTED_COST_SELECT + condition + " group by a.game.id, a.modelId",
                Object[].class
        );
        query.setParameter("accepted", Outcome.ACCEPTED);
        for (Map.Entry<String, Object> parameter : parameters.map().entrySet()) {
            query.setParameter(parameter.getKey(), parameter.getValue());
        }
        return query.getResultList().stream()
                .map(row -> new RejectedAttemptCost((UUID) row[0], (String) row[1], (BigDecimal) row[2]))
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
    public record RejectedAttemptCost(UUID gameId, String modelId, BigDecimal costUsd) {
    }
}
