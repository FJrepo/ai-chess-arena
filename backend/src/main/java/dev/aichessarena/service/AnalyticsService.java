package dev.aichessarena.service;

import dev.aichessarena.dto.AnalyticsHealthDto;
import dev.aichessarena.dto.AnalyticsHealthModelRowDto;
import dev.aichessarena.dto.AnalyticsModelComparisonDto;
import dev.aichessarena.dto.AnalyticsModelComparisonRowDto;
import dev.aichessarena.dto.HighestCostGameDto;
import dev.aichessarena.dto.ModelCostBreakdownDto;
import dev.aichessarena.dto.ModelReliabilityDetailDto;
import dev.aichessarena.dto.ModelReliabilityDto;
import dev.aichessarena.dto.ModelReliabilityResponseDto;
import dev.aichessarena.dto.TournamentCostSummaryDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@ApplicationScoped
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 3650;
    private static final long CACHE_TTL_MS = 15_000L;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    private final AnalyticsReliabilityScorer reliabilityScorer = new AnalyticsReliabilityScorer();

    private final Map<String, CacheEntry<AnalyticsHealthDto>> healthCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<ModelReliabilityResponseDto>> reliabilityCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<AnalyticsModelComparisonDto>> comparisonCache = new ConcurrentHashMap<>();

    public TournamentCostSummaryDto getTournamentCostSummary(UUID tournamentId) {
        List<Game> games = gameRepository.findByTournamentId(tournamentId);
        List<Move> moves = moveRepository.findByTournamentId(tournamentId);

        BigDecimal totalCost = games.stream()
                .map(game -> nonNull(game.totalCostUsd))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averageCostPerGame = divide(totalCost, games.size());

        BigDecimal totalMoveCost = moves.stream()
                .map(move -> move.costUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long pricedMoveCount = moves.stream().filter(move -> move.costUsd != null).count();
        BigDecimal averageCostPerMove = divide(totalMoveCost, pricedMoveCount);

        Game highest = games.stream()
                .max(Comparator.comparing(game -> nonNull(game.totalCostUsd)))
                .orElse(null);

        HighestCostGameDto highestCostGame = highest == null
                ? null
                : new HighestCostGameDto(
                highest.id,
                nonNull(highest.totalCostUsd),
                highest.whitePlayerName,
                highest.blackPlayerName
        );

        Map<String, CostAccumulator> byModel = new HashMap<>();
        for (Move move : moves) {
            String modelId = normalizeModelId(move.modelId);
            CostAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new CostAccumulator());
            accumulator.moveCount++;
            if (move.costUsd != null) {
                accumulator.totalCost = accumulator.totalCost.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }

        List<ModelCostBreakdownDto> topModels = byModel.entrySet().stream()
                .map(entry -> new ModelCostBreakdownDto(
                        entry.getKey(),
                        scale(entry.getValue().totalCost, 6),
                        entry.getValue().moveCount,
                        divide(entry.getValue().totalCost, entry.getValue().pricedMoves)
                ))
                .sorted(Comparator.comparing(ModelCostBreakdownDto::totalCostUsd).reversed())
                .limit(5)
                .toList();

        return new TournamentCostSummaryDto(
                tournamentId,
                scale(totalCost, 6),
                averageCostPerGame,
                averageCostPerMove,
                highestCostGame,
                topModels
        );
    }

    public AnalyticsHealthDto getHealth(int requestedDays, UUID tournamentId) {
        int days = normalizeDays(requestedDays);
        String key = days + "|" + (tournamentId != null ? tournamentId : "all");
        return getCached(healthCache, key, () -> buildHealth(days, tournamentId));
    }

    public ModelReliabilityResponseDto getReliability(int requestedDays, UUID tournamentId, int requestedMinGames) {
        int days = normalizeDays(requestedDays);
        int minGames = normalizeMinGames(requestedMinGames);
        String key = days + "|" + (tournamentId != null ? tournamentId : "all") + "|" + minGames;
        return getCached(reliabilityCache, key, () -> buildReliability(days, tournamentId, minGames));
    }

    public ModelReliabilityDetailDto getReliabilityForModel(String modelId, int requestedDays, UUID tournamentId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }

        int days = normalizeDays(requestedDays);
        ModelReliabilityResponseDto response = getReliability(days, tournamentId, 0);
        ModelReliabilityDto model = response.models().stream()
                .filter(row -> row.modelId().equals(modelId))
                .findFirst()
                .orElse(null);

        if (model == null) {
            return null;
        }

        double completionRate = model.gamesPlayed() == 0
                ? 0
                : (double) model.gamesCompleted() / (double) model.gamesPlayed();
        double forfeitRate = model.forfeitRate().doubleValue();
        double avgRetries = model.averageRetriesPerMove().doubleValue();
        AnalyticsReliabilityScorer.ReliabilityScore score = reliabilityScorer.score(
                model.gamesPlayed(),
                completionRate,
                forfeitRate,
                avgRetries,
                model.averageResponseTimeMs()
        );

        return new ModelReliabilityDetailDto(
                days,
                tournamentId,
                model,
                decimal(score.completionScore(), 2),
                decimal(score.forfeitScore(), 2),
                decimal(score.retryScore(), 2),
                decimal(score.latencyScore(), 2),
                decimal(score.rawScore(), 2),
                decimal(score.sampleWeight(), 4)
        );
    }

    public AnalyticsModelComparisonDto getComparison(int requestedDays, UUID tournamentId, int requestedMinGames) {
        int days = normalizeDays(requestedDays);
        int minGames = normalizeMinGames(requestedMinGames);
        String key = days + "|" + (tournamentId != null ? tournamentId : "all") + "|" + minGames;
        return getCached(comparisonCache, key, () -> buildComparison(days, tournamentId, minGames));
    }

    private AnalyticsHealthDto buildHealth(int days, UUID tournamentId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Game> games = tournamentId == null
                ? gameRepository.findCreatedAfter(cutoff)
                : gameRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);
        List<Move> moves = tournamentId == null
                ? moveRepository.findCreatedAfter(cutoff)
                : moveRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);

        long activeGamesCount = games.stream().filter(this::isActiveGame).count();
        long completedGamesCount = games.stream().filter(game -> game.status == GameStatus.COMPLETED).count();
        long forfeitGamesCount = games.stream().filter(game -> game.status == GameStatus.FORFEIT).count();

        long retriesTotal = moves.stream().mapToLong(move -> Math.max(move.retryCount, 0)).sum();

        List<Long> responseTimes = moves.stream()
                .map(move -> move.responseTimeMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        Long averageResponseTimeMs = responseTimes.isEmpty()
                ? null
                : Math.round(responseTimes.stream().mapToLong(Long::longValue).average().orElse(0.0));
        Long p95ResponseTimeMs = percentile95(responseTimes);

        long promptTokensTotal = moves.stream()
                .map(move -> move.promptTokens)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();

        long completionTokensTotal = moves.stream()
                .map(move -> move.completionTokens)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();

        BigDecimal costTotalUsd = moves.stream()
                .map(move -> move.costUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, HealthAccumulator> byModel = new HashMap<>();
        for (Move move : moves) {
            String modelId = normalizeModelId(move.modelId);
            HealthAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new HealthAccumulator());
            accumulator.movesCount++;
            accumulator.retriesTotal += Math.max(move.retryCount, 0);
            if (move.responseTimeMs != null) {
                accumulator.responseTimeTotal += move.responseTimeMs;
                accumulator.responseTimeCount++;
            }
            if (move.promptTokens != null) {
                accumulator.promptTokensTotal += move.promptTokens;
            }
            if (move.completionTokens != null) {
                accumulator.completionTokensTotal += move.completionTokens;
            }
            if (move.costUsd != null) {
                accumulator.totalCostUsd = accumulator.totalCostUsd.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }

        List<AnalyticsHealthModelRowDto> modelRows = byModel.entrySet().stream()
                .map(entry -> new AnalyticsHealthModelRowDto(
                        entry.getKey(),
                        entry.getValue().movesCount,
                        entry.getValue().retriesTotal,
                        divide(entry.getValue().retriesTotal, entry.getValue().movesCount),
                        entry.getValue().responseTimeCount > 0
                                ? Math.round((double) entry.getValue().responseTimeTotal / (double) entry.getValue().responseTimeCount)
                                : null,
                        entry.getValue().promptTokensTotal,
                        entry.getValue().completionTokensTotal,
                        scale(entry.getValue().totalCostUsd, 6),
                        divide(entry.getValue().totalCostUsd, entry.getValue().pricedMoves)
                ))
                .sorted(
                        Comparator.comparing(AnalyticsHealthModelRowDto::totalCostUsd).reversed()
                                .thenComparing(
                                        Comparator.comparingLong(AnalyticsHealthModelRowDto::movesCount).reversed()
                                )
                )
                .toList();

        return new AnalyticsHealthDto(
                days,
                tournamentId,
                games.size(),
                activeGamesCount,
                completedGamesCount,
                forfeitGamesCount,
                moves.size(),
                retriesTotal,
                divide(retriesTotal, moves.size()),
                averageResponseTimeMs,
                p95ResponseTimeMs,
                promptTokensTotal,
                completionTokensTotal,
                scale(costTotalUsd, 6),
                modelRows
        );
    }

    private ModelReliabilityResponseDto buildReliability(int days, UUID tournamentId, int minGames) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Game> games = tournamentId == null
                ? gameRepository.findCreatedAfter(cutoff)
                : gameRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);
        List<Move> moves = tournamentId == null
                ? moveRepository.findCreatedAfter(cutoff)
                : moveRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);

        Map<String, ReliabilityAccumulator> byModel = new HashMap<>();

        for (Game game : games) {
            registerGame(byModel, game.whiteModelId, game, true);
            registerGame(byModel, game.blackModelId, game, false);
        }

        for (Move move : moves) {
            String modelId = normalizeModelId(move.modelId);
            ReliabilityAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ReliabilityAccumulator());
            accumulator.movesSampled++;
            accumulator.retriesTotal += Math.max(move.retryCount, 0);

            if (move.responseTimeMs != null) {
                accumulator.responseTimeTotal += move.responseTimeMs;
                accumulator.responseTimeCount++;
            }

            if (move.costUsd != null) {
                accumulator.costTotalUsd = accumulator.costTotalUsd.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }

        List<ModelReliabilityDto> rows = new ArrayList<>();
        for (Map.Entry<String, ReliabilityAccumulator> entry : byModel.entrySet()) {
            ReliabilityAccumulator accumulator = entry.getValue();
            if (accumulator.gamesPlayed < minGames) {
                continue;
            }

            double completionRate = accumulator.gamesPlayed == 0
                    ? 0
                    : (double) accumulator.gamesCompleted / (double) accumulator.gamesPlayed;
            double forfeitRate = accumulator.gamesPlayed == 0
                    ? 0
                    : (double) accumulator.forfeitCount / (double) accumulator.gamesPlayed;
            double timeoutForfeitRate = accumulator.gamesPlayed == 0
                    ? 0
                    : (double) accumulator.timeoutForfeitCount / (double) accumulator.gamesPlayed;
            double avgRetriesPerMove = accumulator.movesSampled == 0
                    ? 0
                    : (double) accumulator.retriesTotal / (double) accumulator.movesSampled;
            Long avgResponseTimeMs = accumulator.responseTimeCount == 0
                    ? null
                    : Math.round((double) accumulator.responseTimeTotal / (double) accumulator.responseTimeCount);
            BigDecimal avgCostPerMove = divide(accumulator.costTotalUsd, accumulator.pricedMoves);
            AnalyticsReliabilityScorer.ReliabilityScore score = reliabilityScorer.score(
                    accumulator.gamesPlayed,
                    completionRate,
                    forfeitRate,
                    avgRetriesPerMove,
                    avgResponseTimeMs
            );

            rows.add(new ModelReliabilityDto(
                    entry.getKey(),
                    accumulator.gamesPlayed,
                    accumulator.gamesCompleted,
                    accumulator.forfeitCount,
                    decimal(forfeitRate, 4),
                    decimal(timeoutForfeitRate, 4),
                    decimal(avgRetriesPerMove, 4),
                    avgResponseTimeMs,
                    avgCostPerMove,
                    accumulator.movesSampled,
                    decimal(score.finalScore(), 2),
                    score.band(),
                    score.insufficientData()
            ));
        }

        rows.sort(
                Comparator.comparing(ModelReliabilityDto::finalScore).reversed()
                        .thenComparing(
                                Comparator.comparingLong(ModelReliabilityDto::gamesPlayed).reversed()
                        )
        );

        return new ModelReliabilityResponseDto(days, tournamentId, minGames, rows);
    }

    private AnalyticsModelComparisonDto buildComparison(int days, UUID tournamentId, int minGames) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Game> games = tournamentId == null
                ? gameRepository.findCreatedAfter(cutoff)
                : gameRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);

        List<Game> terminalGames = games.stream()
                .filter(game -> isTerminal(game.status))
                .toList();

        Map<UUID, Game> terminalGamesById = terminalGames.stream()
                .collect(java.util.stream.Collectors.toMap(game -> game.id, game -> game));

        List<Move> moves = (tournamentId == null
                ? moveRepository.findCreatedAfter(cutoff)
                : moveRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff))
                .stream()
                .filter(move -> move.game != null && move.game.id != null && terminalGamesById.containsKey(move.game.id))
                .toList();

        Map<String, ComparisonAccumulator> byModel = new HashMap<>();
        for (Game game : terminalGames) {
            registerComparisonGame(byModel, game.whiteModelId, game, true);
            registerComparisonGame(byModel, game.blackModelId, game, false);
        }

        for (Move move : moves) {
            String modelId = normalizeModelId(move.modelId);
            ComparisonAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ComparisonAccumulator());
            accumulator.movesSampled++;
            accumulator.retriesTotal += Math.max(move.retryCount, 0);
            if (move.responseTimeMs != null) {
                accumulator.responseTimeTotal += move.responseTimeMs;
                accumulator.responseTimeCount++;
            }
            if (move.costUsd != null) {
                accumulator.totalCostUsd = accumulator.totalCostUsd.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }

        List<AnalyticsModelComparisonRowDto> rows = new ArrayList<>();
        for (Map.Entry<String, ComparisonAccumulator> entry : byModel.entrySet()) {
            ComparisonAccumulator accumulator = entry.getValue();
            if (accumulator.gamesPlayed < minGames) {
                continue;
            }

            long losses = Math.max(0, accumulator.gamesPlayed - accumulator.wins - accumulator.draws);
            Long averageResponseTimeMs = accumulator.responseTimeCount == 0
                    ? null
                    : Math.round((double) accumulator.responseTimeTotal / (double) accumulator.responseTimeCount);
            BigDecimal averageRetriesPerMove = divide(accumulator.retriesTotal, accumulator.movesSampled);
            BigDecimal winRate = divide(accumulator.wins, accumulator.gamesPlayed);
            BigDecimal drawRate = divide(accumulator.draws, accumulator.gamesPlayed);
            BigDecimal lossRate = divide(losses, accumulator.gamesPlayed);
            BigDecimal whiteWinRate = divide(accumulator.whiteWins, accumulator.whiteGames);
            BigDecimal blackWinRate = divide(accumulator.blackWins, accumulator.blackGames);

            BigDecimal forfeitRate = divide(accumulator.forfeits, accumulator.gamesPlayed);
            BigDecimal timeoutForfeitRate = divide(accumulator.timeoutForfeits, accumulator.gamesPlayed);
            BigDecimal averageCostPerMoveUsd = pricingAvailable(accumulator)
                    ? divide(accumulator.totalCostUsd, accumulator.pricedMoves)
                    : null;
            BigDecimal totalCostUsd = pricingAvailable(accumulator)
                    ? scale(accumulator.totalCostUsd, 6)
                    : null;
            BigDecimal costPerWinUsd = pricingAvailable(accumulator) && accumulator.wins > 0
                    ? divide(accumulator.totalCostUsd, accumulator.wins)
                    : null;

            double completionRate = accumulator.gamesPlayed == 0
                    ? 0
                    : (double) accumulator.gamesCompleted / (double) accumulator.gamesPlayed;
            double forfeitRateDouble = forfeitRate.doubleValue();
            double avgRetries = averageRetriesPerMove.doubleValue();
            AnalyticsReliabilityScorer.ReliabilityScore score = reliabilityScorer.score(
                    accumulator.gamesPlayed,
                    completionRate,
                    forfeitRateDouble,
                    avgRetries,
                    averageResponseTimeMs
            );

            rows.add(new AnalyticsModelComparisonRowDto(
                    entry.getKey(),
                    accumulator.gamesPlayed,
                    accumulator.wins,
                    accumulator.draws,
                    losses,
                    accumulator.forfeits,
                    accumulator.timeoutForfeits,
                    winRate,
                    drawRate,
                    lossRate,
                    accumulator.whiteGames,
                    accumulator.whiteWins,
                    whiteWinRate,
                    accumulator.blackGames,
                    accumulator.blackWins,
                    blackWinRate,
                    accumulator.movesSampled,
                    averageResponseTimeMs,
                    averageRetriesPerMove,
                    totalCostUsd,
                    averageCostPerMoveUsd,
                    costPerWinUsd,
                    pricingAvailable(accumulator),
                    decimal(score.finalScore(), 2),
                    score.band(),
                    score.insufficientData()
            ));
        }

        rows.sort(
                Comparator.comparing(AnalyticsModelComparisonRowDto::winRate).reversed()
                        .thenComparing(Comparator.comparingLong(AnalyticsModelComparisonRowDto::wins).reversed())
                        .thenComparing(Comparator.comparingLong(AnalyticsModelComparisonRowDto::gamesPlayed).reversed())
        );

        return new AnalyticsModelComparisonDto(days, tournamentId, minGames, terminalGames.size(), rows);
    }

    private void registerGame(Map<String, ReliabilityAccumulator> byModel, String modelId, Game game, boolean isWhite) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }

        ReliabilityAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ReliabilityAccumulator());
        accumulator.gamesPlayed++;

        if (isTerminal(game.status)) {
            accumulator.gamesCompleted++;
        }

        if (isForfeitForSide(game, isWhite)) {
            accumulator.forfeitCount++;
            if (game.resultReason == Game.ResultReason.TIMEOUT) {
                accumulator.timeoutForfeitCount++;
            }
        }
    }

    private void registerComparisonGame(
            Map<String, ComparisonAccumulator> byModel,
            String modelId,
            Game game,
            boolean isWhite
    ) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }

        ComparisonAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ComparisonAccumulator());
        accumulator.gamesPlayed++;
        accumulator.gamesCompleted++;

        if (isWhite) {
            accumulator.whiteGames++;
        } else {
            accumulator.blackGames++;
        }

        if (game.result == null) {
            return;
        }

        switch (game.result) {
            case WHITE_WINS -> {
                if (isWhite) {
                    accumulator.wins++;
                    accumulator.whiteWins++;
                } else {
                    accumulator.losses++;
                }
            }
            case BLACK_WINS -> {
                if (isWhite) {
                    accumulator.losses++;
                } else {
                    accumulator.wins++;
                    accumulator.blackWins++;
                }
            }
            case DRAW -> accumulator.draws++;
            case WHITE_FORFEIT -> {
                if (isWhite) {
                    accumulator.losses++;
                    accumulator.forfeits++;
                    if (game.resultReason == Game.ResultReason.TIMEOUT) {
                        accumulator.timeoutForfeits++;
                    }
                } else {
                    accumulator.wins++;
                    accumulator.blackWins++;
                }
            }
            case BLACK_FORFEIT -> {
                if (isWhite) {
                    accumulator.wins++;
                    accumulator.whiteWins++;
                } else {
                    accumulator.losses++;
                    accumulator.forfeits++;
                    if (game.resultReason == Game.ResultReason.TIMEOUT) {
                        accumulator.timeoutForfeits++;
                    }
                }
            }
        }
    }

    private boolean isForfeitForSide(Game game, boolean isWhite) {
        if (game.result == null) {
            return false;
        }
        if (isWhite) {
            return game.result == GameResult.WHITE_FORFEIT;
        }
        return game.result == GameResult.BLACK_FORFEIT;
    }

    private boolean isTerminal(GameStatus status) {
        return status == GameStatus.COMPLETED || status == GameStatus.FORFEIT;
    }

    private boolean isActiveGame(Game game) {
        return game.status == GameStatus.IN_PROGRESS || game.status == GameStatus.PAUSED;
    }

    private Long percentile95(List<Long> sortedValues) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(sortedValues.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private int normalizeDays(int requestedDays) {
        if (requestedDays <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(requestedDays, MAX_DAYS);
    }

    private int normalizeMinGames(int requestedMinGames) {
        return Math.max(0, requestedMinGames);
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "unknown";
        }
        return modelId.trim();
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private boolean pricingAvailable(ComparisonAccumulator accumulator) {
        return accumulator.movesSampled > 0 && accumulator.pricedMoves == accumulator.movesSampled;
    }

    private BigDecimal divide(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal divide(BigDecimal numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return nonNull(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return nonNull(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private <T> T getCached(Map<String, CacheEntry<T>> cache, String key, Supplier<T> supplier) {
        long now = System.currentTimeMillis();
        CacheEntry<T> cached = cache.get(key);
        if (cached != null && cached.expiresAtMs > now) {
            return cached.value;
        }

        T value = supplier.get();
        cache.put(key, new CacheEntry<>(value, now + CACHE_TTL_MS));
        return value;
    }

    private static final class CacheEntry<T> {
        private final T value;
        private final long expiresAtMs;

        private CacheEntry(T value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class CostAccumulator {
        private BigDecimal totalCost = BigDecimal.ZERO;
        private long moveCount;
        private long pricedMoves;
    }

    private static final class HealthAccumulator {
        private long movesCount;
        private long retriesTotal;
        private long responseTimeCount;
        private long responseTimeTotal;
        private long promptTokensTotal;
        private long completionTokensTotal;
        private BigDecimal totalCostUsd = BigDecimal.ZERO;
        private long pricedMoves;
    }

    private static final class ComparisonAccumulator {
        private long gamesPlayed;
        private long gamesCompleted;
        private long wins;
        private long draws;
        private long losses;
        private long forfeits;
        private long timeoutForfeits;
        private long whiteGames;
        private long whiteWins;
        private long blackGames;
        private long blackWins;
        private long movesSampled;
        private long retriesTotal;
        private long responseTimeCount;
        private long responseTimeTotal;
        private BigDecimal totalCostUsd = BigDecimal.ZERO;
        private long pricedMoves;
    }

    private static final class ReliabilityAccumulator {
        private long gamesPlayed;
        private long gamesCompleted;
        private long forfeitCount;
        private long timeoutForfeitCount;

        private long movesSampled;
        private long retriesTotal;
        private long responseTimeCount;
        private long responseTimeTotal;
        private BigDecimal costTotalUsd = BigDecimal.ZERO;
        private long pricedMoves;
    }
}
