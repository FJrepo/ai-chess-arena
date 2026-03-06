package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
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

    public List<Game> findBySeriesId(UUID seriesId) {
        return list("seriesId = ?1 order by seriesGameNumber asc, createdAt asc", seriesId);
    }
}
