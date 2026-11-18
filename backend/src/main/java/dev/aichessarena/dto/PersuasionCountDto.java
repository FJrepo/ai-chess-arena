package dev.aichessarena.dto;

/**
 * Resignations in one persuasion view: how many there were, how many of them had a Stockfish evaluation of the
 * position given up, and how many counted as "talked out of the game". {@code lowSample} is true below
 * {@link PersuasionRulesDto#lowSampleResignations()} evaluated resignations.
 */
public record PersuasionCountDto(long resignations, long evaluated, long talkedOut, boolean lowSample) {}
