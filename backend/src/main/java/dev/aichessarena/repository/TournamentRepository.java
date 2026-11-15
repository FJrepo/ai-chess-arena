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
        return findById(id, LockModeType.PESSIMISTIC_WRITE);
    }

    public Tournament findByClientRequestId(UUID clientRequestId) {
        return find("clientRequestId", clientRequestId).firstResult();
    }
}
