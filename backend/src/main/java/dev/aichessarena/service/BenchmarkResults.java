package dev.aichessarena.service;

import dev.aichessarena.dto.BenchmarkRunDto.GameResultRow;
import dev.aichessarena.dto.BenchmarkRunDto.ModelResult;
import dev.aichessarena.dto.BenchmarkRunDto.Pair;
import dev.aichessarena.dto.BenchmarkRunDto.Score;
import dev.aichessarena.dto.BenchmarkRunDto.Split;
import dev.aichessarena.entity.BenchmarkDefinition;
import dev.aichessarena.entity.Game;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

final class BenchmarkResults {

    private BenchmarkResults() {}

    static ModelResult model(String modelId, BenchmarkDefinition definition, List<Game> games,
                             AnalyticsGameOutcomeClassifier outcomes) {
        List<Game> played = games.stream().filter(BenchmarkResults::scored).toList();
        List<Split> splits = definition.targetStrengths().stream().flatMap(target -> definition.modelColors().stream()
                .map(color -> new Split(target, color, score(played.stream()
                        .filter(game -> target.equals(game.stockfishElo) && color.equals(game.benchmarkModelColor()))
                        .toList(), outcomes)))).toList();
        List<Pair> pairs = games.stream().collect(Collectors.groupingBy(game -> game.benchmarkPairId,
                LinkedHashMap::new, Collectors.toList())).values().stream().map(BenchmarkResults::pair).toList();
        return new ModelResult(modelId, score(played, outcomes), splits,
                pairs.stream().filter(pair -> !pair.complete()).count(), spend(games), pairs);
    }

    private static Pair pair(List<Game> games) {
        Game first = games.getFirst();
        List<GameResultRow> rows = games.stream().sorted(Comparator.comparing(Game::benchmarkModelColor).reversed())
                .map(game -> new GameResultRow(game.id, game.benchmarkModelColor(), game.status, game.result,
                        game.resultReason, game.statusDetail, game.totalCostUsd)).toList();
        return new Pair(first.benchmarkPairId, first.openingId, first.stockfishElo,
                games.size() == 2 && games.stream().allMatch(BenchmarkResults::scored), rows);
    }

    private static boolean scored(Game game) {
        return AnalyticsGameOutcomeClassifier.isTerminal(game.status) && game.result != null;
    }

    private static Score score(List<Game> games, AnalyticsGameOutcomeClassifier outcomes) {
        var results = games.stream()
                .map(game -> outcomes.classifyComparison(game, "WHITE".equals(game.benchmarkModelColor()))).toList();
        long wins = results.stream().filter(outcome -> outcome.win()).count();
        long draws = results.stream().filter(outcome -> outcome.draw()).count();
        long losses = results.stream().filter(outcome -> outcome.loss()).count();
        long forfeits = results.stream().filter(outcome -> outcome.forfeit()).count();
        BigDecimal points = BigDecimal.valueOf(wins).add(BigDecimal.valueOf(draws).multiply(new BigDecimal("0.5")));
        return new Score(wins, draws, losses, forfeits, points, games.size());
    }

    static BigDecimal spend(List<Game> games) {
        return games.stream().map(game -> game.totalCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
