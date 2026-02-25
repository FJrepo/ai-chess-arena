package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ModelReliabilityDetailDto(
        int windowDays,
        UUID tournamentId,
        ModelReliabilityDto model,
        BigDecimal completionScore,
        BigDecimal forfeitScore,
        BigDecimal retryScore,
        BigDecimal latencyScore,
        BigDecimal rawScore,
        BigDecimal sampleWeight
) {}
