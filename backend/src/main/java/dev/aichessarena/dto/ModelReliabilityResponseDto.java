package dev.aichessarena.dto;

import java.util.List;
import java.util.UUID;

public record ModelReliabilityResponseDto(
        int windowDays,
        UUID tournamentId,
        int minGames,
        List<ModelReliabilityDto> models
) {}
