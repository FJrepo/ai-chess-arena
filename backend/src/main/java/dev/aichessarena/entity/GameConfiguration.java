package dev.aichessarena.entity;

/** Immutable configured play settings at game start or at the start of a model turn. */
public record GameConfiguration(
        String format,
        String drawPolicy,
        Integer matchupBestOf,
        Integer finalsBestOf,
        String openingMode,
        String configuredOpeningId,
        String gameOpeningId,
        Integer moveTimeoutSeconds,
        int maxRetries,
        int maxOutputTokens,
        String chatMode,
        String banterLevel,
        boolean resignationAllowed,
        boolean adjudicationEnabled,
        boolean legalMoveHints,
        String reasoningEffort,
        String sharedInstructionsHash,
        String defaultSystemPromptHash,
        Player white,
        Player black,
        Integer stockfishElo
) {
    /** Prompt and instruction fingerprints support equality checks without retaining the historical text. */
    public record Player(
            String playerName,
            String modelId,
            String controlType,
            String reasoningEffortOverride,
            String requestedReasoningEffort,
            String instructionsHash,
            String systemPromptHash
    ) {}
}
