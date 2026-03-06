package dev.aichessarena.dto;

import java.util.List;
import java.util.UUID;

public record AnalyticsModelComparisonDto(
        int windowDays,
        UUID tournamentId,
        int minGames,
        long gamesCount,
        List<AnalyticsModelComparisonRowDto> models
) {}
