package dev.aichessarena.dto;

import java.math.BigDecimal;

public record AnalyticsHealthModelRowDto(
        String modelId,
        long movesCount,
        long retriesTotal,
        BigDecimal averageRetriesPerMove,
        Long averageResponseTimeMs,
        long promptTokensTotal,
        long completionTokensTotal,
        BigDecimal totalCostUsd,
        BigDecimal averageCostPerMoveUsd
) {}
