package dev.aichessarena.dto;

/** Conversion in evaluated model-side games; unknown metrics are null, observed zeros stay zero. */
public record ConversionDto(
        long evaluatedGames,
        Long winningPositionsReached,
        Long converted,
        Long adjudicatedWins,
        Long thrownAway,
        Long missedMates
) {}
