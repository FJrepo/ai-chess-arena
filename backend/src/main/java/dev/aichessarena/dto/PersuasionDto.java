package dev.aichessarena.dto;

/**
 * One model's persuasion record in finished model-vs-model games (see {@link PersuasionRulesDto}). As persuader:
 * games its opponent resigned, split by the tone of the model's own last move before the resignation (Mind games
 * or any other level, including no tone). As persuaded: games the model resigned itself.
 */
public record PersuasionDto(
        PersuasionCountDto asPersuaderMindGames,
        PersuasionCountDto asPersuaderOtherLevels,
        PersuasionCountDto asPersuaded
) {}
