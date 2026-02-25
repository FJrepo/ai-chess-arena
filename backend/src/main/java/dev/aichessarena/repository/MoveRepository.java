package dev.aichessarena.repository;

import dev.aichessarena.entity.Move;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MoveRepository implements PanacheRepositoryBase<Move, UUID> {

    public List<Move> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by moveNumber", gameId);
    }

    public List<Move> findCreatedAfter(LocalDateTime cutoff) {
        return list("createdAt >= ?1", cutoff);
    }

    public List<Move> findByTournamentId(UUID tournamentId) {
        return list("game.tournament.id = ?1", tournamentId);
    }

    public List<Move> findByTournamentIdAndCreatedAfter(UUID tournamentId, LocalDateTime cutoff) {
        return list("game.tournament.id = ?1 and createdAt >= ?2", tournamentId, cutoff);
    }
}
