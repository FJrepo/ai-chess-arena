package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * White and Black share a move number. Plies stored Black-first and with identical timestamps (as when seeded in
 * one transaction) must still come back in play order. Runs against a real PostgreSQL via Dev Services.
 */
@QuarkusTest
class MoveOrderIT {

    private static final String FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    MoveAttemptRepository attemptRepository;

    @Inject
    GameConversationService conversationService;

    @Test
    void pliesOfOneMoveNumberComeBackWhiteFirst() {
        LocalDateTime sameInstant = LocalDateTime.now().minusMinutes(5);
        UUID gameId = QuarkusTransaction.requiringNew().call(() -> {
            Game game = new Game();
            game.status = GameStatus.PAUSED;
            game.whitePlayerName = "White";
            game.blackPlayerName = "Black";
            game.currentFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3";
            gameRepository.persist(game);
            // Inserted in the "wrong" order with one timestamp for all of them.
            move(game, 2, "BLACK", "Nc6", sameInstant);
            move(game, 1, "BLACK", "e5", sameInstant);
            move(game, 2, "WHITE", "Nf3", sameInstant);
            move(game, 1, "WHITE", "e4", sameInstant);
            attempt(game, 1, "BLACK", 1, sameInstant);
            attempt(game, 1, "WHITE", 2, sameInstant);
            attempt(game, 1, "WHITE", 1, sameInstant);
            return game.id;
        });

        List<String> sans = QuarkusTransaction.requiringNew()
                .call(() -> moveRepository.findByGameId(gameId).stream().map(move -> move.san).toList());
        assertEquals(List.of("e4", "e5", "Nf3", "Nc6"), sans);

        List<String> fromGame = QuarkusTransaction.requiringNew()
                .call(() -> gameRepository.findById(gameId).moves.stream().map(move -> move.san).toList());
        assertEquals(List.of("e4", "e5", "Nf3", "Nc6"), fromGame);

        given().when().get("/api/games/{id}/moves", gameId)
                .then().statusCode(200)
                .body("san", contains("e4", "e5", "Nf3", "Nc6"));
        given().when().get("/api/games/{id}/attempts", gameId)
                .then().statusCode(200)
                .body("color", contains("WHITE", "WHITE", "BLACK"))
                .body("attemptNumber", contains(1, 2, 1));

        // The board rebuilt from the stored moves replays them in play order.
        GameConversationService.ConversationState state = QuarkusTransaction.requiringNew()
                .call(() -> conversationService.initializeConversation(gameId));
        assertEquals("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", state.board().getFen());
    }

    private void move(Game game, int moveNumber, String color, String san, LocalDateTime createdAt) {
        Move move = new Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = color;
        move.san = san;
        move.fen = FEN;
        move.createdAt = createdAt;
        moveRepository.persist(move);
    }

    private void attempt(Game game, int moveNumber, String color, int number, LocalDateTime createdAt) {
        MoveAttempt attempt = new MoveAttempt();
        attempt.game = game;
        attempt.moveNumber = moveNumber;
        attempt.color = color;
        attempt.attemptNumber = number;
        attempt.outcome = number == 1 && "WHITE".equals(color) ? Outcome.ILLEGAL_MOVE : Outcome.ACCEPTED;
        attempt.createdAt = createdAt;
        attemptRepository.persist(attempt);
    }
}
