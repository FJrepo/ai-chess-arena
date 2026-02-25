package dev.aichessarena.dto;

import java.math.BigDecimal;

public record ModelReliabilityDto(
        String modelId,
        long gamesPlayed,
        long gamesCompleted,
        long forfeitCount,
        BigDecimal forfeitRate,
        BigDecimal timeoutForfeitRate,
        BigDecimal averageRetriesPerMove,
        Long averageResponseTimeMs,
        BigDecimal averageCostPerMoveUsd,
        long movesSampled,
        BigDecimal finalScore,
        String band,
        boolean insufficientData
) {}
