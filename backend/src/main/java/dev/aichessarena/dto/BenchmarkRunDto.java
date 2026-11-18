package dev.aichessarena.dto;

import dev.aichessarena.entity.BenchmarkDefinition;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import dev.aichessarena.entity.Tournament.AutoRunStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BenchmarkRunDto(
        Summary run, BenchmarkDefinition definition, boolean legalMoveHints, String reasoningEffort,
        ChatMode chatMode, String budgetUsd, int maxConcurrentGames, BigDecimal spendUsd, List<ModelResult> models
) {
    public record Summary(UUID id, String name, LocalDateTime createdAt, AutoRunStatus status, String pauseReason) {}

    public record ModelResult(
            String modelId, Score overall, List<Split> splits, long incompletePairs, BigDecimal spendUsd, List<Pair> pairs
    ) {}

    public record Split(int targetStrength, String color, Score score) {}

    public record Score(long wins, long draws, long losses, long forfeits, BigDecimal points, long scoredGames) {}

    public record Pair(UUID id, String openingId, int targetStrength, boolean complete, List<GameResultRow> games) {}

    public record GameResultRow(
            UUID id, String color, GameStatus status, GameResult result, ResultReason reason, String statusDetail,
            BigDecimal spendUsd
    ) {}
}
