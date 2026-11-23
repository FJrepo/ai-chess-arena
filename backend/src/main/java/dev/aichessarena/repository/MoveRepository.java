package dev.aichessarena.repository;

import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class MoveRepository implements PanacheRepositoryBase<Move, UUID> {

    /** The game's moves in play order: White and Black share a move number, and "WHITE" sorts after "BLACK". */
    public List<Move> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by moveNumber, color desc, createdAt, id", gameId);
    }

    /** Completed moves played by a side, excluding the authored opening. */
    public long countNonBookByGameIdAndColor(UUID gameId, String color) {
        return count("game.id = ?1 and color = ?2 and isBook = false", gameId, color);
    }

    public List<Move> findByTournamentId(UUID tournamentId) {
        return list("game.tournament.id = ?1", tournamentId);
    }

    /** Lightweight plies from the selected rating sample, including potential earlier legacy duplicates. */
    public List<ReasoningPly> findRatedReasoningPlies(boolean includeForfeits, Boolean legalMoveHints) {
        return reasoningPlies(LeaderboardRepository.rankedGameWhere(includeForfeits, legalMoveHints),
                LeaderboardRepository.rankedGameParameters(includeForfeits, legalMoveHints));
    }

    public List<ReasoningPly> findBenchmarkPlies(UUID runId) {
        return reasoningPlies(" where g.tournament.id = :runId", Parameters.with("runId", runId));
    }

    /**
     * Model moves that were not the model's first answer for their ply: an earlier answer of the same model for that
     * ply was recorded (cut off, empty, unparseable, illegal, discarded, ...; provider failures excluded). The
     * accepted move's retry count alone misses them, because resuming a paused game restarts the attempt numbers.
     */
    private static final String PRIOR_ANSWER = """
             and m.isBook = false and m.modelId is not null
              and exists (select 1 from MoveAttempt a
                  where a.game = m.game and a.color = m.color and a.moveNumber = m.moveNumber
                    and a.modelId = m.modelId and a.outcome not in :providerFailures and a.createdAt < m.createdAt)
            """;

    private static final List<Outcome> PROVIDER_FAILURES = List.of(Outcome.PROVIDER_RETRYABLE, Outcome.PROVIDER_FATAL);

    /** See {@link #PRIOR_ANSWER}. */
    public Set<UUID> findBenchmarkMovesWithPriorAnswers(UUID runId) {
        return movesWithPriorAnswers("m.game.tournament.id = :runId", Parameters.with("runId", runId));
    }

    /** The moves of an analytics window's games (see {@link #findInWindow}) that had an earlier answer for their ply. */
    public Set<UUID> findMovesWithPriorAnswersInWindow(Instant cutoff, UUID tournamentId) {
        return movesWithPriorAnswers(GameRepository.windowPredicate("m.game")
                        + GameRepository.tournamentPredicate("m.game", tournamentId),
                GameRepository.windowParameters(cutoff, tournamentId));
    }

    private Set<UUID> movesWithPriorAnswers(String condition, Parameters parameters) {
        var query = getEntityManager().createQuery("select m.id from Move m where " + condition + PRIOR_ANSWER,
                UUID.class).setParameter("providerFailures", PROVIDER_FAILURES);
        parameters.map().forEach(query::setParameter);
        return new HashSet<>(query.getResultList());
    }

    private List<ReasoningPly> reasoningPlies(String condition, Parameters parameters) {
        var query = getEntityManager().createQuery("""
                select g.id, m.color, m.modelId, m.moveNumber, m.retryCount, m.isBook, m.isOverride,
                       m.createdAt, m.id
                from Move m join m.game g""" + condition, Object[].class);
        parameters.map().forEach(query::setParameter);
        return query.getResultList().stream()
                .map(row -> new ReasoningPly((UUID) row[0], (String) row[1], (String) row[2], (Integer) row[3],
                        (Integer) row[4], (Boolean) row[5], (Boolean) row[6], (Instant) row[7], (UUID) row[8]))
                .toList();
    }

    public record ReasoningPly(UUID gameId, String color, String modelId, int moveNumber, int retryCount,
                               boolean book, boolean override, Instant createdAt, UUID id) {}

    /** Moves of the games in an analytics window (see {@link GameRepository#findInWindow}). */
    public List<Move> findInWindow(Instant cutoff, UUID tournamentId) {
        return list(
                "from Move m where " + GameRepository.windowPredicate("m.game")
                        + GameRepository.tournamentPredicate("m.game", tournamentId),
                GameRepository.windowParameters(cutoff, tournamentId)
        );
    }
}
