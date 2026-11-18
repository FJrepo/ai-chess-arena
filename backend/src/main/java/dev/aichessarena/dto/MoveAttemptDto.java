package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record MoveAttemptDto(
        UUID id,
        int moveNumber,
        String color,
        int attemptNumber,
        String modelId,
        String outcome,
        String proposedMove,
        Integer promptTokens,
        Integer completionTokens,
        Integer reasoningTokens,
        BigDecimal costUsd,
        Long responseTimeMs,
        String finishReason,
        String errorMessage,
        LocalDateTime createdAt,
        // The reasoning effort sent with this attempt (lower case); null when none was sent.
        String reasoningEffort,
        Integer effectiveMaxOutputTokens,
        Integer modelMaxCompletionTokens,
        Integer cachedPromptTokens,
        Integer cacheWriteTokens,
        Long turnStatusRevision,
        Integer configurationRevision
) {}
