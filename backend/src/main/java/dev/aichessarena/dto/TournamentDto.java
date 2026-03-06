package dev.aichessarena.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TournamentDto(
        UUID id,
        String name,
        String status,
        String format,
        String drawPolicy,
        String defaultSystemPrompt,
        int moveTimeoutSeconds,
        int maxRetries,
        int matchupBestOf,
        Integer finalsBestOf,
        boolean trashTalkEnabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ParticipantDto> participants,
        List<GameDto> games
) {}
