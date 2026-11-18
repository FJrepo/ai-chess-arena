package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PracticeGameIT {
    private static final List<String> REPEATED = List.of("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8");

    @Inject ChessService chess;
    @Inject GameRepository games;
    @Inject MoveRepository moves;
    @Inject TournamentRepository tournaments;
    @Inject MoveAttemptService attempts;
    @Inject GameEngineService engine;
    @Inject PracticeGameService practice;

    @Test
    void forkBeforeThirdRepetitionCompletesWithNoProviderCallAndNoCopiedSpend() throws InterruptedException {
        Source source = finishedSource();
        UUID nonce = UUID.randomUUID();
        String id = fork(source.gameId(), 7, "BLACK", nonce);
        assertEquals(id, fork(source.gameId(), 7, "BLACK", nonce));

        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("practiceKind", equalTo("FROM_GAME"))
                .body("practiceSourceGameId", equalTo(source.gameId().toString()))
                .body("practiceSourcePly", equalTo(7))
                .body("tournamentId", nullValue())
                .body("blackControlType", equalTo("HUMAN"))
                .body("whiteModelId", equalTo("practice/white"))
                .body("moves.size()", equalTo(7))
                .body("moves[6].isInherited", equalTo(true))
                .body("moves[6].costUsd", nullValue())
                .body("attempts.size()", equalTo(0))
                .body("totalCostUsd", equalTo(0.0f));
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", id).then().statusCode(200);
        UUID practiceId = UUID.fromString(id);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (engine.isRunning(practiceId) && System.nanoTime() < deadline) Thread.sleep(20);
        given().contentType("application/json").body("{\"move\":\"Ng8\"}")
                .post("/api/games/{id}/human-move", id).then().statusCode(200);

        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("resultReason", equalTo("REPETITION"))
                .body("moves.size()", equalTo(8))
                .body("attempts.size()", equalTo(0))
                .body("totalCostUsd", equalTo(0.0f));
        given().get("/api/tournaments/{id}", source.tournamentId()).then().statusCode(200)
                .body("games.size()", equalTo(1))
                .body("games[0].id", equalTo(source.gameId().toString()))
                .body("games[0].resultReason", equalTo("REPETITION"));

        attempts.record(new MoveAttemptService.AttemptRecord(practiceId, 5, "WHITE", 1, "practice/white",
                Outcome.DISCARDED, "e4", null,
                new OpenRouterService.LlmResponse("e4", 10, 3, 0.12, 1, null, false, null)));
        given().get("/api/analytics/health?days=79").then().statusCode(200)
                .body("practiceCostUsd", equalTo(0.12f));
        given().get("/api/analytics/health?days=79&tournamentId=" + source.tournamentId()).then().statusCode(200)
                .body("practiceCostUsd", equalTo(0.0f));
    }

    @Test
    void rejectsTerminalPositionsAndWrongInputTypes() {
        Source source = finishedSource();
        given().contentType("application/json").body("{\"ply\":8,\"side\":\"WHITE\"}")
                .post("/api/games/{id}/practice", source.gameId()).then().statusCode(409);
        given().contentType("application/json").body("{\"ply\":\"7\",\"side\":\"WHITE\"}")
                .post("/api/games/{id}/practice", source.gameId()).then().statusCode(400);
    }

    @Test
    void sourceWithAHumanOnlyOffersTheSideOppositeTheModelAndCanBeDeletedLater() {
        Source source = finishedSource();
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = games.findByIdForUpdate(source.gameId());
            TournamentParticipant human = new TournamentParticipant();
            human.tournament = game.tournament;
            human.playerName = "Human opponent";
            human.controlType = TournamentParticipant.ControlType.HUMAN;
            human.persist();
            game.blackParticipant = human;
            game.blackPlayerName = human.playerName;
            game.blackModelId = null;
        });

        given().contentType("application/json").body("{\"ply\":1,\"side\":\"WHITE\"}")
                .post("/api/games/{id}/practice", source.gameId()).then().statusCode(409);
        String id = fork(source.gameId(), 1, "BLACK", UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> games.deleteById(source.gameId()));
        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("practiceSourceGameId", nullValue())
                .body("practiceSourcePly", equalTo(1))
                .body("moves[0].san", equalTo("Nf3"));
    }

    @Test
    void copiesTheFirstStoredPlyFromALegacyGameWithDuplicateRows() {
        Source source = finishedSource();
        IllegalStateException rollback = assertThrows(IllegalStateException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    moves.getEntityManager().createNativeQuery("DROP INDEX uq_moves_game_ply").executeUpdate();
                    Game game = games.findByIdForUpdate(source.gameId());
                    Move duplicate = new Move();
                    duplicate.game = game;
                    duplicate.moveNumber = 1;
                    duplicate.color = "WHITE";
                    duplicate.san = "e4";
                    duplicate.fen = chess.validateAndApply(chess.createBoard(), "e4").fen();
                    duplicate.createdAt = java.time.LocalDateTime.now().plusSeconds(1);
                    moves.persistAndFlush(duplicate);
                    var copied = practice.fork(game.id, 1, "WHITE", null, null);
                    assertEquals("Nf3", moves.findByGameId(copied.id()).getFirst().san);
                    assertThrows(GameStateConflictException.class, () ->
                            practice.fork(game.id, 1, "WHITE", null, duplicate.id));
                    throw new IllegalStateException("Rollback legacy duplicate fixture");
                }));
        assertEquals("Rollback legacy duplicate fixture", rollback.getMessage());
    }

    private String fork(UUID gameId, int ply, String side, UUID nonce) {
        return given().contentType("application/json")
                .body("{\"ply\":%d,\"side\":\"%s\",\"clientRequestId\":\"%s\"}".formatted(ply, side, nonce))
                .post("/api/games/{id}/practice", gameId).then().statusCode(201).extract().path("id");
    }

    private Source finishedSource() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Practice origin";
            tournament.status = Tournament.TournamentStatus.COMPLETED;
            tournaments.persist(tournament);
            Game game = new Game();
            game.tournament = tournament;
            game.whiteModelId = "practice/white";
            game.blackModelId = "practice/black";
            game.whitePlayerName = "White model";
            game.blackPlayerName = "Black model";
            game.status = Game.GameStatus.COMPLETED;
            game.result = Game.GameResult.DRAW;
            game.resultReason = Game.ResultReason.REPETITION;
            game.completedAt = java.time.LocalDateTime.now();
            game.totalCostUsd = new BigDecimal("0.50");
            games.persist(game);
            var board = chess.createBoard();
            for (String san : REPEATED) {
                Move move = new Move();
                move.game = game;
                move.moveNumber = chess.getMoveNumber(board);
                move.color = chess.getSideToMove(board);
                var result = chess.validateAndApply(board, san);
                assertTrue(result.valid());
                move.san = result.san();
                move.fen = result.fen();
                move.costUsd = new BigDecimal("0.0625");
                moves.persist(move);
            }
            game.currentFen = board.getFen();
            return new Source(tournament.id, game.id);
        });
    }

    private record Source(UUID tournamentId, UUID gameId) {}
}
