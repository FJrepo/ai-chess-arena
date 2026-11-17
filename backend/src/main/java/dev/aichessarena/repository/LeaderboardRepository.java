package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class LeaderboardRepository implements PanacheRepositoryBase<Game, UUID> {

    /** Fetch both to-one sides in one query; never load moves or make a query per participant. */
    public List<Game> findFinishedModelGames(Boolean legalMoveHints) {
        String from = "select g from Game g left join fetch g.whiteParticipant left join fetch g.blackParticipant";
        String where = " where g.status in :statuses and g.completedAt is not null and g.result is not null "
                + "and g.whiteModelId is not null and g.blackModelId is not null";
        var parameters = Parameters.with("statuses", List.of(Game.GameStatus.COMPLETED, Game.GameStatus.FORFEIT));
        if (legalMoveHints == null) {
            return list(from + where, parameters);
        }
        return list(from + " left join g.tournament t" + where + " and coalesce(t.legalMoveHints, true) = :legalMoveHints",
                parameters.and("legalMoveHints", legalMoveHints));
    }
}
