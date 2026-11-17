package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.entity.BracketDrawMode;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Creation commits the lineup first, then generates once under the normal tournament row lock. */
@QuarkusTest
class TournamentCreationIT {
    @Inject
    TournamentRepository tournaments;

    @Inject
    TournamentService service;

    @Test
    void explicitGenerationUsesApiSeedOrderByDefault() {
        var body = request(4);
        body.put("generateBracket", true);
        TournamentDto created = create(body, 201);

        assertReady(created, 3);
        assertEquals(List.of(10, 20, 30, 40), seeds(created));
        assertEquals(List.of("P0", "P1"), firstPair(created));
        assertEquals(gameIds(created), gameIds(get(created.id())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER", "RANDOM", " random "})
    void createAcceptsDrawModesAndKeepsOneBracketOnReplay(String mode) {
        var body = request(5);
        body.put("drawMode", mode);
        body.put("generateBracket", true);
        body.put("clientRequestId", UUID.randomUUID());
        TournamentDto created = create(body, 201);
        assertReady(created, 7);
        assertEquals(mode.equals("ORDER") ? List.of(10, 20, 30, 40, 50) : List.of(0, 1, 2, 3, 4), seeds(created));
        var drawn = created.participants().stream().sorted(Comparator.comparingInt(p -> p.seed())).toList();
        var firstRound = created.games().stream().filter(g -> "Quarterfinal".equals(g.bracketRound()))
                .sorted(Comparator.comparingInt(g -> g.bracketPosition())).toList();
        for (int i = 0; i < 3; i++) {
            assertEquals(drawn.get(i).id(), firstRound.get(i).advancedParticipantId());
            assertEquals("BYE", firstRound.get(i).blackPlayerName());
        }
        assertEquals(drawn.get(3).id(), firstRound.get(3).whiteParticipantId());
        assertEquals(drawn.get(4).id(), firstRound.get(3).blackParticipantId());

        // A changed retry still means the original creation, never a redraw or new tournament.
        body.put("drawMode", mode.equals("ORDER") ? "RANDOM" : "ORDER");
        body.put("generateBracket", false);
        TournamentDto replay = create(body, 200);
        assertEquals(created.id(), replay.id());
        assertEquals(participantsById(created), participantsById(replay));
        assertEquals(gameIds(created), gameIds(replay));
        given().contentType("application/json").queryParam("drawMode", "RANDOM").post("/api/tournaments/{id}/generate-bracket", created.id())
                .then().statusCode(409);
    }

    @Test
    void nullDrawModeUsesApiSeedOrderForExplicitGeneration() {
        var body = request(2);
        body.put("drawMode", null);
        body.put("generateBracket", true);
        TournamentDto created = create(body, 201);
        assertReady(created, 1);
        assertEquals(List.of(10, 20), seeds(created));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyCreationWithoutOptInAllowsAddingBeforeRandomGeneration(boolean explicitNull) {
        var body = request(2);
        if (explicitNull) {
            body.put("drawMode", null);
            body.put("generateBracket", null);
        }
        TournamentDto draft = create(body, 201);
        assertDraft(draft);
        assertEquals(List.of(10, 20), seeds(draft));
        assertDraft(get(draft.id()));
        for (int i = 2; i < 4; i++) {
            given().contentType("application/json").body(participant(i))
                    .post("/api/tournaments/{id}/participants", draft.id()).then().statusCode(201);
        }
        TournamentDto expanded = get(draft.id());
        assertDraft(expanded);
        assertEquals(4, expanded.participants().size());
        given().contentType("application/json").queryParam("drawMode", "RANDOM")
                .post("/api/tournaments/{id}/generate-bracket", draft.id()).then().statusCode(200);
        TournamentDto generated = get(draft.id());
        assertReady(generated, 3);
        assertEquals(List.of(0, 1, 2, 3), seeds(generated));
        assertEquals(List.of("P0", "P1", "P2", "P3"), generated.participants().stream()
                .map(p -> p.playerName()).sorted().toList());
    }

    @Test
    void optOutRetainsDraftOnReplayAndSupportsManualGeneration() {
        var body = request(4);
        body.put("generateBracket", false);
        body.put("clientRequestId", UUID.randomUUID());
        TournamentDto draft = create(body, 201);
        assertDraft(draft);
        body.put("generateBracket", true);
        body.put("drawMode", "RANDOM");
        TournamentDto replay = create(body, 200);
        assertEquals(draft.id(), replay.id());
        assertDraft(replay);
        given().contentType("application/json").queryParam("drawMode", "ORDER").post("/api/tournaments/{id}/generate-bracket", draft.id())
                .then().statusCode(200);
        assertReady(get(draft.id()), 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void incompleteLineupDefaultsToDraftForIncrementalClients(int count) {
        var body = request(count);
        if (count == 0) body.remove("participants");
        TournamentDto draft = create(body, 201);
        assertDraft(draft);
        for (int i = count; i < 2; i++) {
            given().contentType("application/json").body(participant(i))
                    .post("/api/tournaments/{id}/participants", draft.id()).then().statusCode(201);
        }
        given().contentType("application/json").post("/api/tournaments/{id}/generate-bracket", draft.id()).then().statusCode(200);
        assertReady(get(draft.id()), 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void explicitGenerationRequiresTwoParticipantsBeforeStoringAnything(int count) {
        var body = request(count);
        body.put("generateBracket", true);
        given().contentType("application/json").body(body).post("/api/tournaments").then().statusCode(400);
        assertNotStored(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "0"})
    void invalidModeIsRejectedEvenWhenGenerationIsDisabled(String mode) {
        var body = request(2);
        body.put("drawMode", mode);
        body.put("generateBracket", false);
        given().contentType("application/json").body(body).post("/api/tournaments").then().statusCode(400);
        assertNotStored(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "", "1"})
    void generationFlagRejectsStringCoercion(String value) {
        var body = request(2);
        body.put("generateBracket", value);
        given().contentType("application/json").body(body).post("/api/tournaments").then().statusCode(400);
        assertNotStored(body);
    }

    @Test
    void generationFlagRejectsNumbers() {
        var body = request(2);
        body.put("generateBracket", 1);
        given().contentType("application/json").body(body).post("/api/tournaments").then().statusCode(400);
        assertNotStored(body);
    }

    @Test
    void retryFinishesAnInterruptedCreationUsingItsPersistedDrawChoice() {
        UUID requestId = UUID.randomUUID();
        Tournament pending = new Tournament();
        pending.name = "Interrupted " + requestId;
        pending.clientRequestId = requestId;
        pending.creationDrawMode = BracketDrawMode.RANDOM;
        List<TournamentParticipant> participants = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TournamentParticipant p = new TournamentParticipant();
            p.playerName = "P" + i;
            p.modelId = "openai/gpt-4o-mini";
            p.seed = (i + 1) * 10;
            participants.add(p);
        }
        // Simulate a process stopping after the lineup transaction committed, before generation began.
        UUID id = service.persistNewTournament(pending, participants).id;
        assertDraft(get(id));
        var retry = request(0);
        retry.put("clientRequestId", requestId);
        retry.put("generateBracket", false);
        retry.put("drawMode", "ORDER");
        TournamentDto recovered = create(retry, 200);
        assertEquals(id, recovered.id());
        assertReady(recovered, 3);
        assertEquals(List.of(0, 1, 2, 3), seeds(recovered));
        assertEquals(gameIds(recovered), gameIds(create(retry, 200)));
    }

    @Test
    void concurrentCreateRetriesBothReturnTheSameReadyBracket() throws Exception {
        var body = request(4);
        body.put("drawMode", "RANDOM");
        body.put("generateBracket", true);
        body.put("clientRequestId", UUID.randomUUID());
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                go.await();
                return given().contentType("application/json").body(body).post("/api/tournaments");
            });
            var second = pool.submit(() -> {
                go.await();
                return given().contentType("application/json").body(body).post("/api/tournaments");
            });
            go.countDown();
            var a = first.get(30, TimeUnit.SECONDS);
            var b = second.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 201), List.of(a.statusCode(), b.statusCode()).stream().sorted().toList());
            TournamentDto ta = a.as(TournamentDto.class);
            TournamentDto tb = b.as(TournamentDto.class);
            assertReady(ta, 3);
            assertReady(tb, 3);
            assertEquals(ta.id(), tb.id());
            assertEquals(participantsById(ta), participantsById(tb));
            assertEquals(gameIds(ta), gameIds(tb));
        }
    }

    private void assertNotStored(Map<String, Object> body) {
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> tournaments.count("name", body.get("name"))));
    }

