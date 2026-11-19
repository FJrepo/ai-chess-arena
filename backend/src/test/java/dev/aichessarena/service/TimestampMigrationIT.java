package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V34 turns every naive application timestamp into {@code timestamptz}, reading the stored wall-clock values as UTC
 * (what a Docker install, whose backend runs in UTC, wrote), also when the migrating JVM and its database session use
 * another time zone.
 */
@QuarkusTest
class TimestampMigrationIT {

    private static final String TOURNAMENT = "00000000-0000-0000-0000-0000000000a1";
    private static final String GAME = "00000000-0000-0000-0000-0000000000b1";
    private static final String WAITING_GAME = "00000000-0000-0000-0000-0000000000b2";
    private static final String REBUILT_INDEX = "idx_practice_games_created";

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void v34ReadsNaiveValuesAsUtcAndLeavesNoNaiveApplicationColumn() throws Exception {
        String database = "v34_timestamps_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        TimeZone original = TimeZone.getDefault();
        try {
            flyway(url, "33").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO tournaments (id, name, created_at, updated_at) VALUES
                          ('%1$s', 'Upgrade', '2026-11-18 23:15:24.540714', '2026-11-18 23:59:59.999999');
                        INSERT INTO games (id, tournament_id, created_at, started_at, completed_at) VALUES
                          ('%2$s', '%1$s', '2026-03-29 00:30:00', '2026-03-29 01:30:00', '2026-03-29 02:30:00'),
                          ('%3$s', '%1$s', '2026-10-25 01:30:00', NULL, NULL);
                        INSERT INTO moves (game_id, move_number, color, san, fen, created_at) VALUES
                          ('%2$s', 1, 'WHITE', 'e4', 'fen', '2026-03-29 01:45:00.000001');
                        INSERT INTO chat_messages (game_id, move_number, sender_model, sender_color, message, created_at)
                          VALUES ('%2$s', 1, 'model', 'WHITE', 'Hello', '2026-03-29 01:46:00');
                        INSERT INTO move_attempts (id, game_id, move_number, color, attempt_number, outcome, created_at)
                          VALUES (gen_random_uuid(), '%2$s', 1, 'WHITE', 1, 'ILLEGAL_MOVE', '2026-03-29 01:44:00');
                        """.formatted(TOURNAMENT, GAME, WAITING_GAME));
            }

            Map<String, Long> filesBefore = relationFiles(url);

            // A source install run with a CET JVM: the migration session uses Europe/Vienna, not UTC.
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Vienna"));
            flyway(url, "latest").migrate();
            TimeZone.setDefault(original);

            // A rewrite allocates a new file and holds an exclusive lock while copying. No table is rewritten; only
            // the one index keyed on a converted column (practice games by creation time, a partial index) is rebuilt.
            Map<String, Long> filesAfter = relationFiles(url);
            assertNotEquals(filesBefore.get(REBUILT_INDEX), filesAfter.get(REBUILT_INDEX));
            filesBefore.remove(REBUILT_INDEX);
            filesAfter.remove(REBUILT_INDEX);
            assertEquals(filesBefore, filesAfter, "V34 must not rewrite tables or rebuild other indexes");

            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                assertEquals(List.of(), naiveColumns(statement), "naive timestamp columns left after V34");

                Map<String, String> expected = new LinkedHashMap<>();
                expected.put("SELECT created_at FROM tournaments", "2026-11-18T23:15:24.540714Z");
                expected.put("SELECT updated_at FROM tournaments", "2026-11-18T23:59:59.999999Z");
                expected.put("SELECT created_at FROM games WHERE id = '" + GAME + "'", "2026-03-29T00:30:00Z");
                expected.put("SELECT started_at FROM games WHERE id = '" + GAME + "'", "2026-03-29T01:30:00Z");
                expected.put("SELECT completed_at FROM games WHERE id = '" + GAME + "'", "2026-03-29T02:30:00Z");
                expected.put("SELECT created_at FROM games WHERE id = '" + WAITING_GAME + "'", "2026-10-25T01:30:00Z");
                expected.put("SELECT created_at FROM moves", "2026-03-29T01:45:00.000001Z");
                expected.put("SELECT created_at FROM chat_messages", "2026-03-29T01:46:00Z");
                expected.put("SELECT created_at FROM move_attempts", "2026-03-29T01:44:00Z");
                for (var entry : expected.entrySet()) {
                    assertEquals(Instant.parse(entry.getValue()), instant(statement, entry.getKey()), entry.getKey());
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT started_at IS NULL AND completed_at IS NULL FROM games WHERE id = '"
                                + WAITING_GAME + "'")) {
                    assertTrue(rows.next() && rows.getBoolean(1), "missing timestamps stay null");
                }
            }
            assertDefaultsStoreTheTransactionInstant(url);
        } finally {
            TimeZone.setDefault(original);
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /** Rows inserted after V34 without timestamps, in a non-UTC session, get the transaction's instant. */
    private void assertDefaultsStoreTheTransactionInstant(String url) throws SQLException {
        String tournament = UUID.randomUUID().toString();
        String game = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL TIME ZONE 'Europe/Vienna'");
            statement.execute("""
                    INSERT INTO tournaments (id, name) VALUES ('%1$s', 'Defaults');
                    INSERT INTO games (id, tournament_id) VALUES ('%2$s', '%1$s');
                    INSERT INTO moves (game_id, move_number, color, san, fen) VALUES ('%2$s', 1, 'WHITE', 'e4', 'fen');
                    INSERT INTO chat_messages (game_id, move_number, sender_model, sender_color, message)
                      VALUES ('%2$s', 1, 'model', 'WHITE', 'Hello');
                    INSERT INTO move_attempts (id, game_id, move_number, color, attempt_number, outcome)
                      VALUES (gen_random_uuid(), '%2$s', 1, 'WHITE', 1, 'ILLEGAL_MOVE');
                    """.formatted(tournament, game));
            Instant transaction = instant(statement, "SELECT now()");
            for (String select : List.of(
                    "SELECT created_at FROM tournaments WHERE id = '" + tournament + "'",
                    "SELECT updated_at FROM tournaments WHERE id = '" + tournament + "'",
                    "SELECT created_at FROM games WHERE id = '" + game + "'",
                    "SELECT created_at FROM moves WHERE game_id = '" + game + "'",
                    "SELECT created_at FROM chat_messages WHERE game_id = '" + game + "'",
                    "SELECT created_at FROM move_attempts WHERE game_id = '" + game + "'")) {
                assertEquals(transaction, instant(statement, select), select);
            }
            connection.commit();
        }
    }

    /** The physical file of each application table and index; it changes when PostgreSQL rewrites one. */
    private Map<String, Long> relationFiles(String url) throws SQLException {
        Map<String, Long> files = new java.util.TreeMap<>();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT c.relname, c.relfilenode FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'i')
                       AND c.relname <> 'flyway_schema_history' AND c.relname NOT LIKE 'flyway_schema_history%'
                     """)) {
            while (rows.next()) files.put(rows.getString(1), rows.getLong(2));
        }
        return files;
    }

    /** Application columns still without a time zone; Flyway's own history table is not the application's. */
    private static List<String> naiveColumns(Statement statement) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND data_type = 'timestamp without time zone'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY 1
                """)) {
            while (rows.next()) columns.add(rows.getString(1));
        }
        return columns;
    }

    /** The absolute instant, read through extract(epoch) so the session time zone cannot change it. */
    private static Instant instant(Statement statement, String select) throws SQLException {
        String query = "SELECT (extract(epoch FROM v) * 1000000)::bigint FROM (" + select + ") AS s(v)";
        try (ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next(), select);
            long micros = rows.getLong(1);
            return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
        }
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
