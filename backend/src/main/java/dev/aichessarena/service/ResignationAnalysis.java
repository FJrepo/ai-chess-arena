package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import java.util.List;

/**
 * How a resignation looked on the board, derived on read from the stored evaluations with the win-probability model
 * of {@link MoveQualityClassifier}; nothing is stored.
 *
 * <p>A side resigns instead of playing its move, so the position it gave up is the one after the last move played
 * (normally the opponent's), which Stockfish evaluated in the background before or while the resigning model was
 * thinking. Without that evaluation (Stockfish unavailable or not done yet) the winning chance is unknown.
 */
public final class ResignationAnalysis {

    /**
     * A resignation with at least this winning chance (0–1) for the resigning side counts as "resigned while not
     * losing": roughly level or better (35% is about −0.85 pawns on the Lichess curve).
     */
    public static final double NOT_LOSING_WIN_CHANCE = 0.35;

    private ResignationAnalysis() {
    }

    /** The colour that resigned ("WHITE" or "BLACK"), or null when the game did not end by resignation. */
    public static String resigningColor(Game game) {
        if (game == null || game.resultReason != Game.ResultReason.RESIGNATION || game.result == null) {
            return null;
        }
        return switch (game.result) {
            case WHITE_WINS -> "BLACK";
            case BLACK_WINS -> "WHITE";
            default -> null;
        };
    }

    /**
     * The resigning side's winning chance (0–1) in the position it resigned in: the evaluation of the last move in
     * {@code movesInPlayOrder} seen from {@code resigningColor}; the start position counts as equal (0.5). Null when
     * that move has no evaluation.
     */
    public static Double winChanceAtResignation(List<Move> movesInPlayOrder, String resigningColor) {
        MoveQualityClassifier.Score score;
        if (movesInPlayOrder == null || movesInPlayOrder.isEmpty()) {
            score = MoveQualityClassifier.START;
        } else {
            Move last = movesInPlayOrder.getLast();
            score = MoveQualityClassifier.score(last.evaluationCp, last.evaluationMate, last.color);
        }
        if (score == null) {
            return null;
        }
        int sign = "BLACK".equals(resigningColor) ? -1 : 1;
        return (1 + sign * score.winningChances()) / 2;
    }

    /** Whether a winning chance at resignation counts as "not losing"; an unknown one never does. */
    public static boolean notLosing(Double winChance) {
        return winChance != null && winChance >= NOT_LOSING_WIN_CHANCE;
    }

    /** The move number of the ply the resigning side did not play, from the last move played (1 without moves). */
    public static int resignationMoveNumber(List<Move> movesInPlayOrder, String resigningColor) {
        if (movesInPlayOrder == null || movesInPlayOrder.isEmpty()) {
            return 1;
        }
        Move last = movesInPlayOrder.getLast();
        return "WHITE".equals(resigningColor) && "BLACK".equals(last.color) ? last.moveNumber + 1 : last.moveNumber;
    }
}
