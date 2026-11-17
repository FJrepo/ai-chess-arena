package dev.aichessarena.repository;

import dev.aichessarena.entity.Game.GameStatus;
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

    /** Lightweight plies for all-time reasoning coverage, without game or move payloads. */
    public List<ReasoningPly> findFinishedReasoningPlies() {
        return getEntityManager().createQuery("""
                select m.game.id, m.color, m.modelId, m.moveNumber, m.retryCount, m.isBook, m.isOverride,
                       m.createdAt, m.id
                from Move m where m.game.status in :terminal and m.game.completedAt is not null
                and m.game.whiteModelId is not null and m.game.blackModelId is not null
                """, Object[].class)
                .setParameter("terminal", List.of(GameStatus.COMPLETED, GameStatus.FORFEIT))
                .getResultList().stream()
                .map(row -> new ReasoningPly((UUID) row[0], (String) row[1], (String) row[2], (Integer) row[3],
                        (Integer) row[4], (Boolean) row[5], (Boolean) row[6], (LocalDateTime) row[7], (UUID) row[8]))
                .toList();
    }

    public record ReasoningPly(UUID gameId, String color, String modelId, int moveNumber, int retryCount,
                               boolean book, boolean override, LocalDateTime createdAt, UUID id) {}

    /** Moves of the games in an analytics window (see {@link GameRepository#findInWindow}). */
    public List<Move> findInWindow(LocalDateTime cutoff, UUID tournamentId) {
        return list(
                "from Move m where " + GameRepository.windowPredicate("m.game")
                        + GameRepository.tournamentPredicate("m.game", tournamentId),
                GameRepository.windowParameters(cutoff, tournamentId)
        );
    }
}
