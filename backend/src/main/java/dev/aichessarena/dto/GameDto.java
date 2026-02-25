package dev.aichessarena.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record GameDto(
        UUID id,
        UUID tournamentId,
        String whitePlayerName,
        String whiteModelId,
        String blackPlayerName,
        String blackModelId,
        String status,
        String result,
        String resultReason,
        String pgn,
        String currentFen,
        String bracketRound,
        Integer bracketPosition,
        BigDecimal totalCostUsd,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<MoveDto> moves,
        List<ChatMessageDto> chatMessages
) {}
