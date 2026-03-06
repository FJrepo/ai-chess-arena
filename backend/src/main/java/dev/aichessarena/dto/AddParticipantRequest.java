package dev.aichessarena.dto;

public record AddParticipantRequest(
        String playerName,
        String modelId,
        String customInstructions,
        Integer seed
) {}
