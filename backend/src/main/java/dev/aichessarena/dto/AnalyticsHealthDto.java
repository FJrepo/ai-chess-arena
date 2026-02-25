package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AnalyticsHealthDto(
        int windowDays,
        UUID tournamentId,
        long gamesCount,
        long activeGamesCount,
        long completedGamesCount,
        long forfeitGamesCount,
        long movesCount,
        long retriesTotal,
        BigDecimal averageRetriesPerMove,
        Long averageResponseTimeMs,
        Long p95ResponseTimeMs,
        long promptTokensTotal,
        long completionTokensTotal,
        BigDecimal costTotalUsd,
        List<AnalyticsHealthModelRowDto> models
) {}
