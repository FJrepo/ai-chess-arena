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
import dev.aichessarena.dto.TalkativenessDto;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository.PlyMessage;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.RejectedAttemptCost;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.MoveQualityClassifier.MoveQuality;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    @Inject
    AnalyticsWindowLoader windowLoader;

    @Inject
    AnalyticsGameOutcomeClassifier outcomeClassifier;

    private final AnalyticsReliabilityScorer reliabilityScorer = new AnalyticsReliabilityScorer();

    private final Map<String, CacheEntry<AnalyticsHealthDto>> healthCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<ModelReliabilityResponseDto>> reliabilityCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<AnalyticsModelComparisonDto>> comparisonCache = new ConcurrentHashMap<>();

    public TournamentCostSummaryDto getTournamentCostSummary(UUID tournamentId) {
        List<Game> games = gameRepository.findByTournamentId(tournamentId);
        List<Move> moves = moveRepository.findByTournamentId(tournamentId);
        Map<String, BigDecimal> rejectedCostByModel =
                rejectedCostByModel(moveAttemptRepository.findRejectedCostsByTournamentId(tournamentId));

        BigDecimal totalCost = games.stream()
                .map(game -> nonNull(game.totalCostUsd))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Byes and bracket slots nobody reached cost nothing and are not games anyone played.
        long playedGames = games.stream().filter(game -> !AnalyticsGameOutcomeClassifier.isBye(game)).count();
        BigDecimal averageCostPerGame = divide(totalCost, playedGames);

        BigDecimal totalMoveCost = moves.stream()
                .map(move -> move.costUsd)
                .filter(Objects::nonNull)
                .reduce(totalOf(rejectedCostByModel), BigDecimal::add);
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
            if (!hasTrackedModelId(move.modelId)) {
                continue;
            }
            String modelId = normalizeModelId(move.modelId);
            CostAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new CostAccumulator());
            accumulator.moveCount++;
            if (move.costUsd != null) {
                accumulator.totalCost = accumulator.totalCost.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }
        rejectedCostByModel.forEach((modelId, cost) -> {
            CostAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new CostAccumulator());
            accumulator.totalCost = accumulator.totalCost.add(cost);
        });

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

        AnalyticsReliabilityScorer.ReliabilityScore score = reliabilityScorer.score(
                model.gamesPlayed(),
                model.movesSampled(),
                model.firstTryLegalRate().doubleValue(),
                model.forfeitRate().doubleValue(),
                model.averageRetriesPerMove().doubleValue()
        );

        return new ModelReliabilityDetailDto(
                days,
                tournamentId,
                model,
                decimal(score.firstTryLegalScore(), 2),
                decimal(score.forfeitScore(), 2),
                decimal(score.retryScore(), 2),
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
        AnalyticsWindowLoader.AnalyticsScope scope = windowLoader.loadScope(days, tournamentId);
        List<Game> games = scope.games();
        List<Move> moves = scope.moves();

        long activeGamesCount = games.stream().filter(outcomeClassifier::isActive).count();
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

        Map<String, BigDecimal> rejectedCostByModel = rejectedCostByModel(scope.rejectedAttemptCosts());
        BigDecimal costTotalUsd = moves.stream()
                .map(move -> move.costUsd)
                .filter(Objects::nonNull)
                .reduce(totalOf(rejectedCostByModel), BigDecimal::add);

        Map<String, HealthAccumulator> byModel = new HashMap<>();
        for (Move move : moves) {
            if (!hasTrackedModelId(move.modelId)) {
                continue;
            }
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
        rejectedCostByModel.forEach((modelId, cost) -> {
            HealthAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new HealthAccumulator());
            accumulator.totalCostUsd = accumulator.totalCostUsd.add(cost);
        });

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

    /** Based on finished games only, like the comparison, so both views score a model the same way. */
    private ModelReliabilityResponseDto buildReliability(int days, UUID tournamentId, int minGames) {
        AnalyticsWindowLoader.AnalyticsScope scope = windowLoader.loadTerminalScope(days, tournamentId);
        List<Game> games = scope.games();
        List<Move> moves = scope.moves();

        Map<String, ReliabilityAccumulator> byModel = new HashMap<>();

        for (Game game : games) {
            registerGame(byModel, game.whiteModelId, game, true);
            registerGame(byModel, game.blackModelId, game, false);
        }

        for (Move move : moves) {
            if (!hasTrackedModelId(move.modelId)) {
                continue;
            }
            String modelId = normalizeModelId(move.modelId);
            ReliabilityAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ReliabilityAccumulator());
            accumulator.movesSampled++;
            accumulator.retriesTotal += Math.max(move.retryCount, 0);
            if (move.retryCount <= 0) {
                accumulator.firstTryMoves++;
            }

            if (move.responseTimeMs != null) {
                accumulator.responseTimeTotal += move.responseTimeMs;
                accumulator.responseTimeCount++;
            }

            if (move.costUsd != null) {
                accumulator.costTotalUsd = accumulator.costTotalUsd.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }
        rejectedCostByModel(scope.rejectedAttemptCosts()).forEach((modelId, cost) -> {
            ReliabilityAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ReliabilityAccumulator());
            accumulator.costTotalUsd = accumulator.costTotalUsd.add(cost);
        });

        List<ModelReliabilityDto> rows = new ArrayList<>();
        for (Map.Entry<String, ReliabilityAccumulator> entry : byModel.entrySet()) {
            ReliabilityAccumulator accumulator = entry.getValue();
            if (accumulator.gamesPlayed < minGames) {
                continue;
            }

            double firstTryLegalRate = AnalyticsReliabilityScorer.firstTryLegalRate(
                    accumulator.firstTryMoves,
                    accumulator.movesSampled,
                    accumulator.invalidMoveForfeitCount
            );
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
                    accumulator.movesSampled,
                    firstTryLegalRate,
                    forfeitRate,
                    avgRetriesPerMove
            );

            rows.add(new ModelReliabilityDto(
                    entry.getKey(),
                    accumulator.gamesPlayed,
                    accumulator.gamesCompleted,
                    accumulator.forfeitCount,
                    decimal(forfeitRate, 4),
                    decimal(timeoutForfeitRate, 4),
                    decimal(avgRetriesPerMove, 4),
                    decimal(firstTryLegalRate, 4),
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
        AnalyticsWindowLoader.AnalyticsScope scope = windowLoader.loadTerminalScope(days, tournamentId);
        List<Game> terminalGames = scope.games();
        List<Move> moves = scope.moves();

        Map<String, ComparisonAccumulator> byModel = new HashMap<>();
        for (Game game : terminalGames) {
            registerComparisonGame(byModel, game.whiteModelId, game, true);
            registerComparisonGame(byModel, game.blackModelId, game, false);
        }

        for (Move move : moves) {
            if (!hasTrackedModelId(move.modelId)) {
                continue;
            }
            String modelId = normalizeModelId(move.modelId);
            ComparisonAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ComparisonAccumulator());
            accumulator.movesSampled++;
            accumulator.retriesTotal += Math.max(move.retryCount, 0);
            if (move.retryCount <= 0) {
                accumulator.firstTryMoves++;
            }
            if (move.responseTimeMs != null) {
                accumulator.responseTimeTotal += move.responseTimeMs;
                accumulator.responseTimeCount++;
            }
            if (move.costUsd != null) {
                accumulator.totalCostUsd = accumulator.totalCostUsd.add(move.costUsd);
                accumulator.pricedMoves++;
            }
        }
        rejectedCostByModel(scope.rejectedAttemptCosts()).forEach((modelId, cost) -> {
            ComparisonAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ComparisonAccumulator());
            accumulator.totalCostUsd = accumulator.totalCostUsd.add(cost);
            accumulator.rejectedAttemptsCosted = true;
        });
        registerMoveQuality(byModel, moves);
        registerTalkativeness(byModel, moves, scope.messages());
        registerResignations(byModel, terminalGames, moves);

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
            // The total is known even without moves (e.g. only rejected answers, then a forfeit); the average per
            // move needs at least one move.
            BigDecimal averageCostPerMoveUsd = pricingAvailable(accumulator) && accumulator.movesSampled > 0
                    ? divide(accumulator.totalCostUsd, accumulator.pricedMoves)
                    : null;
            BigDecimal totalCostUsd = pricingAvailable(accumulator)
                    ? scale(accumulator.totalCostUsd, 6)
                    : null;
            BigDecimal costPerWinUsd = pricingAvailable(accumulator) && accumulator.wins > 0
                    ? divide(accumulator.totalCostUsd, accumulator.wins)
                    : null;

            double firstTryLegalRate = AnalyticsReliabilityScorer.firstTryLegalRate(
                    accumulator.firstTryMoves,
                    accumulator.movesSampled,
                    accumulator.invalidMoveForfeits
            );
            AnalyticsReliabilityScorer.ReliabilityScore score = reliabilityScorer.score(
                    accumulator.gamesPlayed,
                    accumulator.movesSampled,
                    firstTryLegalRate,
                    forfeitRate.doubleValue(),
                    averageRetriesPerMove.doubleValue()
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
                    decimal(firstTryLegalRate, 4),
                    totalCostUsd,
                    averageCostPerMoveUsd,
                    costPerWinUsd,
                    pricingAvailable(accumulator),
                    decimal(score.finalScore(), 2),
                    score.band(),
                    score.insufficientData(),
                    accumulator.analyzedMoves,
                    accumulator.analyzedGameIds.size(),
                    accumulator.analyzedMoves == 0
                            ? null
                            : decimal((double) accumulator.centipawnLossTotal / accumulator.analyzedMoves, 1),
                    accumulator.inaccuracies,
                    accumulator.mistakes,
                    accumulator.blunders,
                    accumulator.analyzedGameIds.isEmpty()
                            ? null
                            : decimal((double) accumulator.blunders / accumulator.analyzedGameIds.size(), 2),
                    talkativeness(accumulator.optionalChat),
                    talkativeness(accumulator.mandatoryChat),
                    accumulator.resignations,
                    accumulator.resignationsEvaluated,
                    accumulator.resignationsWhileNotLosing,
                    accumulator.wonByResignation
            ));
        }

        rows.sort(
                Comparator.comparing(AnalyticsModelComparisonRowDto::winRate).reversed()
                        .thenComparing(Comparator.comparingLong(AnalyticsModelComparisonRowDto::wins).reversed())
                        .thenComparing(Comparator.comparingLong(AnalyticsModelComparisonRowDto::gamesPlayed).reversed())
        );

        return new AnalyticsModelComparisonDto(days, tournamentId, minGames, terminalGames.size(), rows);
    }

    /** Counts a finished, non-bye game; unfinished games are not games played yet and are ignored. */
    private void registerGame(Map<String, ReliabilityAccumulator> byModel, String modelId, Game game, boolean isWhite) {
        if (modelId == null || modelId.isBlank() || !countsAsPlayed(game)) {
            return;
        }

        ReliabilityAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ReliabilityAccumulator());
        accumulator.gamesPlayed++;

        AnalyticsGameOutcomeClassifier.ReliabilityOutcome outcome = outcomeClassifier.classifyReliability(game, isWhite);
        if (outcome.completed()) {
            accumulator.gamesCompleted++;
        }

        if (outcome.forfeited()) {
            accumulator.forfeitCount++;
            if (outcome.timeoutForfeit()) {
                accumulator.timeoutForfeitCount++;
            }
            if (outcome.invalidMoveForfeit()) {
                accumulator.invalidMoveForfeitCount++;
            }
        }
    }

    private static boolean countsAsPlayed(Game game) {
        return AnalyticsGameOutcomeClassifier.isTerminal(game.status) && !AnalyticsGameOutcomeClassifier.isBye(game);
    }

    private void registerComparisonGame(
            Map<String, ComparisonAccumulator> byModel,
            String modelId,
            Game game,
            boolean isWhite
    ) {
        if (modelId == null || modelId.isBlank() || !countsAsPlayed(game)) {
            return;
        }

        ComparisonAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ComparisonAccumulator());
        accumulator.gamesPlayed++;
        AnalyticsGameOutcomeClassifier.ComparisonOutcome outcome = outcomeClassifier.classifyComparison(game, isWhite);

        if (outcomeClassifier.isInvalidMoveForfeit(game, isWhite)) {
            accumulator.invalidMoveForfeits++;
        }
        if (outcome.whiteGame()) {
            accumulator.whiteGames++;
        }
        if (outcome.blackGame()) {
            accumulator.blackGames++;
        }
        if (outcome.win()) {
            accumulator.wins++;
        }
        if (outcome.draw()) {
            accumulator.draws++;
        }
        if (outcome.loss()) {
            accumulator.losses++;
        }
        if (outcome.forfeit()) {
            accumulator.forfeits++;
        }
        if (outcome.timeoutForfeit()) {
            accumulator.timeoutForfeits++;
        }
        if (outcome.whiteWin()) {
            accumulator.whiteWins++;
        }
        if (outcome.blackWin()) {
            accumulator.blackWins++;
        }
    }

    /**
     * Adds each model's judged moves (see {@link MoveQualityClassifier}). Quality depends on the move before, so
     * every game's moves are put in play order first; only moves with a model id count (not humans or overrides).
     */
    private void registerMoveQuality(Map<String, ComparisonAccumulator> byModel, List<Move> moves) {
        Map<UUID, List<Move>> movesByGame = new HashMap<>();
        for (Move move : moves) {
            movesByGame.computeIfAbsent(move.game.id, ignored -> new ArrayList<>()).add(move);
        }
        movesByGame.forEach((gameId, gameMoves) -> {
            gameMoves.sort(MoveQualityClassifier.PLAY_ORDER);
            List<MoveQuality> qualities = MoveQualityClassifier.analyze(gameMoves);
            for (int i = 0; i < gameMoves.size(); i++) {
                Move move = gameMoves.get(i);
                MoveQuality quality = qualities.get(i);
                if (!MoveQualityClassifier.counts(quality) || !hasTrackedModelId(move.modelId)) {
                    continue;
                }
                ComparisonAccumulator accumulator =
                        byModel.computeIfAbsent(normalizeModelId(move.modelId), ignored -> new ComparisonAccumulator());
                accumulator.analyzedMoves++;
                accumulator.analyzedGameIds.add(gameId);
                accumulator.centipawnLossTotal += quality.centipawnLoss();
                if (quality.judgement() == MoveQualityClassifier.Judgement.INACCURACY) {
                    accumulator.inaccuracies++;
                } else if (quality.judgement() == MoveQualityClassifier.Judgement.MISTAKE) {
                    accumulator.mistakes++;
                } else if (quality.judgement() == MoveQualityClassifier.Judgement.BLUNDER) {
                    accumulator.blunders++;
                }
            }
        });
    }

    /**
     * Counts each model move per chat mode that applied to it ({@link Move#chatMode}) and whether a non-empty chat
     * message was stored for the same ply (game, move number, colour). Moves without a mode (humans, overrides) and
     * moves made with chat OFF are left out. Databases from before migration V8 may hold several moves or messages
     * for one ply; each ply counts once, with its first stored move and first non-empty message (the duplicates are
     * the later rows, see docs/deployment.md).
     */
    private void registerTalkativeness(Map<String, ComparisonAccumulator> byModel, List<Move> moves,
                                       List<PlyMessage> messages) {
        Map<PlyKey, Integer> messageLengths = new HashMap<>();
        for (PlyMessage message : messages) {
            if (message.length() > 0) {
                messageLengths.putIfAbsent(new PlyKey(message.gameId(), message.moveNumber(), message.color()),
                        message.length());
            }
        }
        Map<PlyKey, Move> firstMovePerPly = new HashMap<>();
        for (Move move : moves) {
            if (move.game == null) {
                continue;
            }
            firstMovePerPly.merge(new PlyKey(move.game.id, move.moveNumber, move.color), move,
                    (kept, other) -> FIRST_STORED.compare(other, kept) < 0 ? other : kept);
        }
        for (Map.Entry<PlyKey, Move> ply : firstMovePerPly.entrySet()) {
            Move move = ply.getValue();
            if (move.chatMode == null || !move.chatMode.allowsMessages() || move.isOverride
                    || !hasTrackedModelId(move.modelId)) {
                continue;
            }
            ComparisonAccumulator accumulator =
                    byModel.computeIfAbsent(normalizeModelId(move.modelId), ignored -> new ComparisonAccumulator());
            TalkAccumulator talk = move.chatMode == ChatMode.MANDATORY
                    ? accumulator.mandatoryChat
                    : accumulator.optionalChat;
            talk.moves++;
            Integer length = messageLengths.get(ply.getKey());
            if (length != null) {
                talk.messages++;
                talk.messageLengthTotal += length;
            }
        }
    }

    /**
     * Counts resignations in finished, non-bye games (see {@link ResignationAnalysis}): for the model that resigned,
     * the resignation, whether the position it resigned in was evaluated and whether its winning chance there was at
     * least 35% ("resigned while not losing"); for the model on the other side, a win by resignation. Human sides
     * are skipped.
     */
    private void registerResignations(Map<String, ComparisonAccumulator> byModel, List<Game> games, List<Move> moves) {
        Map<UUID, List<Move>> movesByGame = new HashMap<>();
        for (Move move : moves) {
            if (move.game != null) {
                movesByGame.computeIfAbsent(move.game.id, ignored -> new ArrayList<>()).add(move);
            }
        }
        for (Game game : games) {
            String color = ResignationAnalysis.resigningColor(game);
            if (color == null || !countsAsPlayed(game)) {
                continue;
            }
            boolean whiteResigned = "WHITE".equals(color);
            String resignedModel = modelSide(game, whiteResigned);
            if (resignedModel != null) {
                List<Move> gameMoves = new ArrayList<>(movesByGame.getOrDefault(game.id, List.of()));
                gameMoves.sort(MoveQualityClassifier.PLAY_ORDER);
                Double winChance = ResignationAnalysis.winChanceAtResignation(gameMoves, color);
                ComparisonAccumulator accumulator =
                        byModel.computeIfAbsent(resignedModel, ignored -> new ComparisonAccumulator());
                accumulator.resignations++;
                if (winChance != null) {
                    accumulator.resignationsEvaluated++;
                }
                if (ResignationAnalysis.notLosing(winChance)) {
                    accumulator.resignationsWhileNotLosing++;
                }
            }
            String winnerModel = modelSide(game, !whiteResigned);
            if (winnerModel != null) {
                byModel.computeIfAbsent(winnerModel, ignored -> new ComparisonAccumulator()).wonByResignation++;
            }
        }
    }

    /** The model id of a side played by a model; null for a human side or a side without a model. */
    private String modelSide(Game game, boolean white) {
        var participant = white ? game.whiteParticipant : game.blackParticipant;
        if (participant != null && participant.controlType == TournamentParticipant.ControlType.HUMAN) {
            return null;
        }
        String modelId = white ? game.whiteModelId : game.blackModelId;
        return hasTrackedModelId(modelId) ? normalizeModelId(modelId) : null;
    }

    /** Earliest stored first; the id only breaks ties so the choice does not depend on load order. */
    private static final Comparator<Move> FIRST_STORED = Comparator
            .comparing((Move move) -> move.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(move -> move.id, Comparator.nullsLast(Comparator.naturalOrder()));

    private TalkativenessDto talkativeness(TalkAccumulator talk) {
        return new TalkativenessDto(
                talk.moves,
                talk.messages,
                talk.moves == 0 ? null : decimal((double) talk.messages / talk.moves, 4),
                talk.messages == 0 ? null : decimal((double) talk.messageLengthTotal / talk.messages, 1)
        );
    }

    private record PlyKey(UUID gameId, int moveNumber, String color) {
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

    private boolean hasTrackedModelId(String modelId) {
        return modelId != null && !modelId.isBlank();
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Cost of rejected attempts per model. Accepted attempts are never included (their cost is already on the
     * move), so adding this to the move cost counts every paid answer exactly once.
     */
    private Map<String, BigDecimal> rejectedCostByModel(List<RejectedAttemptCost> costs) {
        Map<String, BigDecimal> byModel = new HashMap<>();
        for (RejectedAttemptCost cost : costs) {
            if (!hasTrackedModelId(cost.modelId()) || cost.costUsd() == null) {
                continue;
            }
            byModel.merge(normalizeModelId(cost.modelId()), cost.costUsd(), BigDecimal::add);
        }
        return byModel;
    }

    private BigDecimal totalOf(Map<String, BigDecimal> costs) {
        return costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Whether the model's total cost is known: every move is priced and there is at least one priced answer
     * (a move, or a rejected attempt with a reported cost).
     */
    private boolean pricingAvailable(ComparisonAccumulator accumulator) {
        boolean anyCost = accumulator.movesSampled > 0 || accumulator.rejectedAttemptsCosted;
        return anyCost && accumulator.pricedMoves == accumulator.movesSampled;
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
        private long invalidMoveForfeits;
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
        private long firstTryMoves;
        private long retriesTotal;
        private long responseTimeCount;
        private long responseTimeTotal;
        private BigDecimal totalCostUsd = BigDecimal.ZERO;
        private long pricedMoves;
        private boolean rejectedAttemptsCosted;
        private long analyzedMoves;
        private final Set<UUID> analyzedGameIds = new HashSet<>();
        private long centipawnLossTotal;
        private long inaccuracies;
        private long mistakes;
        private long blunders;
        private final TalkAccumulator optionalChat = new TalkAccumulator();
        private final TalkAccumulator mandatoryChat = new TalkAccumulator();
        private long resignations;
        private long resignationsEvaluated;
        private long resignationsWhileNotLosing;
        private long wonByResignation;
    }

    private static final class TalkAccumulator {
        private long moves;
        private long messages;
        private long messageLengthTotal;
    }

    private static final class ReliabilityAccumulator {
        private long gamesPlayed;
        private long gamesCompleted;
        private long forfeitCount;
        private long timeoutForfeitCount;
        private long invalidMoveForfeitCount;

        private long movesSampled;
        private long firstTryMoves;
        private long retriesTotal;
        private long responseTimeCount;
        private long responseTimeTotal;
        private BigDecimal costTotalUsd = BigDecimal.ZERO;
        private long pricedMoves;
    }
}
