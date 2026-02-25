package dev.aichessarena.dto;

public record CreateTournamentRequest(
        String name,
        String defaultSystemPrompt,
        Integer moveTimeoutSeconds,
        Integer maxRetries,
        Boolean trashTalkEnabled,
        String drawPolicy
) {}
