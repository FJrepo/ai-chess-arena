package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Move;
import dev.aichessarena.service.MoveQualityClassifier.Exclusion;
import dev.aichessarena.service.MoveQualityClassifier.GameSummary;
import dev.aichessarena.service.MoveQualityClassifier.Judgement;
import dev.aichessarena.service.MoveQualityClassifier.MoveQuality;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveQualityClassifierTest {

    private static final double EPSILON = 1e-4;
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
    /** White to move, in check from the rook on a1; Kh2 is the only legal move. */
    private static final String ONLY_KH2 = "7k/8/8/8/8/8/5PP1/r5K1 w - - 0 1";

    @Test
    void winningChancesFollowTheLogisticModelAndAreCappedAtAThousandCentipawns() {
        assertEquals(0.0, MoveQualityClassifier.winningChances(0), EPSILON);
        assertEquals(0.4743, MoveQualityClassifier.winningChances(280), EPSILON);
        assertEquals(-0.4743, MoveQualityClassifier.winningChances(-280), EPSILON);
        assertEquals(MoveQualityClassifier.winningChances(1000), MoveQualityClassifier.winningChances(5000), EPSILON);
        assertEquals(-MoveQualityClassifier.winningChances(1000), MoveQualityClassifier.winningChances(-5000), EPSILON);
    }

    @Test
    void thresholdsSplitInaccuracyMistakeAndBlunder() {
        assertNull(MoveQualityClassifier.judgement(0.0999));
        assertEquals(Judgement.INACCURACY, MoveQualityClassifier.judgement(0.1));
        assertEquals(Judgement.INACCURACY, MoveQualityClassifier.judgement(0.1999));
        assertEquals(Judgement.MISTAKE, MoveQualityClassifier.judgement(0.2));
        assertEquals(Judgement.BLUNDER, MoveQualityClassifier.judgement(0.3));
        assertEquals(Judgement.BLUNDER, MoveQualityClassifier.judgement(2.0));
        assertNull(MoveQualityClassifier.judgement(-0.5), "an improvement is not an error");
    }

    @Test
    void blackThrowingAWinningPositionIsABlunder() {
        // Real game: Black stood at -2.80 (White's view), played 35...Bb3?? and the evaluation went to +3.38.
        MoveQuality quality = classify(cp(-280, "WHITE"), cp(338, "BLACK"), "BLACK");

        assertEquals(Judgement.BLUNDER, quality.judgement());
        assertEquals(618, quality.centipawnLoss());
        assertEquals(0.7372, quality.winChanceBefore(), EPSILON);
        assertEquals(0.2236, quality.winChanceAfter(), EPSILON);
        assertNull(quality.excluded());
    }

    @Test
    void theSameSwingIsJudgedFromTheMoversPointOfView() {
        // +0.50 to -0.50 (White's view) is a loss for White but a gain for Black.
        MoveQuality whiteMove = classify(cp(50, "BLACK"), cp(-50, "WHITE"), "WHITE");
        MoveQuality blackMove = classify(cp(50, "WHITE"), cp(-50, "BLACK"), "BLACK");

        assertEquals(100, whiteMove.centipawnLoss());
        assertEquals(Judgement.INACCURACY, whiteMove.judgement(), "drop of about 0.18 in winning chances");
        assertEquals(0, blackMove.centipawnLoss());
        assertNull(blackMove.judgement());
        assertTrue(blackMove.winChanceAfter() > blackMove.winChanceBefore());
    }

    @Test
    void mistakeAndInaccuracyBands() {
        // White at +1.00; drops in winning chances on the -1..+1 scale.
        // -> -1.00: about 0.36.
        assertEquals(Judgement.BLUNDER, classify(cp(100, "BLACK"), cp(-100, "WHITE"), "WHITE").judgement());
        // -> -0.20: about 0.22.
        assertEquals(Judgement.MISTAKE, classify(cp(100, "BLACK"), cp(-20, "WHITE"), "WHITE").judgement());
        // -> +0.40: about 0.11.
        assertEquals(Judgement.INACCURACY, classify(cp(100, "BLACK"), cp(40, "WHITE"), "WHITE").judgement());
        // -> +0.70: about 0.05, fine.
        assertNull(classify(cp(100, "BLACK"), cp(70, "WHITE"), "WHITE").judgement());
    }

    @Test
    void largeEvaluationsBarelyMatter() {
        // +9.00 -> +7.00 is still completely winning: no error, though centipawns were lost.
        MoveQuality quality = classify(cp(900, "BLACK"), cp(700, "WHITE"), "WHITE");

        assertNull(quality.judgement());
        assertEquals(200, quality.centipawnLoss());
        // Beyond +10.00 nothing changes.
        assertEquals(0, classify(cp(2500, "BLACK"), cp(1200, "WHITE"), "WHITE").centipawnLoss());
    }

    @Test
    void missingAMateIsABlunder() {
        // White had mate in 3 and played a move after which it is only +1.50.
        MoveQuality quality = classify(mate(3, "BLACK"), cp(150, "WHITE"), "WHITE");

        assertEquals(Judgement.BLUNDER, quality.judgement());
        assertEquals(850, quality.centipawnLoss());
        assertEquals(1.0, quality.winChanceBefore(), EPSILON);
    }

    @Test
    void findingAMateIsNoError() {
        MoveQuality quality = classify(cp(200, "BLACK"), mate(2, "WHITE"), "WHITE");

        assertNull(quality.judgement());
        assertEquals(0, quality.centipawnLoss());
        assertEquals(1.0, quality.winChanceAfter(), EPSILON);
    }

    @Test
    void walkingIntoAMateIsABlunderAndTheLossIsCapped() {
        // Black was slightly better (-0.50) and allowed White a mate in 3.
        MoveQuality quality = classify(cp(-50, "WHITE"), mate(3, "BLACK"), "BLACK");

        assertEquals(Judgement.BLUNDER, quality.judgement());
        assertEquals(1000, quality.centipawnLoss());
        assertEquals(0.0, quality.winChanceAfter(), EPSILON);
    }

    @Test
    void beingMatedAnywayIsNoError() {
        // White already mates; Black's reply cannot change that.
        MoveQuality quality = classify(mate(2, "WHITE"), mate(1, "BLACK"), "BLACK");

        assertNull(quality.judgement());
        assertEquals(0, quality.centipawnLoss());
        assertEquals(0.0, quality.winChanceBefore(), EPSILON);
    }

    @Test
    void blackMatingIsMateForBlack() {
        MoveQuality quality = classify(cp(-300, "WHITE"), mate(-4, "BLACK"), "BLACK");

        assertNull(quality.judgement());
        assertEquals(1.0, quality.winChanceAfter(), EPSILON);
    }

    @Test
    void mateZeroIsAWinForTheSideThatDeliveredIt() {
        MoveQuality whiteMates = classify(mate(1, "BLACK"), mate(0, "WHITE"), "WHITE");
        MoveQuality blackMates = classify(mate(-1, "WHITE"), mate(0, "BLACK"), "BLACK");

        assertEquals(1.0, whiteMates.winChanceAfter(), EPSILON);
        assertNull(whiteMates.judgement());
        assertEquals(1.0, blackMates.winChanceAfter(), EPSILON);
        assertNull(blackMates.judgement());
    }

    @Test
    void movesWithoutBothEvaluationsAreNotJudged() {
        List<Move> moves = List.of(
                move(1, "WHITE", "e4", AFTER_E4, null, null),
                move(1, "BLACK", "e5", "fen", -20, null),
                move(2, "WHITE", "Nf3", "fen", 30, null),
                move(2, "BLACK", "Nc6", "fen", null, null)
        );

        List<MoveQuality> qualities = MoveQualityClassifier.analyze(moves);

        assertNull(qualities.get(0), "no evaluation after the move");
        assertNull(qualities.get(1), "no evaluation before the move");
        assertNotNull(qualities.get(2));
        assertNull(qualities.get(3));
        GameSummary summary = MoveQualityClassifier.summarize(moves, qualities);
        assertEquals(1, summary.white().analyzedMoves());
        assertEquals(0, summary.black().analyzedMoves());
        assertNull(summary.black().averageCentipawnLoss());
    }

    @Test
    void theFirstMoveIsComparedWithAnEqualStartPosition() {
        MoveQuality goodStart = MoveQualityClassifier.analyze(List.of(move(1, "WHITE", "e4", AFTER_E4, 30, null)))
                .get(0);
        MoveQuality badStart = MoveQualityClassifier.analyze(List.of(move(1, "WHITE", "f3", "fen", -90, null)))
                .get(0);

        assertEquals(0, goodStart.centipawnLoss());
        assertEquals(0.5, goodStart.winChanceBefore(), EPSILON);
        assertEquals(90, badStart.centipawnLoss());
        assertEquals(Judgement.INACCURACY, badStart.judgement());
    }

    @Test
    void overridesAreMarkedAndNotJudged() {
        Move override = move(1, "BLACK", "Qh4", "fen", 900, null);
        override.isOverride = true;
        List<Move> moves = List.of(move(1, "WHITE", "e4", AFTER_E4, 30, null), override);

        List<MoveQuality> qualities = MoveQualityClassifier.analyze(moves);

        MoveQuality quality = qualities.get(1);
        assertEquals(Exclusion.OVERRIDE, quality.excluded());
        assertNull(quality.judgement());
        assertNull(quality.centipawnLoss());
        assertTrue(quality.winChanceAfter() < quality.winChanceBefore());
        assertFalse(MoveQualityClassifier.counts(quality));
        assertEquals(0, MoveQualityClassifier.summarize(moves, qualities).black().analyzedMoves());
    }

    @Test
    void forcedMovesAreMarkedAndNotJudged() {
        assertTrue(MoveQualityClassifier.isForced(ONLY_KH2));
        assertFalse(MoveQualityClassifier.isForced(MoveQualityClassifier.START_FEN));
        assertFalse(MoveQualityClassifier.isForced(null));
        assertFalse(MoveQualityClassifier.isForced("not a fen"));

        List<Move> moves = List.of(
                move(30, "BLACK", "Ra1+", ONLY_KH2, -50, null),
                move(31, "WHITE", "Kh2", "fen", -400, null)
        );
        MoveQuality quality = MoveQualityClassifier.analyze(moves).get(1);

        assertEquals(Exclusion.FORCED, quality.excluded());
        assertNull(quality.judgement());
        assertNull(quality.centipawnLoss());
    }

    @Test
    void summaryCountsJudgementsAndAveragesCentipawnLossPerSide() {
        List<Move> moves = List.of(
                move(1, "WHITE", "e4", AFTER_E4, 30, null), // loss 0
                move(1, "BLACK", "f6", "fen", 70, null), // loss 40, fine
                move(2, "WHITE", "d4", "fen", 60, null), // loss 10, fine
                move(2, "BLACK", "g5", "fen", null, 1), // allows mate: blunder, loss 940
                move(3, "WHITE", "Nc3", "fen", 250, null), // misses the mate: blunder, loss 750
                move(3, "BLACK", "Nh6", "fen", 450, null) // loss 200, mistake
        );

        List<MoveQuality> qualities = MoveQualityClassifier.analyze(moves);
        GameSummary summary = MoveQualityClassifier.summarize(moves, qualities);

        assertEquals(Judgement.MISTAKE, qualities.get(5).judgement());
        assertEquals(3, summary.white().analyzedMoves());
        assertEquals(253, summary.white().averageCentipawnLoss(), "(0 + 10 + 750) / 3");
        assertEquals(0, summary.white().inaccuracies());
        assertEquals(1, summary.white().blunders());
        assertEquals(0, summary.white().mistakes());
        assertEquals(3, summary.black().analyzedMoves());
        assertEquals(393, summary.black().averageCentipawnLoss(), "(40 + 940 + 200) / 3");
        assertEquals(1, summary.black().blunders());
        assertEquals(1, summary.black().mistakes());
        assertEquals(0, summary.black().inaccuracies());
    }

    @Test
    void playOrderPutsWhiteBeforeBlackWithinAMoveNumber() {
        Move blackOne = move(1, "BLACK", "e5", "fen", 0, null);
        Move whiteTwo = move(2, "WHITE", "Nf3", "fen", 0, null);
        Move whiteOne = move(1, "WHITE", "e4", "fen", 0, null);
        List<Move> moves = new ArrayList<>(Arrays.asList(whiteTwo, blackOne, whiteOne));

        moves.sort(MoveQualityClassifier.PLAY_ORDER);

        assertEquals(List.of(whiteOne, blackOne, whiteTwo), moves);
    }

    @Test
    void legacyDuplicatesCannotCreateMissedMatesOrHideTheCanonicalPreviousEvaluation() {
        Move before = move(1, "BLACK", "e5", "fen", null, 2);
        Move duplicateBefore = move(1, "BLACK", "e5", "fen", 0, null);
        Move missed = move(2, "WHITE", "Nf3", "fen", 0, null);
        Move duplicateMissed = move(2, "WHITE", "Nf3", "fen", null, 3);
        Move anotherDuplicate = move(2, "WHITE", "Nf3", "fen", 0, null);
        List<Move> moves = List.of(before, duplicateBefore, missed, duplicateMissed, anotherDuplicate);
        for (int i = 0; i < moves.size(); i++) {
            moves.get(i).createdAt = LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(i);
        }

        List<MoveQuality> qualities = MoveQualityClassifier.analyze(moves);

        assertNull(qualities.get(1));
        assertTrue(qualities.get(2).missedMate());
        assertEquals(2, qualities.get(2).missedMateIn());
        assertNull(qualities.get(3));
        assertNull(qualities.get(4));
        assertEquals(1, MoveQualityClassifier.summarize(moves, qualities).white().analyzedMoves());
    }

    @Test
    void missedMatesAreMarkedEvenWhenThePositionStillHasHighWinningChances() {
        for (String color : List.of("WHITE", "BLACK")) {
            int sign = "WHITE".equals(color) ? 1 : -1;
            MoveQuality lost = classify(mate(sign * 2, "WHITE"), cp(sign * 1000, color), color);
            assertTrue(lost.missedMate());
            assertEquals(2, lost.missedMateIn());
            assertNull(lost.judgement(), "a missed mate is independent of the ordinary quality thresholds");
            assertTrue(classify(mate(sign * 2, "WHITE"), cp(0, color), color).missedMate());
            assertTrue(classify(mate(sign * 2, "WHITE"), mate(-sign * 3, color), color).missedMate());
        }
    }

    @Test
    void longerMatesAndDeliveredMateZeroAreKeptForBothColors() {
        for (String color : List.of("WHITE", "BLACK")) {
            int sign = "WHITE".equals(color) ? 1 : -1;
            MoveQuality longer = classify(mate(sign * 2, "WHITE"), mate(sign * 8, color), color);
            assertFalse(longer.missedMate());
            assertNull(longer.missedMateIn());
            assertFalse(classify(mate(sign, "WHITE"), mate(0, color), color).missedMate());
            assertFalse(classify(cp(sign * 1000, "WHITE"), cp(0, color), color).missedMate());
            assertFalse(classify(mate(-sign * 2, "WHITE"), cp(0, color), color).missedMate());
        }
    }

    @Test
    void overridesForcedAndUnevaluatedMovesDoNotGetMissedMateMarkers() {
        Move before = move(30, "BLACK", "Ra1+", ONLY_KH2, null, 2);
        Move after = move(31, "WHITE", "Kh2", "fen", 500, null);
        MoveQuality forced = MoveQualityClassifier.quality(after, before);
        assertEquals(Exclusion.FORCED, forced.excluded());
        assertFalse(forced.missedMate());
        assertNull(forced.missedMateIn());
        before.fen = AFTER_E4;
        after.isOverride = true;
        assertFalse(MoveQualityClassifier.quality(after, before).missedMate());
        after.isOverride = false;
        after.evaluationCp = null;
        assertNull(MoveQualityClassifier.quality(after, before));
        after.evaluationCp = 500;
        before.evaluationMate = null;
        assertNull(MoveQualityClassifier.quality(after, before));
    }

    private static MoveQuality classify(MoveQualityClassifier.Score before, MoveQualityClassifier.Score after,
                                        String mover) {
        return MoveQualityClassifier.classify(before, after, mover);
    }

    private static MoveQualityClassifier.Score cp(int cp, String movedColor) {
        return MoveQualityClassifier.score(cp, null, movedColor);
    }

    private static MoveQualityClassifier.Score mate(int mate, String movedColor) {
        return MoveQualityClassifier.score(null, mate, movedColor);
    }

    private static Move move(int number, String color, String san, String fen, Integer cp, Integer mate) {
        Move move = new Move();
        move.moveNumber = number;
        move.color = color;
        move.san = san;
        move.fen = fen;
        move.evaluationCp = cp;
        move.evaluationMate = mate;
        move.createdAt = LocalDateTime.of(2026, 1, 1, 12, 0).plusMinutes(number);
        return move;
    }
}
