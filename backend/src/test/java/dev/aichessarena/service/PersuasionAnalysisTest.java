package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Move;
import dev.aichessarena.service.PersuasionAnalysis.Position;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PersuasionAnalysisTest {

    @Test
    void theThresholdIsMinusTwoPawnsFromTheResignersView() {
        assertEquals(-200, PersuasionAnalysis.LOST_THRESHOLD_CP);
        assertEquals(3, PersuasionAnalysis.MESSAGE_WINDOW_MOVES);
        assertEquals(5, PersuasionAnalysis.LOW_SAMPLE_RESIGNATIONS);
    }

    /** Stored evaluations are White's view; the resigner's view flips the sign for Black. */
    @ParameterizedTest
    @CsvSource({
            "WHITE, -199, NOT_LOST",
            "WHITE, -200, LOST",
            "WHITE, -201, LOST",
            "WHITE, 0, NOT_LOST",
            "WHITE, 450, NOT_LOST",
            "BLACK, 199, NOT_LOST",
            "BLACK, 200, LOST",
            "BLACK, 201, LOST",
            "BLACK, -1500, NOT_LOST",
    })
    void centipawnBoundaries(String resigner, int whiteViewCp, Position expected) {
        String lastMover = "WHITE".equals(resigner) ? "BLACK" : "WHITE";
        assertEquals(expected, PersuasionAnalysis.positionAtResignation(
                List.of(evaluated(lastMover, whiteViewCp, null)), resigner));
    }

    @Test
    void aForcedMateAgainstTheResignerIsLostAndOneForItIsNot() {
        // White resigns; Black (last mover) has mate in 7: lost even though a centipawn score is absent.
        assertEquals(Position.LOST,
                PersuasionAnalysis.positionAtResignation(List.of(evaluated("BLACK", null, -7)), "WHITE"));
        // Black resigns facing mate for White.
        assertEquals(Position.LOST,
                PersuasionAnalysis.positionAtResignation(List.of(evaluated("WHITE", null, 12)), "BLACK"));
        // A mate score wins over a level centipawn score.
        assertEquals(Position.LOST,
                PersuasionAnalysis.positionAtResignation(List.of(evaluated("BLACK", 0, -3)), "WHITE"));
        // The resigner had a forced mate itself: certainly not lost.
        assertEquals(Position.NOT_LOST,
                PersuasionAnalysis.positionAtResignation(List.of(evaluated("BLACK", null, 4)), "WHITE"));
        assertEquals(Position.NOT_LOST,
                PersuasionAnalysis.positionAtResignation(List.of(evaluated("WHITE", null, -2)), "BLACK"));
    }

    @Test
    void withoutAnEvaluationThePositionIsUnknownAndTheStartPositionIsLevel() {
        assertEquals(Position.UNKNOWN,
                PersuasionAnalysis.positionAtResignation(List.of(evaluated("BLACK", null, null)), "WHITE"));
        assertEquals(Position.NOT_LOST, PersuasionAnalysis.positionAtResignation(List.of(), "WHITE"));
        assertEquals(Position.NOT_LOST, PersuasionAnalysis.positionAtResignation(null, "BLACK"));
    }

    @Test
    void onlyTheLastMoveInPlayOrderCounts() {
        List<Move> moves = List.of(evaluated("WHITE", -900, null), evaluated("BLACK", 0, null));
        assertEquals(Position.NOT_LOST, PersuasionAnalysis.positionAtResignation(moves, "WHITE"));
    }

    @Test
    void theMessageWindowIsThePersuadersLastThreeMoves() {
        List<Move> moves = new ArrayList<>();
        for (int number = 1; number <= 6; number++) {
            moves.add(ply(number, "WHITE", null));
            moves.add(ply(number, "BLACK", null));
        }
        List<Move> window = PersuasionAnalysis.persuaderWindow(moves, "BLACK");
        assertEquals(List.of(4, 5, 6), window.stream().map(move -> move.moveNumber).toList());
        assertTrue(window.stream().allMatch(move -> "BLACK".equals(move.color)));
        assertEquals(List.of(1), PersuasionAnalysis.persuaderWindow(moves.subList(0, 2), "WHITE").stream()
                .map(move -> move.moveNumber).toList());
        assertTrue(PersuasionAnalysis.persuaderWindow(List.of(), "WHITE").isEmpty());
    }

    @Test
    void mindGamesIsReadFromThePromptVersionOfThePersuadersLastMove() {
        PromptService prompts = new PromptService();
        String mindGames = prompts.getSystemPromptVersion(ChatMode.OPTIONAL, true, BanterLevel.MIND_GAMES);
        String mandatoryMindGames = prompts.getSystemPromptVersion(ChatMode.MANDATORY, true, BanterLevel.MIND_GAMES);
        String spicy = prompts.getSystemPromptVersion(ChatMode.OPTIONAL, true, BanterLevel.SPICY);
        String plain = prompts.getSystemPromptVersion(ChatMode.OPTIONAL, true, null);

        assertTrue(PersuasionAnalysis.usedMindGames(List.of(ply(10, "WHITE", spicy), ply(11, "WHITE", mindGames))));
        assertTrue(PersuasionAnalysis.usedMindGames(List.of(ply(11, "WHITE", mandatoryMindGames))));
        assertTrue(PersuasionAnalysis.usedMindGames(List.of(ply(11, "WHITE", "v2+custom-instructions"
                + mindGames.substring("v2".length()) + "-no-hints"))),
                "custom instructions and the no-hints suffix keep the tone marker");
        assertFalse(PersuasionAnalysis.usedMindGames(List.of(ply(10, "WHITE", mindGames), ply(11, "WHITE", spicy))),
                "the tone of the latest move applies");
        assertFalse(PersuasionAnalysis.usedMindGames(List.of(ply(11, "WHITE", plain))));
        assertFalse(PersuasionAnalysis.usedMindGames(List.of(ply(11, "WHITE", null))));
        assertFalse(PersuasionAnalysis.usedMindGames(List.of()));
    }

    @Test
    void banterLevelIsParsedFromEveryPromptVersion() {
        PromptService prompts = new PromptService();
        for (BanterLevel level : BanterLevel.values()) {
            for (ChatMode mode : List.of(ChatMode.OPTIONAL, ChatMode.MANDATORY)) {
                assertEquals(level, PromptService.banterLevelOf(prompts.getSystemPromptVersion(mode, true, level)));
                assertEquals(level, PromptService.banterLevelOf(prompts.getSystemPromptVersion(mode, false, level)));
            }
            // Chat OFF never carries a tone.
            assertNull(PromptService.banterLevelOf(prompts.getSystemPromptVersion(ChatMode.OFF, true, level)));
        }
        assertNull(PromptService.banterLevelOf(null));
        assertNull(PromptService.banterLevelOf("human"));
    }

    private static Move evaluated(String color, Integer cp, Integer mate) {
        Move move = ply(10, color, null);
        move.evaluationCp = cp;
        move.evaluationMate = mate;
        return move;
    }

    private static Move ply(int number, String color, String promptVersion) {
        Move move = new Move();
        move.moveNumber = number;
        move.color = color;
        move.promptVersion = promptVersion;
        return move;
    }
}
