package dev.aichessarena.service;

final class AnalyticsReliabilityScorer {

    ReliabilityScore score(
            long gamesPlayed,
            double completionRate,
            double forfeitRate,
            double averageRetriesPerMove,
            Long averageResponseTimeMs
    ) {
        double completionScore = clamp(completionRate * 100.0, 0.0, 100.0);
        double forfeitScore = clamp((1.0 - forfeitRate) * 100.0, 0.0, 100.0);
        double retryScore = clamp(100.0 - (averageRetriesPerMove * 50.0), 0.0, 100.0);
        double latencyInput = averageResponseTimeMs != null ? averageResponseTimeMs : 1500.0;
        double latencyScore = clamp(100.0 - ((latencyInput - 1500.0) / 100.0), 0.0, 100.0);
        double rawScore = (completionScore * 0.40)
                + (forfeitScore * 0.30)
                + (retryScore * 0.20)
                + (latencyScore * 0.10);
        double sampleWeight = Math.min(1.0, gamesPlayed / 20.0);
        double finalScore = 60.0 + (rawScore - 60.0) * sampleWeight;
        boolean insufficientData = gamesPlayed < 3;

        return new ReliabilityScore(
                completionScore,
                forfeitScore,
                retryScore,
                latencyScore,
                rawScore,
                sampleWeight,
                finalScore,
                toBand(finalScore, insufficientData),
                insufficientData
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
            double completionScore,
            double forfeitScore,
            double retryScore,
            double latencyScore,
            double rawScore,
            double sampleWeight,
            double finalScore,
            String band,
            boolean insufficientData
    ) {
    }
}
