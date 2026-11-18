package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LiveGamesIT {
    @Inject GameRepository games;
    @Inject TournamentRepository tournaments;

    @Test
    void listsRunningAndPausedGamesAndOneRealFinishedGame() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(() -> {
            LocalDateTime now = LocalDateTime.now();
            Tournament tournament = new Tournament();
            tournament.name = "Live fixture";
            tournament.persist();
            Game playing = game(tournament, "Tournament White", GameStatus.IN_PROGRESS, now.minusMinutes(2));
            Game practice = game(null, "Practice White", GameStatus.IN_PROGRESS, now.minusMinutes(1));
            practice.practiceKind = Game.PracticeKind.HUMAN_STOCKFISH;
            practice.whitePracticeControl = Game.PlayerType.HUMAN;
            practice.blackPracticeControl = Game.PlayerType.STOCKFISH;
            practice.stockfishElo = 1500;
            Game paused = game(tournament, "Paused White", GameStatus.PAUSED, now);
            Game finished = game(tournament, "Finished White", GameStatus.FORFEIT, now);
            finished.completedAt = now.plusDays(1);
            Game old = game(tournament, "Old finish", GameStatus.COMPLETED, now);
            old.completedAt = now.minusMinutes(2);
            Game bye = game(tournament, "BYE", GameStatus.COMPLETED, now);
            bye.completedAt = now.plusDays(2);
            Game cancelled = game(tournament, "Cancelled White", GameStatus.CANCELLED, now);
            cancelled.completedAt = now.plusDays(2);

            Move scored = move(playing, "WHITE");
            scored.evaluationCp = 135;
            move(playing, "BLACK");
            move(practice, "WHITE").evaluationMate = -3;
            return new Fixture(tournament.id, List.of(playing.id, practice.id, paused.id, finished.id,
                    old.id, bye.id, cancelled.id));
        });

        String fixtureGames = "active.findAll { it.whitePlayerName in ['Practice White','Tournament White','Paused White'] }";
        try {
            given().get("/api/games/live").then().statusCode(200).header("Cache-Control", equalTo("no-store"))
                    .body(fixtureGames + ".whitePlayerName", equalTo(List.of("Practice White", "Tournament White", "Paused White")))
                    .body(fixtureGames + ".status", equalTo(List.of("IN_PROGRESS", "IN_PROGRESS", "PAUSED")))
                    .body(fixtureGames + "[0].practice", equalTo(true))
                    .body(fixtureGames + "[0].evaluationMate", equalTo(-3))
                    .body(fixtureGames + "[1].tournamentName", equalTo("Live fixture"))
                    .body(fixtureGames + "[1].moveNumber", equalTo(2))
                    .body(fixtureGames + "[1].evaluationCp", nullValue())
                    .body("lastFinished.whitePlayerName", equalTo("Finished White"));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                fixture.games().forEach(games::deleteById);
                tournaments.deleteById(fixture.tournamentId());
            });
        }
    }

    private record Fixture(UUID tournamentId, List<UUID> games) {}

    private static Game game(Tournament tournament, String white, GameStatus status, LocalDateTime startedAt) {
        Game game = new Game();
        game.tournament = tournament;
        game.whitePlayerName = white;
        game.blackPlayerName = "Black";
        game.status = status;
        game.startedAt = startedAt;
        game.currentFen = game.currentFen.replace("0 1", "0 2");
        game.persist();
        return game;
    }

    private static Move move(Game game, String color) {
        Move move = new Move();
        move.game = game;
        move.color = color;
        move.moveNumber = 1;
        move.san = "e4";
        move.fen = game.currentFen;
        move.persist();
        return move;
    }
}
