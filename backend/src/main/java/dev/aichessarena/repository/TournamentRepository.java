package dev.aichessarena.repository;

import dev.aichessarena.entity.Tournament;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class TournamentRepository implements PanacheRepositoryBase<Tournament, UUID> {
}
