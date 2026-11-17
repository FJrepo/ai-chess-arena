package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Status;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GameRepository implements PanacheRepositoryBase<Game, UUID> {

    @Inject
    TournamentRepository tournamentRepository;

    private static final List<GameStatus> TERMINAL_STATUSES = List.of(GameStatus.COMPLETED, GameStatus.FORFEIT);

    public List<Game> findByTournamentId(UUID tournamentId) {
        return list("tournament.id = ?1", tournamentId);
    }

    /** IDs for post-delete cleanup, without attaching game entities that a bulk delete would leave stale. */
    public List<UUID> findIdsByTournamentId(UUID tournamentId) {
        return getEntityManager().createQuery("select g.id from Game g where g.tournament.id = :tournamentId", UUID.class)
                .setParameter("tournamentId", tournamentId).getResultList();
    }

    /**
     * Detaches every entity the current session has loaded, so the next read comes from the database. For a
     * long-lived request context outside a transaction (the game loop), whose session would otherwise keep returning
     * the copies it loaded first, however often other transactions have changed them since.
     *
     * @throws IllegalStateException inside a transaction, where it would also drop unflushed changes
     */
    public void detachAll() {
        if (QuarkusTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException("detachAll() would drop the unflushed changes of the current transaction");
        }
        getEntityManager().clear();
    }

    /**
     * Games in an analytics window: finished games by their completion time, unfinished ones by their creation
     * time.
     *
     * @param tournamentId restricts the window to one tournament, or {@code null} for all tournaments
     */
    public List<Game> findInWindow(LocalDateTime cutoff, UUID tournamentId) {
        return list(
                "from Game g where " + windowPredicate("g") + tournamentPredicate("g", tournamentId),
                windowParameters(cutoff, tournamentId)
        );
    }

    /**
     * HQL condition selecting the games of an analytics window, for a game path such as {@code g} or
     * {@code m.game}. Bind it with {@link #windowParameters}.
     */
    public static String windowPredicate(String game) {
        return ("((%1$s.status in :terminalStatuses and %1$s.completedAt >= :cutoff)"
                + " or (%1$s.status not in :terminalStatuses and %1$s.createdAt >= :cutoff))").formatted(game);
    }

    /** Restricts {@link #windowPredicate} to one tournament when {@code tournamentId} is not null. */
    public static String tournamentPredicate(String game, UUID tournamentId) {
        return tournamentId == null ? "" : " and " + game + ".tournament.id = :tournamentId";
    }

    public static Parameters windowParameters(LocalDateTime cutoff, UUID tournamentId) {
        Parameters parameters = Parameters.with("cutoff", cutoff).and("terminalStatuses", TERMINAL_STATUSES);
        return tournamentId == null ? parameters : parameters.and("tournamentId", tournamentId);
    }

    /**
     * Locks the tournament first, then the game, until the transaction ends. This order serializes
     * cancellation with game writes and bracket advancement without a game/tournament lock inversion.
     */
    public Game findByIdForUpdate(UUID id) {
        UUID tournamentId = getEntityManager().createQuery(
                "select g.tournament.id from Game g where g.id = :id and g.tournament is not null", UUID.class)
                .setParameter("id", id).getResultStream().findFirst().orElse(null);
        if (tournamentId != null) {
            tournamentRepository.findByIdForUpdate(tournamentId);
        }
        Game game = findById(id);
        if (game != null && getEntityManager().getLockMode(game) != LockModeType.PESSIMISTIC_WRITE) {
            refreshForUpdate(game);
        }
        return game;
    }

    /** Re-reads a managed game from the database and locks its row until the transaction ends. */
    public void refreshForUpdate(Game game) {
        getEntityManager().refresh(game, LockModeType.PESSIMISTIC_WRITE);
    }

    public List<Game> findBySeriesId(UUID seriesId) {
        return list("seriesId = ?1 order by seriesGameNumber asc, createdAt asc", seriesId);
    }
}
