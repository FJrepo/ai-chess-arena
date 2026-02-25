package dev.aichessarena.dto;

public record OpenRouterModelOptionDto(
        String id,
        String name,
        String provider,
        Integer contextLength,
        Double promptPricePerMillion,
        Double completionPricePerMillion,
        boolean featured
) {}
