package dev.aichessarena.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageDto(
        UUID id,
        int moveNumber,
        String senderModel,
        String senderColor,
        String message,
        Instant createdAt
) {}
