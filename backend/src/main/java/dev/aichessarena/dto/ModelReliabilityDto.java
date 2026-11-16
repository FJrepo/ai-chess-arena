package dev.aichessarena.dto;

import java.math.BigDecimal;

/**
 * Reliability of one model. Games are finished, non-bye games; {@code averageCostPerMoveUsd} includes the cost of
 * rejected attempts.
 */
public record ModelReliabilityDto(
        String modelId,
        long gamesPlayed,
        long gamesCompleted,
        long forfeitCount,
        BigDecimal forfeitRate,
        BigDecimal timeoutForfeitRate,
        BigDecimal averageRetriesPerMove,
        BigDecimal firstTryLegalRate,
        Long averageResponseTimeMs,
        BigDecimal averageCostPerMoveUsd,
        long movesSampled,
        BigDecimal finalScore,
        String band,
        boolean insufficientData
) {}
