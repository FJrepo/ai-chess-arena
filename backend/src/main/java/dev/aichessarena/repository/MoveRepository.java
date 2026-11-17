package dev.aichessarena.repository;

import dev.aichessarena.entity.Move;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MoveRepository implements PanacheRepositoryBase<Move, UUID> {

    /** The game's moves in play order: White and Black share a move number, and "WHITE" sorts after "BLACK". */
    public List<Move> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by moveNumber, color desc, createdAt", gameId);
    }

    /** Completed moves played by a side, excluding the authored opening. */
    public long countNonBookByGameIdAndColor(UUID gameId, String color) {
        return count("game.id = ?1 and color = ?2 and isBook = false", gameId, color);
    }

    public List<Move> findByTournamentId(UUID tournamentId) {
        return list("game.tournament.id = ?1", tournamentId);
    }

    /** Moves of the games in an analytics window (see {@link GameRepository#findInWindow}). */
    public List<Move> findInWindow(LocalDateTime cutoff, UUID tournamentId) {
        return list(
                "from Move m where " + GameRepository.windowPredicate("m.game")
                        + GameRepository.tournamentPredicate("m.game", tournamentId),
                GameRepository.windowParameters(cutoff, tournamentId)
        );
    }
}
