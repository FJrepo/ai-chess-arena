package dev.aichessarena.dto;

import java.math.BigDecimal;

/**
 * One model's results in finished, non-bye games. Costs include rejected attempts. Move quality counts the model's
 * judged moves (evaluated by Stockfish, not overrides, not forced): {@code averageCentipawnLoss} over those moves
 * and {@code blundersPerGame} over the games with at least one of them ({@code analyzedGames}); both are null
 * without judged moves. Talkativeness ({@code optionalChat}, {@code mandatoryChat}) counts the model's moves per chat
 * mode that applied to them and how many of them came with a message.
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
        BigDecimal blundersPerGame,
        // Talkativeness per chat mode; moves made with chat OFF are left out.
        TalkativenessDto optionalChat,
        TalkativenessDto mandatoryChat,
        // Games the model resigned; of those, how many had a Stockfish evaluation of the position it resigned in and
        // how many it resigned with at least 35% winning chances ("while not losing").
        long resignations,
        long resignationsEvaluated,
        long resignationsWhileNotLosing,
        // Games the model won because its opponent (a model) resigned.
        long wonByResignation
) {}
