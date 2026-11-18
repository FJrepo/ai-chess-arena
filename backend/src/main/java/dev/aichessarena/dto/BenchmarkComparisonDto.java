package dev.aichessarena.dto;

import dev.aichessarena.dto.BenchmarkRunDto.Score;
import dev.aichessarena.dto.BenchmarkRunDto.Summary;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BenchmarkComparisonDto(
        Summary base, Summary candidate, boolean comparable, ComparisonType comparisonType, List<Difference> differences, List<Difference> settingChanges,
        BigDecimal baseSpendUsd, BigDecimal candidateSpendUsd, BigDecimal spendDeltaUsd,
        List<String> unmatchedBaseModels, List<String> unmatchedCandidateModels,
        List<Model> models, List<Matchup> matchups
) {
    public enum ComparisonType { CONTROLLED_RERUN, COMPARABLE, MULTIPLE_SETTINGS, NOT_COMPARABLE }

    public record Difference(String setting, String base, String candidate) {}

    public record Model(
            String baseModelId, String candidateModelId, Side base, Side candidate, int matchedGames,
            BigDecimal scoreDelta, BigDecimal spendDeltaUsd
    ) {}

    public record Side(Score score, Telemetry telemetry, long incompletePairs, BigDecimal spendUsd,
                       List<String> resolvedModelIds) {}

    public record Matchup(
            String baseModelId, String candidateModelId, String openingId, String openingName,
            int targetStrength, String color, GameSide base, GameSide candidate, boolean outcomeChanged,
            BigDecimal scoreDelta, BigDecimal spendDeltaUsd
    ) {}

    public record GameSide(UUID gameId, GameStatus status, ResultReason reason, String outcome, BigDecimal points,
                           boolean forfeit, boolean pairIncomplete, Telemetry telemetry, BigDecimal spendUsd) {}

    public record Telemetry(long firstTryMoves, long turnsSampled, BigDecimal firstTryLegalRate,
                            long reasoningMovesReported, BigDecimal reasoningTokensPerMove) {}
}
