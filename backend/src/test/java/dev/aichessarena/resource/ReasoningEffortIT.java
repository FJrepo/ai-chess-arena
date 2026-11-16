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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Reasoning effort (migration V15) through the REST API and the migration itself, against a real PostgreSQL. */
@QuarkusTest
class ReasoningEffortIT {

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void createStoresTheLevelInLowerCaseAndWithoutItTheModelDefault() {
        String omitted = create("{}");
        given().when().get("/api/tournaments/{id}", omitted)
                .then().statusCode(200)
                .body("reasoningEffort", nullValue())
                .body("participants[0].reasoningEffort", nullValue());
        assertNull(stored("tournaments", omitted, "reasoning_effort"));

        String explicitDefault = create("{\"reasoningEffort\":\"default\"}");
        assertNull(stored("tournaments", explicitDefault, "reasoning_effort"));

        String low = create("{\"reasoningEffort\":\" LOW \"}");
        given().when().get("/api/tournaments/{id}", low)
                .then().statusCode(200)
                .body("reasoningEffort", equalTo("low"));
        assertEquals("low", stored("tournaments", low, "reasoning_effort"));
    }

    @Test
    void participantsCanOverrideTheTournamentsLevelOnCreate() {
        String id = given().contentType("application/json")
                .body("""
                        {"name": "Effort %s", "reasoningEffort": "medium", "participants": [
                          {"playerName": "Ember", "modelId": "meta/muse-ember", "reasoningEffort": "Minimal"},
                          {"playerName": "Kimi", "modelId": "moonshotai/kimi-k3.1-pro"}]}"""
                        .formatted(UUID.randomUUID()))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("reasoningEffort", equalTo("medium"))
                .body("participants.find { it.playerName == 'Ember' }.reasoningEffort", equalTo("minimal"))
                .body("participants.find { it.playerName == 'Kimi' }.reasoningEffort", nullValue())
                .extract().path("id");
        assertEquals("minimal", QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                        "select reasoning_effort from tournament_participants where tournament_id = cast(?1 as uuid) "
                                + "and player_name = 'Ember'")
                .setParameter(1, id)
                .getSingleResult()));
    }

    @Test
    void unknownLevelsAreRejected() {
        for (String body : new String[] {"{\"reasoningEffort\":\"ultra\"}", "{\"reasoningEffort\":\"\"}"}) {
            given().contentType("application/json").body(withName(body))
                    .when().post("/api/tournaments")
                    .then().statusCode(400)
                    .body("error", containsString("reasoningEffort"));
        }
        given().contentType("application/json")
                .body(withName("{\"participants\":[{\"playerName\":\"A\",\"modelId\":\"a/b\","
                        + "\"reasoningEffort\":\"turbo\"}]}"))
                .when().post("/api/tournaments")
                .then().statusCode(400)
                .body("error", containsString("reasoningEffort"));
        given().contentType("application/json")
                .body(withName("{\"participants\":[{\"playerName\":\"A\",\"modelId\":\"a/b\","
                        + "\"reasoningEffort\":\"default\"}]}"))
                .when().post("/api/tournaments")
                .then().statusCode(400)
                .body("error", containsString("null to use the tournament's"));
        given().contentType("application/json")
                .body(withName("{\"participants\":[{\"playerName\":\"Me\",\"controlType\":\"HUMAN\","
                        + "\"reasoningEffort\":\"low\"}]}"))
                .when().post("/api/tournaments")
                .then().statusCode(400)
                .body("error", containsString("Human participants have no reasoning effort"));

        String id = create("{\"reasoningEffort\":\"high\"}");
        given().contentType("application/json").body("{\"reasoningEffort\":\"extreme\"}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(400);
        assertEquals("high", stored("tournaments", id, "reasoning_effort"));
    }

    @Test
    void updateKeepsTheLevelWhenOmittedAndDefaultClearsIt() {
        String id = create("{\"reasoningEffort\":\"xhigh\"}");

        given().contentType("application/json").body("{\"maxRetries\":2}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("reasoningEffort", equalTo("xhigh"));

        given().contentType("application/json").body("{\"reasoningEffort\":\"none\"}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("reasoningEffort", equalTo("none"));

        given().contentType("application/json").body("{\"reasoningEffort\":\"DEFAULT\"}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200)
                .body("reasoningEffort", nullValue());
        assertNull(stored("tournaments", id, "reasoning_effort"));
    }

    @Test
    void aParticipantsOverrideCanBeChangedAfterCreation() {
        String id = create("{\"reasoningEffort\":\"medium\"}");
        String pid = given().when().get("/api/tournaments/{id}", id)
                .then().statusCode(200).extract().path("participants[0].id");

        given().contentType("application/json").body("{\"reasoningEffort\":\"low\"}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(200)
                .body("id", equalTo(pid))
                .body("reasoningEffort", equalTo("low"));
        assertEquals("low", stored("tournament_participants", pid, "reasoning_effort"));

        // Omitted keeps it; null follows the tournament's again.
        given().contentType("application/json").body("{}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(200)
                .body("reasoningEffort", equalTo("low"));
        given().contentType("application/json").body("{\"reasoningEffort\":null}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(200)
                .body("reasoningEffort", nullValue());
        assertNull(stored("tournament_participants", pid, "reasoning_effort"));

        // Invalid values and other fields are rejected; unknown ids are 404.
        given().contentType("application/json").body("{\"reasoningEffort\":\"lots\"}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(400);
        given().contentType("application/json").body("{\"reasoningEffort\":3}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(400);
        given().contentType("application/json").body("{\"playerName\":\"Renamed\"}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(400)
                .body("error", containsString("Only reasoningEffort"));
        String other = create("{}");
        given().contentType("application/json").body("{\"reasoningEffort\":\"low\"}")
                .when().put("/api/tournaments/{id}/participants/{pid}", other, pid)
                .then().statusCode(404);
        given().contentType("application/json").body("{\"reasoningEffort\":\"low\"}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, UUID.randomUUID().toString())
                .then().statusCode(404);
    }

    @Test
    void anOverrideCanBeChangedOnceTheBracketExists() {
        String id = given().contentType("application/json")
                .body("""
                        {"name": "Bracket %s", "participants": [
                          {"playerName": "A", "modelId": "a/one", "seed": 1},
                          {"playerName": "B", "modelId": "b/two", "seed": 2}]}""".formatted(UUID.randomUUID()))
                .when().post("/api/tournaments")
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json").when().post("/api/tournaments/{id}/generate-bracket", id)
                .then().statusCode(200);
        String gameId = given().when().get("/api/tournaments/{id}", id)
                .then().statusCode(200).extract().path("games[0].id");
        String pid = given().when().get("/api/tournaments/{id}", id)
                .then().statusCode(200).extract().path("participants.find { it.playerName == 'B' }.id");
        given().contentType("application/json").body("{\"reasoningEffort\":\"high\"}")
                .when().put("/api/tournaments/{id}", id)
                .then().statusCode(200);

        given().contentType("application/json").body("{\"reasoningEffort\":\"minimal\"}")
                .when().put("/api/tournaments/{id}/participants/{pid}", id, pid)
                .then().statusCode(200);

        // The game shows what each side asks for: the tournament's level, or the override.
        given().when().get("/api/games/{id}", gameId)
                .then().statusCode(200)
                .body("whiteReasoningEffort", equalTo("high"))
                .body("blackReasoningEffort", equalTo("minimal"));
    }

    /**
     * Runs the migrations up to V14 on a fresh database, stores pre-1.4 data, then runs V15: existing rows get no level
     * (model default, the tournament's, nothing sent), and only known levels can be stored.
     */
    @Test
    void migrationV15AddsNullableLevelsAndRejectsUnknownOnes() throws SQLException {
        String database = "reasoning_effort_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "14").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tournaments (id, name) VALUES ('00000000-0000-0000-0000-0000000000a1', 'Old');
                        INSERT INTO tournament_participants (id, tournament_id, player_name, model_id) VALUES
                          ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a1', 'A', 'a/b');
                        INSERT INTO games (id, tournament_id) VALUES
                          ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1');
                        INSERT INTO move_attempts (id, game_id, move_number, color, attempt_number, outcome) VALUES
                          ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000b1', 1,
                           'WHITE', 1, 'ACCEPTED');
                        """);
            }

            flyway(url, "latest").migrate();

            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                for (String table : new String[] {"tournaments", "tournament_participants", "move_attempts"}) {
                    try (ResultSet rows = statement.executeQuery(
                            "SELECT count(*), count(reasoning_effort) FROM " + table)) {
                        rows.next();
                        assertEquals(1, rows.getInt(1), table);
                        assertEquals(0, rows.getInt(2), table + ": existing rows have no level");
                    }
                    assertThrows(SQLException.class, () -> statement.execute(
                            "UPDATE " + table + " SET reasoning_effort = 'LOW'"), table + ": upper case");
                    assertThrows(SQLException.class, () -> statement.execute(
                            "UPDATE " + table + " SET reasoning_effort = 'ultra'"), table + ": unknown level");
                    statement.execute("UPDATE " + table + " SET reasoning_effort = 'xhigh'");
                }
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

    /** Creates a tournament with one AI participant. */
    private String create(String body) {
        return given().contentType("application/json")
                .body(withName(body.equals("{}") ? "{\"participants\":[{\"playerName\":\"P\",\"modelId\":\"p/q\"}]}"
                        : "{\"participants\":[{\"playerName\":\"P\",\"modelId\":\"p/q\"}]," + body.substring(1)))
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static String withName(String body) {
        String name = "\"name\":\"Effort " + UUID.randomUUID() + "\"";
        return "{}".equals(body) ? "{" + name + "}" : "{" + name + "," + body.substring(1);
    }

    private Object stored(String table, String id, String column) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                        "select " + column + " from " + table + " where id = cast(?1 as uuid)")
                .setParameter(1, id)
                .getSingleResult());
    }
}
