package dev.aichessarena.dto;

import java.util.List;

/**
 * One OpenRouter model as offered in tournament setup. Prices are USD per million tokens ({@code 0} for a free
 * model, {@code null} when unknown or variable). The detail fields after {@code featured} are null (or an empty
 * list) when the catalog does not provide them.
 *
 * @param releasedAt ISO date (YYYY-MM-DD, UTC) derived from the catalog's {@code created} timestamp
 * @param reasoningMandatory null when the catalog does not say
 * @param intelligenceIndex Artificial Analysis Intelligence Index as published in the OpenRouter catalog
 * @param description trimmed to about 400 characters, cut at a word boundary
 */
public record OpenRouterModelOptionDto(
        String id,
        String name,
        String provider,
        Integer contextLength,
        Double promptPricePerMillion,
        Double completionPricePerMillion,
        boolean featured,
        String releasedAt,
        Double cacheReadPricePerMillion,
        Integer maxCompletionTokens,
        boolean reasoningSupported,
        Boolean reasoningMandatory,
        List<String> reasoningEfforts,
        Double intelligenceIndex,
        String description
) {}
