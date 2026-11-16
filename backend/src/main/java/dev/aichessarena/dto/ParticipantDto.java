package dev.aichessarena.dto;

import java.util.UUID;

/** {@code reasoningEffort} is the participant's override (lower case); null (omitted) when it uses the tournament's. */
public record ParticipantDto(
        UUID id,
        String playerName,
        String controlType,
        String modelId,
        String customInstructions,
        int seed,
        String reasoningEffort
) {}
