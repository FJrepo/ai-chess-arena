package dev.aichessarena.repository;

import dev.aichessarena.entity.TournamentParticipant;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TournamentParticipantRepository implements PanacheRepositoryBase<TournamentParticipant, UUID> {

    public List<TournamentParticipant> findByTournamentId(UUID tournamentId) {
        return list("tournament.id = ?1", tournamentId);
    }
}
