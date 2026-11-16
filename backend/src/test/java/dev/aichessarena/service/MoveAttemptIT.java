package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import dev.aichessarena.service.OpenRouterService.ProviderOutcome;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Failed model attempts are stored with their cost: runs against a real PostgreSQL via Dev Services. */
@QuarkusTest
class MoveAttemptIT {

    @Inject
    MoveAttemptService attempts;

    @Inject
    MoveAttemptRepository attemptRepository;

    @Inject
    GameRepository games;

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentParticipantRepository participantRepository;

    @Inject
    TournamentService tournaments;

    @Test
    void failedAttemptIsPersistedAndCountsTowardsTheGameCost() {
        UUID id = newGame(new BigDecimal("0.01000000"));

        attempts.record(new MoveAttemptService.AttemptRecord(id, 1, "WHITE", 1, "model-a", Outcome.ILLEGAL_MOVE,
                "Qxh7", "Illegal move", new LlmResponse("{\"move\":\"Qxh7\"}", 200, 40, 0.0025, 800, "{\"id\":1}",
                        false, null, ProviderOutcome.OK, 200, "stop", 16)));
        attempts.record(new MoveAttemptService.AttemptRecord(id, 1, "WHITE", 2, "model-a", Outcome.ACCEPTED,
                "e4", null, new LlmResponse("{\"move\":\"e4\"}", 210, 20, 0.004, 700, "{}", false, null)));

        List<MoveAttempt> stored = QuarkusTransaction.requiringNew().call(() -> attemptRepository.findByGameId(id));
        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.ACCEPTED), stored.stream().map(a -> a.outcome).toList());
        MoveAttempt illegal = stored.getFirst();
        assertEquals("Qxh7", illegal.proposedMove);
        assertEquals(16, illegal.reasoningTokens);
        assertEquals("{\"id\":1}", illegal.rawResponse);

        // Only the rejected answer is added here; the accepted one is costed when its move is recorded.
        BigDecimal total = QuarkusTransaction.requiringNew().call(() -> games.findById(id).totalCostUsd);
        assertEquals(0, new BigDecimal("0.0125").compareTo(total), total.toPlainString());

        given().when().get("/api/games/{id}/attempts", id)
                .then().statusCode(200)
                .body("$", hasSize(2))
                .body("[0].outcome", equalTo("ILLEGAL_MOVE"))
                .body("[0].proposedMove", equalTo("Qxh7"))
                .body("[0].attemptNumber", equalTo(1))
                .body("[0].reasoningTokens", equalTo(16))
                .body("[0].finishReason", equalTo("stop"))
                .body("[1].outcome", equalTo("ACCEPTED"));
    }

    @Test
    void tinyRejectedChargesAddUpExactly() {
        UUID id = newGame(BigDecimal.ZERO);

        for (int attempt = 1; attempt <= 3; attempt++) {
            attempts.record(new MoveAttemptService.AttemptRecord(id, 1, "WHITE", attempt, "model-a",
                    Outcome.PARSE_FAILURE, null, "Could not extract a move",
                    new LlmResponse("hmm", 3, 1, 0.0000004, 50, "{}", false, null)));
        }

        BigDecimal total = QuarkusTransaction.requiringNew().call(() -> games.findById(id).totalCostUsd);
        assertEquals(0, new BigDecimal("0.0000012").compareTo(total), total.toPlainString());
    }

    @Test
    void attemptsOfAnUnknownGameAreNotFound() {
        given().when().get("/api/games/{id}/attempts", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void tournamentsDefaultToTheOutputTokenLimit() {
        given().contentType("application/json").body("{\"name\":\"Token limit " + UUID.randomUUID() + "\"}")
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("maxOutputTokens", equalTo(16384));
        given().contentType("application/json")
                .body("{\"name\":\"Token limit " + UUID.randomUUID() + "\",\"maxOutputTokens\":16384}")
                .when().post("/api/tournaments")
                .then().statusCode(201)
                .body("maxOutputTokens", equalTo(16384));
    }

    @Test
    void aDrawnSingleGameUnderReplayKeepsItsAttemptsAndTheTiebreakStartsEmpty() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Replay " + UUID.randomUUID();
            tournament.drawPolicy = Tournament.DrawPolicy.REPLAY_GAME;
            tournamentRepository.persist(tournament);
            TournamentParticipant white = participant(tournament, "White", 0);
            TournamentParticipant black = participant(tournament, "Black", 1);
            Game game = new Game();
            game.tournament = tournament;
            game.status = GameStatus.COMPLETED;
            game.result = Game.GameResult.DRAW;
            game.bracketRound = "Final";
            game.bracketPosition = 0;
            game.whiteParticipant = white;
            game.whitePlayerName = white.playerName;
            game.whiteModelId = white.modelId;
            game.blackParticipant = black;
            game.blackPlayerName = black.playerName;
            game.blackModelId = black.modelId;
            games.persist(game);
            return game.id;
        });
        attempts.record(new MoveAttemptService.AttemptRecord(id, 5, "BLACK", 1, "model-b", Outcome.PARSE_FAILURE,
                null, "Could not extract a move", new LlmResponse("hmm", 50, 5, 0.001, 300, "{}", false, null)));

        tournaments.advanceWinner(id);

        long kept = QuarkusTransaction.requiringNew().call(() -> attemptRepository.count("game.id", id));
        assertEquals(1, kept);
        Game drawn = QuarkusTransaction.requiringNew().call(() -> games.findById(id));
        assertEquals(0, new BigDecimal("0.001").compareTo(drawn.totalCostUsd), drawn.totalCostUsd.toPlainString());
        List<Game> series = QuarkusTransaction.requiringNew().call(() -> games.findBySeriesId(drawn.seriesId));
        assertEquals(2, series.size(), "a tiebreak game is added");
        UUID tiebreakId = series.get(1).id;
        long tiebreakAttempts = QuarkusTransaction.requiringNew()
                .call(() -> attemptRepository.count("game.id", tiebreakId));
        assertEquals(0, tiebreakAttempts);
    }

    private TournamentParticipant participant(Tournament tournament, String name, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.AI;
        participant.modelId = "model-" + name.toLowerCase();
        participant.seed = seed;
        participantRepository.persist(participant);
        return participant;
    }

    private UUID newGame(BigDecimal totalCost) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.status = GameStatus.IN_PROGRESS;
            game.whitePlayerName = "White";
            game.blackPlayerName = "Black";
            game.totalCostUsd = totalCost;
            games.persist(game);
            return game.id;
        });
    }
}
