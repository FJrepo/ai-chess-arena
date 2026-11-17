package dev.aichessarena.repository;

import dev.aichessarena.entity.Tournament;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.UUID;

@ApplicationScoped
public class TournamentRepository implements PanacheRepositoryBase<Tournament, UUID> {

    /** Loads the tournament with a row lock held until the surrounding transaction ends. */
    public Tournament findByIdForUpdate(UUID id) {
        Tournament tournament = findById(id);
        if (tournament != null && getEntityManager().getLockMode(tournament) != LockModeType.PESSIMISTIC_WRITE) {
            // A request/loop may already have cached this entity. Re-read after acquiring the lock,
            // but never refresh a row this transaction already locked and may have changed.
            getEntityManager().flush();
            getEntityManager().refresh(tournament, LockModeType.PESSIMISTIC_WRITE);
        }
        return tournament;
    }

    public Tournament findByClientRequestId(UUID clientRequestId) {
        return find("clientRequestId", clientRequestId).firstResult();
    }
}
