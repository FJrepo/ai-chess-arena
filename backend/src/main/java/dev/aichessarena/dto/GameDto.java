package dev.aichessarena.dto;

import dev.aichessarena.entity.GameConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record GameDto(
        UUID id,
        UUID tournamentId,
        String whitePlayerName,
        String whiteControlType,
        String whiteModelId,
        UUID whiteParticipantId,
        String blackPlayerName,
        String blackControlType,
        String blackModelId,
        UUID blackParticipantId,
        String status,
        String result,
        String resultReason,
        String statusDetail,
        Instant resumeRequestedAt,
        RunCapacity runCapacity,
        String advancedPlayerName,
        UUID advancedParticipantId,
        String pgn,
        String currentFen,
        String bracketRound,
        Integer bracketPosition,
        UUID seriesId,
        int seriesGameNumber,
        int seriesBestOf,
        BigDecimal totalCostUsd,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<MoveDto> moves,
        List<ChatMessageDto> chatMessages,
        /** Per-side move quality; only with the moves (single game), otherwise null. */
        GameQualitySummaryDto qualitySummary,
        /** How the game ended by resignation; only with the moves and only for a resignation, otherwise null. */
        ResignationDto resignation,
        /** Decisive-position adjudication evidence; only with the moves of an adjudicated game. */
        AdjudicationDto adjudication,
        /** Grows with every status change; live status updates carry it too (see Game.statusRevision). */
        long statusRevision,
        /**
         * The reasoning effort each side asks for (the participant's override, else the tournament's; lower case);
         * null for the model default, a human side or a game outside a tournament. The level sent may be mapped to
         * the nearest one the model supports (see MoveAttemptDto.reasoningEffort).
         */
        String whiteReasoningEffort,
        String blackReasoningEffort,
        String openingId,
        String openingName,
        String openingEco,
        boolean legalMoveHints,
        long liveRevision,
        String activeColor,
        Instant turnStartedAt,
        Instant turnDeadlineAt,
        String turnChatMode,
        String turnBanterLevel,
        List<MoveAttemptDto> attempts,
        String streamId,
        GameConfiguration startingConfiguration,
        List<GameConfigurationRevisionDto> configurationHistory,
        String practiceKind,
        UUID practiceSourceGameId,
        Integer practiceSourcePly,
        Integer stockfishElo,
        UUID benchmarkRunId,
        String benchmarkChatMode,
        /**
         * A model request was sent and its answer never recorded, so it may have been charged: the game resumes only
         * with {@code acknowledgeUncertainRequest=true} on the start endpoint.
         */
        boolean uncertainRequest
) {
    /** Present on individual game reads and status changes while a resume is queued. */
    public record RunCapacity(long runningGames, int maxConcurrentGames) {}
}
