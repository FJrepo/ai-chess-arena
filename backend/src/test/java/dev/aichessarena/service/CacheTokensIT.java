package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Cache counters through persistence, the attempts API and the V16 -> V17 database upgrade. */
@QuarkusTest
class CacheTokensIT {
    @Inject
    MoveAttemptService attempts;

    @Inject
    MoveAttemptRepository attemptRepository;

    @Inject
    GameRepository games;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void cacheCountersPersistForEveryOutcomeAndRemainNullable() throws Exception {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.status = Game.GameStatus.IN_PROGRESS;
            games.persist(game);
            return game.id;
        });
        int number = 0;
        for (Outcome outcome : Outcome.values()) {
            LlmResponse response = new LlmResponse("e4", 100, 10, 0.001, 100, "{}", false, null)
                    .withUsage(json.readTree("""
                            {"prompt_tokens":100,"completion_tokens":10,"cost":0.001,
                            "prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":0}}
                            """));
            attempts.record(new MoveAttemptService.AttemptRecord(id, 1, "WHITE", ++number, "cache/model",
                    outcome, null, null, response));
        }
        attempts.record(new MoveAttemptService.AttemptRecord(id, 1, "WHITE", ++number, "cache/model",
                Outcome.ACCEPTED, null, null, new LlmResponse("e4", 100, 10, null, 100, "{}", false, null)));
        var stored = QuarkusTransaction.requiringNew().call(() -> attemptRepository.findByGameId(id));
        assertEquals(Outcome.values().length + 1, stored.size());
        for (MoveAttempt attempt : stored.subList(0, stored.size() - 1)) {
            assertEquals(80, attempt.cachedPromptTokens, attempt.outcome.name());
            assertEquals(0, attempt.cacheWriteTokens, attempt.outcome.name());
        }
        assertNull(stored.getLast().cachedPromptTokens);
        assertNull(stored.getLast().cacheWriteTokens);
        given().when().get("/api/games/{id}/attempts", id).then().statusCode(200)
                .body("[0].cachedPromptTokens", equalTo(80))
                .body("[0].cacheWriteTokens", equalTo(0))
                .body("[-1].cachedPromptTokens", nullValue())
                .body("[-1].cacheWriteTokens", nullValue());
    }

    @Test
    void migrationV17PreservesLegacyRowsWithoutInventingCacheUsage() throws SQLException {
        String database = "cache_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "16").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO games (id) VALUES ('00000000-0000-0000-0000-0000000000b1');
                        INSERT INTO move_attempts
                          (id, game_id, move_number, color, attempt_number, outcome, prompt_tokens, cost_usd) VALUES
                          ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000b1',
                           1, 'WHITE', 1, 'ACCEPTED', 100, 0.001);
                        """);
            }
            assertEquals(1, flyway(url, "17").migrate().migrationsExecuted);
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT prompt_tokens, cost_usd, cached_prompt_tokens, cache_write_tokens FROM move_attempts")) {
                    rows.next();
                    assertEquals(100, rows.getInt(1));
                    assertEquals(0, new java.math.BigDecimal("0.001").compareTo(rows.getBigDecimal(2)));
                    assertNull(rows.getObject(3));
                    assertNull(rows.getObject(4));
                }
                statement.execute("UPDATE move_attempts SET cached_prompt_tokens = 0, cache_write_tokens = 25");
                try (ResultSet rows = statement.executeQuery(
                        "SELECT cached_prompt_tokens, cache_write_tokens FROM move_attempts")) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                    assertEquals(25, rows.getInt(2));
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
        return Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").target(target).load();
    }
}
