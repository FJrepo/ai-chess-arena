package dev.aichessarena.dto;

/**
 * One side's move quality in a game: judged moves (evaluated, not an override, not forced), their average
 * centipawn loss (null without judged moves) and the number of inaccuracies, mistakes and blunders.
 */
public record SideQualityDto(
        int analyzedMoves,
        Integer averageCentipawnLoss,
        int inaccuracies,
        int mistakes,
        int blunders
) {}
