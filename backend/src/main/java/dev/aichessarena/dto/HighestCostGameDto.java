package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record HighestCostGameDto(
        UUID gameId,
        BigDecimal costUsd,
        String whitePlayerName,
        String blackPlayerName
) {}
