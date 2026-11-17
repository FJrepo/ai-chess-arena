package dev.aichessarena.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.service.ChessService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ExportIT {
    @Test
    void tournamentAndGamePgnAreUtf8AttachmentsWithChatOnlyWhenRequested() {
        Fixture f = fixture();
        String pgn = given().get("/api/tournaments/{id}/export.pgn", f.tournamentId).then().statusCode(200)
                .header("Content-Type", containsString("application/x-chess-pgn"))
                .header("Content-Type", containsString("charset=utf-8"))
                .header("Content-Disposition", equalTo("attachment; filename=\"tournament-" + f.tournamentId + ".pgn\""))
                .header("Cache-Control", equalTo("no-store"))
                .body(containsString("[Event \"Export Ω\"]"))
                .body(not(containsString("Chat:"))).extract().asString();
        PgnHolder parsed = new PgnHolder();
        parsed.loadPgn(pgn);
        assertEquals(2, parsed.getGames().size());
        assertEquals(2, parsed.getGames().getFirst().getHalfMoves().size());
        given().queryParam("includeChat", true).get("/api/tournaments/{id}/export.pgn", f.tournamentId)
                .then().statusCode(200).body(containsString("{Chat: =Hello (Ω), \"world\"}"));
        given().get("/api/games/{id}/pgn", f.gameId).then().statusCode(200)
                .header("Content-Disposition", equalTo("attachment; filename=\"game-" + f.gameId + ".pgn\""))
                .body(containsString("[TerminationDetails \"resignation\"]"))
                .body(containsString("{[%eval 0.35]}"))
                .body(not(containsString("Chat:")));
        given().queryParam("includeChat", true).get("/api/games/{id}/pgn", f.gameId)
                .then().statusCode(200).body(containsString("{Chat: =Hello (Ω), \"world\"}"));
    }

    @Test
    void csvContainsQuotedProtectedNamesAndQualityAndAuthoritativeCost() {
        Fixture f = fixture();
        byte[] data = given().get("/api/tournaments/{id}/export.csv", f.tournamentId).then().statusCode(200)
                .header("Content-Type", containsString("text/csv"))
                .header("Content-Type", containsString("charset=utf-8"))
                .header("Content-Disposition", equalTo("attachment; filename=\"tournament-" + f.tournamentId + ".csv\""))
                .extract().asByteArray();
        String csv = new String(data, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\"game_id\",\"round\""));
        assertTrue(csv.contains("\"'=Alpha, \"\"Ω\"\"\""));
        assertTrue(csv.contains("\"0.12345678\""));
        assertTrue(csv.contains("\"2\",\"0.12345678\",\"0\",\"0\",\"0\",\"0\""), csv);
        assertEquals(3, csv.split("\r\n").length);
    }

    @Test
    void jsonContainsAnalysisAndUsageButNeverSecretBearingFields() {
        Fixture f = fixture();
        var response = given().get("/api/tournaments/{id}/export.json", f.tournamentId).then().statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .header("Content-Type", containsString("charset=utf-8"))
                .header("Content-Disposition", equalTo("attachment; filename=\"tournament-" + f.tournamentId + ".json\""))
                .body("schemaVersion", equalTo(1))
                .body("id", equalTo(f.tournamentId.toString()))
                .body("settings.maxRetries", equalTo(3))
                .body("settings.reasoningEffort", equalTo("low"))
                .body("participants.size()", equalTo(2))
                .body("games.size()", equalTo(2))
                .body("games[0].moves.size()", equalTo(2))
                .body("games[0].moves[0].san", equalTo("e4"))
                .body("games[0].moves[0].fen", containsString(" b "))
                .body("games[0].moves[0].evaluationCp", equalTo(35))
                .body("games[0].moves[0].quality.centipawnLoss", equalTo(0))
                .body("games[0].qualitySummary.white.analyzedMoves", equalTo(1))
                .body("games[0].chatMessages[0].message", equalTo("=Hello {Ω}, \"world\""))
                .body("games[0].attempts[0].outcome", equalTo("DISCARDED"))
                .body("games[0].attempts[0].reasoningEffort", equalTo("low"))
                .body("games[0].attempts[0].promptTokens", equalTo(100))
                .body("games[0].attempts[0].cachedPromptTokens", equalTo(50))
                .body("games[0].attempts[0].reasoningTokens", equalTo(20))
                .body("games[0].resultReason", equalTo("RESIGNATION"))
                .body("games[1].status", equalTo("CANCELLED"))
                .body("games[1].result", equalTo("*"))
                .extract();
        String json = response.asString();
        for (String excluded : new String[]{"PRIVATE_PROMPT_SENTINEL", "RAW_RESPONSE_SENTINEL", "PROVIDER_ERROR_SENTINEL",
                "defaultSystemPrompt", "customSystemPrompt", "sharedCustomInstructions", "customInstructions",
                "rawResponse", "errorMessage", "statusDetail", "apiKey", "password", "clientRequestId"}) {
            assertFalse(json.contains(excluded), excluded);
        }
        assertEquals(new BigDecimal("0.12345678"), response.jsonPath().getObject("totalCostUsd", BigDecimal.class));
        assertEquals(0, new BigDecimal("0.00000001").compareTo(
                response.jsonPath().getObject("games[0].attempts[0].costUsd", BigDecimal.class)));
    }

    @Test
    void emptyTournamentExportsAndMissingIdsAreHandled() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament t = new Tournament();
            t.name = "Empty";
            t.persist();
            return t.id;
        });
        given().get("/api/tournaments/{id}/export.pgn", id).then().statusCode(200).body(equalTo(""));
        given().get("/api/tournaments/{id}/export.csv", id).then().statusCode(200)
                .body(startsWith("\"game_id\""));
        given().get("/api/tournaments/{id}/export.json", id).then().statusCode(200)
                .body("games", empty()).body("participants", empty());
        UUID missing = UUID.randomUUID();
        for (String format : new String[]{"pgn", "csv", "json"}) {
            given().get("/api/tournaments/{id}/export." + format, missing).then().statusCode(404);
        }
        given().get("/api/games/{id}/pgn", missing).then().statusCode(404);
    }

    private Fixture fixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament t = new Tournament();
            t.name = "Export Ω";
            t.defaultSystemPrompt = "PRIVATE_PROMPT_SENTINEL";
            t.sharedCustomInstructions = "PRIVATE_PROMPT_SENTINEL";
            t.reasoningEffort = ReasoningEffort.LOW;
            t.persist();
            TournamentParticipant white = participant(t, "=Alpha, \"Ω\"", "test/alpha", 0);
            TournamentParticipant black = participant(t, "Beta", "test/beta", 1);
            Game game = new Game();
            game.tournament = t;
            game.whiteParticipant = white;
            game.blackParticipant = black;
            game.whitePlayerName = white.playerName;
            game.blackPlayerName = black.playerName;
            game.whiteModelId = white.modelId;
            game.blackModelId = black.modelId;
            game.status = Game.GameStatus.COMPLETED;
            game.result = Game.GameResult.WHITE_WINS;
            game.resultReason = Game.ResultReason.RESIGNATION;
            game.bracketRound = "Final";
            game.totalCostUsd = new BigDecimal("0.12345678");
            game.statusDetail = "PROVIDER_ERROR_SENTINEL";
            game.persist();
            Board board = new Board();
            ChessService chess = new ChessService();
            for (String san : new String[]{"e4", "e5"}) {
                Move m = new Move();
                m.game = game;
                m.moveNumber = 1;
                m.color = "e4".equals(san) ? "WHITE" : "BLACK";
                m.san = san;
                assertTrue(chess.validateAndApply(board, san).valid());
                m.fen = board.getFen();
                m.evaluationCp = 35;
                m.rawResponse = "RAW_RESPONSE_SENTINEL";
                m.persist();
            }
            game.currentFen = board.getFen();
            ChatMessage chat = new ChatMessage();
            chat.game = game;
            chat.moveNumber = 1;
            chat.senderColor = "WHITE";
            chat.message = "=Hello {Ω}, \"world\"";
            chat.persist();
            MoveAttempt attempt = new MoveAttempt();
            attempt.game = game;
            attempt.moveNumber = 2;
            attempt.color = "WHITE";
            attempt.attemptNumber = 1;
            attempt.outcome = MoveAttempt.Outcome.DISCARDED;
            attempt.promptTokens = 100;
            attempt.cachedPromptTokens = 50;
            attempt.completionTokens = 30;
            attempt.reasoningTokens = 20;
            attempt.reasoningEffort = ReasoningEffort.LOW;
            attempt.costUsd = new BigDecimal("0.00000001");
            attempt.rawResponse = "RAW_RESPONSE_SENTINEL";
            attempt.errorMessage = "PROVIDER_ERROR_SENTINEL";
            attempt.persist();
            Game cancelled = new Game();
            cancelled.tournament = t;
            cancelled.status = Game.GameStatus.CANCELLED;
            cancelled.createdAt = game.createdAt.plusSeconds(1);
            cancelled.persist();
            return new Fixture(t.id, game.id);
        });
    }

    private TournamentParticipant participant(Tournament t, String name, String model, int seed) {
        TournamentParticipant p = new TournamentParticipant();
        p.tournament = t;
        p.playerName = name;
        p.modelId = model;
        p.seed = seed;
        p.customInstructions = "PRIVATE_PROMPT_SENTINEL";
        p.customSystemPrompt = "PRIVATE_PROMPT_SENTINEL";
        p.persist();
        return p;
    }

    private record Fixture(UUID tournamentId, UUID gameId) {}
}
