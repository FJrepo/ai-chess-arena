package dev.aichessarena.dto;

import java.math.BigDecimal;

/** Observed request effort and provider token-counter coverage for the selected model moves. */
public record ReasoningAnalyticsDto(String effort, BigDecimal averageTokens, long reportedMoves, long modelMoves) {}
