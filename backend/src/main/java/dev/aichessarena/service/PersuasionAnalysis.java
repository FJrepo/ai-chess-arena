package dev.aichessarena.service;

import dev.aichessarena.dto.PersuasionRulesDto;
import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.Move;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a resignation was "talked out of the game", derived on read from stored evaluations, chat messages and
 * prompt versions; nothing is stored.
 *
 * <p>A resignation counts when both hold:
 * <ul>
 *   <li>the resigning side was <b>not lost</b>: Stockfish's evaluation of the position it gave up (the one after
 *       the last move played, see {@link ResignationAnalysis}) is better than {@value #LOST_THRESHOLD_CP}
 *       centipawns from the resigner's view and shows no forced mate against it. Exactly −2.00 pawns is lost. A
 *       forced mate for the resigner is not lost. Without an evaluation the position is unknown and never counts;
 *       the start position counts as level;</li>
 *   <li>the opponent (the persuader) sent a non-empty chat message on at least one of its last
 *       {@value #MESSAGE_WINDOW_MOVES} model-played moves before the resignation (operator overrides, human and book
 *       moves are skipped).</li>
 * </ul>
 * The persuader's banter level is the tone of its own last model-played move before the resignation, read from that
 * move's prompt version (tone changes apply from the next turn). The evaluation is always the final board ply's.
 */
public final class PersuasionAnalysis {

    /** The resigner was lost at or below this evaluation (centipawns, resigner's view): −2.00 pawns. */
    public static final int LOST_THRESHOLD_CP = -200;

    /** How many of the persuader's latest moves before the resignation are checked for a chat message. */
    public static final int MESSAGE_WINDOW_MOVES = 3;

    /** Fewer evaluated resignations than this is labelled a low sample. */
    public static final int LOW_SAMPLE_RESIGNATIONS = 5;

    public static final PersuasionRulesDto RULES =
            new PersuasionRulesDto(LOST_THRESHOLD_CP, MESSAGE_WINDOW_MOVES, LOW_SAMPLE_RESIGNATIONS);

    /** The resigner's position by Stockfish at the moment of resignation. */
    public enum Position {
        NOT_LOST, LOST, UNKNOWN
    }

    private PersuasionAnalysis() {
    }

    /**
     * The resigning side's position: the evaluation of the last move in {@code movesInPlayOrder} (White's view)
     * from {@code resigningColor}'s view. A mate score wins over a centipawn score.
     */
    public static Position positionAtResignation(List<Move> movesInPlayOrder, String resigningColor) {
        if (movesInPlayOrder == null || movesInPlayOrder.isEmpty()) {
            return Position.NOT_LOST;
        }
        Move last = movesInPlayOrder.getLast();
        MoveQualityClassifier.Score score =
                MoveQualityClassifier.score(last.evaluationCp, last.evaluationMate, last.color);
        if (score == null) {
            return Position.UNKNOWN;
        }
        int sign = "BLACK".equals(resigningColor) ? -1 : 1;
        if (score.mateIn() != null) {
            return sign * score.winningChances() > 0 ? Position.NOT_LOST : Position.LOST;
        }
        return sign * score.cp() > LOST_THRESHOLD_CP ? Position.NOT_LOST : Position.LOST;
    }

    /**
     * The persuader's last {@value #MESSAGE_WINDOW_MOVES} model-played moves in {@code movesInPlayOrder} (canonical
     * plies), in play order. Operator overrides, human moves and book moves take no slot: the model did not choose
     * them, so they say nothing about its messages or its tone.
     */
    public static List<Move> persuaderWindow(List<Move> movesInPlayOrder, String persuaderColor) {
        List<Move> window = new ArrayList<>();
        for (int i = movesInPlayOrder.size() - 1; i >= 0 && window.size() < MESSAGE_WINDOW_MOVES; i--) {
            Move move = movesInPlayOrder.get(i);
            if (persuaderColor.equals(move.color) && isModelPlayed(move)) {
                window.addFirst(move);
            }
        }
        return window;
    }

    private static boolean isModelPlayed(Move move) {
        return !move.isOverride && !move.isBook && move.modelId != null && !move.modelId.isBlank();
    }

    /** Whether the latest model move of {@code persuaderWindow} was played with the Mind games tone. */
    public static boolean usedMindGames(List<Move> persuaderWindow) {
        return !persuaderWindow.isEmpty()
                && PromptService.banterLevelOf(persuaderWindow.getLast().promptVersion) == BanterLevel.MIND_GAMES;
    }
}
