package dev.aichessarena.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.aichessarena.dto.CreateTournamentRequest;
import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.dto.TournamentRequests;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.ReasoningEffort;
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
    void createWithoutTimeoutUsesNoLimitAnd16kOutputTokens() {
        TournamentResource resource = resourceWithCapturingCreation();

        TournamentDto dto = (TournamentDto) resource.create(withTimeout("Default", null)).getEntity();

        assertNull(Tournament.DEFAULT_MOVE_TIMEOUT_SECONDS);
        assertNull(dto.moveTimeoutSeconds());
        assertNull(new Tournament().moveTimeoutSeconds, "a new entity starts without a limit");
        assertEquals(16384, dto.maxOutputTokens());
        assertEquals(16384, Tournament.DEFAULT_MAX_OUTPUT_TOKENS);
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
        assertNull(stored.moveTimeoutSeconds);
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
    void createUsesTheChatModeOrTheDefault() {
        TournamentResource resource = resourceWithCapturingCreation();

        assertChat(resource.create(withChat(null)), "OPTIONAL");
        // case-insensitive
        assertChat(resource.create(withChat("OFF")), "OFF");
        assertChat(resource.create(withChat("optional")), "OPTIONAL");
        assertChat(resource.create(withChat(" Mandatory ")), "MANDATORY");
    }

    @Test
    void createAndUpdateRejectFieldsRemovedIn20AndNameTheReplacement() {
        TournamentResource resource = resourceWithCapturingCreation();
        Tournament stored = new Tournament();
        stored.chatMode = ChatMode.MANDATORY;
        stored.maxAttempts = 5;
        resource.tournamentRepository = repositoryHolding(stored);

        for (JsonNode value : new JsonNode[] {IntNode.valueOf(3), NullNode.getInstance()}) {
            CreateTournamentRequest oldAttempts = TournamentRequests.removed("Old", value, null);
            IllegalArgumentException onCreate = assertThrows(IllegalArgumentException.class,
                    () -> resource.create(oldAttempts));
            assertTrue(onCreate.getMessage().contains("send maxAttempts"), onCreate.getMessage());
            IllegalArgumentException onUpdate = assertThrows(IllegalArgumentException.class,
                    () -> resource.update(UUID.randomUUID(), oldAttempts));
            assertTrue(onUpdate.getMessage().contains("send maxAttempts"), onUpdate.getMessage());
        }
        for (JsonNode value : new JsonNode[] {BooleanNode.FALSE, NullNode.getInstance()}) {
            CreateTournamentRequest oldChat = TournamentRequests.removed("Old", null, value);
            IllegalArgumentException onCreate = assertThrows(IllegalArgumentException.class,
                    () -> resource.create(oldChat));
            assertTrue(onCreate.getMessage().contains("send chatMode"), onCreate.getMessage());
            assertThrows(IllegalArgumentException.class, () -> resource.update(UUID.randomUUID(), oldChat));
        }
        assertEquals(ChatMode.MANDATORY, stored.chatMode);
        assertEquals(5, stored.maxAttempts);
    }

    @Test
    void createAndUpdateAcceptMaxAttemptsFrom1To20() {
        TournamentResource resource = resourceWithCapturingCreation();
        Tournament stored = new Tournament();
        resource.tournamentRepository = repositoryHolding(stored);

        assertEquals(3, ((TournamentDto) resource.create(TournamentRequests.attempts("Default", null)).getEntity())
                .maxAttempts());
        for (int attempts : new int[] {1, 20}) {
            assertEquals(attempts, ((TournamentDto) resource.create(
                    TournamentRequests.attempts("Bound", IntNode.valueOf(attempts))).getEntity()).maxAttempts());
        }
        resource.update(UUID.randomUUID(), TournamentRequests.attempts(null, IntNode.valueOf(7)));
        assertEquals(7, stored.maxAttempts);
        for (int attempts : new int[] {0, 21}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> resource.update(UUID.randomUUID(), TournamentRequests.attempts(null, IntNode.valueOf(attempts))));
            assertEquals("maxAttempts must be a whole number from 1 to 20", error.getMessage());
        }
        assertEquals(7, stored.maxAttempts);
    }

    @Test
    void createAndUpdateRejectUnknownChatModes() {
        TournamentResource resource = resourceWithCapturingCreation();
        Tournament stored = new Tournament();
        stored.chatMode = ChatMode.MANDATORY;
        resource.tournamentRepository = repositoryHolding(stored);

        for (String invalid : new String[] {"LOUD", "", "  ", "on"}) {
            IllegalArgumentException onCreate = assertThrows(IllegalArgumentException.class,
                    () -> resource.create(withChat(invalid)));
            assertTrue(onCreate.getMessage().contains("OFF, OPTIONAL or MANDATORY"), onCreate.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> resource.update(UUID.randomUUID(), withChat(invalid)));
        }
        assertEquals(ChatMode.MANDATORY, stored.chatMode);
    }

    @Test
    void updateKeepsTheChatModeUnlessItIsSent() {
        TournamentResource resource = new TournamentResource();
        Tournament stored = new Tournament();
        stored.chatMode = ChatMode.MANDATORY;
        resource.tournamentRepository = repositoryHolding(stored);

        assertChat(resource.update(UUID.randomUUID(), withChat(null)), "MANDATORY");
        assertChat(resource.update(UUID.randomUUID(), withChat("off")), "OFF");
        assertEquals(ChatMode.OFF, stored.chatMode);
    }

    @Test
    void reasoningEffortIsTheModelDefaultUnlessSentAndKeptOnUpdateWhenOmitted() {
        TournamentDto created = (TournamentDto) resourceWithCapturingCreation().create(withTimeout("Old client", null))
                .getEntity();
        assertNull(created.reasoningEffort(), "a request without the field (older clients) means the model default");

        TournamentResource resource = new TournamentResource();
        Tournament stored = new Tournament();
        stored.reasoningEffort = ReasoningEffort.LOW;
        resource.tournamentRepository = repositoryHolding(stored);

        assertEquals("low", ((TournamentDto) resource.update(UUID.randomUUID(), withTimeout("Renamed", null))
                .getEntity()).reasoningEffort());
        assertEquals("max", ((TournamentDto) resource.update(UUID.randomUUID(), withEffort("Max")).getEntity())
                .reasoningEffort());
        assertNull(((TournamentDto) resource.update(UUID.randomUUID(), withEffort("default")).getEntity())
                .reasoningEffort());
        assertNull(stored.reasoningEffort);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resource.update(UUID.randomUUID(), withEffort("turbo")));
        assertEquals("Invalid reasoningEffort: turbo (must be one of none, minimal, low, medium, high, xhigh, max or "
                + "default)", error.getMessage());
    }

    @Test
    void participantOverridesAreLevelsOnly() {
        assertEquals(ReasoningEffort.MINIMAL, TournamentResource.parseParticipantReasoningEffort("MINIMAL"));
        assertThrows(IllegalArgumentException.class, () -> TournamentResource.parseParticipantReasoningEffort("default"));
        assertThrows(IllegalArgumentException.class, () -> TournamentResource.parseParticipantReasoningEffort(" "));
        assertNull(TournamentResource.parseTournamentReasoningEffort(" Default "));
    }

    private static CreateTournamentRequest withEffort(String reasoningEffort) {
        return TournamentRequests.settings("Effort", null, null, null, null, reasoningEffort);
    }

    private static void assertChat(Response response, String chatMode) {
        TournamentDto dto = (TournamentDto) response.getEntity();
        assertEquals(chatMode, dto.chatMode());
    }

    private static CreateTournamentRequest withChat(String chatMode) {
        return TournamentRequests.settings("Chat", null, null, null, chatMode, null);
    }

    private static CreateTournamentRequest withTimeout(String name, Integer moveTimeoutSeconds) {
        return TournamentRequests.settings(name, moveTimeoutSeconds, null, null, null, null);
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
                return new Result(DtoMapper.toDto(tournament), true);
            }
        };
        return resource;
    }

    private static TournamentRepository repositoryHolding(Tournament tournament) {
        return new TournamentRepository() {
            @Override
            public Tournament findByIdForUpdate(UUID id) {
                return tournament;
            }

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
                TournamentRequests.settings("Series Test", null, 9, null, null, null)
        ));
    }

    @Test
    void createAndUpdateAcceptOutputTokenBoundsAndPreserveStoredValuesOnNullUpdate() {
        TournamentResource resource = resourceWithCapturingCreation();
        Tournament stored = new Tournament();
        stored.maxOutputTokens = 4096;
        resource.tournamentRepository = repositoryHolding(stored);
        for (int limit : new int[] {128, 262144}) {
            CreateTournamentRequest request = withOutputTokens(limit);
            assertEquals(limit, ((TournamentDto) resource.create(request).getEntity()).maxOutputTokens());
            assertEquals(limit, ((TournamentDto) resource.update(UUID.randomUUID(), request).getEntity())
                    .maxOutputTokens());
        }
        stored.maxOutputTokens = 4096;
        resource.update(UUID.randomUUID(), withOutputTokens(null));
        assertEquals(4096, stored.maxOutputTokens);
        for (int limit : new int[] {127, 262145}) {
            assertThrows(IllegalArgumentException.class,
                    () -> resource.update(UUID.randomUUID(), withOutputTokens(limit)));
        }
    }

    private static CreateTournamentRequest withOutputTokens(Integer limit) {
        return TournamentRequests.settings("Limits", null, null, limit, null, null);
    }

    @Test
    void createRejectsOutputTokenLimitsOutsideTheAllowedRange() {
        TournamentResource resource = new TournamentResource();
        resource.tournamentRepository = new TournamentRepository();
        resource.tournamentService = new TournamentService();
        resource.analyticsService = new AnalyticsService();

        for (int limit : new int[] {127, 262145}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resource.create(
                    TournamentRequests.settings("Limits", null, null, limit, null, null)
            ));
            assertEquals("maxOutputTokens must be between 128 and 262144", error.getMessage());
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
