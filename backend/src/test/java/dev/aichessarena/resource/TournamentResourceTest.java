package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.AnalyticsService;
import dev.aichessarena.service.TournamentService;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentResourceTest {

    @Test
    void removeParticipantReturnsNotFoundWhenServiceReportsMissingParticipant() {
        TournamentResource resource = new TournamentResource();
        resource.tournamentRepository = new TournamentRepository();
        resource.tournamentService = new MissingParticipantTournamentService();
        resource.analyticsService = new AnalyticsService();

        Response response = resource.removeParticipant(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(404, response.getStatus());
    }

    private static final class MissingParticipantTournamentService extends TournamentService {
        @Override
        public boolean removeParticipant(UUID tournamentId, UUID participantId) {
            return false;
        }
    }
}
