package dev.aichessarena.dto;

import java.util.Map;

/**
 * The default system rules: {@code template}, {@code version} and {@code hash} are those of the default chat mode
 * (OPTIONAL); {@code chatModes} has the rules of every chat mode by name (OFF, OPTIONAL, MANDATORY).
 */
public record PromptTemplateDto(
        String template,
        String version,
        String hash,
        Map<String, Variant> chatModes
) {
    /** The system rules of one chat mode, unformatted (with the %s placeholders). */
    public record Variant(String template, String version, String hash) {}
}
