package dev.aichessarena.dto;

import java.util.List;

/**
 * @param featuredProviders the configured featured providers ({@code openrouter.featured-providers}) in their
 *     configured order, so clients can list them first
 */
public record OpenRouterModelsResponseDto(
        List<OpenRouterModelOptionDto> data,
        int totalMatched,
        int featuredCount,
        String error,
        List<String> featuredProviders
) {}
