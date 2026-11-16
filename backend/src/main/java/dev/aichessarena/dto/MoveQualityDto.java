package dev.aichessarena.dto;

/**
 * Quality of one move, derived from the Stockfish evaluations before and after it (Lichess-style win-probability
 * model). {@code judgement} is {@code INACCURACY}, {@code MISTAKE}, {@code BLUNDER} or null (no notable error).
 * Winning chances are the mover's, as a probability between 0 and 1. For an excluded move ({@code excluded} is
 * {@code OVERRIDE} or {@code FORCED}) {@code judgement} and {@code centipawnLoss} are null.
 */
public record MoveQualityDto(
        String judgement,
        Integer centipawnLoss,
        double winChanceBefore,
        double winChanceAfter,
        String excluded
) {}
