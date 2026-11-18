package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.GameConfiguration;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Configuration snapshots written by 1.x (key {@code maxRetries}) through the real V30 migration and back into the
 * application: the game API shows {@code maxAttempts}, nested player settings survive, an unchanged turn reuses the
 * last migrated revision, and JSON objects without the old key stay byte-identical.
 */
@QuarkusTest
class ConfigurationSnapshotUpgradeIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WITHOUT_OLD_KEY = "{\"note\": {\"nested\": [1, 2]}, \"format\": \"SINGLE_ELIMINATION\"}";

    @Inject
    GameConfigurationHistory configurations;

    @Inject
    GameRepository games;

    @Inject
    TournamentRepository tournaments;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void v29SnapshotsReadAsMaxAttemptsAndAnUnchangedTurnReusesTheMigratedRevision() throws Exception {
        UUID gameId = seedGame();
        GameConfiguration current = QuarkusTransaction.requiringNew()
                .call(() -> configurations.capture(games.findById(gameId)));
        assertEquals(7, current.maxAttempts());

        // What 1.9 stored: the same record, with the attempts limit under its old name.
        String startV29 = v29(current, 7, null);
        String firstRevisionV29 = v29(current, 3, "low");
        List<String> migrated = migrateFromV29(List.of(startV29, firstRevisionV29, startV29));

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                            "UPDATE games SET starting_configuration = cast(?1 as jsonb) WHERE id = ?2")
                    .setParameter(1, migrated.get(0)).setParameter(2, gameId).executeUpdate();
            for (int revision = 1; revision <= 2; revision++) {
                entityManager.createNativeQuery("""
                                INSERT INTO game_configuration_revisions (id, game_id, revision, settings)
                                VALUES (gen_random_uuid(), ?1, ?2, cast(?3 as jsonb))
                                """)
                        .setParameter(1, gameId).setParameter(2, revision).setParameter(3, migrated.get(revision))
                        .executeUpdate();
            }
        });

        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("startingConfiguration.maxAttempts", equalTo(7))
                .body("startingConfiguration", not(hasKey("maxRetries")))
                .body("startingConfiguration.chatMode", equalTo("MANDATORY"))
                .body("startingConfiguration.banterLevel", equalTo("SPICY"))
                .body("startingConfiguration.black.requestedReasoningEffort", equalTo("low"))
                .body("startingConfiguration.black.reasoningEffortOverride", equalTo("low"))
                .body("startingConfiguration.black.instructionsHash", equalTo(current.black().instructionsHash()))
                .body("startingConfiguration.white.modelId", equalTo("test/white"))
                .body("configurationHistory.revision", equalTo(List.of(1, 2)))
                .body("configurationHistory.settings.maxAttempts", equalTo(List.of(3, 7)))
                .body("configurationHistory[0].settings.reasoningEffort", equalTo("low"))
                .body("configurationHistory[1].settings", not(hasKey("maxRetries")));

        // The game loop records a revision per turn; unchanged settings must match the migrated latest one.
        GameConfigurationHistory.Revision revision = QuarkusTransaction.requiringNew().call(() ->
                configurations.recordRevision(gameId, configurations.capture(games.findByIdForUpdate(gameId))));
        assertEquals(new GameConfigurationHistory.Revision(2, false), revision);
        assertEquals(2L, QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM game_configuration_revisions WHERE game_id = ?1")
                .setParameter(1, gameId).getSingleResult()).longValue()));
    }

    private UUID seedGame() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Upgrade " + UUID.randomUUID();
            tournament.maxAttempts = 7;
            tournament.chatMode = ChatMode.MANDATORY;
            tournament.banterLevel = BanterLevel.SPICY;
            tournament.reasoningEffort = ReasoningEffort.MEDIUM;
            tournament.sharedCustomInstructions = "Play sharp lines.";
            tournaments.persist(tournament);
            TournamentParticipant white = participant(tournament, "White", "test/white", null);
            TournamentParticipant black = participant(tournament, "Black", "test/black", ReasoningEffort.LOW);
            black.customInstructions = "Prefer the Sicilian.";
            Game game = new Game();
            game.tournament = tournament;
            game.whitePlayerName = "White";
            game.whiteModelId = "test/white";
            game.whiteParticipant = white;
            game.blackPlayerName = "Black";
            game.blackModelId = "test/black";
            game.blackParticipant = black;
            games.persist(game);
            return game.id;
        });
    }

    private static TournamentParticipant participant(Tournament tournament, String name, String modelId,
                                                     ReasoningEffort effort) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.modelId = modelId;
        participant.reasoningEffort = effort;
        participant.persist();
        return participant;
    }

    private static String v29(GameConfiguration settings, int maxRetries, String reasoningEffort) {
        ObjectNode node = JSON.valueToTree(settings);
        node.remove("maxAttempts");
        node.put("maxRetries", maxRetries);
        if (reasoningEffort != null) node.put("reasoningEffort", reasoningEffort);
        return node.toString();
    }

    /**
     * Stores the snapshots (game start, revisions 1 and 2) plus objects without the old key in a fresh database at
     * V29, migrates it to the latest version and returns the migrated snapshots in the same order.
     */
    private List<String> migrateFromV29(List<String> snapshots) throws SQLException {
        String database = "v30_snapshots_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "29").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO tournaments (id, name, max_retries) VALUES
                              ('00000000-0000-0000-0000-0000000000a1', 'Snapshots', 7);
                            INSERT INTO games (id, tournament_id) VALUES
                              ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1'),
                              ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000000a1');
                            """);
                }
                try (PreparedStatement start = connection.prepareStatement(
                        "UPDATE games SET starting_configuration = cast(? as jsonb) WHERE id = cast(? as uuid)");
                     PreparedStatement revision = connection.prepareStatement("""
                             INSERT INTO game_configuration_revisions (id, game_id, revision, settings)
                             VALUES (gen_random_uuid(), cast(? as uuid), ?, cast(? as jsonb))
                             """)) {
                    start.setString(1, snapshots.get(0));
                    start.setString(2, "00000000-0000-0000-0000-0000000000b1");
                    start.executeUpdate();
                    start.setString(1, WITHOUT_OLD_KEY);
                    start.setString(2, "00000000-0000-0000-0000-0000000000b2");
                    start.executeUpdate();
                    for (int number = 1; number <= 2; number++) {
                        revision.setString(1, "00000000-0000-0000-0000-0000000000b1");
                        revision.setInt(2, number);
                        revision.setString(3, snapshots.get(number));
                        revision.executeUpdate();
                    }
                    revision.setString(1, "00000000-0000-0000-0000-0000000000b2");
                    revision.setInt(2, 1);
                    revision.setString(3, WITHOUT_OLD_KEY);
                    revision.executeUpdate();
                }
            }
            String untouchedBefore = jsonbText(url, WITHOUT_OLD_KEY);

            flyway(url, "latest").migrate();

            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                assertEquals(untouchedBefore, single(statement, """
                        SELECT starting_configuration::text FROM games
                        WHERE id = '00000000-0000-0000-0000-0000000000b2'
                        """), "a snapshot without maxRetries stays as it was");
                assertEquals(untouchedBefore, single(statement, """
                        SELECT settings::text FROM game_configuration_revisions
                        WHERE game_id = '00000000-0000-0000-0000-0000000000b2'
                        """), "a revision without maxRetries stays as it was");
                return List.of(
                        single(statement, """
                                SELECT starting_configuration::text FROM games
                                WHERE id = '00000000-0000-0000-0000-0000000000b1'
                                """),
                        single(statement, """
                                SELECT settings::text FROM game_configuration_revisions
                                WHERE game_id = '00000000-0000-0000-0000-0000000000b1' AND revision = 1
                                """),
                        single(statement, """
                                SELECT settings::text FROM game_configuration_revisions
                                WHERE game_id = '00000000-0000-0000-0000-0000000000b1' AND revision = 2
                                """));
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private String jsonbText(String url, String json) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement statement = connection.prepareStatement("SELECT cast(? as jsonb)::text")) {
            statement.setString(1, json);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
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
