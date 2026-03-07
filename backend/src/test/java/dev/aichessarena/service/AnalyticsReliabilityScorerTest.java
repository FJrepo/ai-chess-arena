package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnalyticsReliabilityScorerTest {

    @Test
    void scoreKeepsLowSampleModelsInInsufficientDataBand() {
        AnalyticsReliabilityScorer scorer = new AnalyticsReliabilityScorer();

        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(2, 1.0, 0.0, 0.0, 1200L);

        assertTrue(score.insufficientData());
        assertEquals("Insufficient data", score.band());
        assertEquals(1.0 / 10.0, score.sampleWeight(), 0.0001);
    }

    @Test
    void scoreCombinesCompletionForfeitRetryAndLatencySignals() {
        AnalyticsReliabilityScorer scorer = new AnalyticsReliabilityScorer();

        AnalyticsReliabilityScorer.ReliabilityScore score = scorer.score(20, 0.9, 0.1, 0.5, 2000L);

        assertFalse(score.insufficientData());
        assertEquals(90.0, score.completionScore(), 0.0001);
        assertEquals(90.0, score.forfeitScore(), 0.0001);
        assertEquals(75.0, score.retryScore(), 0.0001);
        assertEquals(95.0, score.latencyScore(), 0.0001);
        assertEquals(87.5, score.rawScore(), 0.0001);
        assertEquals(1.0, score.sampleWeight(), 0.0001);
        assertEquals(87.5, score.finalScore(), 0.0001);
        assertEquals("A", score.band());
    }
}
