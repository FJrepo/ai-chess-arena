package dev.aichessarena.dto;

import java.util.Map;

/**
 * The default system rules: {@code template}, {@code version} and {@code hash} are those of the default chat mode
 * (OPTIONAL); {@code chatModes} has the rules of every chat mode by name (OFF, OPTIONAL, MANDATORY) and
 * {@code resignationChatModes} the same rules for a tournament that allows resignation (rule 9 added, version suffix
 * -resign). Banter variants are keyed by level then chat mode, with/without resignation.
 */
public record PromptTemplateDto(
        String template,
        String version,
        String hash,
        Map<String, Variant> chatModes,
        Map<String, Variant> resignationChatModes,
        Map<String, Map<String, Variant>> banterChatModes,
        Map<String, Map<String, Variant>> resignationBanterChatModes,
        NoHintsVariant noHints
) {
    /** Additions to the selected generated rules when legal move hints are disabled. */
    public record NoHintsVariant(String rule, String versionSuffix) {}

    /** The system rules of one chat mode, unformatted (with the %s placeholders). */
    public record Variant(String template, String version, String hash) {}
}
