package dev.aichessarena.dto;

import java.math.BigDecimal;

/**
 * One model's results in finished, non-bye games. Costs include rejected attempts. Move quality counts the model's
 * judged moves (evaluated by Stockfish, not overrides, not forced): {@code averageCentipawnLoss} over those moves
 * and {@code blundersPerGame} over the games with at least one of them ({@code analyzedGames}); both are null
 * without judged moves.
 */
public record AnalyticsModelComparisonRowDto(
        String modelId,
        long gamesPlayed,
        long wins,
        long draws,
        long losses,
        long forfeits,
        long timeoutForfeits,
        BigDecimal winRate,
        BigDecimal drawRate,
        BigDecimal lossRate,
        long whiteGames,
        long whiteWins,
        BigDecimal whiteWinRate,
        long blackGames,
        long blackWins,
        BigDecimal blackWinRate,
        long movesSampled,
        Long averageResponseTimeMs,
        BigDecimal averageRetriesPerMove,
        BigDecimal firstTryLegalRate,
        BigDecimal totalCostUsd,
        BigDecimal averageCostPerMoveUsd,
        BigDecimal costPerWinUsd,
        boolean pricingAvailable,
        BigDecimal reliabilityScore,
        String reliabilityBand,
        boolean insufficientData,
        long analyzedMoves,
        long analyzedGames,
        BigDecimal averageCentipawnLoss,
        long inaccuracies,
        long mistakes,
        long blunders,
        BigDecimal blundersPerGame
) {}
