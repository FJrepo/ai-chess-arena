package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record TournamentCostSummaryDto(
        UUID tournamentId,
        BigDecimal totalCostUsd,
        BigDecimal averageCostPerGameUsd,
        BigDecimal averageCostPerMoveUsd,
        HighestCostGameDto highestCostGame,
        List<ModelCostBreakdownDto> topModels
) {}
