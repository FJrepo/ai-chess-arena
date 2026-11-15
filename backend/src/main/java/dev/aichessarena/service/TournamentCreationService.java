package dev.aichessarena.service;

import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Creates a tournament together with its participants: all participants are validated first (model checks
 * may call OpenRouter, so this happens outside any transaction), then everything is stored in one
 * transaction. A repeated request with the same client request id returns the first result.
 */
@ApplicationScoped
public class TournamentCreationService {

    private static final Logger LOG = Logger.getLogger(TournamentCreationService.class);

    @Inject
    TournamentService tournamentService;

    @Inject
    TournamentRepository tournamentRepository;

    public record Result(Tournament tournament, boolean created) {}

    public Result create(Tournament tournament, List<TournamentParticipant> participants, UUID clientRequestId) {
        if (clientRequestId != null) {
            Tournament existing = tournamentRepository.findByClientRequestId(clientRequestId);
            if (existing != null) {
                return new Result(existing, false);
            }
        }

        if (tournament.name == null || tournament.name.isBlank()) {
            throw new IllegalArgumentException("Tournament name is required");
        }
        tournament.name = tournament.name.trim();

        List<TournamentParticipant> prepared = new ArrayList<>();
        int humans = 0;
        for (TournamentParticipant participant : participants == null ? List.<TournamentParticipant>of() : participants) {
            TournamentParticipant valid = tournamentService.prepareParticipant(participant);
            if (valid.controlType == TournamentParticipant.ControlType.HUMAN && ++humans > 1) {
                throw new IllegalArgumentException("Only one human participant is supported per tournament");
            }
            prepared.add(valid);
        }

        tournament.clientRequestId = clientRequestId;
        try {
            return new Result(tournamentService.persistNewTournament(tournament, prepared), true);
        } catch (RuntimeException e) {
            // A concurrent request with the same client request id won the unique constraint.
            if (clientRequestId != null) {
                Tournament existing = tournamentRepository.findByClientRequestId(clientRequestId);
                if (existing != null) {
                    LOG.debugf("Tournament creation %s already completed by a concurrent request", clientRequestId);
                    return new Result(existing, false);
                }
            }
            throw e;
        }
    }
}
