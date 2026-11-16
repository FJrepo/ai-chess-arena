package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GameRepository implements PanacheRepositoryBase<Game, UUID> {

    private static final List<GameStatus> TERMINAL_STATUSES = List.of(GameStatus.COMPLETED, GameStatus.FORFEIT);

    public List<Game> findByTournamentId(UUID tournamentId) {
        return list("tournament.id = ?1", tournamentId);
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

    /** Loads the game with a row lock held until the surrounding transaction ends. */
    public Game findByIdForUpdate(UUID id) {
        return findById(id, LockModeType.PESSIMISTIC_WRITE);
    }

    /** Re-reads a managed game from the database and locks its row until the transaction ends. */
    public void refreshForUpdate(Game game) {
        getEntityManager().refresh(game, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Atomically moves a WAITING or PAUSED game to IN_PROGRESS and clears any pause detail.
     *
     * @return false when the game does not exist or is in any other state
     */
    public boolean markStartedIfStartable(UUID id, LocalDateTime now) {
        return update(
                "status = ?1, startedAt = coalesce(startedAt, ?2), statusDetail = null "
                        + "where id = ?3 and status in ?4",
                GameStatus.IN_PROGRESS, now, id, List.of(GameStatus.WAITING, GameStatus.PAUSED)
        ) == 1;
    }

    public List<Game> findBySeriesId(UUID seriesId) {
        return list("seriesId = ?1 order by seriesGameNumber asc, createdAt asc", seriesId);
    }
}
