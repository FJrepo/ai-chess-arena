package dev.aichessarena.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
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
        int maxAttempts,
        int matchupBestOf,
        Integer finalsBestOf,
        String chatMode,
        @JsonInclude(JsonInclude.Include.ALWAYS) String banterLevel,
        // Models may resign instead of moving from their 10th move on (Tournament.MIN_RESIGNATION_MOVE).
        boolean resignationAllowed,
        boolean legalMoveHints,
        boolean adjudicationEnabled,
        int maxOutputTokens,
        // OpenRouter reasoning effort for all models (lower case); null (omitted) means each model's default.
        String reasoningEffort,
        Instant createdAt,
        Instant updatedAt,
        List<ParticipantDto> participants,
        List<GameDto> games,
        String winnerName,
        String openingMode,
        String openingId,
        String autoRunStatus,
        int maxConcurrentGames,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal budgetUsd,
        String autoRunPauseReason,
        String creationDrawMode,
        boolean legacySystemPrompt,
        String budgetUsdExact
) {}
