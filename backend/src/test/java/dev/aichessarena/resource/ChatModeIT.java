package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Chat modes (migration V12) through the REST API and the migration itself, against a real PostgreSQL. */
@QuarkusTest
class ChatModeIT {

    @Inject
    EntityManager entityManager;

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    GameRepository gameRepository;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void createAcceptsChatModeAndTheOlderTrashTalkFlag() {
        // neither: OPTIONAL
        assertCreated("{}", "OPTIONAL", true);
        // only trashTalkEnabled
        assertCreated("{\"trashTalkEnabled\":false}", "OFF", false);
        assertCreated("{\"trashTalkEnabled\":true}", "OPTIONAL", true);
        // only chatMode
        assertCreated("{\"chatMode\":\"MANDATORY\"}", "MANDATORY", true);
        assertCreated("{\"chatMode\":\"off\"}", "OFF", false);
        // both: chatMode wins
        assertCreated("{\"chatMode\":\"MANDATORY\",\"trashTalkEnabled\":false}", "MANDATORY", true);
        assertCreated("{\"chatMode\":\"OFF\",\"trashTalkEnabled\":true}", "OFF", false);
    }

    @Test
    void unknownChatModesAreRejected() {
        for (String body : new String[] {"{\"chatMode\":\"LOUD\"}", "{\"chatMode\":\"\"}",
                "{\"chatMode\":\"LOUD\",\"trashTalkEnabled\":true}"}) {
            given().contentType("application/json").body(withName(body))
                    .when().post("/api/tournaments")
                    .then().statusCode(400)
                    .body(containsString("chatMode"));
        }

        String id = create("{\"chatMode\":\"MANDATORY\"}");
        given().contentType("application/json").body("{\"chatMode\":\"SHOUTING\"}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(400);
        assertEquals("MANDATORY", stored(id, "chat_mode"));
    }

    @Test
    void updateChangesTheModeOnlyWhenAFieldIsSentAndKeepsTheOldColumnInSync() {
        String id = create("{\"chatMode\":\"MANDATORY\"}");
        assertEquals(true, stored(id, "trash_talk_enabled"));

        given().contentType("application/json").body("{\"maxRetries\":2}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("chatMode", equalTo("MANDATORY"));

        given().contentType("application/json").body("{\"trashTalkEnabled\":false}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("chatMode", equalTo("OFF"))
                .body("trashTalkEnabled", equalTo(false));
        assertEquals("OFF", stored(id, "chat_mode"));
        assertEquals(false, stored(id, "trash_talk_enabled"));

        given().contentType("application/json").body("{\"chatMode\":\"OPTIONAL\",\"trashTalkEnabled\":false}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("chatMode", equalTo("OPTIONAL"))
                .body("trashTalkEnabled", equalTo(true));
        assertEquals(true, stored(id, "trash_talk_enabled"));

        given().when().get("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("chatMode", equalTo("OPTIONAL"));
    }

    /**
     * A game loop runs in its own request context, whose session keeps the entities it loaded; the loop therefore
     * reads the chat mode with a query, so a change made meanwhile applies from the next turn.
     */
    @Test
    void aGameLoopSeesAChatModeChangedWhileItRuns() throws InterruptedException {
        UUID[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Loop " + UUID.randomUUID();
            tournamentRepository.persist(tournament);
            Game game = new Game();
            game.tournament = tournament;
            gameRepository.persist(game);
            return new UUID[] {tournament.id, game.id};
        });
        AtomicReference<String> seen = new AtomicReference<>();
        Thread loop = Thread.startVirtualThread(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                Game loaded = gameRepository.findById(ids[1]);
                // The loop reads tournament settings every turn, which loads the tournament into its session.
                String before = loaded.tournament.chatMode + "/" + gameRepository.findChatMode(ids[1]).name();
                given().contentType("application/json").body("{\"chatMode\":\"MANDATORY\"}")
                        .when().put("/api/tournaments/{id}", ids[0].toString())
                        .then().statusCode(200);
                seen.set(before + " -> " + gameRepository.findChatMode(ids[1]).name()
                        + " (entity: " + loaded.tournament.chatMode + ")");
            } finally {
                requestContext.terminate();
            }
        });
        loop.join();

        assertEquals("OPTIONAL/OPTIONAL -> MANDATORY (entity: OPTIONAL)", seen.get());
    }

    @Test
    void promptTemplateListsTheRulesOfEveryChatMode() {
        given().when().get("/api/config/prompt-template")
                .then().statusCode(200)
                .body("version", equalTo("v2"))
                .body("chatModes.OFF.version", equalTo("v2-silent"))
                .body("chatModes.OPTIONAL.version", equalTo("v2"))
                .body("chatModes.MANDATORY.version", equalTo("v2-chat-required"))
                .body("chatModes.MANDATORY.template", containsString("Chat is required in this tournament"));
    }

    /**
     * Runs the migrations up to V11 on a fresh database, stores pre-1.4 data, then runs V12 and checks the backfill
     * of tournaments.chat_mode and moves.chat_mode.
     */
    @Test
    void migrationV12BackfillsChatModesFromTheOldSwitchAndTheStoredMoves() throws SQLException {
        String database = "chat_mode_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "11").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tournaments (id, name, trash_talk_enabled) VALUES
                          ('00000000-0000-0000-0000-0000000000a1', 'Silent', false),
                          ('00000000-0000-0000-0000-0000000000a2', 'Talkative', true);
                        INSERT INTO games (id, tournament_id) VALUES
                          ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1'),
                          ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000000a2'),
                          ('00000000-0000-0000-0000-0000000000b3', NULL);
                        INSERT INTO moves (id, game_id, move_number, color, san, fen, model_id, prompt_version,
                                           is_override) VALUES
                          -- a tournament switched off after games with chat on: silent prompts, default
                          -- prompts with and without a message, older versions and a legacy template
                          ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1',
                           1, 'WHITE', 'e4', 'fen', 'alpha', 'v2-silent', false),
                          ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-0000000000b1',
                           1, 'BLACK', 'e5', 'fen', 'beta', 'v2', false),
                          ('00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-0000000000b1',
                           2, 'WHITE', 'Nf3', 'fen', 'alpha', 'v2+custom-instructions', false),
                          ('00000000-0000-0000-0000-0000000000c9', '00000000-0000-0000-0000-0000000000b1',
                           2, 'BLACK', 'Nc6', 'fen', 'beta', 'v1', false),
                          ('00000000-0000-0000-0000-0000000000ca', '00000000-0000-0000-0000-0000000000b1',
                           3, 'WHITE', 'Bb5', 'fen', 'alpha', NULL, false),
                          ('00000000-0000-0000-0000-0000000000cb', '00000000-0000-0000-0000-0000000000b1',
                           3, 'BLACK', 'a6', 'fen', 'beta', 'legacy-custom-template', false),
                          ('00000000-0000-0000-0000-0000000000cc', '00000000-0000-0000-0000-0000000000b1',
                           4, 'WHITE', 'Ba4', 'fen', 'alpha', 'v2+custom-instructions-silent', false),
                          -- precedence: a legacy template with a message, a silent prompt with a message
                          ('00000000-0000-0000-0000-0000000000cd', '00000000-0000-0000-0000-0000000000b1',
                           4, 'BLACK', 'Nf6', 'fen', 'beta', 'legacy-custom-template', false),
                          ('00000000-0000-0000-0000-0000000000ce', '00000000-0000-0000-0000-0000000000b1',
                           5, 'WHITE', 'O-O', 'fen', 'alpha', 'v2-silent', false),
                          -- talkative tournament: with and without a message, a human move and an override
                          ('00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-0000000000b2',
                           1, 'WHITE', 'e4', 'fen', 'alpha', 'v2', false),
                          ('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-0000000000b2',
                           1, 'BLACK', 'e5', 'fen', 'beta', 'legacy-custom-template', false),
                          ('00000000-0000-0000-0000-0000000000c6', '00000000-0000-0000-0000-0000000000b2',
                           2, 'WHITE', 'Nf3', 'fen', NULL, 'human', false),
                          ('00000000-0000-0000-0000-0000000000c7', '00000000-0000-0000-0000-0000000000b2',
                           2, 'BLACK', 'Nc6', 'fen', NULL, 'admin-override', true),
                          -- a game outside a tournament
                          ('00000000-0000-0000-0000-0000000000c8', '00000000-0000-0000-0000-0000000000b3',
                           1, 'WHITE', 'd4', 'fen', 'alpha', 'v2', false);
                        INSERT INTO chat_messages (game_id, move_number, sender_model, sender_color, message) VALUES
                          ('00000000-0000-0000-0000-0000000000b1', 2, 'alpha', 'WHITE', 'Switched on mid-game'),
                          ('00000000-0000-0000-0000-0000000000b1', 4, 'beta', 'BLACK', 'Legacy but talking'),
                          ('00000000-0000-0000-0000-0000000000b1', 5, 'alpha', 'WHITE', 'Told to stay silent'),
                          ('00000000-0000-0000-0000-0000000000b2', 1, 'alpha', 'WHITE', 'Good luck');
                        """);
            }

            flyway(url, "latest").migrate();

            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                Map<String, String> tournaments = column(statement,
                        "SELECT name, chat_mode FROM tournaments");
                assertEquals(Map.of("Silent", "OFF", "Talkative", "OPTIONAL"), tournaments);

                Map<String, String> moves = column(statement,
                        "SELECT right(id::text, 2), chat_mode FROM moves");
                assertEquals("OFF", moves.get("c1"), "silent prompt");
                assertEquals("OFF", moves.get("cc"), "silent prompt with custom instructions");
                // Regression: a silent move under the default prompt in a tournament switched off later is
                // OPTIONAL like its talking neighbour, not OFF (which would make the talk rate 100%).
                assertEquals("OPTIONAL", moves.get("c2"), "default prompt: chat was on, the model stayed silent");
                assertEquals("OPTIONAL", moves.get("c3"), "a stored message proves chat was on");
                assertEquals("OPTIONAL", moves.get("c9"), "v1 predates the switch");
                assertEquals("OPTIONAL", moves.get("ca"), "no prompt version predates the switch");
                assertEquals("OFF", moves.get("cb"), "legacy template without a message: the tournament's setting");
                // Precedence: the silent prompt wins over a stored message (the model was told to stay silent, so
                // counting its move would distort the talk rate); a stored message wins over the tournament's
                // setting for a legacy template, whose prompt does not tell the mode.
                assertEquals("OPTIONAL", moves.get("cd"), "legacy template with a message: chat was on");
                assertEquals("OFF", moves.get("ce"), "silent prompt wins over a stored message");
                assertEquals("OPTIONAL", moves.get("c4"));
                assertEquals("OPTIONAL", moves.get("c5"));
                assertNull(moves.get("c6"), "human move");
                assertNull(moves.get("c7"), "override");
                assertEquals("OPTIONAL", moves.get("c8"), "games outside a tournament allowed chat");

                // New tournaments default to OPTIONAL; unknown values are rejected by the database too.
                statement.execute("INSERT INTO tournaments (name) VALUES ('Fresh')");
                assertEquals("OPTIONAL", column(statement,
                        "SELECT name, chat_mode FROM tournaments WHERE name = 'Fresh'").get("Fresh"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE tournaments SET chat_mode = 'LOUD' WHERE name = 'Fresh'"));
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Map<String, String> column(Statement statement, String sql) throws SQLException {
        Map<String, String> values = new HashMap<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.put(rows.getString(1), rows.getString(2));
            }
        }
        return values;
    }

    private void assertCreated(String body, String chatMode, boolean trashTalkEnabled) {
        String id = given().contentType("application/json").body(withName(body))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("chatMode", equalTo(chatMode))
                .body("trashTalkEnabled", equalTo(trashTalkEnabled))
                .extract().path("id");
        assertEquals(chatMode, stored(id, "chat_mode"), body);
        assertEquals(trashTalkEnabled, stored(id, "trash_talk_enabled"), body);
    }

    private String create(String body) {
        return given().contentType("application/json").body(withName(body))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static String withName(String body) {
        String name = "\"name\":\"Chat " + UUID.randomUUID() + "\"";
        return "{}".equals(body) ? "{" + name + "}" : "{" + name + "," + body.substring(1);
    }

    private Object stored(String id, String column) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                        "select " + column + " from tournaments where id = cast(?1 as uuid)")
                .setParameter(1, id)
                .getSingleResult());
    }
}
