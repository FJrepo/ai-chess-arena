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
import dev.aichessarena.dto.PromptCachingDto;
import dev.aichessarena.dto.ConversionDto;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository.PlyMessage;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.AttemptUsage;
import dev.aichessarena.repository.MoveAttemptRepository.RejectedAttemptCost;
import dev.aichessarena.repository.MoveAttemptRepository.ResignationTurnAttempt;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.MoveQualityClassifier.MoveQuality;
import dev.aichessarena.service.ReasoningAnalytics.Side;
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
import java.util.function.Function;
import java.util.function.Predicate;

@ApplicationScoped
public class AnalyticsService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 3650;

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

    @Inject
    OpenRouterService openRouterService;

    private final AnalyticsReliabilityScorer reliabilityScorer = new AnalyticsReliabilityScorer();

    private final AnalyticsResponseCache<AnalyticsHealthDto> healthCache = new AnalyticsResponseCache<>();
    private final AnalyticsResponseCache<ModelReliabilityResponseDto> reliabilityCache = new AnalyticsResponseCache<>();
    private final AnalyticsResponseCache<AnalyticsModelComparisonDto> comparisonCache = new AnalyticsResponseCache<>();

    public TournamentCostSummaryDto getTournamentCostSummary(UUID tournamentId) {
        List<Game> games = gameRepository.findByTournamentId(tournamentId);
        List<Move> moves = moveRepository.findByTournamentId(tournamentId).stream().filter(move -> !move.isBook).toList();
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
            if (move.isBook || !hasTrackedModelId(move.modelId)) {
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
        return getHealth(requestedDays, tournamentId, null);
    }

    public AnalyticsHealthDto getHealth(int requestedDays, UUID tournamentId, Boolean legalMoveHints) {
        int days = normalizeDays(requestedDays);
        String key = days + "|" + (tournamentId != null ? tournamentId : "all") + "|" + legalMoveHints;
        return healthCache.get(key, () -> buildHealth(days, tournamentId, legalMoveHints));
    }

    public ModelReliabilityResponseDto getReliability(int requestedDays, UUID tournamentId, int requestedMinGames) {
        return getReliability(requestedDays, tournamentId, requestedMinGames, null);
    }

    public ModelReliabilityResponseDto getReliability(int requestedDays, UUID tournamentId, int requestedMinGames,
                                                     Boolean legalMoveHints) {
        int days = normalizeDays(requestedDays);
        int minGames = normalizeMinGames(requestedMinGames);
        String key = days + "|" + (tournamentId != null ? tournamentId : "all") + "|" + minGames + "|" + legalMoveHints;
        return reliabilityCache.get(key, () -> buildReliability(days, tournamentId, minGames, legalMoveHints));
    }

    public ModelReliabilityDetailDto getReliabilityForModel(String modelId, int requestedDays, UUID tournamentId) {
        return getReliabilityForModel(modelId, requestedDays, tournamentId, null);
    }

    public ModelReliabilityDetailDto getReliabilityForModel(String modelId, int requestedDays, UUID tournamentId,
                                                           Boolean legalMoveHints) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }

        int days = normalizeDays(requestedDays);
        ModelReliabilityResponseDto response = getReliability(days, tournamentId, 0, legalMoveHints);
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
        return getComparison(requestedDays, tournamentId, requestedMinGames, null);
    }

    public AnalyticsModelComparisonDto getComparison(int requestedDays, UUID tournamentId, int requestedMinGames,
                                                    Boolean legalMoveHints) {
        return getComparison(requestedDays, tournamentId, requestedMinGames, legalMoveHints, null);
    }

    public AnalyticsModelComparisonDto getComparison(int requestedDays, UUID tournamentId, int requestedMinGames,
                                                    Boolean legalMoveHints, String requestedEffort) {
        String effort = ReasoningAnalytics.validateFilter(requestedEffort);
        int days = normalizeDays(requestedDays);
        int minGames = normalizeMinGames(requestedMinGames);
        String key = days + "|" + (tournamentId != null ? tournamentId : "all") + "|" + minGames + "|"
                + legalMoveHints + "|" + effort;
        return comparisonCache.get(key, () -> buildComparison(days, tournamentId, minGames, legalMoveHints, effort));
    }

    private AnalyticsHealthDto buildHealth(int days, UUID tournamentId, Boolean legalMoveHints) {
        AnalyticsWindowLoader.AnalyticsScope scope = windowLoader.loadHealthScope(days, tournamentId, legalMoveHints);
        List<Game> games = scope.games();
        List<Move> moves = scope.moves().stream().filter(move -> !move.isBook).toList();

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
            if (move.isBook || !hasTrackedModelId(move.modelId)) {
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
    private ModelReliabilityResponseDto buildReliability(int days, UUID tournamentId, int minGames,
                                                          Boolean legalMoveHints) {
        AnalyticsWindowLoader.AnalyticsScope scope = windowLoader.loadTerminalScope(days, tournamentId, legalMoveHints);
        List<Game> games = scope.games();
        List<Move> plies = CanonicalPlies.of(scope.moves());

        Map<String, ReliabilityAccumulator> byModel = new HashMap<>();

        for (Game game : games) {
            registerGame(byModel, game.whiteModelId, game, true);
            registerGame(byModel, game.blackModelId, game, false);
        }

        registerModelMoves(scope, plies, id -> byModel.computeIfAbsent(id, ignored -> new ReliabilityAccumulator()), side -> true);
        resignedTurnFailures(scope.resignationAttempts()).forEach((modelId, failures) -> {
            ReliabilityAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ReliabilityAccumulator());
            accumulator.resignedTurnsWithFailures += failures.turns();
            accumulator.resignedTurnFailures += failures.failures();
        });

        List<ModelReliabilityDto> rows = new ArrayList<>();
        for (Map.Entry<String, ReliabilityAccumulator> entry : byModel.entrySet()) {
            ReliabilityAccumulator accumulator = entry.getValue();
            if (accumulator.gamesPlayed < minGames) {
                continue;
            }

            // Turns: moves plus resigned turns that needed retries (see resignedTurnFailures).
            long turns = accumulator.movesSampled + accumulator.resignedTurnsWithFailures;
            double firstTryLegalRate = AnalyticsReliabilityScorer.firstTryLegalRate(
                    accumulator.firstTryMoves,
                    turns,
                    accumulator.invalidMoveForfeitCount
            );
            double forfeitRate = accumulator.gamesPlayed == 0
                    ? 0
                    : (double) accumulator.forfeitCount / (double) accumulator.gamesPlayed;
            double timeoutForfeitRate = accumulator.gamesPlayed == 0
                    ? 0
                    : (double) accumulator.timeoutForfeitCount / (double) accumulator.gamesPlayed;
            double avgRetriesPerMove = turns == 0
                    ? 0
                    : (double) (accumulator.retriesTotal + accumulator.resignedTurnFailures) / (double) turns;
            Long avgResponseTimeMs = accumulator.responseTimeCount == 0
                    ? null
                    : Math.round((double) accumulator.responseTimeTotal / (double) accumulator.responseTimeCount);
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
                    averageCostPerPly(accumulator),
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

    private AnalyticsModelComparisonDto buildComparison(int days, UUID tournamentId, int minGames,
                                                        Boolean legalMoveHints, String effort) {
        AnalyticsWindowLoader.AnalyticsScope scope = windowLoader.loadTerminalScope(days, tournamentId, legalMoveHints);
        List<Game> terminalGames = scope.games();
        List<Move> plies = CanonicalPlies.of(scope.moves());

        Map<Side, ReasoningAnalytics.Sample> samples = ReasoningAnalytics.bySide(
                moveAttemptRepository.findReasoningAttempts(scope.cutoff(), tournamentId, legalMoveHints),
                plies.stream().map(ReasoningAnalytics::modelPly).toList());
        Map<String, ComparisonAccumulator> byModel = new HashMap<>();
        Set<Side> selectedSides = new HashSet<>();
        long gamesCount = 0;
        for (Game game : terminalGames) {
            boolean counted = false;
            for (boolean white : new boolean[] {true, false}) {
                String model = modelSide(game, white);
                Side side = Side.of(game.id, white ? "WHITE" : "BLACK", model);
                ReasoningAnalytics.Sample sample = samples.get(side);
                if (model == null || (effort != null && !effort.equals(ReasoningAnalytics.effortOf(sample)))) continue;
                selectedSides.add(side);
                registerComparisonGame(byModel, model, game, white);
                byModel.get(model).reasoning.include(sample);
                counted = true;
            }
            if (effort == null || counted) gamesCount++;
        }
        Predicate<Side> selected = effort == null ? side -> true : selectedSides::contains;

        List<Move> selectedPlies = plies.stream().filter(move -> selected.test(sideOf(move))).toList();
        registerModelMoves(scope, selectedPlies, id -> byModel.computeIfAbsent(id, ignored -> new ComparisonAccumulator()),
                selected);
        registerMoveQuality(byModel, plies, selected);
        registerConversion(byModel, terminalGames, plies, selected);
        registerTalkativeness(byModel, selectedPlies, scope.messages());
        registerResignations(byModel, terminalGames, plies, selected);
        for (AttemptUsage usage : scope.attemptUsage()) {
            if (hasTrackedModelId(usage.modelId()) && selected.test(Side.of(usage.gameId(), usage.color(), usage.modelId()))) {
                byModel.computeIfAbsent(normalizeModelId(usage.modelId()), ignored -> new ComparisonAccumulator())
                        .cacheUsage.add(usage);
            }
        }
        resignedTurnFailures(scope.resignationAttempts().stream()
                .filter(attempt -> selected.test(Side.of(attempt.gameId(), attempt.color(), attempt.modelId())))
                .toList()).forEach((modelId, failures) -> {
            ComparisonAccumulator accumulator = byModel.computeIfAbsent(modelId, ignored -> new ComparisonAccumulator());
            accumulator.resignedTurnsWithFailures += failures.turns();
            accumulator.resignedTurnFailures += failures.failures();
        });

        Map<String, BigDecimal> cacheReadSavings = openRouterService.cacheReadSavingsSnapshot();
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
            // Turns: moves plus resigned turns that needed retries (see resignedTurnFailures).
            long turns = accumulator.movesSampled + accumulator.resignedTurnsWithFailures;
            BigDecimal averageRetriesPerMove =
                    divide(accumulator.retriesTotal + accumulator.resignedTurnFailures, turns);
            BigDecimal winRate = divide(accumulator.wins, accumulator.gamesPlayed);
            BigDecimal drawRate = divide(accumulator.draws, accumulator.gamesPlayed);
            BigDecimal lossRate = divide(losses, accumulator.gamesPlayed);
            BigDecimal whiteWinRate = divide(accumulator.whiteWins, accumulator.whiteGames);
            BigDecimal blackWinRate = divide(accumulator.blackWins, accumulator.blackGames);

            BigDecimal forfeitRate = divide(accumulator.forfeits, accumulator.gamesPlayed);
            BigDecimal timeoutForfeitRate = divide(accumulator.timeoutForfeits, accumulator.gamesPlayed);
            BigDecimal totalCostUsd = pricingAvailable(accumulator)
                    ? scale(accumulator.totalCostUsd, 6)
                    : null;
            BigDecimal costPerWinUsd = pricingAvailable(accumulator) && accumulator.wins > 0
                    ? divide(accumulator.totalCostUsd, accumulator.wins)
                    : null;

            double firstTryLegalRate = AnalyticsReliabilityScorer.firstTryLegalRate(
                    accumulator.firstTryMoves,
                    turns,
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
                    averageCostPerPly(accumulator),
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
                    accumulator.wonByResignation,
                    promptCaching(entry.getKey(), accumulator.cacheUsage, cacheReadSavings),
                    new ConversionDto(accumulator.evaluatedGames,
                            accumulator.evaluatedGames == 0 ? null : accumulator.winningPositionsReached,
                            accumulator.evaluatedGames == 0 ? null : accumulator.converted,
                            accumulator.evaluatedGames == 0 ? null : accumulator.adjudicatedWins,
                            accumulator.evaluatedGames == 0 ? null : accumulator.thrownAway,
                            accumulator.analyzedMoves == 0 ? null : accumulator.missedMates),
                    accumulator.reasoning.toDto(effort)
            ));
        }

        rows.sort(
                Comparator.comparing(AnalyticsModelComparisonRowDto::winRate).reversed()
                        .thenComparing(Comparator.comparingLong(AnalyticsModelComparisonRowDto::wins).reversed())
                        .thenComparing(Comparator.comparingLong(AnalyticsModelComparisonRowDto::gamesPlayed).reversed())
        );

        return new AnalyticsModelComparisonDto(days, tournamentId, minGames, gamesCount, rows);
    }

    /** Score each played ply once, but count every priced answer in the selected games. */
    private void registerModelMoves(AnalyticsWindowLoader.AnalyticsScope scope, List<Move> plies,
                                    Function<String, ModelMoveAccumulator> forModel, Predicate<Side> selected) {
        for (Move move : plies) {
            if (move.isBook || !hasTrackedModelId(move.modelId)) continue;
            ModelMoveAccumulator accumulator = forModel.apply(normalizeModelId(move.modelId));
            accumulator.movesSampled++;
            accumulator.retriesTotal += Math.max(move.retryCount, 0);
            if (move.retryCount <= 0) accumulator.firstTryMoves++;
            if (move.costUsd != null) accumulator.pricedPlies++;
            if (move.responseTimeMs != null) {
                accumulator.responseTimeTotal += move.responseTimeMs;
                accumulator.responseTimeCount++;
            }
        }
        for (Move move : scope.moves()) {
            if (move.isBook || !hasTrackedModelId(move.modelId) || !selected.test(sideOf(move))) continue;
            ModelMoveAccumulator accumulator = forModel.apply(normalizeModelId(move.modelId));
            if (move.costUsd == null) {
                accumulator.missingMovePrice = true;
            } else {
                accumulator.totalCostUsd = accumulator.totalCostUsd.add(move.costUsd);
                accumulator.hasReportedCost = true;
            }
        }
        var selectedCosts = scope.rejectedAttemptCosts().stream()
                .filter(cost -> selected.test(Side.of(cost.gameId(), cost.color(), cost.modelId())))
                .toList();
        rejectedCostByModel(selectedCosts).forEach((modelId, cost) -> {
            ModelMoveAccumulator accumulator = forModel.apply(modelId);
            accumulator.totalCostUsd = accumulator.totalCostUsd.add(cost);
            accumulator.hasReportedCost = true;
        });
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
    private void registerMoveQuality(Map<String, ComparisonAccumulator> byModel, List<Move> moves,
                                     Predicate<Side> selected) {
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
                if (!MoveQualityClassifier.counts(quality) || !hasTrackedModelId(move.modelId)
                        || !selected.test(sideOf(move))) {
                    continue;
                }
                ComparisonAccumulator accumulator =
                        byModel.computeIfAbsent(normalizeModelId(move.modelId), ignored -> new ComparisonAccumulator());
                accumulator.analyzedMoves++;
                accumulator.analyzedGameIds.add(gameId);
                accumulator.centipawnLossTotal += quality.centipawnLoss();
                if (quality.missedMate()) {
                    accumulator.missedMates++;
                }
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

    /** Probability threshold for a winning position, using the same model as move quality. */
    static final double CONVERSION_WIN_CHANCE = 0.90;

    /**
     * Each model side/game counts once if any evaluated position (including the opponent's moves, overrides and
     * forced moves) reached 90% winning chances. Missing evaluations give no evidence, never an assumed zero.
     */
    private void registerConversion(Map<String, ComparisonAccumulator> byModel, List<Game> games, List<Move> plies,
                                    Predicate<Side> selected) {
        Map<UUID, List<MoveQualityClassifier.Score>> scoresByGame = new HashMap<>();
        for (Move move : plies) {
            if (move.isBook) {
                continue;
            }
            MoveQualityClassifier.Score score = MoveQualityClassifier.score(
                    move.evaluationCp, move.evaluationMate, move.color);
            if (score != null && move.game != null) {
                scoresByGame.computeIfAbsent(move.game.id, ignored -> new ArrayList<>()).add(score);
            }
        }
        for (Game game : games) {
            List<MoveQualityClassifier.Score> scores = scoresByGame.getOrDefault(game.id, List.of());
            if (!countsAsPlayed(game) || scores.isEmpty()) {
                continue;
            }
            for (boolean white : new boolean[] {true, false}) {
                String model = modelSide(game, white);
                if (model == null || !selected.test(Side.of(game.id, white ? "WHITE" : "BLACK", model))) {
                    continue;
                }
                ComparisonAccumulator accumulator = byModel.computeIfAbsent(model, ignored -> new ComparisonAccumulator());
                accumulator.evaluatedGames++;
                int sign = white ? 1 : -1;
                boolean reached = scores.stream()
                        .anyMatch(score -> (1 + sign * score.winningChances()) / 2 >= CONVERSION_WIN_CHANCE);
                if (!reached) {
                    continue;
                }
                accumulator.winningPositionsReached++;
                var outcome = outcomeClassifier.classifyComparison(game, white);
                if (outcome.win()) {
                    accumulator.converted++;
                    if (game.resultReason == Game.ResultReason.ADJUDICATION) {
                        accumulator.adjudicatedWins++;
                    }
                } else if (outcome.draw() || outcome.loss()) {
                    accumulator.thrownAway++;
                }
            }
        }
    }

    /**
     * Counts each model move per chat mode that applied to it ({@link Move#chatMode}) and whether a non-empty chat
     * message was stored for the same ply (game, move number, colour). Moves without a mode (humans, overrides) and
     * moves made with chat OFF are left out. Databases from before migration V8 may hold several moves or messages
     * for one ply; each ply counts once, with its first stored move and first non-empty message (the duplicates are
     * the later rows, see docs/deployment.md).
     */
    private void registerTalkativeness(Map<String, ComparisonAccumulator> byModel, List<Move> plies,
                                       List<PlyMessage> messages) {
        Map<PlyKey, Integer> messageLengths = new HashMap<>();
        for (PlyMessage message : messages) {
            if (message.length() > 0) {
                messageLengths.putIfAbsent(new PlyKey(message.gameId(), message.moveNumber(), message.color()),
                        message.length());
            }
        }
        for (Move move : plies) {
            if (move.isBook || move.game == null || move.chatMode == null || !move.chatMode.allowsMessages() || move.isOverride
                    || !hasTrackedModelId(move.modelId)) {
                continue;
            }
            ComparisonAccumulator accumulator =
                    byModel.computeIfAbsent(normalizeModelId(move.modelId), ignored -> new ComparisonAccumulator());
            TalkAccumulator talk = move.chatMode == ChatMode.MANDATORY
                    ? accumulator.mandatoryChat
                    : accumulator.optionalChat;
            talk.moves++;
            Integer length = messageLengths.get(new PlyKey(move.game.id, move.moveNumber, move.color));
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
    private void registerResignations(Map<String, ComparisonAccumulator> byModel, List<Game> games, List<Move> moves,
                                      Predicate<Side> selected) {
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
            if (resignedModel != null
                    && selected.test(Side.of(game.id, color, resignedModel))) {
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
            if (winnerModel != null
                    && selected.test(Side.of(game.id, whiteResigned ? "BLACK" : "WHITE", winnerModel))) {
                byModel.computeIfAbsent(winnerModel, ignored -> new ComparisonAccumulator()).wonByResignation++;
            }
        }
    }

    /** Per model: resigned turns with failed attempts before the resignation, and those failed attempts. */
    record ResignedTurnFailures(long turns, long failures) {
    }

    /**
     * Failed attempts before an accepted resignation. A resigned turn has no move, so the failures before it would
     * otherwise vanish from reliability (it is computed from the moves' retry counts). A resigned turn with failures
     * counts like a move that needed that many retries: one more turn, not first-try legal, plus its failures as
     * retries. A resignation on the first attempt is left out entirely, so resigning itself is never penalised. Only
     * plies with a RESIGNED attempt count; games stored without attempts (before 1.3) have none, like before.
     */
    static Map<String, ResignedTurnFailures> resignedTurnFailures(List<ResignationTurnAttempt> attempts) {
        Map<PlyKey, List<ResignationTurnAttempt>> byPly = new HashMap<>();
        for (ResignationTurnAttempt attempt : attempts) {
            byPly.computeIfAbsent(new PlyKey(attempt.gameId(), attempt.moveNumber(), attempt.color()),
                    ignored -> new ArrayList<>()).add(attempt);
        }
        Map<String, ResignedTurnFailures> byModel = new HashMap<>();
        for (List<ResignationTurnAttempt> ply : byPly.values()) {
            String modelId = ply.stream()
                    .filter(attempt -> attempt.outcome() == MoveAttempt.Outcome.RESIGNED)
                    .map(ResignationTurnAttempt::modelId)
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst()
                    .orElse(null);
            long failures = ply.stream().filter(attempt -> attempt.outcome().isModelFailure()).count();
            if (modelId == null || failures == 0) {
                continue;
            }
            byModel.merge(modelId.trim(), new ResignedTurnFailures(1, failures),
                    (a, b) -> new ResignedTurnFailures(a.turns() + b.turns(), a.failures() + b.failures()));
        }
        return byModel;
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

    private TalkativenessDto talkativeness(TalkAccumulator talk) {
        return new TalkativenessDto(
                talk.moves,
                talk.messages,
                talk.moves == 0 ? null : decimal((double) talk.messages / talk.moves, 4),
                talk.messages == 0 ? null : decimal((double) talk.messageLengthTotal / talk.messages, 1)
        );
    }

    private PromptCachingDto promptCaching(String modelId, List<AttemptUsage> usage,
                                           Map<String, BigDecimal> cacheReadSavings) {
        Long prompt = tokenTotal(usage, AttemptUsage::promptTokens);
        Long cached = tokenTotal(usage, AttemptUsage::cachedPromptTokens);
        Long writes = tokenTotal(usage, AttemptUsage::cacheWriteTokens);
        BigDecimal rate = prompt == null || prompt == 0 || cached == null ? null : divide(cached, prompt);
        BigDecimal savingPerToken = cached == null ? null : cacheReadSavings.get(modelId);
        BigDecimal savings = savingPerToken == null ? null : savingPerToken.multiply(BigDecimal.valueOf(cached));
        return new PromptCachingDto(prompt, cached, rate, writes, savings);
    }

    private Long tokenTotal(List<AttemptUsage> usage, java.util.function.Function<AttemptUsage, Integer> counter) {
        if (usage.isEmpty() || usage.stream().anyMatch(attempt -> counter.apply(attempt) == null)) {
            return null;
        }
        return usage.stream().mapToLong(attempt -> counter.apply(attempt)).sum();
    }

    private static Side sideOf(Move move) {
        return Side.of(move.game == null ? null : move.game.id, move.color, move.modelId);
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
    private boolean pricingAvailable(ModelMoveAccumulator accumulator) {
        return accumulator.hasReportedCost && !accumulator.missingMovePrice;
    }

    private BigDecimal averageCostPerPly(ModelMoveAccumulator accumulator) {
        return pricingAvailable(accumulator) && accumulator.pricedPlies > 0
                ? divide(accumulator.totalCostUsd, accumulator.pricedPlies)
                : null;
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

    private static class ModelMoveAccumulator {
        long movesSampled;
        long firstTryMoves;
        long retriesTotal;
        long responseTimeCount;
        long responseTimeTotal;
        BigDecimal totalCostUsd = BigDecimal.ZERO;
        long pricedPlies;
        boolean hasReportedCost;
        boolean missingMovePrice;
    }

    private static final class ComparisonAccumulator extends ModelMoveAccumulator {
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
        private long analyzedMoves;
        private final Set<UUID> analyzedGameIds = new HashSet<>();
        private long centipawnLossTotal;
        private long inaccuracies;
        private long mistakes;
        private long blunders;
        private final TalkAccumulator optionalChat = new TalkAccumulator();
        private final TalkAccumulator mandatoryChat = new TalkAccumulator();
        private long resignedTurnsWithFailures;
        private long resignedTurnFailures;
        private long resignations;
        private long resignationsEvaluated;
        private long resignationsWhileNotLosing;
        private long wonByResignation;
        private long evaluatedGames;
        private long winningPositionsReached;
        private long converted;
        private long adjudicatedWins;
        private long thrownAway;
        private long missedMates;
        private final ReasoningAnalytics.Sample reasoning = new ReasoningAnalytics.Sample();
        private final List<AttemptUsage> cacheUsage = new ArrayList<>();
    }

    private static final class TalkAccumulator {
        private long moves;
        private long messages;
        private long messageLengthTotal;
    }

    private static final class ReliabilityAccumulator extends ModelMoveAccumulator {
        private long gamesPlayed;
        private long gamesCompleted;
        private long forfeitCount;
        private long timeoutForfeitCount;
        private long invalidMoveForfeitCount;
        private long resignedTurnsWithFailures;
        private long resignedTurnFailures;
    }
}
