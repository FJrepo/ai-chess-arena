package dev.aichessarena.dto;

/**
 * A participant on create. {@code reasoningEffort} overrides the tournament's reasoning effort for this participant
 * (none, minimal, low, medium, high, xhigh, max; case-insensitive); null uses the tournament's.
 */
public record AddParticipantRequest(
        String playerName,
        String controlType,
        String modelId,
        String customInstructions,
        Integer seed,
        String reasoningEffort
) {
    /** A participant without a reasoning-effort override. */
    public AddParticipantRequest(String playerName, String controlType, String modelId, String customInstructions,
                                 Integer seed) {
        this(playerName, controlType, modelId, customInstructions, seed, null);
    }
}
