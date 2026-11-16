package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import dev.aichessarena.entity.Move;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Judges moves by their stored Stockfish evaluations, with the win-probability model Lichess uses. Pure: derived
 * on read from {@code Move.evaluationCp} / {@code evaluationMate}, nothing is stored.
 *
 * <p>Rules:
 * <ul>
 *   <li>A stored evaluation is White's view of the position after a move. A move is judged by the evaluation
 *       before it (the previous ply's; the start position counts as 0.00) and the one after it, both from the
 *       mover's point of view.</li>
 *   <li>Winning chances (−1…+1): centipawns are capped at ±1000, then {@code 2 / (1 + exp(−0.00368208 · cp)) − 1}.
 *       A mate score is +1 for the side that mates and −1 for the side that gets mated; "mate 0" (checkmate on
 *       the board) is +1 for the side that delivered it.</li>
 *   <li>Judgement on the drop in the mover's winning chances: at least 0.3 is a blunder ({@code ??}), at least
 *       0.2 a mistake ({@code ?}), at least 0.1 an inaccuracy ({@code ?!}). On the 0–100% scale shown to users
 *       that is a drop of 15, 10 and 5 percentage points.</li>
 *   <li>Centipawn loss: each evaluation as centipawns capped at ±1000, a mate as ±1000 for the mating side; the
 *       loss is the mover's drop, clamped to 0…1000. Gains count as 0.</li>
 *   <li>A move without both evaluations (Stockfish unavailable or still running) gets no quality and is left out
 *       of every average. Operator overrides and forced moves (the only legal move) get their winning chances
 *       but no judgement and no centipawn loss, and are left out of the averages too.</li>
 * </ul>
 */
public final class MoveQualityClassifier {

    static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final double WIN_CHANCE_SLOPE = 0.00368208;
    static final int CP_CAP = 1000;
    static final double INACCURACY_DROP = 0.1;
    static final double MISTAKE_DROP = 0.2;
    static final double BLUNDER_DROP = 0.3;

    /** The start position counts as roughly equal. */
    static final Score START = new Score(0, 0.0);

    /** Moves in play order: by move number, White before Black, then by creation time. */
    public static final Comparator<Move> PLAY_ORDER = Comparator
            .comparingInt((Move move) -> move.moveNumber)
            .thenComparingInt(move -> "WHITE".equals(move.color) ? 0 : 1)
            .thenComparing(move -> move.createdAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));

    private MoveQualityClassifier() {
    }

    public enum Judgement {
        INACCURACY, MISTAKE, BLUNDER
    }

    /** Why a move with both evaluations is not judged. */
    public enum Exclusion {
        OVERRIDE, FORCED
    }

    /**
     * One move's quality. Winning chances are the mover's, as a probability (0–1). {@code judgement} is null for a
     * move without a notable error; {@code centipawnLoss} and {@code judgement} are null for an excluded move.
     */
    public record MoveQuality(
            Judgement judgement,
            Integer centipawnLoss,
            double winChanceBefore,
            double winChanceAfter,
            Exclusion excluded
    ) {
    }

    /** Per-side totals over the judged moves (excluded and unevaluated moves do not count). */
    public record SideSummary(int analyzedMoves, long centipawnLossTotal, int inaccuracies, int mistakes, int blunders) {

        static final SideSummary EMPTY = new SideSummary(0, 0, 0, 0, 0);

        /** Average centipawn loss, rounded to whole centipawns; null without judged moves. */
        public Integer averageCentipawnLoss() {
            return analyzedMoves == 0 ? null : (int) Math.round((double) centipawnLossTotal / analyzedMoves);
        }

        SideSummary plus(MoveQuality quality) {
            Judgement judgement = quality.judgement();
            return new SideSummary(
                    analyzedMoves + 1,
                    centipawnLossTotal + quality.centipawnLoss(),
                    inaccuracies + (judgement == Judgement.INACCURACY ? 1 : 0),
                    mistakes + (judgement == Judgement.MISTAKE ? 1 : 0),
                    blunders + (judgement == Judgement.BLUNDER ? 1 : 0)
            );
        }
    }

    public record GameSummary(SideSummary white, SideSummary black) {
    }

    /** An evaluation from White's point of view: capped centipawns and winning chances (−1…+1). */
    record Score(int cp, double winningChances) {
    }

    /**
     * White's view of a stored evaluation of the position after a move by {@code movedColor}; null when there is
     * none. A mate score wins over a centipawn score.
     */
    static Score score(Integer cp, Integer mate, String movedColor) {
        if (mate != null) {
            int sign;
            if (mate == 0) {
                // Checkmate on the board: the side that just moved delivered it.
                sign = "BLACK".equals(movedColor) ? -1 : 1;
            } else {
                sign = mate > 0 ? 1 : -1;
            }
            return new Score(sign * CP_CAP, sign);
        }
        if (cp == null) {
            return null;
        }
        int capped = Math.max(-CP_CAP, Math.min(CP_CAP, cp));
        return new Score(capped, winningChances(capped));
    }

    /** Lichess's logistic model: centipawns to winning chances between −1 and +1. */
    static double winningChances(int cp) {
        int capped = Math.max(-CP_CAP, Math.min(CP_CAP, cp));
        return 2 / (1 + Math.exp(-WIN_CHANCE_SLOPE * capped)) - 1;
    }

    static Judgement judgement(double winningChancesDrop) {
        if (winningChancesDrop >= BLUNDER_DROP) {
            return Judgement.BLUNDER;
        }
        if (winningChancesDrop >= MISTAKE_DROP) {
            return Judgement.MISTAKE;
        }
        if (winningChancesDrop >= INACCURACY_DROP) {
            return Judgement.INACCURACY;
        }
        return null;
    }

    /** Judges one move of {@code moverColor} from the White-view scores before and after it. */
    static MoveQuality classify(Score before, Score after, String moverColor) {
        int sign = "BLACK".equals(moverColor) ? -1 : 1;
        double chancesBefore = sign * before.winningChances();
        double chancesAfter = sign * after.winningChances();
        int loss = Math.max(0, Math.min(CP_CAP, sign * before.cp() - sign * after.cp()));
        return new MoveQuality(
                judgement(chancesBefore - chancesAfter),
                loss,
                probability(chancesBefore),
                probability(chancesAfter),
                null
        );
    }

    /**
     * The quality of every move of a game, in the order given (play order). Entries are null for moves that cannot
     * be judged because an evaluation is missing.
     */
    public static List<MoveQuality> analyze(List<Move> movesInPlayOrder) {
        List<MoveQuality> qualities = new ArrayList<>(movesInPlayOrder.size());
        for (int i = 0; i < movesInPlayOrder.size(); i++) {
            Move move = movesInPlayOrder.get(i);
            Move previous = i > 0 ? movesInPlayOrder.get(i - 1) : null;
            qualities.add(quality(move, previous));
        }
        return qualities;
    }

    /** Quality of {@code move}, given the move played just before it ({@code null} for the first move). */
    static MoveQuality quality(Move move, Move previous) {
        if (move.color == null) {
            return null;
        }
        Score after = score(move.evaluationCp, move.evaluationMate, move.color);
        Score before = previous == null
                ? START
                : score(previous.evaluationCp, previous.evaluationMate, previous.color);
        if (after == null || before == null) {
            return null;
        }
        MoveQuality judged = classify(before, after, move.color);
        if (move.isOverride) {
            return excluded(judged, Exclusion.OVERRIDE);
        }
        if (isForced(previous == null ? START_FEN : previous.fen)) {
            return excluded(judged, Exclusion.FORCED);
        }
        return judged;
    }

    /** Per-side summary of a game; {@code qualities} is parallel to {@code movesInPlayOrder}. */
    public static GameSummary summarize(List<Move> movesInPlayOrder, List<MoveQuality> qualities) {
        SideSummary white = SideSummary.EMPTY;
        SideSummary black = SideSummary.EMPTY;
        for (int i = 0; i < movesInPlayOrder.size(); i++) {
            MoveQuality quality = qualities.get(i);
            if (!counts(quality)) {
                continue;
            }
            if ("WHITE".equals(movesInPlayOrder.get(i).color)) {
                white = white.plus(quality);
            } else {
                black = black.plus(quality);
            }
        }
        return new GameSummary(white, black);
    }

    /** Whether a move's quality counts towards averages and error counts. */
    public static boolean counts(MoveQuality quality) {
        return quality != null && quality.excluded() == null;
    }

    /** Whether the side to move in {@code fen} has exactly one legal move. Unknown positions are not forced. */
    static boolean isForced(String fen) {
        if (fen == null || fen.isBlank()) {
            return false;
        }
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            return MoveGenerator.generateLegalMoves(board).size() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static MoveQuality excluded(MoveQuality judged, Exclusion reason) {
        return new MoveQuality(null, null, judged.winChanceBefore(), judged.winChanceAfter(), reason);
    }

    /** Winning chances (−1…+1) as a probability (0–1). */
    private static double probability(double winningChances) {
        return (1 + winningChances) / 2;
    }
}
