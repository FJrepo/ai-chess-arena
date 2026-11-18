package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TournamentAttemptsIT {

    private static final String REMOVED_MAX_RETRIES =
            "maxRetries was removed in 2.0; send maxAttempts (attempts per move including the first, 1-20)";

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void createAndUpdateAcceptBothBoundariesAndPreserveOmittedLimits() {
        String id = given().contentType("application/json")
                .body("{\"name\":\"Attempts\",\"generateBracket\":false,\"maxAttempts\":1}")
                .post("/api/tournaments").then().statusCode(201)
                .body("maxAttempts", equalTo(1))
                .body("$", not(hasKey("maxRetries")))
                .extract().path("id");
        given().contentType("application/json").body("{\"maxAttempts\":20}")
                .put("/api/tournaments/{id}", id).then().statusCode(200).body("maxAttempts", equalTo(20));
        for (String body : List.of("{}", "{\"maxAttempts\":null}")) {
            given().contentType("application/json").body(body)
                    .put("/api/tournaments/{id}", id).then().statusCode(200).body("maxAttempts", equalTo(20));
        }
        given().get("/api/tournaments/{id}/export.json", id).then().statusCode(200)
                .body("schemaVersion", equalTo(3))
                .body("settings.maxAttempts", equalTo(20))
                .body("settings", not(hasKey("maxRetries")));
    }

    @Test
    void invalidAttemptsFailClosedOnCreateAndUpdate() {
        String id = given().contentType("application/json").body("{\"name\":\"Original\",\"generateBracket\":false}")
                .post("/api/tournaments").then().statusCode(201)
                .body("maxAttempts", equalTo(3)).extract().path("id");
        String message = "maxAttempts must be a whole number from 1 to 20";
        for (String invalid : List.of("0", "-1", "21", "1.5", "\"\"", "2147483649")) {
            String body = "{\"name\":\"Invalid\",\"generateBracket\":false,\"maxAttempts\":" + invalid + "}";
            given().contentType("application/json").body(body).post("/api/tournaments")
                    .then().statusCode(400).body("error", equalTo(message));
            given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                    .then().statusCode(400).body("error", equalTo(message));
        }
        given().get("/api/tournaments/{id}", id).then().statusCode(200)
                .body("maxAttempts", equalTo(3)).body("name", equalTo("Original"));
    }

    @Test
    void theRemovedMaxRetriesFieldIsRejectedOnCreateAndUpdate() {
        String id = given().contentType("application/json").body("{\"name\":\"Kept\",\"generateBracket\":false}")
                .post("/api/tournaments").then().statusCode(201).extract().path("id");
        for (String value : List.of("2", "null", "\"3\"")) {
            String body = "{\"name\":\"Old client\",\"generateBracket\":false,\"maxRetries\":" + value + "}";
            given().contentType("application/json").body(body).post("/api/tournaments")
                    .then().statusCode(400).body("error", equalTo(REMOVED_MAX_RETRIES));
            given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                    .then().statusCode(400).body("error", equalTo(REMOVED_MAX_RETRIES));
        }
        // Also when sent next to the new field.
        given().contentType("application/json").body("{\"maxAttempts\":4,\"maxRetries\":4}")
                .put("/api/tournaments/{id}", id).then().statusCode(400).body("error", equalTo(REMOVED_MAX_RETRIES));
        given().get("/api/tournaments/{id}", id).then().statusCode(200)
                .body("maxAttempts", equalTo(3)).body("name", equalTo("Kept"));
    }

    /**
     * Runs the migrations up to V29 on a fresh database, stores 1.x data, then runs V30 and checks the renamed column,
     * the rewritten configuration snapshots and the dropped chat switch.
     */
    @Test
    void migrationV30RenamesTheAttemptsLimitAndDropsTheOldChatSwitch() throws SQLException {
        String database = "v30_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "29").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tournaments (id, name, max_retries, trash_talk_enabled, chat_mode) VALUES
                          ('00000000-0000-0000-0000-0000000000a1', 'Seven', 7, false, 'OFF');
                        INSERT INTO games (id, tournament_id, starting_configuration) VALUES
                          ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1',
                           '{"maxRetries": 7, "chatMode": "OFF"}'),
                          ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000000a1', NULL);
                        INSERT INTO game_configuration_revisions (id, game_id, revision, settings) VALUES
                          ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1', 1,
                           '{"maxRetries": 7, "chatMode": "OFF"}'),
                          ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-0000000000b1', 2,
                           '{"maxRetries": 20, "chatMode": "OPTIONAL"}');
                        """);
            }

            flyway(url, "latest").migrate();

            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                assertEquals("7|OFF", single(statement,
                        "SELECT max_attempts || '|' || chat_mode FROM tournaments"));
                assertEquals("0", single(statement, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_name = 'tournaments' AND column_name IN ('max_retries', 'trash_talk_enabled')
                        """));
                assertEquals("{\"chatMode\": \"OFF\", \"maxAttempts\": 7}", single(statement,
                        "SELECT starting_configuration::text FROM games WHERE starting_configuration IS NOT NULL"));
                assertEquals("1", single(statement, "SELECT count(*) FROM games WHERE starting_configuration IS NULL"));
                assertEquals("7,20", single(statement, """
                        SELECT string_agg(settings ->> 'maxAttempts', ',' ORDER BY revision)
                        FROM game_configuration_revisions
                        """));
                assertEquals("0", single(statement,
                        "SELECT count(*) FROM game_configuration_revisions WHERE settings ? 'maxRetries'"));
                // New rows keep the default of three attempts.
                statement.execute("INSERT INTO tournaments (name) VALUES ('Fresh')");
                assertEquals("3", single(statement, "SELECT max_attempts FROM tournaments WHERE name = 'Fresh'"));
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

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
