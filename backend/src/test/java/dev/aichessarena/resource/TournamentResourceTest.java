package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.CreateTournamentRequest;
import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.AnalyticsService;
import dev.aichessarena.service.TournamentCreationService;
import dev.aichessarena.service.TournamentService;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentResourceTest {

    @Test
    void createWithZeroTimeoutStoresNoLimit() {
        TournamentResource resource = resourceWithCapturingCreation();

        TournamentDto dto = (TournamentDto) resource.create(withTimeout("No limit", 0)).getEntity();

        assertNull(dto.moveTimeoutSeconds());
    }

    @Test
    void createWithoutTimeoutUsesTheDefaultOf120Seconds() {
        TournamentResource resource = resourceWithCapturingCreation();

        TournamentDto dto = (TournamentDto) resource.create(withTimeout("Default", null)).getEntity();

        assertEquals(120, Tournament.DEFAULT_MOVE_TIMEOUT_SECONDS);
        assertEquals(120, dto.moveTimeoutSeconds());
        assertEquals(120, new Tournament().moveTimeoutSeconds, "a new entity starts with the default");
    }

    @Test
    void updateWithoutTimeoutKeepsAStoredLimitFromBeforeTheNewDefault() {
        TournamentResource resource = new TournamentResource();
        Tournament stored = new Tournament();
        stored.name = "Old";
        stored.moveTimeoutSeconds = 60;
        resource.tournamentRepository = repositoryHolding(stored);

        TournamentDto dto = (TournamentDto) resource.update(UUID.randomUUID(), withTimeout("Renamed", null))
                .getEntity();

        assertEquals(60, dto.moveTimeoutSeconds());
        assertEquals(60, stored.moveTimeoutSeconds);
    }

    @Test
    void createAcceptsTheBoundsOfTheAllowedRange() {
        TournamentResource resource = resourceWithCapturingCreation();

        assertEquals(10, ((TournamentDto) resource.create(withTimeout("Min", 10)).getEntity()).moveTimeoutSeconds());
        assertEquals(3600,
                ((TournamentDto) resource.create(withTimeout("Max", 3600)).getEntity()).moveTimeoutSeconds());
    }

    @Test
    void createAndUpdateRejectTimeoutsOutsideTheAllowedRange() {
        TournamentResource resource = resourceWithCapturingCreation();
        Tournament stored = new Tournament();
        resource.tournamentRepository = repositoryHolding(stored);

        for (int seconds : new int[] {5, 9, 3601, -1}) {
            IllegalArgumentException onCreate = assertThrows(IllegalArgumentException.class,
                    () -> resource.create(withTimeout("Invalid", seconds)));
            assertEquals("moveTimeoutSeconds must be 0 (no limit) or between 10 and 3600", onCreate.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> resource.update(UUID.randomUUID(), withTimeout(null, seconds)));
        }
        assertEquals(120, stored.moveTimeoutSeconds);
    }

    @Test
    void updateWithoutTimeoutLeavesItUnchangedAndZeroRemovesTheLimit() {
        TournamentResource resource = new TournamentResource();
        Tournament stored = new Tournament();
        stored.name = "Running";
        stored.moveTimeoutSeconds = 45;
        resource.tournamentRepository = repositoryHolding(stored);

        resource.update(UUID.randomUUID(), withTimeout("Renamed", null));
        assertEquals(45, stored.moveTimeoutSeconds);
        assertEquals("Renamed", stored.name);

        TournamentDto noLimit = (TournamentDto) resource.update(UUID.randomUUID(), withTimeout(null, 0)).getEntity();
        assertNull(noLimit.moveTimeoutSeconds());
        assertNull(stored.moveTimeoutSeconds);

        resource.update(UUID.randomUUID(), withTimeout(null, null));
        assertNull(stored.moveTimeoutSeconds, "null on update keeps \"no limit\"");

        resource.update(UUID.randomUUID(), withTimeout(null, 90));
        assertEquals(90, stored.moveTimeoutSeconds);
    }

    @Test
    void createResolvesTheChatModeFromChatModeAndTheOlderTrashTalkFlag() {
        TournamentResource resource = resourceWithCapturingCreation();

        // neither: the default
        assertChat(resource.create(withChat(null, null)), "OPTIONAL", true);
        // only trashTalkEnabled
        assertChat(resource.create(withChat(null, false)), "OFF", false);
        assertChat(resource.create(withChat(null, true)), "OPTIONAL", true);
        // only chatMode (case-insensitive)
        assertChat(resource.create(withChat("OFF", null)), "OFF", false);
        assertChat(resource.create(withChat("optional", null)), "OPTIONAL", true);
        assertChat(resource.create(withChat(" Mandatory ", null)), "MANDATORY", true);
        // both: chatMode wins
        assertChat(resource.create(withChat("MANDATORY", false)), "MANDATORY", true);
        assertChat(resource.create(withChat("OFF", true)), "OFF", false);
    }

    @Test
    void createAndUpdateRejectUnknownChatModes() {
        TournamentResource resource = resourceWithCapturingCreation();
        Tournament stored = new Tournament();
        stored.chatMode = ChatMode.MANDATORY;
        resource.tournamentRepository = repositoryHolding(stored);

        for (String invalid : new String[] {"LOUD", "", "  ", "on"}) {
            IllegalArgumentException onCreate = assertThrows(IllegalArgumentException.class,
                    () -> resource.create(withChat(invalid, true)));
            assertTrue(onCreate.getMessage().contains("OFF, OPTIONAL or MANDATORY"), onCreate.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> resource.update(UUID.randomUUID(), withChat(invalid, null)));
        }
        assertEquals(ChatMode.MANDATORY, stored.chatMode);
    }

    @Test
    void updateKeepsTheChatModeUnlessOneOfTheFieldsIsSent() {
        TournamentResource resource = new TournamentResource();
        Tournament stored = new Tournament();
        stored.chatMode = ChatMode.MANDATORY;
        resource.tournamentRepository = repositoryHolding(stored);

        assertChat(resource.update(UUID.randomUUID(), withChat(null, null)), "MANDATORY", true);
        assertChat(resource.update(UUID.randomUUID(), withChat(null, false)), "OFF", false);
        assertChat(resource.update(UUID.randomUUID(), withChat(null, true)), "OPTIONAL", true);
        assertChat(resource.update(UUID.randomUUID(), withChat("MANDATORY", false)), "MANDATORY", true);
        assertEquals(ChatMode.MANDATORY, stored.chatMode);
    }

    private static void assertChat(Response response, String chatMode, boolean trashTalkEnabled) {
        TournamentDto dto = (TournamentDto) response.getEntity();
        assertEquals(chatMode, dto.chatMode());
        assertEquals(trashTalkEnabled, dto.trashTalkEnabled());
    }

    private static CreateTournamentRequest withChat(String chatMode, Boolean trashTalkEnabled) {
        return new CreateTournamentRequest("Chat", null, null, null, null, null, trashTalkEnabled, null, null, null,
                null, chatMode);
    }

    private static CreateTournamentRequest withTimeout(String name, Integer moveTimeoutSeconds) {
        return new CreateTournamentRequest(name, null, moveTimeoutSeconds, null, null, null, null, null, null, null,
                null, null);
    }

    private static TournamentResource resourceWithCapturingCreation() {
        TournamentResource resource = new TournamentResource();
        resource.tournamentRepository = new TournamentRepository();
        resource.tournamentService = new TournamentService();
        resource.analyticsService = new AnalyticsService();
        resource.tournamentCreationService = new TournamentCreationService() {
            @Override
            public Result create(Tournament tournament, List<TournamentParticipant> participants,
                                 UUID clientRequestId) {
                return new Result(tournament, true);
            }
        };
        return resource;
    }

    private static TournamentRepository repositoryHolding(Tournament tournament) {
        return new TournamentRepository() {
            @Override
            public Tournament findById(UUID id) {
                return tournament;
            }

            @Override
            public void persist(Tournament entity) {
                // in memory
            }
        };
    }

    @Test
    void createRejectsUnsupportedBestOfValues() {
        TournamentResource resource = new TournamentResource();
        resource.tournamentRepository = new TournamentRepository();
        resource.tournamentService = new TournamentService();
        resource.analyticsService = new AnalyticsService();

        assertThrows(IllegalArgumentException.class, () -> resource.create(
                new CreateTournamentRequest("Series Test", null, null, null, 9, null, null, null, null, null, null, null)
        ));
    }

    @Test
    void createRejectsOutputTokenLimitsOutsideTheAllowedRange() {
        TournamentResource resource = new TournamentResource();
        resource.tournamentRepository = new TournamentRepository();
        resource.tournamentService = new TournamentService();
        resource.analyticsService = new AnalyticsService();

        for (int limit : new int[] {127, 32769}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resource.create(
                    new CreateTournamentRequest("Limits", null, null, null, null, null, null, null, null, null, limit, null)
            ));
            assertEquals("maxOutputTokens must be between 128 and 32768", error.getMessage());
        }
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
