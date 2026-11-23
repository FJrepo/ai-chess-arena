package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import dev.aichessarena.service.OpenRouterService.ProviderOutcome;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * First-try legality in tournament Analytics: a move is first-try legal only when it was the model's first answer for
 * its ply, also when a pause and resume restarted the attempt numbers in between.
 */
@QuarkusTest
class FirstTryLegalityIT {

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String CUT_OFF_MODEL = "first-try/cut-off";
    private static final String CLEAN_MODEL = "first-try/clean";
    private static final String LEGACY_MODEL = "first-try/legacy";

    @Inject TournamentRepository tournaments;
    @Inject GameRepository games;
    @Inject MoveRepository moves;

    @Test
    void aCutOffAnswerBeforeAPauseMakesTheResumedMoveNotFirstTryLegal() throws Exception {
        UUID tournamentId = tournament();
        UUID gameId = game(tournamentId, CUT_OFF_MODEL, CLEAN_MODEL, GameStatus.WAITING);
        // White is cut off (pause), answers e4 after the resume (attempt 1 again); Black answers e5 at once; White's
        // next answer is cut off again, which pauses the game so the test can finish it.
        var calls = new AtomicInteger();
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String modelId, List<ChatMsg> history, AnswerDeadline deadline,
                                              int maxOutputTokens, dev.aichessarena.entity.ReasoningEffort effort) {
                return switch (calls.getAndIncrement()) {
                    case 1 -> answer("e4");
                    case 2 -> answer("e5");
                    default -> new LlmResponse(null, 10, 128, 0.001, 1, null, false, null,
                            ProviderOutcome.OK, 200, "length", null).withOutputTokenLimits(128, 8192);
                };
            }
        }, OpenRouterService.class);

        start(gameId);
        awaitStatus(gameId, "PAUSED");
        start(gameId);
        awaitStatus(gameId, "PAUSED");
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("attempts.outcome", equalTo(List.of("TRUNCATED", "ACCEPTED", "ACCEPTED", "TRUNCATED")))
                .body("attempts.attemptNumber", equalTo(List.of(1, 1, 1, 1)))
                .body("moves.san", equalTo(List.of("e4", "e5")))
                .body("moves.retryCount", equalTo(List.of(0, 0)));
        finish(gameId);

        // The legacy game has moves but no recorded attempts: the retry count decides, as before.
        UUID legacy = game(tournamentId, LEGACY_MODEL, CLEAN_MODEL, GameStatus.COMPLETED);
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findById(legacy);
            legacyMove(game, 1, "WHITE", LEGACY_MODEL, 0);
            legacyMove(game, 1, "BLACK", CLEAN_MODEL, 0);
            legacyMove(game, 2, "WHITE", LEGACY_MODEL, 1);
        });
        finish(legacy);

        for (String path : List.of("/api/analytics/comparison", "/api/analytics/reliability")) {
            ValidatableResponse response = given().queryParam("tournamentId", tournamentId).get(path)
                    .then().statusCode(200);
            response.body(firstTry(CUT_OFF_MODEL), equalTo(0.0f))
                    .body(firstTry(CLEAN_MODEL), equalTo(1.0f))
                    .body(firstTry(LEGACY_MODEL), equalTo(0.5f));
        }
        given().queryParam("tournamentId", tournamentId).get("/api/analytics/reliability/{model}", CUT_OFF_MODEL)
                .then().statusCode(200).body("firstTryLegalScore", equalTo(0.0f));
    }

    private static String firstTry(String model) {
        return "models.find { it.modelId == '" + model + "' }.firstTryLegalRate";
    }

    private static LlmResponse answer(String move) {
        return new LlmResponse("{\"move\":\"" + move + "\"}", 10, 3, 0.01, 1, null, false, null);
    }

    /** Starts or resumes the game; the previous loop may still be ending right after a pause. */
    private static void start(UUID gameId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        int status;
        do {
            status = given().contentType("application/json").body("{}").post("/api/games/{id}/start", gameId)
                    .then().extract().statusCode();
            if (status == 200) return;
            Thread.sleep(20);
        } while (status == 409 && System.nanoTime() < deadline);
        assertEquals(200, status);
    }

    private static void awaitStatus(UUID gameId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        String status;
        do {
            status = given().get("/api/games/{id}", gameId).then().statusCode(200).extract().path("status");
            if (expected.equals(status)) return;
            Thread.sleep(20);
        } while (("IN_PROGRESS".equals(status) || "WAITING".equals(status)) && System.nanoTime() < deadline);
        assertEquals(expected, status);
    }

    private void finish(UUID gameId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findById(gameId);
            game.status = GameStatus.COMPLETED;
            game.result = Game.GameResult.DRAW;
            game.resultReason = Game.ResultReason.REPETITION;
            game.statusDetail = null;
            game.completedAt = Instant.now();
        });
    }

    private UUID tournament() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "First-try legality " + UUID.randomUUID();
            tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
            tournaments.persist(tournament);
            return tournament.id;
        });
    }

    private UUID game(UUID tournamentId, String whiteModel, String blackModel, GameStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = tournaments.findById(tournamentId);
            TournamentParticipant white = participant(tournament, whiteModel);
            TournamentParticipant black = participant(tournament, blackModel);
            Game game = new Game();
            game.tournament = tournament;
            game.whiteParticipant = white;
            game.blackParticipant = black;
            game.whiteModelId = whiteModel;
            game.blackModelId = blackModel;
            game.whitePlayerName = whiteModel;
            game.blackPlayerName = blackModel;
            game.status = status;
            game.bracketRound = "Final";
            game.bracketPosition = (int) games.count("tournament.id", tournamentId);
            game.createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
            games.persist(game);
            return game.id;
        });
    }

    private TournamentParticipant participant(Tournament tournament, String model) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = model;
        participant.modelId = model;
        participant.seed = (int) TournamentParticipant.count("tournament.id", tournament.id);
        participant.persist();
        return participant;
    }

    private void legacyMove(Game game, int number, String color, String model, int retryCount) {
        Move move = new Move();
        move.game = game;
        move.moveNumber = number;
        move.color = color;
        move.san = "WHITE".equals(color) ? "e4" : "e5";
        move.fen = START_FEN;
        move.modelId = model;
        move.retryCount = retryCount;
        moves.persist(move);
    }
}
