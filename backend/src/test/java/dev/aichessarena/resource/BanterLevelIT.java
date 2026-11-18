package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BanterLevelIT {
    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;
    @ConfigProperty(name = "quarkus.datasource.username")
    String username;
    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void oldClientsAndExplicitNullKeepTheOriginalBehaviorOnCreate() {
        for (String fields : new String[] {"", ",\"banterLevel\":null", ",\"chatMode\":\"OFF\""}) {
            String id = create(fields, null);
            assertNull(stored(id));
            given().get("/api/tournaments/{id}", id).then().statusCode(200).body("banterLevel", nullValue());
        }
    }

    @Test
    void eachToneRoundTripsWithEveryChatModeIncludingOff() {
        for (String mode : new String[] {"OPTIONAL", "MANDATORY", "OFF"}) {
            for (String level : new String[] {"POLITE", "PLAYFUL", "SPICY", "MIND_GAMES"}) {
                String id = create(",\"chatMode\":\"" + mode + "\",\"banterLevel\":\" "
                        + level.toLowerCase(java.util.Locale.ROOT) + " \"", level);
                assertEquals(level, stored(id));
                given().get("/api/tournaments/{id}", id).then().statusCode(200)
                        .body("banterLevel", equalTo(level)).body("chatMode", equalTo(mode));
            }
        }
    }

    @Test
    void omittedUpdateRetainsToneExplicitNullClearsAndOffRetainsSelection() {
        String id = create(",\"banterLevel\":\"POLITE\"", "POLITE");
        update(id, "{\"maxAttempts\":2}", "POLITE");
        update(id, "{\"chatMode\":\"OFF\"}", "POLITE");
        update(id, "{\"banterLevel\":\"SPICY\"}", "SPICY");
        update(id, "{\"chatMode\":\"MANDATORY\"}", "SPICY");
        update(id, "{\"banterLevel\":null}", null);
        assertNull(stored(id));
        update(id, "{\"banterLevel\":\"PLAYFUL\"}", "PLAYFUL");
        assertEquals("PLAYFUL", stored(id));
        update(id, "{\"banterLevel\":\"MIND_GAMES\"}", "MIND_GAMES");
        assertEquals("MIND_GAMES", stored(id));
    }

    @Test
    void invalidTonesFailClosedOnCreateAndUpdateEvenWithChatOff() {
        String id = create(",\"banterLevel\":\"POLITE\"", "POLITE");
        for (String value : new String[] {"\"LOUD\"", "\"default\"", "\"\"", "\" \"", "true", "42", "[]", "{}"}) {
            String body = "{\"name\":\"Must roll back\",\"chatMode\":\"OFF\",\"banterLevel\":" + value + "}";
            given().contentType("application/json").body(body).post("/api/tournaments")
                    .then().statusCode(400).body(containsString("banterLevel"));
            given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                    .then().statusCode(400).body(containsString("banterLevel"));
            assertEquals("POLITE", stored(id));
            given().get("/api/tournaments/{id}", id).then().statusCode(200)
                    .body("chatMode", equalTo("OPTIONAL"));
        }
    }

    @Test
    void rulesPreviewIncludesToneAndResignationWithoutChangingOldVariants() {
        given().get("/api/config/prompt-template").then().statusCode(200)
                .body("chatModes.OPTIONAL.version", equalTo("v2"))
                .body("banterChatModes.POLITE.OPTIONAL.version", equalTo("v2-banter-polite"))
                .body("banterChatModes.PLAYFUL.MANDATORY.template", containsString("good-natured teasing"))
                .body("banterChatModes.SPICY.OFF.version", equalTo("v2-silent"))
                .body("resignationBanterChatModes.SPICY.MANDATORY.version",
                        equalTo("v2-chat-required-resign-banter-spicy"))
                .body("resignationBanterChatModes.SPICY.MANDATORY.template", containsString("no slurs"))
                .body("banterChatModes.MIND_GAMES.OFF.version", equalTo("v2-silent"))
                .body("resignationBanterChatModes.MIND_GAMES.MANDATORY.version",
                        equalTo("v2-chat-required-resign-banter-mind-games"))
                .body("resignationBanterChatModes.MIND_GAMES.MANDATORY.template",
                        containsString("try to persuade your opponent to resign"));
    }

    @Test
    void banterMigrationsPreserveExistingRowsAndConstrainNewValues() throws SQLException {
        String database = "banter_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "20").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tournaments (id, name, chat_mode) VALUES
                        ('00000000-0000-0000-0000-0000000000a1', 'Existing', 'MANDATORY');
                        INSERT INTO games (id, tournament_id) VALUES
                        ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1');
                        INSERT INTO moves (game_id, move_number, color, san, fen, model_id, prompt_version, prompt_hash)
                        VALUES ('00000000-0000-0000-0000-0000000000b1', 1, 'WHITE', 'e4', 'fen', 'test/model',
                        'v2-chat-required', '818eb691f98294ccd6ac69b211f214381f7b67a329e63941b075c18f31e1f631');
                        """);
            }
            flyway(url, "21").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT banter_level, chat_mode FROM tournaments")) {
                    rows.next();
                    assertNull(rows.getString(1));
                    assertEquals("MANDATORY", rows.getString(2));
                }
                try (var rows = statement.executeQuery("SELECT prompt_version, prompt_hash FROM moves")) {
                    rows.next();
                    assertEquals("v2-chat-required", rows.getString(1));
                    assertEquals("818eb691f98294ccd6ac69b211f214381f7b67a329e63941b075c18f31e1f631", rows.getString(2));
                }
                statement.execute("INSERT INTO tournaments (name) VALUES ('Fresh')");
                try (var rows = statement.executeQuery("SELECT banter_level FROM tournaments WHERE name = 'Fresh'")) {
                    rows.next();
                    assertNull(rows.getString(1));
                }
                for (String level : new String[] {"POLITE", "PLAYFUL", "SPICY"}) {
                    statement.execute("UPDATE tournaments SET banter_level = '" + level + "' WHERE name = 'Fresh'");
                }
                assertThrows(SQLException.class,
                        () -> statement.execute("UPDATE tournaments SET banter_level = 'LOUD'"));
            }

            flyway(url, "28").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class,
                        () -> statement.execute("UPDATE tournaments SET banter_level = 'MIND_GAMES'"));
            }
            flyway(url, "29").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT banter_level FROM tournaments ORDER BY name")) {
                    rows.next();
                    assertNull(rows.getString(1));
                    rows.next();
                    assertEquals("SPICY", rows.getString(1));
                }
                statement.execute("UPDATE tournaments SET banter_level = 'MIND_GAMES' WHERE name = 'Fresh'");
                assertThrows(SQLException.class,
                        () -> statement.execute("UPDATE tournaments SET banter_level = 'LOUD'"));
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .target(target).load();
    }

    private String create(String fields, String level) {
        return given().contentType("application/json")
                .body("{\"name\":\"Banter " + UUID.randomUUID() + "\",\"generateBracket\":false" + fields + "}")
                .post("/api/tournaments").then().statusCode(201)
                .body("banterLevel", level == null ? nullValue() : equalTo(level)).extract().path("id");
    }

    private void update(String id, String body, String level) {
        given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                .then().statusCode(200).body("banterLevel", level == null ? nullValue() : equalTo(level));
    }

    private Object stored(String id) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                        "select banter_level from tournaments where id = cast(?1 as uuid)")
                .setParameter(1, id).getSingleResult());
    }
}
