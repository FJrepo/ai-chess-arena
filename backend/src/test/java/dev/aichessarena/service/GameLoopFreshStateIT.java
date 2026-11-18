package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Runs the real game loop (its own thread and request context) against a real PostgreSQL with a scripted model, and
 * checks that every turn sees what other transactions committed since the loop started: the moves (PGN), the cost
 * and tournament settings changed mid-game, including the reasoning effort (tournament level and a participant's
 * override), which is also recorded on every attempt.
 */
@QuarkusTest
class GameLoopFreshStateIT {

    private static final List<String> SCRIPT = List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6");
    private static final double COST_PER_ANSWER = 0.25;
    /** Before answering this turn (0-based), the tournament's settings are changed through the REST API. */
    private static final int SETTINGS_CHANGED_DURING_TURN = 2;
    private static final int CHANGED_MAX_OUTPUT_TOKENS = 2000;
    private static final int CHANGED_MAX_ATTEMPTS = 5;

    @Inject
    GameLifecycleService lifecycle;

    @Inject
    GameConfigurationHistory configurations;

    @Inject
    GameEngineService gameEngineService;

    @Inject
    GameTurnService gameTurnService;

    @Inject
    GameRepository gameRepository;

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    @Test
    void everyTurnSeesTheMovesCostAndSettingsCommittedSinceTheLoopStarted() throws InterruptedException {
        UUID[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Fresh state " + UUID.randomUUID();
            tournament.reasoningEffort = ReasoningEffort.MEDIUM;
            tournamentRepository.persist(tournament);
            TournamentParticipant white = participant(tournament, "White", "test/white");
            TournamentParticipant black = participant(tournament, "Black", "test/black");
            Game game = new Game();
            game.tournament = tournament;
            game.whitePlayerName = "White";
            game.whiteModelId = "test/white";
            game.whiteParticipant = white;
            game.blackPlayerName = "Black";
            game.blackModelId = "test/black";
            game.blackParticipant = black;
            gameRepository.persist(game);
            return new UUID[] {tournament.id, game.id, black.id};
        });

