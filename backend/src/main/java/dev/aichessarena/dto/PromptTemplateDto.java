package dev.aichessarena.dto;

public record PromptTemplateDto(
        String template,
        String version,
        String hash
) {}
