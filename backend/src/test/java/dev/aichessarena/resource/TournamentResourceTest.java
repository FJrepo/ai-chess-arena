package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.dto.CreateTournamentRequest;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.AnalyticsService;
import dev.aichessarena.service.TournamentService;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentResourceTest {

    @Test
    void createRejectsUnsupportedBestOfValues() {
        TournamentResource resource = new TournamentResource();
        resource.tournamentRepository = new TournamentRepository();
        resource.tournamentService = new TournamentService();
        resource.analyticsService = new AnalyticsService();

        assertThrows(IllegalArgumentException.class, () -> resource.create(
                new CreateTournamentRequest("Series Test", null, null, null, 9, null, null, null)
        ));
    }

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
