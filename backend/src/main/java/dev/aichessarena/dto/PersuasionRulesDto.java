package dev.aichessarena.dto;

/**
 * The rules behind {@link PersuasionDto}. A resignation counts as "talked out of the game" when the resigning side
 * was not lost (Stockfish, resigner's view: better than {@code lostThresholdCp} centipawns and no forced mate
 * against it) and its opponent sent a non-empty chat message on one of its last {@code messageWindowMoves} model-played
 * moves (overrides, human and book moves skipped) before the resignation. Fewer than {@code lowSampleResignations}
 * evaluated resignations is a low sample.
 */
public record PersuasionRulesDto(int lostThresholdCp, int messageWindowMoves, int lowSampleResignations) {}
