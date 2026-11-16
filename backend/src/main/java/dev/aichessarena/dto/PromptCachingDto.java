package dev.aichessarena.dto;

import java.math.BigDecimal;

/** Usage-bearing attempts only; a total is null if any included attempt lacks that counter. */
public record PromptCachingDto(
        Long promptTokens,
        Long cachedPromptTokens,
        BigDecimal cacheHitRate,
        Long cacheWriteTokens,
        BigDecimal estimatedSavingsUsd
) {}
