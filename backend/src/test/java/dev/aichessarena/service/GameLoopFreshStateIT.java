package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Runs the real game loop (its own thread and request context) against a real PostgreSQL with a scripted model, and
 * checks that every turn sees what other transactions committed since the loop started: the moves (PGN), the cost
 * and tournament settings changed mid-game.
 */
@QuarkusTest
class GameLoopFreshStateIT {

    private static final List<String> SCRIPT = List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6");
    private static final double COST_PER_ANSWER = 0.25;
    /** Before answering this turn (0-based), the tournament's settings are changed through the REST API. */
    private static final int SETTINGS_CHANGED_DURING_TURN = 2;
    private static final int CHANGED_MAX_OUTPUT_TOKENS = 2000;
    private static final int CHANGED_MAX_RETRIES = 5;

    @Inject
    GameEngineService gameEngineService;

    @Inject
    GameTurnService gameTurnService;

    @Inject
    GameRepository gameRepository;

    @Inject
    TournamentRepository tournamentRepository;

    @Test
    void everyTurnSeesTheMovesCostAndSettingsCommittedSinceTheLoopStarted() throws InterruptedException {
        UUID[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Fresh state " + UUID.randomUUID();
            tournamentRepository.persist(tournament);
            Game game = new Game();
            game.tournament = tournament;
            game.whitePlayerName = "White";
            game.whiteModelId = "test/white";
            game.blackPlayerName = "Black";
            game.blackModelId = "test/black";
            gameRepository.persist(game);
            return new UUID[] {tournament.id, game.id};
        });

        ScriptedModel model = new ScriptedModel(ids[0]);
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
            checks.add(() -> assertEquals(changed ? CHANGED_MAX_RETRIES : 3, request.maxRetries(), "retries" + label));
            checks.add(() -> assertEquals(changed ? ChatMode.MANDATORY : ChatMode.OPTIONAL, request.chatMode(),
                    "chat mode" + label));
            checks.add(() -> assertEquals(changed ? ChatMode.MANDATORY : ChatMode.OPTIONAL,
                    request.tournamentChatMode(), "tournament chat mode" + label));
            if (turn < SCRIPT.size()) {
                played.add(SCRIPT.get(turn));
            }
        }
        assertAll(checks);
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6",
                QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(ids[1]).pgn));
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

    /** Plays the script, changes the tournament's settings once mid-game, then pauses the game with a fatal error. */
    private static final class ScriptedModel extends OpenRouterService {
        private final UUID tournamentId;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> maxOutputTokens = Collections.synchronizedList(new ArrayList<>());

        private ScriptedModel(UUID tournamentId) {
            this.tournamentId = tournamentId;
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                                int maxOutputTokens) {
            int turn = prompts.size();
            prompts.add(messages.getLast().content());
            this.maxOutputTokens.add(maxOutputTokens);
            if (turn == SETTINGS_CHANGED_DURING_TURN) {
                given().contentType("application/json")
                        .body("{\"chatMode\":\"MANDATORY\",\"maxOutputTokens\":%d,\"maxRetries\":%d}"
                                .formatted(CHANGED_MAX_OUTPUT_TOKENS, CHANGED_MAX_RETRIES))
                        .when().put("/api/tournaments/{id}", tournamentId.toString())
                        .then().statusCode(200);
            }
            if (turn >= SCRIPT.size()) {
                return LlmResponse.providerFailure(ProviderOutcome.FATAL, 402, "Script finished", 1, null);
            }
            String answer = "{\"move\":\"%s\",\"message\":\"Move %d\"}".formatted(SCRIPT.get(turn), turn + 1);
            return new LlmResponse(answer, 10, 5, COST_PER_ANSWER, 1, answer, false, null);
        }
    }
}
