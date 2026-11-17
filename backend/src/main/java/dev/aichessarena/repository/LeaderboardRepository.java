package dev.aichessarena.repository;

import dev.aichessarena.entity.Game;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class LeaderboardRepository implements PanacheRepositoryBase<Game, UUID> {

    /** Fetch both to-one sides in one query; never load moves or make a query per participant. */
    public List<Game> findFinishedModelGames() {
        return list("from Game g left join fetch g.whiteParticipant left join fetch g.blackParticipant "
                + "where g.status in ?1 and g.completedAt is not null and g.result is not null "
                + "and g.whiteModelId is not null and g.blackModelId is not null",
                List.of(Game.GameStatus.COMPLETED, Game.GameStatus.FORFEIT));
    }
}
