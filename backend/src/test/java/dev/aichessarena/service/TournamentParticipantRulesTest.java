package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentParticipantRulesTest {

    @Test
    void addParticipantAllowsSingleHumanWithoutModel() {
        Tournament tournament = tournament();
        TournamentService service = createService(tournament, List.of());

        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = "Human Challenger";
        participant.controlType = TournamentParticipant.ControlType.HUMAN;

        TournamentParticipant saved = service.addParticipant(tournament.id, participant);

        assertEquals(TournamentParticipant.ControlType.HUMAN, saved.controlType);
        assertNull(saved.modelId);
    }

    @Test
    void addParticipantRejectsSecondHumanParticipant() {
        Tournament tournament = tournament();
        TournamentParticipant existingHuman = new TournamentParticipant();
        existingHuman.id = UUID.randomUUID();
        existingHuman.playerName = "Existing Human";
        existingHuman.controlType = TournamentParticipant.ControlType.HUMAN;
        existingHuman.tournament = tournament;

        TournamentService service = createService(tournament, List.of(existingHuman));

        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = "Second Human";
        participant.controlType = TournamentParticipant.ControlType.HUMAN;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.addParticipant(tournament.id, participant)
        );

        assertEquals("Only one human participant is supported per tournament", error.getMessage());
    }

    private TournamentService createService(Tournament tournament, List<TournamentParticipant> existingParticipants) {
        TournamentService service = new TournamentService();
        service.tournamentRepository = new FakeTournamentRepository(Map.of(tournament.id, tournament));
        service.participantRepository = new FakeParticipantRepository(existingParticipants);
        service.gameRepository = new GameRepository();
        service.moveRepository = new MoveRepository();
        service.chatMessageRepository = new ChatMessageRepository();
        service.openRouterService = new PermissiveOpenRouterService();
        service.validateParticipantModels = false;
        return service;
    }

    private Tournament tournament() {
        Tournament tournament = new Tournament();
        tournament.id = UUID.randomUUID();
        tournament.name = "Human Arena";
        return tournament;
    }

    private static final class FakeTournamentRepository extends TournamentRepository {
        private final Map<UUID, Tournament> tournaments;

        private FakeTournamentRepository(Map<UUID, Tournament> tournaments) {
            this.tournaments = new HashMap<>(tournaments);
        }

        @Override
        public Tournament findById(UUID id) {
            return tournaments.get(id);
        }
    }

    private static final class FakeParticipantRepository extends TournamentParticipantRepository {
        private final List<TournamentParticipant> participants;

        private FakeParticipantRepository(List<TournamentParticipant> participants) {
            this.participants = new ArrayList<>(participants);
        }

        @Override
        public List<TournamentParticipant> findByTournamentId(UUID tournamentId) {
            return participants.stream()
                    .filter(participant -> participant.tournament != null && tournamentId.equals(participant.tournament.id))
                    .toList();
        }

        @Override
        public void persist(TournamentParticipant entity) {
            participants.add(entity);
        }
    }

    private static final class PermissiveOpenRouterService extends OpenRouterService {
        @Override
        public boolean isModelAllowed(String modelId) {
            return true;
        }
    }
}
