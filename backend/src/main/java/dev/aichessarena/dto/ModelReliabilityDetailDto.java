package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Score breakdown of one model: {@code rawScore = 0.5 * firstTryLegal + 0.3 * forfeit + 0.2 * retry}. */
public record ModelReliabilityDetailDto(
        int windowDays,
        UUID tournamentId,
        ModelReliabilityDto model,
        BigDecimal firstTryLegalScore,
        BigDecimal forfeitScore,
        BigDecimal retryScore,
        BigDecimal rawScore,
        BigDecimal sampleWeight
) {}
