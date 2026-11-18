package dev.aichessarena.dto;

import java.util.List;
import java.util.UUID;

public record LiveGamesDto(List<GameSummary> active, GameSummary lastFinished) {
    public record GameSummary(UUID id, String status, String tournamentName, String whitePlayerName,
                              String blackPlayerName, boolean practice, Integer moveNumber,
                              Integer evaluationCp, Integer evaluationMate) {}
}
