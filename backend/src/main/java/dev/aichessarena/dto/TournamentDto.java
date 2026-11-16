package dev.aichessarena.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TournamentDto(
        UUID id,
        String name,
        String status,
        String format,
        String drawPolicy,
        String sharedCustomInstructions,
        // Always serialized: null means no time limit per move (other null fields are omitted).
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer moveTimeoutSeconds,
        int maxRetries,
        int matchupBestOf,
        Integer finalsBestOf,
        boolean trashTalkEnabled,
        int maxOutputTokens,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ParticipantDto> participants,
        List<GameDto> games,
        String winnerName
) {}
