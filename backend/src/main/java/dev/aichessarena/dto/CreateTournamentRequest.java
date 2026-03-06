package dev.aichessarena.dto;

public record CreateTournamentRequest(
        String name,
        String defaultSystemPrompt,
        Integer moveTimeoutSeconds,
        Integer maxRetries,
        Integer matchupBestOf,
        Integer finalsBestOf,
        Boolean trashTalkEnabled,
        String drawPolicy
) {}