    private static void assertReady(TournamentDto t, int games) {
        assertEquals("IN_PROGRESS", t.status());
        assertEquals(games, t.games().size());
        assertTrue(t.games().stream().allMatch(g -> List.of("WAITING", "COMPLETED").contains(g.status())));
        assertFalse(t.games().stream().anyMatch(g -> "IN_PROGRESS".equals(g.status())));
    }

    private static void assertDraft(TournamentDto t) {
        assertEquals("CREATED", t.status());
        assertTrue(t.games().isEmpty());
    }

    private static List<Integer> seeds(TournamentDto t) {
        return t.participants().stream().map(p -> p.seed()).sorted().toList();
    }

    private static List<dev.aichessarena.dto.ParticipantDto> participantsById(TournamentDto t) {
        // Response collection order is unspecified; compare every DTO field and preserve duplicates.
        return t.participants().stream().sorted(Comparator.comparing(p -> p.id())).toList();
    }

    private static List<UUID> gameIds(TournamentDto t) {
        return t.games().stream().map(g -> g.id()).sorted().toList();
    }

    private static List<String> firstPair(TournamentDto t) {
        var game = t.games().stream().filter(g -> "Semifinal".equals(g.bracketRound())
                && g.bracketPosition() == 0).findFirst().orElseThrow();
        return List.of(game.whitePlayerName(), game.blackPlayerName());
    }

    private static TournamentDto create(Map<String, Object> body, int status) {
        return given().contentType("application/json").body(body).post("/api/tournaments")
                .then().statusCode(status).extract().as(TournamentDto.class);
    }

    private static TournamentDto get(UUID id) {
        return given().get("/api/tournaments/{id}", id).then().statusCode(200).extract().as(TournamentDto.class);
    }

    private static Map<String, Object> request(int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Create bracket " + UUID.randomUUID());
        body.put("participants", java.util.stream.IntStream.range(0, count).mapToObj(TournamentCreationIT::participant).toList());
        return body;
    }

    private static Map<String, Object> participant(int i) {
        return Map.of("playerName", "P" + i, "modelId", "openai/gpt-4o-mini", "seed", (i + 1) * 10);
    }
}
