package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every API timestamp is an absolute instant with an explicit UTC offset ({@code ...Z}), and the stored value is the
 * real instant also when the JVM default time zone is not UTC (a source install run in CET, for example).
 */
@QuarkusTest
class TimestampOffsetIT {

    private static final Pattern UTC_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
    private static final TimeZone VIENNA = TimeZone.getTimeZone("Europe/Vienna");

    @Inject
    TournamentRepository tournaments;

    @Inject
    GameRepository games;

    @Inject
    MoveAttemptRepository attempts;

    @Inject
    EntityManager entityManager;

    private TimeZone originalZone;
    private final List<UUID> tournamentIds = new ArrayList<>();

    @BeforeEach
    void runInVienna() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(VIENNA);
    }

    @AfterEach
    void restoreZoneAndCleanUp() {
        TimeZone.setDefault(originalZone);
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : tournamentIds) {
                attempts.delete("game.tournament.id", id);
                games.delete("tournament.id", id);
                tournaments.deleteById(id);
            }
        });
    }

    @Test
    void apiTimestampsCarryAUtcOffsetAndTheRealInstantInANonUtcJvmZone() {
        Instant before = Instant.now().minusSeconds(1);
        String white = "timestamp-test/white-" + UUID.randomUUID();
        Seeded seeded = QuarkusTransaction.requiringNew().call(() -> seed(white));
        Instant after = Instant.now().plusSeconds(1);

        JsonPath tournament = get("/api/tournaments/{id}", seeded.tournamentId());
        assertInstant(tournament.getString("createdAt"), before, after, "tournament createdAt");
        assertInstant(tournament.getString("updatedAt"), before, after, "tournament updatedAt");

        JsonPath list = get("/api/tournaments");
        assertInstant(list.getString("find { it.id == '%s' }.createdAt".formatted(seeded.tournamentId())), before,
                after, "tournament list createdAt");

        JsonPath game = get("/api/games/{id}", seeded.gameId());
        for (String field : List.of("createdAt", "startedAt", "completedAt")) {
            assertInstant(game.getString(field), before, after, "game " + field);
        }
        assertInstant(game.getString("moves[0].createdAt"), before, after, "game move createdAt");
        assertInstant(game.getString("chatMessages[0].createdAt"), before, after, "game chat createdAt");

        assertInstant(get("/api/games/{id}/moves", seeded.gameId()).getString("[0].createdAt"), before, after,
                "moves createdAt");
        assertInstant(get("/api/games/{id}/attempts", seeded.gameId()).getString("[0].createdAt"), before, after,
                "attempt createdAt");

        JsonPath leaderboard = get("/api/analytics/leaderboard");
        String row = "models.find { it.modelId == '%s' }".formatted(white);
        assertInstant(leaderboard.getString(row + ".lastPlayed"), before, after, "leaderboard lastPlayed");
        assertInstant(leaderboard.getString(row + ".ratingHistory[0].completedAt"), before, after,
                "leaderboard history completedAt");

        JsonPath export = get("/api/tournaments/{id}/export.json", seeded.tournamentId());
        assertEquals(3, export.getInt("schemaVersion"), "timestamp format change bumps the export schema");
        for (String path : List.of("createdAt", "updatedAt", "games[0].createdAt", "games[0].startedAt",
                "games[0].completedAt", "games[0].moves[0].createdAt", "games[0].chatMessages[0].createdAt",
                "games[0].attempts[0].createdAt")) {
            assertInstant(export.getString(path), before, after, "export " + path);
        }

        String checkedAt = get("/api/config/system-status").getString("checkedAt");
        assertInstant(checkedAt, before, Instant.now().plusSeconds(1), "system status checkedAt");
    }

    @Test
    void storedValuesAreTheRealInstantInANonUtcJvmZone() {
        Instant before = Instant.now().minusSeconds(1);
        Seeded seeded = QuarkusTransaction.requiringNew().call(() -> seed("timestamp-test/db-" + UUID.randomUUID()));
        Instant after = Instant.now().plusSeconds(1);

        for (String column : List.of("t.created_at", "t.updated_at", "g.created_at", "g.started_at", "g.completed_at",
                "m.created_at", "c.created_at", "a.created_at")) {
            // extract(epoch) of a timestamptz is the absolute instant, independent of the session time zone.
            Number epoch = (Number) QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery("""
                            SELECT extract(epoch FROM %s) FROM tournaments t
                            JOIN games g ON g.tournament_id = t.id
                            JOIN moves m ON m.game_id = g.id
                            JOIN chat_messages c ON c.game_id = g.id
                            JOIN move_attempts a ON a.game_id = g.id
                            WHERE t.id = ?1
                            """.formatted(column)).setParameter(1, seeded.tournamentId()).getSingleResult());
            Instant stored = Instant.ofEpochSecond(0, Math.round(epoch.doubleValue() * 1_000_000) * 1_000);
            assertFalse(stored.isBefore(before) || stored.isAfter(after),
                    column + " stored " + stored + ", expected between " + before + " and " + after);
        }
    }

    private Seeded seed(String whiteModel) {
        Tournament tournament = new Tournament();
        tournament.name = "Timestamp fixture";
        tournaments.persist(tournament);
        tournamentIds.add(tournament.id);

        Game game = new Game();
        game.tournament = tournament;
        game.whitePlayerName = "White";
        game.whiteModelId = whiteModel;
        game.blackPlayerName = "Black";
        game.blackModelId = whiteModel + "-opponent";
        game.status = Game.GameStatus.COMPLETED;
        game.result = Game.GameResult.WHITE_WINS;
        game.startedAt = game.createdAt;
        game.completedAt = game.createdAt;
        games.persist(game);

        Move move = new Move();
        move.game = game;
        move.moveNumber = 1;
        move.color = "WHITE";
        move.san = "e4";
        move.fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
        move.modelId = whiteModel;
        move.costUsd = BigDecimal.ZERO;
        move.persist();

        ChatMessage chat = new ChatMessage();
        chat.game = game;
        chat.moveNumber = 1;
        chat.senderColor = "WHITE";
        chat.senderModel = whiteModel;
        chat.message = "Good luck.";
        chat.persist();

        MoveAttempt attempt = new MoveAttempt();
        attempt.game = game;
        attempt.moveNumber = 1;
        attempt.color = "WHITE";
        attempt.attemptNumber = 1;
        attempt.modelId = whiteModel;
        attempt.outcome = MoveAttempt.Outcome.ILLEGAL_MOVE;
        attempts.persist(attempt);
        return new Seeded(tournament.id, game.id);
    }

    private static JsonPath get(String path, Object... parameters) {
        return given().get(path, parameters).then().statusCode(200).extract().jsonPath();
    }

    private static void assertInstant(String value, Instant before, Instant after, String label) {
        assertNotNull(value, label + " is missing");
        assertTrue(UTC_INSTANT.matcher(value).matches(), label + " has no UTC offset: " + value);
        Instant instant = Instant.parse(value);
        assertFalse(instant.isBefore(before) || instant.isAfter(after),
                label + " is " + instant + ", expected between " + before + " and " + after);
    }

    private record Seeded(UUID tournamentId, UUID gameId) {}
}
