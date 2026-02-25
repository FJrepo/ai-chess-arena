package dev.aichessarena.dto;

import java.util.List;

public record OpenRouterModelsResponseDto(
        List<OpenRouterModelOptionDto> data,
        int totalMatched,
        int featuredCount,
        String error
) {}
