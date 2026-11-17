package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real HTTP, Hibernate and PostgreSQL; no external model or engine calls. */
@QuarkusTest
class LeaderboardIT {

    @Inject
    GameRepository games;

    @Inject
    TournamentRepository tournaments;

    @Inject
    TournamentParticipantRepository participants;

    @Inject
    MoveAttemptRepository attempts;

    private final List<UUID> tournamentIds = new ArrayList<>();
    private String alpha;
    private String beta;
    private UUID pendingId;

    @BeforeEach
    void seed() {
        alpha = "leaderboard-test/alpha-" + UUID.randomUUID();
        beta = "leaderboard-test/beta-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Tournament first = tournament();
            // No date window: this result must still count, even in a cancelled tournament.
            first.status = Tournament.TournamentStatus.CANCELLED;
            Game old = game(first, Game.GameStatus.COMPLETED, Game.GameResult.WHITE_WINS,
                    LocalDateTime.of(2000, 1, 1, 12, 0));
            old.resultReason = Game.ResultReason.ADJUDICATION;
            recordEffort(old, ReasoningEffort.LOW);
            Tournament second = tournament(false);
            Game forfeit = game(second, Game.GameStatus.FORFEIT, Game.GameResult.WHITE_FORFEIT,
                    LocalDateTime.of(2000, 1, 2, 12, 0));
            forfeit.resultReason = Game.ResultReason.TIMEOUT;
            recordEffort(forfeit, ReasoningEffort.HIGH);
            Game pending = game(second, Game.GameStatus.PAUSED, null, null);
            pendingId = pending.id;
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : tournamentIds) {
                attempts.delete("game.tournament.id", id);
                games.delete("tournament.id", id);
                participants.delete("tournament.id", id);
                tournaments.deleteById(id);
            }
        });
    }

    @Test
    void defaultIncludesAllTournamentsHistoricalResultsAndForfeitLosses() {
        Response response = leaderboard(null);
        assertEquals(Boolean.TRUE, response.path("includeForfeits"));
        assertEquals(2, value(response, alpha, "games"));
        assertEquals(1499, value(response, alpha, "rating"));
        assertEquals(1, value(response, alpha, "wins"));
        assertEquals(1, value(response, alpha, "losses"));
        assertEquals(1, value(response, alpha, "forfeits"));
        assertEquals(0, value(response, beta, "forfeits"));
        assertEquals(Boolean.TRUE, value(response, alpha, "provisional"));
        assertEquals(2, value(response, alpha, "ratingHistory.size()"));
        assertEquals("mixed", value(response, alpha, "reasoningEffort"));
        assertEquals("2000-01-02T12:00:00", value(response, alpha, "lastPlayed"));
    }

    @Test
    void leaderboardReplaysOnlyTheSelectedHintMode() {
        Response hinted = given().queryParam("legalMoveHints", true).get("/api/analytics/leaderboard")
                .then().statusCode(200).extract().response();
        Response unhinted = given().queryParam("legalMoveHints", false).get("/api/analytics/leaderboard")
                .then().statusCode(200).extract().response();
        assertEquals(1516, value(hinted, alpha, "rating"));
        assertEquals(1484, value(unhinted, alpha, "rating"));
        assertEquals(1, value(hinted, alpha, "games"));
        assertEquals(1, value(unhinted, alpha, "games"));
        assertEquals("low", value(hinted, alpha, "reasoningEffort"));
        assertEquals("high", value(unhinted, alpha, "reasoningEffort"));

        given().queryParam("legalMoveHints", false).queryParam("includeForfeits", false)
                .get("/api/analytics/leaderboard").then().statusCode(200)
                .body("models.find { it.modelId == '" + alpha + "' }", equalTo(null));
        given().queryParam("legalMoveHints", "").get("/api/analytics/leaderboard").then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "TrUe"})
    void explicitlyIncludingForfeitsKeepsForfeitLosses(String includeForfeits) {
        Response response = given().queryParam("includeForfeits", includeForfeits)
                .when().get("/api/analytics/leaderboard")
                .then().statusCode(200).extract().response();
        assertEquals(Boolean.TRUE, response.path("includeForfeits"));
        assertEquals(2, value(response, alpha, "games"));
        assertEquals(1499, value(response, alpha, "rating"));
        assertEquals(1, value(response, alpha, "forfeits"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "FaLsE"})
    void excludingForfeitsReplaysTheEntireSample(String includeForfeits) {
        Response response = given().queryParam("includeForfeits", includeForfeits)
                .when().get("/api/analytics/leaderboard")
                .then().statusCode(200).extract().response();
        assertEquals(Boolean.FALSE, response.path("includeForfeits"));
        assertEquals(1, value(response, alpha, "games"));
        assertEquals(1516, value(response, alpha, "rating"));
        assertEquals(1484, value(response, beta, "rating"));
        assertEquals(0, value(response, alpha, "forfeits"));
        assertEquals(1, value(response, alpha, "ratingHistory.size()"));
        assertEquals("low", value(response, alpha, "reasoningEffort"));
        assertEquals("2000-01-01T12:00:00", value(response, alpha, "lastPlayed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "1", "", "0", " true", "false "})
    void invalidIncludeForfeitsIsRejected(String includeForfeits) {
        given().queryParam("includeForfeits", includeForfeits)
                .when().get("/api/analytics/leaderboard")
                .then().statusCode(400)
                .contentType(ContentType.JSON)
                .body("error", equalTo("includeForfeits must be true or false"));
    }

    @Test
    void excludesByesHumansCancelledUnfinishedAndMissingCompletionTimes() {
        QuarkusTransaction.requiringNew().run(() -> {
            Tournament tournament = tournaments.findById(tournamentIds.getFirst());
            for (Game.GameStatus status : List.of(Game.GameStatus.CANCELLED, Game.GameStatus.WAITING,
                    Game.GameStatus.IN_PROGRESS, Game.GameStatus.PAUSED)) {
                game(tournament, status, Game.GameResult.WHITE_WINS, LocalDateTime.now());
            }
            Game bye = game(tournament, Game.GameStatus.COMPLETED, Game.GameResult.WHITE_WINS, LocalDateTime.now());
            bye.blackPlayerName = "BYE";
            Game human = game(tournament, Game.GameStatus.COMPLETED, Game.GameResult.WHITE_WINS, LocalDateTime.now());
            TournamentParticipant participant = new TournamentParticipant();
            participant.tournament = tournament;
            participant.playerName = "Human";
            participant.controlType = TournamentParticipant.ControlType.HUMAN;
            participants.persist(participant);
            human.blackParticipant = participant;
            // Stale model id on a human side must not bypass the scope rule.
            Game missingModel = game(tournament, Game.GameStatus.COMPLETED, Game.GameResult.DRAW, LocalDateTime.now());
            missingModel.blackModelId = null;
            game(tournament, Game.GameStatus.COMPLETED, Game.GameResult.DRAW, null);
        });
        assertEquals(2, value(leaderboard(true), alpha, "games"));
    }

    @Test
    void nextRequestImmediatelySeesACompletedGameWithoutInvalidationHooks() {
        assertEquals(2, value(leaderboard(true), alpha, "games"));
        QuarkusTransaction.requiringNew().run(() -> {
            Game pending = games.findById(pendingId);
            pending.status = Game.GameStatus.COMPLETED;
            pending.result = Game.GameResult.BLACK_WINS;
            pending.resultReason = Game.ResultReason.RESIGNATION;
            pending.completedAt = LocalDateTime.of(2000, 1, 3, 12, 0);
        });
        assertEquals(3, value(leaderboard(true), alpha, "games"));
        assertEquals(3, value(leaderboard(true), alpha, "ratingHistory.size()"));
    }

    @Test
    void fiveThousandGamesKeepHistoriesBoundedAndMeasureRequestCost() {
        String prefix = "leaderboard-benchmark/" + UUID.randomUUID() + "-";
        QuarkusTransaction.requiringNew().run(() -> {
            Tournament tournament = tournament();
            for (int i = 0; i < 5000; i++) {
                Game game = game(tournament, Game.GameStatus.COMPLETED, Game.GameResult.WHITE_WINS,
                        LocalDateTime.of(2001, 1, 1, 0, 0).plusSeconds(i));
                game.whiteModelId = prefix + (i % 100);
                game.blackModelId = prefix + ((i + 1) % 100);
            }
        });
        leaderboard(true); // warm HTTP and query compilation
        long started = System.nanoTime();
        Response response = leaderboard(true);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(100, value(response, prefix + "0", "games"));
        assertEquals(50, value(response, prefix + "0", "ratingHistory.size()"));
        assertEquals(Boolean.FALSE, value(response, prefix + "0", "provisional"));
        System.out.printf("Leaderboard PostgreSQL + HTTP: 5,000 games / 100 models, warm request %d ms%n", elapsedMs);
    }

    private Response leaderboard(Boolean includeForfeits) {
        var request = given();
        if (includeForfeits != null) {
            request.queryParam("includeForfeits", includeForfeits);
        }
        return request.when().get("/api/analytics/leaderboard").then().statusCode(200).extract().response();
    }

    private Object value(Response response, String modelId, String field) {
        return response.path("models.find { it.modelId == '%s' }.%s".formatted(modelId, field));
    }

    private Tournament tournament() {
        return tournament(true);
    }

    private void recordEffort(Game game, ReasoningEffort effort) {
        MoveAttempt attempt = new MoveAttempt();
        attempt.game = game;
        attempt.moveNumber = 1;
        attempt.color = "WHITE";
        attempt.attemptNumber = 1;
        attempt.modelId = alpha;
        attempt.outcome = MoveAttempt.Outcome.ILLEGAL_MOVE;
        attempt.reasoningEffort = effort;
        attempt.createdAt = game.completedAt;
        attempts.persist(attempt);
    }

    private Tournament tournament(boolean legalMoveHints) {
        Tournament tournament = new Tournament();
        tournament.name = "Leaderboard fixture";
        tournament.legalMoveHints = legalMoveHints;
        tournaments.persist(tournament);
        tournamentIds.add(tournament.id);
        return tournament;
    }

    private Game game(Tournament tournament, Game.GameStatus status, Game.GameResult result,
                      LocalDateTime completedAt) {
        Game game = new Game();
        game.tournament = tournament;
        game.whiteModelId = alpha;
        game.blackModelId = beta;
        game.whitePlayerName = "Alpha";
        game.blackPlayerName = "Beta";
        game.status = status;
        game.result = result;
        game.completedAt = completedAt;
        games.persist(game);
        return game;
    }
}
