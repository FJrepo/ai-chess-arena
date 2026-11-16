package dev.aichessarena.service;

/**
 * Reliability score of a model, 0–100.
 *
 * <p>{@code raw = 0.5 * firstTryLegal + 0.3 * (1 - forfeitRate) + 0.2 * retryScore}, each part scaled 0–100.
 * Low samples are pulled toward {@value #NEUTRAL_SCORE}: the weight of the raw score grows linearly with the
 * number of moves up to {@value #FULL_WEIGHT_MOVES}. Latency is deliberately not scored, so slow reasoning models
 * are not penalised for thinking.
 */
final class AnalyticsReliabilityScorer {

    static final double NEUTRAL_SCORE = 60.0;
    static final double FULL_WEIGHT_MOVES = 60.0;
    static final long MIN_GAMES_FOR_BAND = 3;

    private static final double FIRST_TRY_LEGAL_WEIGHT = 0.5;
    private static final double NO_FORFEIT_WEIGHT = 0.3;
    private static final double RETRY_WEIGHT = 0.2;

    /**
     * Scores one model.
     *
     * @param gamesPlayed           finished games (no byes, no unfinished games)
     * @param movesSampled          moves the model played
     * @param firstTryLegalRate     share of turns answered with a legal move on the first attempt, 0–1
     * @param forfeitRate           share of finished games the model forfeited, 0–1
     * @param averageRetriesPerMove mean retry count of the model's moves
     */
    ReliabilityScore score(
            long gamesPlayed,
            long movesSampled,
            double firstTryLegalRate,
            double forfeitRate,
            double averageRetriesPerMove
    ) {
        double firstTryLegalScore = clamp(firstTryLegalRate * 100.0);
        double forfeitScore = clamp((1.0 - forfeitRate) * 100.0);
        double retryScore = clamp(100.0 - (averageRetriesPerMove * 50.0));
        double rawScore = (firstTryLegalScore * FIRST_TRY_LEGAL_WEIGHT)
                + (forfeitScore * NO_FORFEIT_WEIGHT)
                + (retryScore * RETRY_WEIGHT);
        double sampleWeight = Math.min(1.0, Math.max(0, movesSampled) / FULL_WEIGHT_MOVES);
        double finalScore = NEUTRAL_SCORE + (rawScore - NEUTRAL_SCORE) * sampleWeight;
        boolean insufficientData = gamesPlayed < MIN_GAMES_FOR_BAND;

        return new ReliabilityScore(
                firstTryLegalScore,
                forfeitScore,
                retryScore,
                rawScore,
                sampleWeight,
                finalScore,
                toBand(finalScore, insufficientData),
                insufficientData
        );
    }

    /**
     * Share of the model's turns that got a legal move on the first attempt: moves with no retry, divided by all
     * moves plus the turns that ended in an invalid-move forfeit. Works without stored attempts; cut-off answers
     * never count because the attempt counter restarts when a paused game resumes.
     */
    static double firstTryLegalRate(long firstTryMoves, long movesSampled, long invalidMoveForfeits) {
        long turns = movesSampled + invalidMoveForfeits;
        return turns <= 0 ? 0.0 : (double) firstTryMoves / (double) turns;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private String toBand(double score, boolean insufficientData) {
        if (insufficientData) {
            return "Insufficient data";
        }
        if (score >= 85) {
            return "A";
        }
        if (score >= 70) {
            return "B";
        }
        if (score >= 55) {
            return "C";
        }
        return "D";
    }

    record ReliabilityScore(
            double firstTryLegalScore,
            double forfeitScore,
            double retryScore,
            double rawScore,
            double sampleWeight,
            double finalScore,
            String band,
            boolean insufficientData
    ) {
    }
}
