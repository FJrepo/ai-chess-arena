package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.dto.ChatMessageDto;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.websocket.GameWebSocket;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Resignation (migration V13) through the REST API, the migration itself and the real game loop with a scripted
 * model, against a real PostgreSQL.
 */
@QuarkusTest
class ResignationIT {

    @Inject
    EntityManager entityManager;

    @Inject
    GameEngineService gameEngineService;

    @Inject
    GameRepository gameRepository;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentParticipantRepository participantRepository;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void resignationIsOffByDefaultAndChangesOnlyWhenSent() {
        String id = given().contentType("application/json").body(withName("{}"))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("resignationAllowed", equalTo(false))
                .extract().path("id");
        assertEquals(false, stored(id));

        given().contentType("application/json").body("{\"resignationAllowed\":true}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("resignationAllowed", equalTo(true));
        assertEquals(true, stored(id));

        given().contentType("application/json").body("{\"maxRetries\":2}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("resignationAllowed", equalTo(true));

        String allowed = given().contentType("application/json").body(withName("{\"resignationAllowed\":true}"))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("resignationAllowed", equalTo(true))
                .extract().path("id");
        given().when().get("/api/tournaments/{id}", allowed)
                .then().statusCode(200)
                .body("resignationAllowed", equalTo(true));
    }

    @Test
    void aValueThatIsNotABooleanIsRejected() {
        given().contentType("application/json").body(withName("{\"resignationAllowed\":\"sometimes\"}"))
                .when().post("/api/tournaments")
                .then().statusCode(400);

        String id = given().contentType("application/json").body(withName("{\"resignationAllowed\":true}"))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .extract().path("id");
        given().contentType("application/json").body("{\"resignationAllowed\":[1]}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(400);
        assertEquals(true, stored(id));
    }

    @Test
    void promptTemplateListsTheResignationRulesOfEveryChatMode() {
        given().when().get("/api/config/prompt-template")
                .then().statusCode(200)
                .body("chatModes.OPTIONAL.version", equalTo("v2"))
                .body("chatModes.OPTIONAL.template", not(containsString("Resigning")))
                .body("resignationChatModes.OFF.version", equalTo("v2-silent-resign"))
                .body("resignationChatModes.OPTIONAL.version", equalTo("v2-resign"))
                .body("resignationChatModes.MANDATORY.version", equalTo("v2-chat-required-resign"))
                .body("resignationChatModes.OPTIONAL.template", containsString("9. Resigning is allowed"));
    }

    /** Runs the migrations up to V12, stores a tournament, then runs V13: existing tournaments do not resign. */
    @Test
    void migrationV13LeavesExistingTournamentsPlayingToTheEnd() throws SQLException {
        String database = "resignation_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "12").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO tournaments (name, chat_mode) VALUES ('Existing', 'MANDATORY')");
            }

            flyway(url, "latest").migrate();

            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                assertEquals(Boolean.FALSE, resignationAllowed(statement, "Existing"));
                statement.execute("INSERT INTO tournaments (name) VALUES ('Fresh')");
                assertEquals(Boolean.FALSE, resignationAllowed(statement, "Fresh"));
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /**
     * A final played by a scripted model: Black tries to resign while it is not allowed (retried), the operator then
     * allows resignation mid-game (applies from the next turn), Black tries again before move 10 (retried), sends a
     * move along with a flag that is not allowed yet (played), and finally resigns at move 10, after an illegal answer,
     * with last words and an ignored move. The game, the tournament, the attempts, the API and the analytics all show the resignation.
     */
    @Test
    void aModelResignsInARunningGameAndTheTournamentIsDecided() throws InterruptedException {
        UUID[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Resignation " + UUID.randomUUID();
            tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
            tournamentRepository.persist(tournament);
            TournamentParticipant white = participant(tournament, "White Bot", "test/white", 0);
            TournamentParticipant black = participant(tournament, "Black Bot", "test/black", 1);
            Game game = new Game();
            game.tournament = tournament;
            game.bracketRound = "Final";
            game.bracketPosition = 0;
            game.whiteParticipant = white;
            game.whitePlayerName = white.playerName;
            game.whiteModelId = white.modelId;
            game.blackParticipant = black;
            game.blackPlayerName = black.playerName;
            game.blackModelId = black.modelId;
            gameRepository.persist(game);
            return new UUID[] {tournament.id, game.id};
        });

        ScriptedModel model = new ScriptedModel(ids[0]);
        QuarkusMock.installMockForType(model, OpenRouterService.class);
        gameEngineService.startGame(ids[1]);
        awaitStatus(ids[1], GameStatus.COMPLETED);

        assertEquals(ScriptedModel.SCRIPT.size(), model.calls.size(), String.join("\n----\n", model.lastMessages()));
        // Not offered: the retry explains it. Offered but before move 10: the retry says so.
        assertTrue(model.calls.get(2).getLast().content().contains("Resigning is not allowed in this game"));
        assertTrue(model.calls.get(9).getLast().content().contains("Resigning is not allowed before your own move 10 (book moves do not count)"));
        // The setting changed during call 6 (White's 3rd move) applies from Black's 3rd move on.
        assertFalse(model.calls.get(5).getFirst().content().contains("9. Resigning is allowed"));
        assertTrue(model.calls.get(6).getFirst().content().contains("9. Resigning is allowed"));
        // The turn prompt offers resignation from move 10 only.
        assertFalse(model.calls.get(19).getLast().content().contains("You may resign"), "Black's 9th move");
        assertTrue(model.calls.get(20).getLast().content().contains("You may resign"), "White's 10th move");

        given().when().get("/api/games/{id}", ids[1].toString())
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("result", equalTo("WHITE_WINS"))
                .body("resultReason", equalTo("RESIGNATION"))
                .body("advancedPlayerName", equalTo("White Bot"))
                .body("pgn", equalTo("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 6. Re1 b5 7. Bb3 d6 "
                        + "8. c3 O-O 9. h3 Nb8 10. d4"))
                .body("moves[3].promptVersion", equalTo("v2"))
                .body("moves[4].promptVersion", equalTo("v2"))
                .body("moves[5].promptVersion", equalTo("v2-resign"))
                .body("moves.find { it.san == 'Be7' }.retryCount", equalTo(0))
                .body("moves.find { it.san == 'Nc6' }.retryCount", equalTo(0))
                .body("moves.find { it.san == 'e5' }.retryCount", equalTo(1))
                .body("moves.find { it.san == 'Nf6' }.retryCount", equalTo(1))
                .body("resignation.color", equalTo("BLACK"))
                .body("resignation.moveNumber", equalTo(10))
                .body("resignation.lastWords", equalTo("Good game"))
                .body("resignation.opponentMessage", equalTo("White 10"))
                .body("resignation.opponentMessageMoveNumber", equalTo(10))
                // Stockfish does not run in the tests: the winning chance is unknown, nothing breaks.
                .body("resignation.winChance", nullValue())
                .body("resignation.notLosing", equalTo(false))
                // 23 answers at 0.01 each, the rejected and the resigning ones included.
                .body("totalCostUsd", equalTo(0.23f));

        given().when().get("/api/games/{id}/attempts", ids[1].toString())
                .then().statusCode(200)
                .body("findAll { it.outcome == 'RESIGN_NOT_ALLOWED' }.size()", equalTo(2))
                .body("find { it.outcome == 'RESIGN_NOT_ALLOWED' }.errorMessage",
                        equalTo("Resigning is not allowed in this game; send a legal move."))
                .body("find { it.outcome == 'RESIGNED' }.moveNumber", equalTo(10))
                .body("find { it.outcome == 'RESIGNED' }.color", equalTo("BLACK"))
                .body("find { it.outcome == 'RESIGNED' }.proposedMove", equalTo("Bb7"))
                .body("find { it.outcome == 'RESIGNED' }.promptTokens", equalTo(10));

        given().when().get("/api/tournaments/{id}", ids[0].toString())
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("winnerName", equalTo("White Bot"));

        // With an evaluation of White's 10th move (+1.50 for White), Black resigned at 36.5%: not losing.
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                        "update moves set evaluation_cp = 150 where game_id = cast(?1 as uuid) "
                                + "and move_number = 10 and color = 'WHITE'")
                .setParameter(1, ids[1].toString())
                .executeUpdate());
        given().when().get("/api/games/{id}", ids[1].toString())
                .then().statusCode(200)
                .body("resignation.winChance", equalTo(0.3653f))
                .body("resignation.notLosing", equalTo(true));

