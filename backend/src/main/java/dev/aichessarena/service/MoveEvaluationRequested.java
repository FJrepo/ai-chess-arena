package dev.aichessarena.service;

import java.util.UUID;

public record MoveEvaluationRequested(
        UUID moveId,
        UUID gameId,
        int moveNumber,
        String color,
        String fen
) {}
