package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WinAdjudicationTest {

    @Test
    void requiresTenPliesAndIncludesTheThreshold() {
        assertNull(WinAdjudication.winner(window(9, 1000)));
        assertNull(WinAdjudication.winner(window(10, 999)));
        assertEquals("WHITE", WinAdjudication.winner(window(10, 1000)));
        assertEquals("BLACK", WinAdjudication.winner(window(10, -1000)));
        assertNull(WinAdjudication.winner(window(10, -999)));
    }

    @Test
    void evaluatesOnlyTheLastTenActualPliesIncludingOverrides() {
        List<Move> moves = window(11, 1230);
        moves.getFirst().evaluationCp = null;
        moves.get(5).isOverride = true;
        assertEquals("WHITE", WinAdjudication.winner(moves));
        moves.get(5).evaluationCp = 999;
        assertNull(WinAdjudication.winner(moves), "an override does not disappear from the window");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterLegacyRowsCannotReplaceAMissingOrNonDecisiveCanonicalEvaluation(boolean tiedTimestamp) {
        List<Move> moves = window(10, 1000);
        Move first = moves.getFirst();
        first.id = new UUID(0, 1);
        first.evaluationCp = null;
        Move duplicate = new Move();
        duplicate.id = new UUID(0, 2);
        duplicate.moveNumber = first.moveNumber;
        duplicate.color = first.color;
        duplicate.createdAt = tiedTimestamp ? first.createdAt : first.createdAt.plusSeconds(1);
        duplicate.evaluationCp = 1500;
        moves.add(0, duplicate);

        assertNull(WinAdjudication.winner(moves));
        first.evaluationCp = 999;
        assertNull(WinAdjudication.winner(moves));
        first.evaluationCp = 1000;
        assertEquals("WHITE", WinAdjudication.winner(moves));
    }

    @Test
    void missingEvaluationsAtAnyPointDisableThisTurnsAdjudication() {
        for (int i = 0; i < 10; i++) {
            List<Move> moves = window(10, 1000);
            moves.get(i).evaluationCp = null;
            assertNull(WinAdjudication.winner(moves));
        }
    }

    @Test
    void mixedWinningSidesAreNotDecisiveForEitherSide() {
        List<Move> moves = window(10, 1000);
        moves.get(3).evaluationCp = -1200;
        assertNull(WinAdjudication.winner(moves));
        moves.get(3).evaluationMate = -2;
        assertNull(WinAdjudication.winner(moves));
    }

    @Test
    void mateSignsAreWhitesViewRegardlessOfWhoseMoveItIs() {
        for (int sign : new int[] {1, -1}) {
            List<Move> moves = window(10, sign * 1000);
            for (int i = 0; i < 10; i++) {
                moves.get(i).evaluationCp = -sign * 2000; // Mate takes precedence.
                moves.get(i).evaluationMate = sign * (i + 1);
            }
            assertEquals(sign > 0 ? "WHITE" : "BLACK", WinAdjudication.winner(moves));
        }
    }

    @Test
    void mateZeroBelongsToTheDeliveringMover() {
        List<Move> moves = window(10, -1000);
        moves.getLast().evaluationMate = 0; // Last mover is Black.
        assertEquals("BLACK", WinAdjudication.winner(moves));
        moves = window(11, 1000);
        moves.getLast().evaluationMate = 0; // Last mover is White.
        assertEquals("WHITE", WinAdjudication.winner(moves));
    }

    @Test
    void optionIsOffByDefaultAndGamesWithAHumanNeverQualify() {
        Game game = new Game();
        game.tournament = new Tournament();
        game.whiteModelId = "test/white";
        game.blackModelId = "test/black";
        assertFalse(WinAdjudication.enabledFor(game));
        game.tournament.adjudicationEnabled = true;
        assertTrue(WinAdjudication.enabledFor(game));
        TournamentParticipant human = new TournamentParticipant();
        human.controlType = TournamentParticipant.ControlType.HUMAN;
        game.whiteParticipant = human;
        assertFalse(WinAdjudication.enabledFor(game));
        game.whiteParticipant = null;
        game.blackParticipant = human;
        assertFalse(WinAdjudication.enabledFor(game));
        game.blackParticipant = null;
        game.blackModelId = null;
        assertFalse(WinAdjudication.enabledFor(game));
        game.tournament = null;
        assertFalse(WinAdjudication.enabledFor(game));
    }

    static List<Move> window(int plies, int cp) {
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < plies; i++) {
            Move move = new Move();
            move.moveNumber = i / 2 + 1;
            move.color = i % 2 == 0 ? "WHITE" : "BLACK";
            move.evaluationCp = cp;
            moves.add(move);
        }
        return moves;
    }
}
