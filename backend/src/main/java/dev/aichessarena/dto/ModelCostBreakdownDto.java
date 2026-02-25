package dev.aichessarena.dto;

import java.math.BigDecimal;

public record ModelCostBreakdownDto(
        String modelId,
        BigDecimal totalCostUsd,
        long moveCount,
        BigDecimal averageCostPerMoveUsd
) {}
