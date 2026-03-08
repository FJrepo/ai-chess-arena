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
        String blackPlayerName,
        String blackControlType,
        String blackModelId,
        String status,
        String result,
        String resultReason,
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
        List<ChatMessageDto> chatMessages
) {}
