package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record MoveDto(
        UUID id,
        int moveNumber,
        String color,
        String san,
        String fen,
        String modelId,
        String promptVersion,
        String promptHash,
        Integer promptTokens,
        Integer completionTokens,
        BigDecimal costUsd,
        Long responseTimeMs,
        int retryCount,
        boolean isOverride,
        Integer evaluationCp,
        Integer evaluationMate,
        LocalDateTime createdAt,
        /** Null when the move is not judged (an evaluation is missing) or outside a full game view. */
        MoveQualityDto quality
) {}
