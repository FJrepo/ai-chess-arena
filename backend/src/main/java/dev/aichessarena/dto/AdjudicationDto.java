package dev.aichessarena.dto;

/** The adjudicated winner and latest evaluation from its view, over the required window. */
public record AdjudicationDto(String color, int plies, Integer evaluationCp, Integer evaluationMate) {}
