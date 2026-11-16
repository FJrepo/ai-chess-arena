package dev.aichessarena.dto;

import java.math.BigDecimal;
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
        /** Grows with every status change; live status updates carry it too (see Game.statusRevision). */
        long statusRevision
) {}