        ScriptedModel model = new ScriptedModel(ids[0], ids[2]);
        GameTurnService realTurns = ClientProxy.unwrap(gameTurnService);
        List<GameTurnService.TurnRequest> turns = Collections.synchronizedList(new ArrayList<>());
        QuarkusMock.installMockForType(model, OpenRouterService.class);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override
            public TurnResult playTurn(TurnRequest request) {
                turns.add(request);
                return realTurns.playTurn(request);
            }
        }, GameTurnService.class);

        gameEngineService.startGame(ids[1]);
        awaitStatus(ids[1], GameStatus.PAUSED);

        // One prompt per scripted move, plus the final request that pauses the game.
        assertEquals(SCRIPT.size() + 1, model.prompts.size(), String.join("\n----\n", model.prompts));
        // Every turn is checked, so a failure lists all stale values at once.
        List<Executable> checks = new ArrayList<>();
        List<String> played = new ArrayList<>();
        for (int turn = 0; turn < model.prompts.size(); turn++) {
            String label = " of turn " + (turn + 1);
            String expectedPgnLine = "PGN: " + expectedPgn(played);
            String pgnLine = pgnLine(model.prompts.get(turn));
            checks.add(() -> assertEquals(expectedPgnLine, pgnLine, "PGN in the prompt" + label));

            GameTurnService.TurnRequest request = turns.get(turn);
            BigDecimal expectedCost = BigDecimal.valueOf(COST_PER_ANSWER * turn);
            checks.add(() -> assertEquals(0, expectedCost.compareTo(request.totalCostUsd()),
                    "cost at the start" + label + ": expected " + expectedCost + " but was " + request.totalCostUsd()));
            boolean changed = turn > SETTINGS_CHANGED_DURING_TURN;
            int tokens = model.maxOutputTokens.get(turn);
            checks.add(() -> assertEquals(changed ? CHANGED_MAX_OUTPUT_TOKENS : Tournament.DEFAULT_MAX_OUTPUT_TOKENS,
                    tokens, "output limit" + label));
            checks.add(() -> assertEquals(changed ? CHANGED_MAX_ATTEMPTS : 3, request.maxAttempts(), "retries" + label));
            checks.add(() -> assertEquals(changed ? ChatMode.MANDATORY : ChatMode.OPTIONAL, request.chatMode(),
                    "chat mode" + label));
            checks.add(() -> assertEquals(changed ? ChatMode.MANDATORY : ChatMode.OPTIONAL,
                    request.tournamentChatMode(), "tournament chat mode" + label));
            String system = model.systemPrompts.get(turn);
            boolean toneChanged = turn > 3;
            checks.add(() -> assertEquals(toneChanged ? BanterLevel.SPICY : null, request.tournamentBanterLevel(),
                    "live tone" + label));
            checks.add(() -> assertEquals(toneChanged, system.contains(PromptService.toneSentence(BanterLevel.SPICY)),
                    "system tone" + label));
            String expectedVersion = changed ? "v2-chat-required" : "v2";
            if (toneChanged) expectedVersion += "-banter-spicy";
            String version = expectedVersion;
            checks.add(() -> assertEquals(version, request.promptVersion(), "prompt version" + label));
            checks.add(() -> assertEquals(new PromptService().computePromptHash(system), request.promptHash(),
                    "prompt hash" + label));
            // White follows the tournament's new level, Black's override wins over it.
            boolean white = turn % 2 == 0;
            ReasoningEffort expectedEffort = !changed ? ReasoningEffort.MEDIUM : white ? ReasoningEffort.HIGH : ReasoningEffort.LOW;
            ReasoningEffort sentEffort = model.efforts.get(turn);
            checks.add(() -> assertEquals(expectedEffort, request.reasoningEffort(), "requested effort" + label));
            checks.add(() -> assertEquals(expectedEffort, sentEffort, "effort sent" + label));
            if (turn < SCRIPT.size()) {
                played.add(SCRIPT.get(turn));
            }
        }
        assertAll(checks);
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6",
                QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(ids[1]).pgn));

        // Every attempt records the level that was sent with it.
        List<String> recorded = QuarkusTransaction.requiringNew().call(() -> moveAttemptRepository
                .list("game.id = ?1 order by createdAt", ids[1]).stream()
                .map(attempt -> attempt.outcome + " " + attempt.color + " " + attempt.moveNumber + " "
                        + (attempt.reasoningEffort != null ? attempt.reasoningEffort.apiValue() : "-"))
                .toList());
        assertEquals(List.of(
                "ACCEPTED WHITE 1 medium", "ACCEPTED BLACK 1 medium", "ILLEGAL_MOVE WHITE 2 medium",
                "ACCEPTED WHITE 2 medium",
                "ACCEPTED BLACK 2 low", "ACCEPTED WHITE 3 high", "ACCEPTED BLACK 3 low",
                "PROVIDER_FATAL WHITE 4 high"), recorded);
        given().when().get("/api/games/{id}", ids[1]).then().statusCode(200)
                .body("whiteReasoningEffort", equalTo("high"))
                .body("startingConfiguration.reasoningEffort", equalTo("medium"))
                .body("configurationHistory.revision", equalTo(List.of(1, 2, 3)))
                .body("configurationHistory[0].settings.reasoningEffort", equalTo("medium"))
                .body("configurationHistory[1].settings.reasoningEffort", equalTo("high"))
                .body("configurationHistory[1].settings.black.requestedReasoningEffort", equalTo("low"))
                .body("configurationHistory[2].settings.banterLevel", equalTo("SPICY"))
                .body("attempts.configurationRevision", equalTo(List.of(1, 1, 1, 1, 2, 3, 3, 3)));
        given().when().get("/api/games/{id}/attempts", ids[1]).then().statusCode(200)
                .body("[2].reasoningEffort", equalTo("medium"))
                .body("[2].configurationRevision", equalTo(1))
                .body("[3].reasoningEffort", equalTo("medium"))
                .body("[3].configurationRevision", equalTo(1))
                .body("[3].attemptNumber", equalTo(2))
                .body("[4].reasoningEffort", equalTo("low"))
                .body("[4].configurationRevision", equalTo(2));
        given().when().get("/api/tournaments/{id}", ids[0]).then().statusCode(200)
                .body("reasoningEffort", equalTo("high"))
                .body("games[0].startingConfiguration.reasoningEffort", equalTo("medium"));

        QuarkusTransaction.requiringNew().run(() -> gameRepository.deleteById(ids[1]));
        assertTrue(QuarkusTransaction.requiringNew().call(() -> configurations.read(ids[1]).isEmpty()));
        assertTrue(QuarkusTransaction.requiringNew().call(() -> moveAttemptRepository.findByGameId(ids[1]).isEmpty()));
    }

    @Test
    void resumingALegacyGameDoesNotInventStartingSettings() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.status = GameStatus.PAUSED;
            game.startedAt = LocalDateTime.now().minusDays(1);
            gameRepository.persist(game);
            return game.id;
        });

        lifecycle.markGameStarted(id);

        given().when().get("/api/games/{id}", id).then().statusCode(200)
                .body("startingConfiguration", nullValue())
                .body("configurationHistory.size()", equalTo(0));
    }

    private static TournamentParticipant participant(Tournament tournament, String name, String modelId) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.modelId = modelId;
        participant.persist();
        return participant;
    }

    @Test
    void detachAllRefusesToRunInsideATransaction() {
        assertThrows(IllegalStateException.class,
                () -> QuarkusTransaction.requiringNew().run(gameRepository::detachAll));
    }

    private static String expectedPgn(List<String> played) {
        if (played.isEmpty()) {
            return "(starting position)";
        }
        StringBuilder pgn = new StringBuilder();
        for (int i = 0; i < played.size(); i++) {
            if (i > 0) {
                pgn.append(' ');
            }
            if (i % 2 == 0) {
                pgn.append(i / 2 + 1).append(". ");
            }
            pgn.append(played.get(i));
        }
        return pgn.toString();
    }

    private static String pgnLine(String prompt) {
        return prompt.lines().map(String::trim).filter(line -> line.startsWith("PGN:")).findFirst().orElse(prompt);
    }

    private void awaitStatus(UUID gameId, GameStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        GameStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            status = QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(gameId).status);
            if (status == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(false, "game did not reach " + expected + " (last status " + status + ")");
    }

    /**
     * Plays the script, changes the tournament's settings and Black's reasoning effort once mid-game, then pauses the
     * game with a fatal error. Without a catalog every reasoning effort is sent as requested.
     */
    private static final class ScriptedModel extends OpenRouterService {
        private final UUID tournamentId;
        private final UUID blackParticipantId;
        private boolean retryPending;
        final List<String> systemPrompts = Collections.synchronizedList(new ArrayList<>());
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> maxOutputTokens = Collections.synchronizedList(new ArrayList<>());
        final List<ReasoningEffort> efforts = Collections.synchronizedList(new ArrayList<>());

        private ScriptedModel(UUID tournamentId, UUID blackParticipantId) {
            this.tournamentId = tournamentId;
            this.blackParticipantId = blackParticipantId;
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                                int maxOutputTokens, ReasoningEffort reasoningEffort) {
            if (retryPending) {
                retryPending = false;
                assertEquals(ReasoningEffort.MEDIUM, reasoningEffort);
                return answer(SETTINGS_CHANGED_DURING_TURN);
            }
            int turn = prompts.size();
            prompts.add(messages.getLast().content());
            systemPrompts.add(messages.getFirst().content());
            this.maxOutputTokens.add(maxOutputTokens);
            efforts.add(reasoningEffort);
            if (turn == SETTINGS_CHANGED_DURING_TURN) {
                given().contentType("application/json")
                        .body(("{\"chatMode\":\"MANDATORY\",\"maxOutputTokens\":%d,\"maxAttempts\":%d,"
                                + "\"reasoningEffort\":\"HIGH\"}")
                                .formatted(CHANGED_MAX_OUTPUT_TOKENS, CHANGED_MAX_ATTEMPTS))
                        .when().put("/api/tournaments/{id}", tournamentId.toString())
                        .then().statusCode(200);
                given().contentType("application/json")
                        .body("{\"reasoningEffort\":\"low\"}")
                        .when().put("/api/tournaments/{id}/participants/{pid}", tournamentId.toString(),
                                blackParticipantId.toString())
                        .then().statusCode(200);
                retryPending = true;
                return new LlmResponse("{\"move\":\"Ke8\"}", 8, 3, null, 1, null, false, null);
            }
            if (turn == 3) {
                // Change tone alone while a request is in flight: this request retains its original prompt.
                given().contentType("application/json").body("{\"banterLevel\":\"SPICY\"}")
                        .put("/api/tournaments/{id}", tournamentId.toString()).then().statusCode(200);
            }
            if (turn >= SCRIPT.size()) {
                return LlmResponse.providerFailure(ProviderOutcome.FATAL, 402, "Script finished", 1, null);
            }
            return answer(turn);
        }

        private LlmResponse answer(int turn) {
            String answer = "{\"move\":\"%s\",\"message\":\"Move %d\"}".formatted(SCRIPT.get(turn), turn + 1);
            return new LlmResponse(answer, 10, 5, COST_PER_ANSWER, 1, answer, false, null);
        }
    }
}
