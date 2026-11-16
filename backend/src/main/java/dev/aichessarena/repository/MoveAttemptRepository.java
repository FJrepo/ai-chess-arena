package dev.aichessarena.repository;

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

    /** Summed cost of one model's rejected attempts in one game. */
    public record RejectedAttemptCost(UUID gameId, String modelId, BigDecimal costUsd) {
    }
}
