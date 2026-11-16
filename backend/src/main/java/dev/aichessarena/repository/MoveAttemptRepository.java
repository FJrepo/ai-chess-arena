package dev.aichessarena.repository;

import dev.aichessarena.entity.MoveAttempt;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class MoveAttemptRepository implements PanacheRepositoryBase<MoveAttempt, UUID> {

    public List<MoveAttempt> findByGameId(UUID gameId) {
        return list("game.id = ?1 order by moveNumber, createdAt", gameId);
    }
}
