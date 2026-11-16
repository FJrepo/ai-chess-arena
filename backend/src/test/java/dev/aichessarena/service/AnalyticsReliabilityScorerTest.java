package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalyticsReliabilityScorerTest {

    private final AnalyticsReliabilityScorer scorer = new AnalyticsReliabilityScorer();

    @Test
    void scoreWeighsFirstTryLegalityForfeitsAndRetries() {
        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(20, 60, 0.9, 0.1, 0.5);

        assertFalse(score.insufficientData());
        assertEquals(90.0, score.firstTryLegalScore(), 0.0001);
        assertEquals(90.0, score.forfeitScore(), 0.0001);
        assertEquals(75.0, score.retryScore(), 0.0001);
        // 0.5 * 90 + 0.3 * 90 + 0.2 * 75
        assertEquals(87.0, score.rawScore(), 0.0001);
        assertEquals(1.0, score.sampleWeight(), 0.0001);
        assertEquals(87.0, score.finalScore(), 0.0001);
        assertEquals("A", score.band());
    }

    @Test
    void lowMoveSamplesArePulledTowardSixty() {
        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(4, 30, 1.0, 0.0, 0.0);

        assertEquals(100.0, score.rawScore(), 0.0001);
        assertEquals(0.5, score.sampleWeight(), 0.0001);
        assertEquals(80.0, score.finalScore(), 0.0001);
        assertEquals("B", score.band());
    }

    @Test
    void weightIsCappedAtSixtyMoves() {
        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(5, 600, 0.4, 0.5, 2.5);

        assertEquals(1.0, score.sampleWeight(), 0.0001);
        assertEquals(0.0, score.retryScore(), 0.0001);
        // 0.5 * 40 + 0.3 * 50 + 0.2 * 0
        assertEquals(35.0, score.finalScore(), 0.0001);
        assertEquals("D", score.band());
    }

    @Test
    void fewGamesKeepTheInsufficientDataBandEvenWithManyMoves() {
        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(2, 120, 1.0, 0.0, 0.0);

        assertTrue(score.insufficientData());
        assertEquals("Insufficient data", score.band());
        assertEquals(100.0, score.finalScore(), 0.0001);
    }

    @Test
    void noMovesMeansNeutralScore() {
        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(0, 0, 0.0, 1.0, 0.0);

        assertEquals(0.0, score.sampleWeight(), 0.0001);
        assertEquals(60.0, score.finalScore(), 0.0001);
    }

    @Test
    void firstTryLegalRateCountsInvalidMoveForfeitsAsFailedTurns() {
        assertEquals(8.0 / 12.0, AnalyticsReliabilityScorer.firstTryLegalRate(8, 10, 2), 0.0001);
        assertEquals(0.0, AnalyticsReliabilityScorer.firstTryLegalRate(0, 0, 1), 0.0001);
        assertEquals(0.0, AnalyticsReliabilityScorer.firstTryLegalRate(0, 0, 0), 0.0001);
    }
}
