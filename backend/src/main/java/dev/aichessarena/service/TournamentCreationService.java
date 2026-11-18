package dev.aichessarena.service;

import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Creates a tournament together with its participants: all participants are validated first (model checks
 * may call OpenRouter, so this happens outside any transaction), then everything is stored in one
 * transaction, then the requested bracket is generated after that commit. A repeated request with the
 * same client request id finishes any interrupted generation using the original choice.
 */
@ApplicationScoped
public class TournamentCreationService {

    private static final Logger LOG = Logger.getLogger(TournamentCreationService.class);

    @Inject
    OpenRouterKeyStore keys;

    @Inject
    TournamentService tournamentService;

    @Inject
    TournamentRepository tournamentRepository;

    public record Result(TournamentDto tournament, boolean created) {}

    @ActivateRequestContext
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Result create(Tournament tournament, List<TournamentParticipant> participants, UUID clientRequestId) {
        if (keys != null) keys.requireKey();
        if (clientRequestId != null) {
            Tournament existing = findReplay(clientRequestId);
            if (existing != null) {
                return new Result(tournamentService.completeCreation(existing.id), false);
            }
        }

        if (tournament.name == null || tournament.name.isBlank()) {
            throw new IllegalArgumentException("Tournament name is required");
        }
        tournament.name = tournament.name.trim();

        if (tournament.creationDrawMode != null && (participants == null || participants.size() < 2)) {
            throw new IllegalArgumentException(
                    "Need at least 2 participants to generate the bracket; send generateBracket: false to create a draft");
        }

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
        Tournament stored;
        try {
            stored = tournamentService.persistNewTournament(tournament, prepared);
        } catch (RuntimeException e) {
            // A concurrent request with the same client request id won the unique constraint.
            if (clientRequestId != null) {
                Tournament existing = findReplay(clientRequestId);
                if (existing != null) {
                    LOG.debugf("Tournament creation %s already completed by a concurrent request", clientRequestId);
                    return new Result(tournamentService.completeCreation(existing.id), false);
                }
            }
            throw e;
        }
        return new Result(tournamentService.completeCreation(stored.id), true);
    }

    private Tournament findReplay(UUID requestId) {
        Tournament existing = tournamentRepository.findByClientRequestId(requestId);
        if (existing != null && existing.isBenchmark()) {
            throw new WebApplicationException("Creation request ID belongs to a benchmark", 409);
        }
        return existing;
    }
}
