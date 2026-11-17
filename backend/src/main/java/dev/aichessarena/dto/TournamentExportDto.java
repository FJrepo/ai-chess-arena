package dev.aichessarena.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Versioned, allowlisted export contract. Never serialize persistence entities or provider payloads. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TournamentExportDto(
        int schemaVersion, UUID id, String name, String status,
        LocalDateTime createdAt, LocalDateTime updatedAt, Settings settings,
        List<Participant> participants, List<ExportGame> games, BigDecimal totalCostUsd
) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Settings(
            String format, String drawPolicy, Integer moveTimeoutSeconds, int maxRetries,
            int matchupBestOf, Integer finalsBestOf, String chatMode, String banterLevel, boolean resignationAllowed,
            boolean adjudicationEnabled, int maxOutputTokens, String reasoningEffort,
            String openingMode, String openingId, String autoRunStatus, int maxConcurrentGames,
            BigDecimal budgetUsd
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Participant(
            UUID id, String playerName, String modelId, String controlType, int seed, String reasoningEffort
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExportGame(
            UUID id, String bracketRound, Integer bracketPosition, UUID seriesId,
            int seriesGameNumber, int seriesBestOf,
            UUID whiteParticipantId, String whitePlayerName, String whiteModelId,
            UUID blackParticipantId, String blackPlayerName, String blackModelId,
            UUID advancedParticipantId, String status, String result, String resultReason,
            String termination, String currentFen, String openingId, String openingName, String openingEco,
            LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime completedAt,
            List<MoveDto> moves, GameQualitySummaryDto qualitySummary,
            List<ChatMessageDto> chatMessages, List<Attempt> attempts, BigDecimal totalCostUsd
    ) {}

    /** Per-attempt summary, including failed/discarded paid answers, without raw answers or error text. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Attempt(
            UUID id, int moveNumber, String color, int attemptNumber, String modelId, String outcome,
            Integer promptTokens, Integer cachedPromptTokens, Integer cacheWriteTokens,
            Integer completionTokens, Integer reasoningTokens, Integer effectiveMaxOutputTokens,
            Integer modelMaxCompletionTokens, String reasoningEffort, Long responseTimeMs,
            BigDecimal costUsd, LocalDateTime createdAt
    ) {}
}
