package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Tournament creation and bracket generation against a real PostgreSQL via Dev Services. */
@QuarkusTest
class TournamentIntegrityIT {

    @Inject
    TournamentCreationService creation;

    @Inject
    TournamentService tournaments;

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentParticipantRepository participantRepository;

    @Inject
    GameRepository gameRepository;

    @Test
    void creationStoresTournamentAndParticipantsTogetherOrNotAtAll() {
        String name = "Atomic " + UUID.randomUUID();
        TournamentParticipant tooLong = human("x".repeat(300)); // exceeds VARCHAR(255): fails inside the transaction

        assertThrows(RuntimeException.class, () -> creation.create(
                tournament(name), List.of(ai("A", 0), ai("B", 1), tooLong), null));

        long stored = QuarkusTransaction.requiringNew().call(() -> tournamentRepository.count("name", name));
        assertEquals(0, stored);
    }

    @Test
    void repeatingACreateRequestReturnsTheFirstTournament() {
        UUID requestId = UUID.randomUUID();

        TournamentCreationService.Result first = creation.create(
                tournament("Idempotent"), List.of(ai("A", 0), ai("B", 1)), requestId);
        TournamentCreationService.Result second = creation.create(
                tournament("Idempotent"), List.of(ai("A", 0), ai("B", 1)), requestId);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.tournament().id, second.tournament().id);
        long participants = QuarkusTransaction.requiringNew().call(
                () -> participantRepository.count("tournament.id", first.tournament().id));
        assertEquals(2, participants);
    }

    @Test
    void concurrentBracketGenerationCreatesExactlyOneBracket() throws Exception {
        UUID id = creation.create(tournament("Bracket race"),
                List.of(ai("A", 0), ai("B", 1), ai("C", 2), ai("D", 3)), null).tournament().id;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Throwable>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        tournaments.generateBracket(id);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            go.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, outcomes.stream().filter(o -> o == null).count());
            assertEquals(1, outcomes.stream().filter(o -> o instanceof WebApplicationException w
                    && w.getResponse().getStatus() == 409).count());
        } finally {
            pool.shutdownNow();
        }

        long games = QuarkusTransaction.requiringNew().call(() -> gameRepository.count("tournament.id", id));
        assertEquals(3, games); // two semifinals + final
    }

    private static Tournament tournament(String name) {
        Tournament tournament = new Tournament();
        tournament.name = name;
        return tournament;
    }

    private static TournamentParticipant ai(String name, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.AI;
        participant.modelId = "openai/gpt-4o-mini";
        participant.seed = seed;
        return participant;
    }

    private static TournamentParticipant human(String name) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.HUMAN;
        participant.seed = 2;
        return participant;
    }
}
