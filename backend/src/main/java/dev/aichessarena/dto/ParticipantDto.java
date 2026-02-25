package dev.aichessarena.dto;

import java.util.UUID;

public record ParticipantDto(
        UUID id,
        String playerName,
        String modelId,
        String customSystemPrompt,
        int seed
) {}
