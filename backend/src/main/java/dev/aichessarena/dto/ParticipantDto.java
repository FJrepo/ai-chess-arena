package dev.aichessarena.dto;

import java.util.UUID;

public record ParticipantDto(
        UUID id,
        String playerName,
        String controlType,
        String modelId,
        String customInstructions,
        int seed
) {}
