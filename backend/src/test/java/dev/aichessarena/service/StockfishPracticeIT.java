package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StockfishPracticeIT {
    @Inject GameRepository games;

    @Test
    void startsWithoutAKeyAndStoresAnUnchargedEngineMove() throws InterruptedException {
        QuarkusMock.installMockForType(
                OpenRouterKeyStoreFixture.keys(null, Path.of("target", "keyless-stockfish")),
                OpenRouterKeyStore.class);
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override
            Process startProcess() {
                return new StockfishServiceTest.FakeProcess(StockfishOpponentServiceTest.HANDSHAKE + "bestmove e2e4\n", true);
            }
        }, StockfishOpponentService.class);
        String id = given().contentType("application/json").body("{\"side\":\"BLACK\",\"elo\":1500}")
                .post("/api/games/practice/stockfish").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", id)
                .then().statusCode(200);
        awaitGameState(UUID.fromString(id), game -> !game.moves.isEmpty());
        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("practiceKind", equalTo("HUMAN_STOCKFISH"))
                .body("whiteControlType", equalTo("STOCKFISH"))
                .body("blackControlType", equalTo("HUMAN"))
                .body("whiteModelId", nullValue())
                .body("moves[0].costUsd", nullValue())
                .body("attempts.size()", equalTo(0))
                .body("startingConfiguration.stockfishElo", equalTo(1500));
    }

    @Test
    void missingEnginePausesWithoutAnAttemptOrForfeit() throws InterruptedException {
        QuarkusMock.installMockForType(new StockfishOpponentService() {
            @Override
            Process startProcess() throws IOException {
                throw new IOException("Fixture engine unavailable");
            }
        }, StockfishOpponentService.class);

        String id = given().contentType("application/json").body("{\"side\":\"BLACK\",\"elo\":1500}")
                .post("/api/games/practice/stockfish").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{}").post("/api/games/{id}/start", id)
                .then().statusCode(200);
        UUID gameId = UUID.fromString(id);
        awaitGameState(gameId, game -> game.status == Game.GameStatus.PAUSED);
        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("status", equalTo("PAUSED"))
                .body("statusDetail", containsString("Stockfish"))
                .body("result", nullValue())
                .body("moves.size()", equalTo(0))
                .body("attempts.size()", equalTo(0));
    }

    @Test
    void invalidEloAndColorDoNotCreateAGame() {
        given().contentType("application/json").body("{\"side\":\"WHITE\",\"elo\":1319}")
                .post("/api/games/practice/stockfish").then().statusCode(400);
        given().contentType("application/json").body("{\"side\":\"WHITE\",\"elo\":1500.5}")
                .post("/api/games/practice/stockfish").then().statusCode(400);
        given().contentType("application/json").body("{\"side\":\"GREEN\",\"elo\":1500}")
                .post("/api/games/practice/stockfish").then().statusCode(400);
    }

    private void awaitGameState(UUID id, java.util.function.Predicate<Game> check) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean played;
        do {
            played = QuarkusTransaction.requiringNew().call(() -> {
                Game game = games.findById(id);
                return game != null && check.test(game);
            });
            if (played) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertTrue(played, "Stockfish game did not reach the expected state");
    }
}
