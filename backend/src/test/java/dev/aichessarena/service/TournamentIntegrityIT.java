package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.BracketDrawMode;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bracketApiDefaultsToOrderWithNoBodyOrAnEmptyObject(boolean sendEmptyObject) {
        UUID id = creation.create(tournament("Default order"),
                List.of(ai("A", 10), ai("B", 20), ai("C", 30), ai("D", 40)), null).tournament().id;
        var request = given().contentType("application/json");
        if (sendEmptyObject) request.body("{}");

        request.when().post("/api/tournaments/{id}/generate-bracket", id).then().statusCode(200);

        TournamentDto stored = getTournament(id);
        assertEquals(List.of(10, 20, 30, 40), stored.participants().stream()
                .map(p -> p.seed()).sorted().toList());
        var first = stored.games().stream()
                .filter(g -> "Semifinal".equals(g.bracketRound()) && g.bracketPosition() == 0).findFirst().orElseThrow();
        assertEquals("A", first.whitePlayerName());
        assertEquals("B", first.blackPlayerName());
    }

    @Test
    void bracketApiAcceptsExplicitOrder() {
        UUID id = creation.create(tournament("Explicit order"), List.of(ai("A", 10), ai("B", 20)), null).tournament().id;

        given().contentType("application/json").queryParam("drawMode", "ORDER")
                .when().post("/api/tournaments/{id}/generate-bracket", id).then().statusCode(200);

        assertEquals(List.of(10, 20), getTournament(id).participants().stream().map(p -> p.seed()).sorted().toList());
    }

    @Test
    void bracketApiPersistsRandomSeedsAndRejectsRedrawsWithoutChangingThem() {
        UUID id = creation.create(tournament("Random seeds"),
                List.of(ai("A", 10), ai("B", 20), ai("C", 30), ai("D", 40), ai("E", 50)), null).tournament().id;

        given().contentType("application/json").queryParam("drawMode", "RANDOM")
                .when().post("/api/tournaments/{id}/generate-bracket", id).then().statusCode(200);

        TournamentDto stored = getTournament(id);
        var drawn = stored.participants().stream().sorted(Comparator.comparingInt(p -> p.seed())).toList();
        assertEquals(List.of(0, 1, 2, 3, 4), drawn.stream().map(p -> p.seed()).toList());
        var opening = stored.games().stream().filter(g -> "Quarterfinal".equals(g.bracketRound()))
                .sorted(Comparator.comparingInt(g -> g.bracketPosition())).toList();
        assertEquals(4, opening.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(drawn.get(i).id(), opening.get(i).whiteParticipantId());
            assertEquals("BYE", opening.get(i).blackPlayerName());
            assertEquals(drawn.get(i).id(), opening.get(i).advancedParticipantId());
        }
        assertEquals(drawn.get(3).id(), opening.get(3).whiteParticipantId());
        assertEquals(drawn.get(4).id(), opening.get(3).blackParticipantId());

        for (String mode : List.of("RANDOM", "ORDER")) {
            given().contentType("application/json").queryParam("drawMode", mode)
                    .when().post("/api/tournaments/{id}/generate-bracket", id).then().statusCode(409);
        }
        TournamentDto afterRetry = getTournament(id);
        assertEquals(drawn, afterRetry.participants().stream().sorted(Comparator.comparingInt(p -> p.seed())).toList());
        assertEquals(stored.games().stream().map(g -> g.id()).sorted().toList(),
                afterRetry.games().stream().map(g -> g.id()).sorted().toList());
    }

    @Test
    void bracketApiRejectsUnknownDrawModeWithoutGeneratingOrReseeding() {
        UUID id = creation.create(tournament("Invalid draw"), List.of(ai("A", 10), ai("B", 20)), null).tournament().id;

        for (String mode : List.of("unknown", "")) {
            given().contentType("application/json")
                    .when().post("/api/tournaments/{id}/generate-bracket?drawMode=" + mode, id)
                    .then().statusCode(400);
        }

        TournamentDto stored = getTournament(id);
        assertEquals("CREATED", stored.status());
        assertTrue(stored.games().isEmpty());
        assertEquals(List.of(10, 20), stored.participants().stream().map(p -> p.seed()).sorted().toList());
    }

    private TournamentDto getTournament(UUID id) {
        return given().when().get("/api/tournaments/{id}", id).then().statusCode(200)
                .extract().as(TournamentDto.class);
    }

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
    void concurrentCreatesWithTheSameRequestIdProduceOneCompleteTournament() throws Exception {
        UUID requestId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TournamentCreationService.Result>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return creation.create(tournament("Same request"), List.of(ai("A", 0), ai("B", 1)), requestId);
                }));
            }
            go.countDown();
            TournamentCreationService.Result first = results.get(0).get(30, TimeUnit.SECONDS);
            TournamentCreationService.Result second = results.get(1).get(30, TimeUnit.SECONDS);

            assertEquals(first.tournament().id, second.tournament().id);
            assertEquals(1, (first.created() ? 1 : 0) + (second.created() ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        long tournamentsWithId = QuarkusTransaction.requiringNew().call(
                () -> tournamentRepository.count("clientRequestId", requestId));
        assertEquals(1, tournamentsWithId);
        UUID id = QuarkusTransaction.requiringNew().call(() -> tournamentRepository.findByClientRequestId(requestId).id);
        long participants = QuarkusTransaction.requiringNew().call(() -> participantRepository.count("tournament.id", id));
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
                        tournaments.generateBracket(id, BracketDrawMode.RANDOM);
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
