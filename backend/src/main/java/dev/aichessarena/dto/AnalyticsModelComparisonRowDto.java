package dev.aichessarena.dto;

import java.math.BigDecimal;

public record AnalyticsModelComparisonRowDto(
        String modelId,
        long gamesPlayed,
        long wins,
        long draws,
        long losses,
        long forfeits,
        long timeoutForfeits,
        BigDecimal winRate,
        BigDecimal drawRate,
        BigDecimal lossRate,
        long whiteGames,
        long whiteWins,
        BigDecimal whiteWinRate,
        long blackGames,
        long blackWins,
        BigDecimal blackWinRate,
        long movesSampled,
        Long averageResponseTimeMs,
        BigDecimal averageRetriesPerMove,
        BigDecimal totalCostUsd,
        BigDecimal averageCostPerMoveUsd,
        BigDecimal costPerWinUsd,
        boolean pricingAvailable,
        BigDecimal reliabilityScore,
        String reliabilityBand,
        boolean insufficientData
) {}
