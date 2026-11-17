package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExportServiceTest {
    private final ExportService exports = new ExportService();

    @Test
    void sevenTagsExtrasAndAnnotationsRoundTripToTheSamePosition() throws Exception {
        Game game = game("e4", "e5", "Nf3", "Nc6");
        game.status = Game.GameStatus.COMPLETED;
        game.result = Game.GameResult.DRAW;
        game.resultReason = Game.ResultReason.REPETITION;
        game.startedAt = LocalDateTime.of(2026, 3, 6, 10, 30);
        game.tournament.moveTimeoutSeconds = 120;
        game.openingName = "Open Game";
        game.openingEco = "C20";
        game.moves.get(0).isBook = true;
        game.moves.get(1).evaluationCp = 35;
        game.moves.get(2).evaluationMate = -3;
        String pgn = exports.gamePgn(game, false);
        assertTrue(pgn.startsWith("[Event \"Test Arena\"]\n[Site \"AI Chess Arena\"]\n"
                + "[Date \"2026.03.06\"]\n[Round \"Final.2\"]\n[White \"Alpha\"]\n"
                + "[Black \"Beta\"]\n[Result \"1/2-1/2\"]\n"));
        assertTrue(pgn.contains("[WhiteModel \"test/alpha\"]"));
        assertTrue(pgn.contains("[BlackModel \"test/beta\"]"));
        assertTrue(pgn.contains("[TimeControl \"*120\"]"));
        assertTrue(pgn.contains("[Opening \"Open Game\"]\n[ECO \"C20\"]"));
        assertTrue(pgn.contains("1. e4 {book}"));
        assertTrue(pgn.contains("{[%eval 0.35]}"));
        assertTrue(pgn.contains("{[%eval #-3]}"));
        PgnHolder parsed = parse(pgn);
        assertEquals(1, parsed.getGames().size());
        var restored = parsed.getGames().getFirst();
        assertEquals(4, restored.getHalfMoves().size());
        assertEquals(game.currentFen, restored.getHalfMoves().getFen());
        assertEquals("1/2-1/2", restored.getResult().getDescription());
    }

    @Test
    void tagsAndChatCannotBreakPgnAndChatIsOptIn() throws Exception {
        Game game = game("e4", "e5");
        game.tournament.name = "Arena \"quoted\" \\ title\n[Result \"1-0\"]";
        game.whitePlayerName = "=Alpha \"A\" \\ Ω";
        ChatMessage message = new ChatMessage();
        message.moveNumber = 1;
        message.senderColor = "WHITE";
        message.message = "Hello {escape} } 1-0 {\n[Event \"injected\"]";
        game.chatMessages.add(message);
        String pgn = exports.gamePgn(game, true);
        assertTrue(pgn.contains("\\\"quoted\\\" \\\\ title [Result \\\"1-0\\\"]"));
        assertTrue(pgn.contains("{Chat: Hello (escape) ) 1-0 ( [Event \"injected\"]}"));
        assertFalse(exports.gamePgn(game, false).contains("Chat:"));
        assertEquals(2, parse(pgn).getGames().getFirst().getHalfMoves().size());
    }

    @ParameterizedTest
    @CsvSource({
            "CHECKMATE,COMPLETED,WHITE_WINS,normal,1-0",
            "RESIGNATION,COMPLETED,BLACK_WINS,normal,0-1",
            "ADJUDICATION,COMPLETED,WHITE_WINS,adjudication,1-0",
            "TIMEOUT,FORFEIT,WHITE_FORFEIT,time forfeit,0-1",
            "FORFEIT_INVALID_MOVES,FORFEIT,BLACK_FORFEIT,rules infraction,1-0",
            "STALEMATE,COMPLETED,DRAW,normal,1/2-1/2",
            ",CANCELLED,,abandoned,*",
            ",PAUSED,,unterminated,*",
            ",WAITING,,unterminated,*"
    })
    void allTerminationKindsAreStandardAndParseable(String reason, String status, String result,
                                                    String termination, String marker) throws Exception {
        Game game = game();
        game.status = Game.GameStatus.valueOf(status);
        game.result = result == null ? null : Game.GameResult.valueOf(result);
        game.resultReason = reason == null ? null : Game.ResultReason.valueOf(reason);
        String pgn = exports.gamePgn(game, false);
        assertTrue(pgn.contains("[Termination \"" + termination + "\"]"));
        if (reason != null) assertTrue(pgn.contains("[TerminationDetails \"" + reason.toLowerCase() + "\"]"));
        if ("CANCELLED".equals(status)) assertTrue(pgn.contains("[TerminationDetails \"cancelled\"]"));
        assertTrue(pgn.endsWith(marker + "\n"));
        assertEquals(1, parse(pgn).getGames().size());
    }

    @Test
    void unfinishedDefaultsUnknownPlayersAndDateAndUnlimitedTimeAreExplicit() {
        Game game = game();
        game.whitePlayerName = null;
        game.createdAt = null;
        game.bracketRound = null;
        String pgn = exports.gamePgn(game, false);
        assertTrue(pgn.contains("[White \"?\"]"));
        assertTrue(pgn.contains("[Date \"????.??.??\"]"));
        assertTrue(pgn.contains("[TimeControl \"-\"]"));
        assertTrue(pgn.contains("[Result \"*\"]"));
        assertFalse(pgn.contains("[%eval"));
    }

    @Test
    void lastWordsWithoutAMoveAndSignedMateZeroArePreserved() {
        Game game = game("f3", "e5", "g4", "Qh4#");
        game.status = Game.GameStatus.COMPLETED;
        game.result = Game.GameResult.BLACK_WINS;
        game.resultReason = Game.ResultReason.CHECKMATE;
        game.moves.getLast().evaluationMate = 0;
        ChatMessage lastWords = new ChatMessage();
        lastWords.moveNumber = 3;
        lastWords.senderColor = "WHITE";
        lastWords.message = "Well {played}";
        game.chatMessages.add(lastWords);
        String pgn = exports.gamePgn(game, true);
        assertTrue(pgn.contains("{[%eval #-0]}"));
        assertTrue(pgn.contains("{Chat 3 WHITE: Well (played)}"));
        var restored = parse(pgn).getGames().getFirst();
        assertEquals(game.currentFen, restored.getHalfMoves().getFen());
        Game whiteMate = game("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#");
        whiteMate.moves.getLast().evaluationMate = 0;
        assertTrue(exports.gamePgn(whiteMate, false).contains("{[%eval #0]}"));
    }

    @Test
    void tournamentConcatenatesFinishedAndUnfinishedGamesAndExcludesByes() throws Exception {
        Game finished = game("e4", "e5");
        finished.status = Game.GameStatus.COMPLETED;
        finished.result = Game.GameResult.WHITE_WINS;
        Game waiting = game();
        waiting.tournament = finished.tournament;
        waiting.createdAt = finished.createdAt.plusSeconds(1);
        Game bye = game();
        bye.blackPlayerName = "BYE";
        bye.tournament = finished.tournament;
        finished.tournament.games.addAll(List.of(waiting, bye, finished));
        PgnHolder parsed = parse(exports.tournamentPgn(finished.tournament, false));
        assertEquals(2, parsed.getGames().size());
        assertEquals(2, parsed.getGames().getFirst().getHalfMoves().size());
        assertTrue(parsed.getGames().getLast().getHalfMoves().isEmpty());
        assertEquals("", exports.tournamentPgn(new Tournament(), false));
    }

    @Test
    void canonicalMoveOrderAndBookQualityRemainConsistentAcrossExports() throws Exception {
        Game game = game("e4", "e5");
        game.moves.getFirst().isBook = true;
        game.moves.getFirst().evaluationCp = 0;
        game.moves.getLast().evaluationCp = 400;
        Move duplicate = new Move();
        duplicate.game = game;
        duplicate.moveNumber = 1;
        duplicate.color = "WHITE";
        duplicate.san = "d4";
        duplicate.createdAt = game.moves.getFirst().createdAt.plusSeconds(1);
        game.moves.add(duplicate);
        Collections.reverse(game.moves);
        game.tournament.games.add(game);
        assertEquals(2, parse(exports.gamePgn(game, false)).getGames().getFirst().getHalfMoves().size());
        String csv = exports.tournamentCsv(game.tournament);
        assertTrue(csv.contains("\"2\",\"0.12345678\",\"\",\"400\",\"0\",\"1\""), csv);
    }

    @Test
    void csvQuotesCommasQuotesAndCrLfAndRetainsUnicode() {
        Game game = game();
        game.whitePlayerName = "Alpha, \"Ω\"\r\nsecond line";
        game.blackPlayerName = "=SUM(1,2)";
        game.tournament.games.add(game);
        String csv = exports.tournamentCsv(game.tournament);
        assertTrue(csv.contains("\"Alpha, \"\"Ω\"\"\r\nsecond line\""));
        assertTrue(csv.contains("\"'=SUM(1,2)\""));
        assertTrue(csv.endsWith("\r\n"));
        assertEquals(1, exports.tournamentCsv(new Tournament()).lines().count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+SUM(1)", "-1", "@formula", "\t=1", "\r\n+1", "  @x", "\u00a0-x"})
    void formulaInjectionIsNeutralized(String formula) {
        assertEquals("'" + formula, ExportService.csvCell(formula));
    }

    @Test
    void numericCellsRemainNumericAndFilenamesUseOnlyUuid() {
        assertEquals("-12.50", ExportService.csvCell(new BigDecimal("-12.50")));
        assertEquals("", ExportService.csvCell(null));
        UUID id = UUID.randomUUID();
        assertEquals("tournament-" + id + ".pgn", ExportService.filename("tournament", id, "pgn"));
    }

    private static PgnHolder parse(String pgn) {
        PgnHolder parsed = new PgnHolder();
        parsed.loadPgn(pgn);
        return parsed;
    }

    static Game game(String... sans) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.tournament = new Tournament();
        game.tournament.name = "Test Arena";
        game.whitePlayerName = "Alpha";
        game.blackPlayerName = "Beta";
        game.whiteModelId = "test/alpha";
        game.blackModelId = "test/beta";
        game.bracketRound = "Final";
        game.seriesGameNumber = 2;
        game.totalCostUsd = new BigDecimal("0.12345678");
        Board board = new Board();
        ChessService chess = new ChessService();
        for (int i = 0; i < sans.length; i++) {
            Move move = new Move();
            move.id = UUID.randomUUID();
            move.game = game;
            move.moveNumber = i / 2 + 1;
            move.color = i % 2 == 0 ? "WHITE" : "BLACK";
            move.san = sans[i];
            assertTrue(chess.validateAndApply(board, move.san).valid());
            move.fen = board.getFen();
            game.moves.add(move);
        }
        game.currentFen = board.getFen();
        return game;
    }
}
