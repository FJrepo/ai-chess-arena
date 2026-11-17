package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
                + "[Date \"2026.03.06\"]\n[Round \"1\"]\n[White \"Alpha\"]\n"
                + "[Black \"Beta\"]\n[Result \"1/2-1/2\"]\n"));
        assertTrue(pgn.contains("[WhiteModel \"test/alpha\"]"));
        assertTrue(pgn.contains("[BlackModel \"test/beta\"]"));
        assertTrue(pgn.contains("[TimeControl \"1/120\"]"));
        assertTrue(pgn.contains("[Opening \"Open Game\"]\n[ECO \"C20\"]"));
        assertTrue(pgn.contains("1. e4 {book}"));
        assertTrue(pgn.contains("{[%eval 0.35]}"));
        assertTrue(pgn.contains("{[%eval #-3]}"));
        PgnHolder parsed = parse(pgn);
        assertEquals(1, parsed.getGames().size());
        var restored = parsed.getGames().getFirst();
        assertEquals(1, restored.getRound().getNumber());
        assertEquals("Final", restored.getProperty().get("BracketRound"));
        assertEquals("2", restored.getProperty().get("SeriesGame"));
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
    @CsvSource({"Quarterfinal,1", "Semifinal,2", "Final,3"})
    void bracketRoundsAndSeriesMetadataRoundTrip(String label, int expected) {
        Game game = game("e4", "e5");
        for (int i = 0; i < 8; i++) game.tournament.participants.add(new TournamentParticipant());
        game.bracketRound = label;
        game.seriesGameNumber = 2;
        game.seriesBestOf = 3;
        String pgn = exports.gamePgn(game, false);
        assertTrue(pgn.contains("[Round \"" + expected + "\"]"));
        var restored = parse(pgn).getGames().getFirst();
        assertEquals(expected, restored.getRound().getNumber());
        assertEquals(label, restored.getProperty().get("BracketRound"));
        assertEquals("2", restored.getProperty().get("SeriesGame"));
    }

    @Test
    void storedBracketSizesSupplyRoundNumbersWhenParticipantsAreUnavailable() {
        Game first = game("e4");
        first.bracketRound = "Round of 16";
        first.seriesGameNumber = 1;
        Game last = game("d4");
        last.tournament = first.tournament;
        last.bracketRound = "Final";
        first.tournament.games.addAll(List.of(first, last));
        var restored = parse(exports.tournamentPgn(first.tournament, false)).getGames();
        assertEquals(List.of(1, 4), restored.stream().map(g -> g.getRound().getNumber()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tiebreak 1", "Round of invalid", "Round of 3", ""})
    void unknownRoundLabelsRemainReadableWithoutInvalidRoundComponents(String label) {
        Game game = game();
        game.bracketRound = label;
        String pgn = exports.gamePgn(game, false);
        assertTrue(pgn.contains("[Round \"?\"]"));
        if (!label.isBlank()) assertEquals(label, parse(pgn).getGames().getFirst().getProperty().get("BracketRound"));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 120, 3600})
    void perMoveTimeControlUsesRepeatingMovePeriods(int seconds) {
        Game game = game();
        game.tournament.moveTimeoutSeconds = seconds;
        assertTrue(exports.gamePgn(game, false).contains("[TimeControl \"1/" + seconds + "\"]"));
    }

    @Test
    void chatCannotCreateEvaluationOrClockDirectivesWithOrWithoutStoredEvaluations() {
        Game game = game("e4", "e5");
        ChatMessage chat = new ChatMessage();
        chat.moveNumber = 1;
        chat.senderColor = "WHITE";
        chat.message = "[%eval #99] [%clk 0:00:01] {words}\u0000\n[%eval -9.99]";
        game.chatMessages.add(chat);
        // python-chess scans comment text for these exact directive introducers regardless of Chat:.
        Pattern directives = Pattern.compile("\\[%\\s*(?:eval|clk)\\s+[^]]*]");
        String pgn = exports.gamePgn(game, true);
        assertFalse(directives.matcher(pgn).find(), pgn);
        assertTrue(pgn.contains("[ %eval #99] [ %clk 0:00:01] (words)"));
        assertFalse(pgn.contains("\u0000"));
        var restored = parse(pgn).getGames().getFirst();
        assertEquals(game.currentFen, restored.getHalfMoves().getFen());
        assertFalse(directives.matcher(String.join(" ", restored.getComments().values())).find());
        // Unmatched resignation chat goes through the same sanitizer.
        chat.moveNumber = 2;
        chat.senderColor = "BLACK";
        pgn = exports.gamePgn(game, true);
        assertFalse(directives.matcher(pgn).find());
        assertFalse(directives.matcher(String.join(" ", parse(pgn).getGames().getFirst().getComments().values())).find());
        game.moves.getFirst().evaluationCp = 35;
        pgn = exports.gamePgn(game, true);
        assertEquals(1, directives.matcher(pgn).results().count());
        assertTrue(pgn.contains("{[%eval 0.35]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"word ", "x", "😀", "%"})
    void longChatWrapsInsideCommentsAndRoundTrips(String fragment) {
        Game game = game("e4", "e5");
        ChatMessage chat = new ChatMessage();
        chat.moveNumber = 1;
        chat.senderColor = "WHITE";
        chat.message = fragment.repeat(300 / fragment.length());
        assertEquals(300, chat.message.length());
        game.chatMessages.add(chat);
        String pgn = exports.gamePgn(game, true);
        assertTrue(pgn.lines().allMatch(line -> line.codePointCount(0, line.length()) < 80), pgn);
        assertFalse(pgn.lines().anyMatch(line -> line.startsWith("%")), pgn);
        var restored = parse(pgn).getGames().getFirst();
        assertEquals(2, restored.getHalfMoves().size());
        assertEquals(game.currentFen, restored.getHalfMoves().getFen());
        String comments = String.join(" ", restored.getComments().values()).replaceAll("\\s+", "");
        assertTrue(comments.contains(chat.message.replaceAll("\\s+", "")), comments);
        chat.moveNumber = 2;
        chat.senderColor = "BLACK";
        pgn = exports.gamePgn(game, true);
        assertTrue(pgn.lines().allMatch(line -> line.codePointCount(0, line.length()) < 80), pgn);
        assertEquals(game.currentFen, parse(pgn).getGames().getFirst().getHalfMoves().getFen());
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
