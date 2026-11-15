package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GameRepository implements PanacheRepositoryBase<Game, UUID> {

    public List<Game> findByTournamentId(UUID tournamentId) {
        return list("tournament.id = ?1", tournamentId);
    }

    public List<Game> findCreatedAfter(LocalDateTime cutoff) {
        return list("createdAt >= ?1", cutoff);
    }

    public List<Game> findByTournamentIdAndCreatedAfter(UUID tournamentId, LocalDateTime cutoff) {
        return list("tournament.id = ?1 and createdAt >= ?2", tournamentId, cutoff);
    }

    /** Loads the game with a row lock held until the surrounding transaction ends. */
    public Game findByIdForUpdate(UUID id) {
        return findById(id, LockModeType.PESSIMISTIC_WRITE);
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
