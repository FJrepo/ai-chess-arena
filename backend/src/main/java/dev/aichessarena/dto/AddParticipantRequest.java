package dev.aichessarena.dto;

public record AddParticipantRequest(
        String playerName,
        String controlType,
        String modelId,
        String customInstructions,
        Integer seed
) {}
