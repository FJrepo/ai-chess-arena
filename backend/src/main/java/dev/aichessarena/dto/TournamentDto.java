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
        // True unless chatMode is OFF; kept for older clients.
        boolean trashTalkEnabled,
        String chatMode,
        // Models may resign instead of moving from their 10th move on (Tournament.MIN_RESIGNATION_MOVE).
        boolean resignationAllowed,
        boolean adjudicationEnabled,
        int maxOutputTokens,
        // OpenRouter reasoning effort for all models (lower case); null (omitted) means each model's default.
        String reasoningEffort,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ParticipantDto> participants,
        List<GameDto> games,
        String winnerName,
        String openingMode,
        String openingId
) {}
