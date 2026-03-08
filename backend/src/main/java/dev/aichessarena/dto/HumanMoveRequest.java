package dev.aichessarena.dto;

public record HumanMoveRequest(
        String move,
        String from,
        String to,
        String promotion,
        String message
) {}