        given().queryParam("tournamentId", ids[0].toString())
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models.find { it.modelId == 'test/black' }.resignations", equalTo(1))
                .body("models.find { it.modelId == 'test/black' }.resignationsEvaluated", equalTo(1))
                .body("models.find { it.modelId == 'test/black' }.resignationsWhileNotLosing", equalTo(1))
                .body("models.find { it.modelId == 'test/black' }.wonByResignation", equalTo(0))
                .body("models.find { it.modelId == 'test/white' }.wonByResignation", equalTo(1))
                .body("models.find { it.modelId == 'test/white' }.resignations", equalTo(0))
                // Black: 9 moves, two of them retried once (e5, Nf6), and the illegal Qxh7 before resigning:
                // 7 first-try turns of 10, 3 retries over 10 turns.
                .body("models.find { it.modelId == 'test/black' }.movesSampled", equalTo(9))
                .body("models.find { it.modelId == 'test/black' }.firstTryLegalRate", equalTo(0.7f))
                .body("models.find { it.modelId == 'test/black' }.averageRetriesPerMove", equalTo(0.3f));
        given().queryParam("tournamentId", ids[0].toString())
                .when().get("/api/analytics/reliability")
                .then().statusCode(200)
                .body("models.find { it.modelId == 'test/black' }.firstTryLegalRate", equalTo(0.7f))
                .body("models.find { it.modelId == 'test/black' }.averageRetriesPerMove", equalTo(0.3f))
                .body("models.find { it.modelId == 'test/white' }.firstTryLegalRate", equalTo(1.0f));
    }

    /**
     * Regression: the game-over update went out before the resignation was committed, so a game view that reloaded
     * the game on it could read the game as still running. At the moment of the broadcast, another transaction must
     * already see the result.
     */
    @Test
    void theGameOverUpdateIsSentOnlyOnceTheResultIsVisible() {
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 10";
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.whitePlayerName = "White";
            game.whiteModelId = "test/white";
            game.blackPlayerName = "Black";
            game.blackModelId = "test/black";
            game.status = GameStatus.IN_PROGRESS;
            game.currentFen = fen;
            gameRepository.persist(game);
            return game.id;
        });
        List<String> seenAtBroadcast = Collections.synchronizedList(new ArrayList<>());
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override
            public void broadcastStatusChange(UUID id, String status, String result, String resultReason,
                                              int totalMoves, java.math.BigDecimal totalCostUsd, String detail,
                                              long statusRevision, Instant resumeRequestedAt,
                                              RunCapacity capacity) {
                seenAtBroadcast.add(status + " announced, stored "
                        + QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(id).status));
            }

            @Override
            public void broadcastChat(UUID id, ChatMessageDto chat, String senderName) {
                seenAtBroadcast.add("chat announced, stored "
                        + QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(id).status));
            }
        }, GameWebSocket.class);

        gameLifecycleService.resign(new GameLifecycleService.Resignation(gameId, 10, "BLACK", "test/black",
                "Black", "gg", null, fen));

        assertEquals(List.of("chat announced, stored COMPLETED", "COMPLETED announced, stored COMPLETED"),
                seenAtBroadcast);
    }

    private TournamentParticipant participant(Tournament tournament, String name, String modelId, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.modelId = modelId;
        participant.seed = seed;
        participantRepository.persist(participant);
        return participant;
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

    /** Answers in a fixed order (one entry per model call, retries included). */
    private static final class ScriptedModel extends OpenRouterService {
        static final List<String> SCRIPT = List.of(
                "{\"move\":\"e4\",\"message\":\"White 1\"}",
                "{\"resign\":true,\"message\":\"Already?\"}",
                "{\"move\":\"e5\"}",
                "{\"move\":\"Nf3\"}",
                "{\"move\":\"Nc6\"}",
                "{\"move\":\"Bb5\"}",
                "{\"move\":\"a6\"}",
                "{\"move\":\"Ba4\"}",
                "{\"resign\":true}",
                "{\"move\":\"Nf6\"}",
                "{\"move\":\"O-O\"}",
                "{\"resign\":true,\"move\":\"Be7\"}",
                "{\"move\":\"Re1\"}",
                "{\"move\":\"b5\"}",
                "{\"move\":\"Bb3\"}",
                "{\"move\":\"d6\"}",
                "{\"move\":\"c3\"}",
                "{\"move\":\"O-O\"}",
                "{\"move\":\"h3\"}",
                "{\"move\":\"Nb8\"}",
                "{\"move\":\"d4\",\"message\":\"White 10\"}",
                "{\"move\":\"Qxh7\"}",
                "{\"resign\":true,\"message\":\"Good game\",\"move\":\"Bb7\"}");
        /** During this call (White's 3rd move) the operator allows resignation. */
        static final int SETTING_CHANGED_DURING_CALL = 5;

        private final UUID tournamentId;
        final List<List<ChatMsg>> calls = Collections.synchronizedList(new ArrayList<>());

        private ScriptedModel(UUID tournamentId) {
            this.tournamentId = tournamentId;
        }

        List<String> lastMessages() {
            return calls.stream().map(call -> call.getLast().content()).toList();
        }

        @Override
        public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                                int maxOutputTokens, ReasoningEffort reasoningEffort) {
            int call = calls.size();
            calls.add(List.copyOf(messages));
            if (call == SETTING_CHANGED_DURING_CALL) {
                given().contentType("application/json").body("{\"resignationAllowed\":true}")
                        .when().put("/api/tournaments/{id}", tournamentId.toString())
                        .then().statusCode(200);
            }
            if (call >= SCRIPT.size()) {
                return LlmResponse.providerFailure(ProviderOutcome.FATAL, 402, "Script finished", 1, null);
            }
            String answer = SCRIPT.get(call);
            return new LlmResponse(answer, 10, 5, 0.01, 1, answer, false, null);
        }
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Boolean resignationAllowed(Statement statement, String name) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
                "SELECT resignation_allowed FROM tournaments WHERE name = '" + name + "'")) {
            return rows.next() ? rows.getBoolean(1) : null;
        }
    }

    private static String withName(String body) {
        String name = "\"name\":\"Resign " + UUID.randomUUID() + "\"";
        return "{}".equals(body) ? "{" + name + "}" : "{" + name + "," + body.substring(1);
    }

    private Object stored(String id) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                        "select resignation_allowed from tournaments where id = cast(?1 as uuid)")
                .setParameter(1, id)
                .getSingleResult());
    }
}
