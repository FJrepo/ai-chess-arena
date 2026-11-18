package dev.aichessarena.service;

import dev.aichessarena.dto.BenchmarkComparisonDto;
import dev.aichessarena.dto.BenchmarkComparisonDto.ComparisonType;
import dev.aichessarena.dto.BenchmarkComparisonDto.Difference;
import dev.aichessarena.dto.BenchmarkComparisonDto.GameSide;
import dev.aichessarena.dto.BenchmarkComparisonDto.Matchup;
import dev.aichessarena.dto.BenchmarkComparisonDto.Model;
import dev.aichessarena.dto.BenchmarkComparisonDto.Side;
import dev.aichessarena.dto.BenchmarkComparisonDto.Telemetry;
import dev.aichessarena.entity.BenchmarkDefinition;
import dev.aichessarena.entity.BenchmarkDefinition.Opening;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.ResolvedModel;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.MoveRepository.ReasoningPly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class BenchmarkComparisonService {
    private static final BigDecimal HALF_POINT = new BigDecimal("0.5");

    @Inject BenchmarkRunService runs;
    @Inject GameRepository games;
    @Inject MoveRepository moves;
    @Inject MoveAttemptRepository attempts;
    @Inject AnalyticsGameOutcomeClassifier outcomes;

    @Transactional
    public BenchmarkComparisonDto compare(UUID baseId, UUID candidateId) {
        if (baseId.equals(candidateId)) throw new IllegalArgumentException("Choose two different benchmark runs");
        games.beginSnapshotRead();
        Tournament base = runs.requireRun(baseId);
        Tournament candidate = runs.requireRun(candidateId);
        List<Game> baseGames = games.findByTournamentId(baseId);
        List<Game> candidateGames = games.findByTournamentId(candidateId);
        BigDecimal baseSpend = BenchmarkResults.spend(baseGames);
        BigDecimal candidateSpend = BenchmarkResults.spend(candidateGames);
        List<Difference> differences = differences(base, candidate);
        Map<String, Difference> changedSettings = settingChanges(base, candidate);
        var displayedChanges = new ArrayList<>(changedSettings.values());
        differ(displayedChanges, "Prompt version",
                base.benchmarkDefinition.promptVersion(), candidate.benchmarkDefinition.promptVersion());
        boolean comparable = differences.isEmpty();
        ComparisonType comparisonType = comparable ? comparisonType(base, candidate, changedSettings)
                : ComparisonType.NOT_COMPARABLE;
        List<Model> models = new ArrayList<>();
        List<Matchup> matchups = new ArrayList<>();
        List<String> unmatchedBase = new ArrayList<>(base.benchmarkDefinition.modelIds());
        List<String> unmatchedCandidate = new ArrayList<>(candidate.benchmarkDefinition.modelIds());
        if (comparable) {
            RunData before = runData(base, baseGames);
            RunData after = runData(candidate, candidateGames);
            for (String id : base.benchmarkDefinition.modelIds()) {
                if (unmatchedCandidate.remove(id)) {
                    compareModel(before, after, id, id, models, matchups);
                    unmatchedBase.remove(id);
                }
            }
            // An unambiguous model replacement is compared under both actual IDs.
            if (unmatchedBase.size() == 1 && unmatchedCandidate.size() == 1) {
                compareModel(before, after, unmatchedBase.removeFirst(), unmatchedCandidate.removeFirst(), models, matchups);
            }
            matchups.sort(Comparator.comparing(Matchup::outcomeChanged).reversed()
                    .thenComparing(row -> row.scoreDelta() == null ? BigDecimal.ZERO : row.scoreDelta().abs(),
                            Comparator.reverseOrder())
                    .thenComparing(row -> row.scoreDelta() == null)
                    .thenComparing(Matchup::openingName)
                    .thenComparingInt(Matchup::targetStrength)
                    .thenComparing(Matchup::color, Comparator.reverseOrder())
                    .thenComparing(Matchup::baseModelId));
        } else {
            unmatchedBase.clear();
            unmatchedCandidate.clear();
        }
        return new BenchmarkComparisonDto(BenchmarkRunService.summary(base), BenchmarkRunService.summary(candidate),
                comparable, comparisonType, differences, displayedChanges, baseSpend, candidateSpend,
                comparable ? candidateSpend.subtract(baseSpend) : null,
                unmatchedBase, unmatchedCandidate, models, matchups);
    }

    private RunData runData(Tournament run, List<Game> runGames) {
        var canonical = CanonicalPlies.reasoningOf(moves.findBenchmarkPlies(run.id)).stream()
                .filter(move -> !move.book() && !move.override()).toList();
        var modelGames = runGames.stream().collect(Collectors.toMap(
                game -> new GameKey(game.benchmarkModelId(), game.openingId, game.stockfishElo, game.benchmarkModelColor()),
                game -> game));
        var byGame = runGames.stream().collect(Collectors.toMap(game -> game.id, game -> game));
        var modelMoves = canonical.stream().filter(move -> {
            Game game = byGame.get(move.gameId());
            return game != null && game.benchmarkModelColor().equals(move.color())
                    && game.benchmarkModelId().equals(move.modelId());
        }).toList();
        var movesWithPriorAnswers = moves.findBenchmarkMovesWithPriorAnswers(run.id);
        var firstTries = modelMoves.stream()
                .filter(move -> move.retryCount() <= 0 && !movesWithPriorAnswers.contains(move.id()))
                .collect(Collectors.groupingBy(ReasoningPly::gameId, Collectors.counting()));
        var reasoning = ReasoningAnalytics.bySide(attempts.findBenchmarkReasoningAttempts(run.id), modelMoves);
        var resolved = run.benchmarkDefinition.modelIds().stream().anyMatch(id -> id.startsWith("~"))
                ? attempts.findResolvedBenchmarkModels(run.id).stream().collect(
                        Collectors.groupingBy(ResolvedModel::alias, Collectors.mapping(ResolvedModel::modelId, Collectors.toList())))
                : Map.<String, List<String>>of();
        return new RunData(run.benchmarkDefinition, modelGames, firstTries, reasoning, resolved);
    }

    private void compareModel(RunData before, RunData after, String baseModel, String candidateModel,
                              List<Model> models, List<Matchup> matchups) {
        var baseTotals = new Totals();
        var candidateTotals = new Totals();
        int matched = 0;
        BigDecimal delta = BigDecimal.ZERO;
        for (Opening opening : before.definition().openings()) {
            for (int target : before.definition().targetStrengths()) {
                boolean baseIncomplete = incomplete(before, baseModel, opening, target);
                boolean candidateIncomplete = incomplete(after, candidateModel, opening, target);
                if (baseIncomplete) baseTotals.incompletePairs++;
                if (candidateIncomplete) candidateTotals.incompletePairs++;
                for (String color : before.definition().modelColors()) {
                    GameSide base = gameSide(before, baseModel, opening, target, color, baseIncomplete, baseTotals);
                    GameSide candidate = gameSide(after, candidateModel, opening, target, color, candidateIncomplete, candidateTotals);
                    BigDecimal scoreDelta = difference(base.points(), candidate.points());
                    if (scoreDelta != null) {
                        matched++;
                        delta = delta.add(scoreDelta);
                    }
                    matchups.add(new Matchup(baseModel, candidateModel, opening.id(), opening.name(), target, color,
                            base, candidate, outcomeChanged(base, candidate), scoreDelta,
                            difference(base.spendUsd(), candidate.spendUsd())));
                }
            }
        }
        Side base = baseTotals.side(outcomes, before.resolved().getOrDefault(baseModel, List.of()));
        Side candidate = candidateTotals.side(outcomes, after.resolved().getOrDefault(candidateModel, List.of()));
        models.add(new Model(baseModel, candidateModel, base, candidate, matched,
                divide(delta, matched, 4), candidate.spendUsd().subtract(base.spendUsd())));
    }

    private static boolean outcomeChanged(GameSide base, GameSide candidate) {
        return base.points() != null && candidate.points() != null
                && (!Objects.equals(base.outcome(), candidate.outcome()) || base.forfeit() != candidate.forfeit());
    }

    private static boolean incomplete(RunData data, String model, Opening opening, int target) {
        return data.definition().modelColors().stream().map(color -> data.game(model, opening, target, color))
                .anyMatch(game -> game == null || !BenchmarkResults.scored(game));
    }

    private GameSide gameSide(RunData data, String model, Opening opening, int target, String color,
                              boolean incomplete, Totals totals) {
        Game game = data.game(model, opening, target, color);
        if (game == null) return new GameSide(null, null, null, null, null, false, incomplete, null, null);
        boolean white = "WHITE".equals(color);
        var outcome = outcomes.classifyComparison(game, white);
        BigDecimal points = null;
        String label = null;
        if (BenchmarkResults.scored(game)) {
            totals.scored.add(game);
            points = outcome.win() ? BigDecimal.ONE : outcome.draw() ? HALF_POINT : BigDecimal.ZERO;
            label = outcome.win() ? "WIN" : outcome.draw() ? "DRAW" : "LOSS";
        }
        var sample = data.reasoning().get(ReasoningAnalytics.Side.of(game.id, color, model));
        var reasoning = sample == null ? new ReasoningAnalytics.Sample() : sample;
        var usage = reasoning.toDto(null);
        long first = data.firstTries().getOrDefault(game.id, 0L);
        long turns = usage.modelMoves() + (points != null && outcomes.isInvalidMoveForfeit(game, white) ? 1 : 0);
        totals.first += first;
        totals.turns += turns;
        totals.reasoning.include(reasoning);
        totals.spend = totals.spend.add(game.totalCostUsd);
        return new GameSide(game.id, game.status, game.resultReason, label, points, points != null && outcome.forfeit(),
                incomplete, telemetry(first, turns, reasoning), game.totalCostUsd);
    }

    private static BigDecimal difference(BigDecimal base, BigDecimal candidate) {
        return base == null || candidate == null ? null : candidate.subtract(base);
    }

    private static BigDecimal divide(BigDecimal numerator, long denominator, int scale) {
        return denominator == 0 ? null : numerator.divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }

    private static Telemetry telemetry(long first, long turns, ReasoningAnalytics.Sample reasoning) {
        var usage = reasoning.toDto(null);
        return new Telemetry(first, turns, divide(BigDecimal.valueOf(first), turns, 4),
                usage.reportedMoves(), usage.averageTokens());
    }

    private static Map<String, Difference> settingChanges(Tournament base, Tournament candidate) {
        var changes = new LinkedHashMap<String, Difference>();
        settingChange(changes, "modelId", "Model IDs", String.join(", ", base.benchmarkDefinition.modelIds()),
                String.join(", ", candidate.benchmarkDefinition.modelIds()));
        settingChange(changes, "reasoningEffort", "Requested effort", BenchmarkRunService.effortValue(base.reasoningEffort),
                BenchmarkRunService.effortValue(candidate.reasoningEffort));
        settingChange(changes, "legalMoveHints", "Legal move hints", base.legalMoveHints ? "On" : "Off",
                candidate.legalMoveHints ? "On" : "Off");
        settingChange(changes, "chatMode", "Chat mode", base.chatMode, candidate.chatMode);
        settingChange(changes, "budgetUsd", "Current budget (USD)", base.budgetUsd, candidate.budgetUsd);
        settingChange(changes, "maxConcurrentGames", "Concurrent games", base.maxConcurrentGames, candidate.maxConcurrentGames);
        settingChange(changes, "maxAttempts", "Max attempts per move", base.maxAttempts, candidate.maxAttempts);
        settingChange(changes, "moveTimeoutSeconds", "Move time limit (seconds)", base.moveTimeoutSeconds,
                candidate.moveTimeoutSeconds);
        settingChange(changes, "maxOutputTokens", "Max output tokens", base.maxOutputTokens, candidate.maxOutputTokens);
        return changes;
    }

    private static void settingChange(Map<String, Difference> settings, String key, String label, Object base, Object candidate) {
        if (!Objects.equals(base, candidate)) {
            settings.put(key, new Difference(label, Objects.toString(base, "Missing"), Objects.toString(candidate, "Missing")));
        }
    }

    private static ComparisonType comparisonType(Tournament base, Tournament candidate, Map<String, Difference> changes) {
        if (changes.size() > 1) return ComparisonType.MULTIPLE_SETTINGS;
        return isControlledRerun(base, candidate, changes) || isControlledRerun(candidate, base, changes)
                ? ComparisonType.CONTROLLED_RERUN : ComparisonType.COMPARABLE;
    }

    private static boolean isControlledRerun(Tournament source, Tournament rerun, Map<String, Difference> changes) {
        var recorded = rerun.benchmarkDefinition.rerun();
        if (recorded == null || !source.id.equals(recorded.baseId())) return false;
        if (changes.isEmpty()) return recorded.setting() == null;
        if (!changes.containsKey(recorded.setting())) return false;
        // Budget is the only recorded change that can be edited after the rerun is created.
        return !"budgetUsd".equals(recorded.setting()) || rerun.budgetUsd.compareTo(new BigDecimal(recorded.value())) == 0;
    }

    private static List<Difference> differences(Tournament before, Tournament after) {
        var base = before.benchmarkDefinition;
        var candidate = after.benchmarkDefinition;
        var differences = new ArrayList<Difference>();
        differ(differences, "Opening suite", base.suite(), candidate.suite());
        differ(differences, "Opening seed", base.openingSeed(), candidate.openingSeed());
        var baseLines = base.openings().stream().collect(Collectors.toMap(Opening::id, opening -> String.join(" ", opening.moves())));
        var candidateLines = candidate.openings().stream().collect(Collectors.toMap(Opening::id, opening -> String.join(" ", opening.moves())));
        var openingIds = new TreeSet<>(baseLines.keySet());
        openingIds.addAll(candidateLines.keySet());
        for (String id : openingIds) {
            differ(differences, "Opening line " + id, baseLines.get(id), candidateLines.get(id));
        }
        differ(differences, "Stockfish executable SHA-256", base.engineSha256(), candidate.engineSha256());
        differ(differences, "Stockfish NNUE weights", networks(base), networks(candidate));
        differ(differences, "Search nodes", base.searchNodes(), candidate.searchNodes());
        differ(differences, "Threads", base.threads(), candidate.threads());
        differ(differences, "Hash (MiB)", base.hashMiB(), candidate.hashMiB());
        differ(differences, "Stockfish levels", base.targetStrengths().stream().sorted().toList(),
                candidate.targetStrengths().stream().sorted().toList());
        differ(differences, "Model colors", base.modelColors().stream().sorted().toList(),
                candidate.modelColors().stream().sorted().toList());
        if (!compatiblePrompts(before, after)) {
            differences.add(new Difference("Prompt version", Objects.toString(base.promptVersion(), "Missing"),
                    Objects.toString(candidate.promptVersion(), "Missing")));
        }
        return differences;
    }

    private static boolean compatiblePrompts(Tournament base, Tournament candidate) {
        String baseRules = promptRulesVersion(base);
        String candidateRules = promptRulesVersion(candidate);
        return baseRules != null && baseRules.equals(candidateRules);
    }

    private static String promptRulesVersion(Tournament run) {
        String version = run.benchmarkDefinition.promptVersion();
        String suffix = PromptService.benchmarkVersionSuffix(run.chatMode, run.legalMoveHints);
        return version != null && version.length() > suffix.length() && version.endsWith(suffix)
                ? version.substring(0, version.length() - suffix.length()) : null;
    }

    private static List<String> networks(BenchmarkDefinition definition) {
        return definition.engineNetworks().stream().map(network -> network.name() + " " + network.sha256()).sorted().toList();
    }

    private static void differ(List<Difference> differences, String setting, Object base, Object candidate) {
        if (!Objects.equals(base, candidate)) {
            differences.add(new Difference(setting, Objects.toString(base, "Missing"), Objects.toString(candidate, "Missing")));
        }
    }

    private record GameKey(String model, String opening, int target, String color) {}

    private record RunData(BenchmarkDefinition definition, Map<GameKey, Game> games, Map<UUID, Long> firstTries,
                           Map<ReasoningAnalytics.Side, ReasoningAnalytics.Sample> reasoning,
                           Map<String, List<String>> resolved) {
        Game game(String model, Opening opening, int target, String color) {
            return games.get(new GameKey(model, opening.id(), target, color));
        }
    }

    private static final class Totals {
        final List<Game> scored = new ArrayList<>();
        final ReasoningAnalytics.Sample reasoning = new ReasoningAnalytics.Sample();
        long first;
        long turns;
        long incompletePairs;
        BigDecimal spend = BigDecimal.ZERO;

        Side side(AnalyticsGameOutcomeClassifier outcomes, List<String> resolved) {
            return new Side(BenchmarkResults.score(scored, outcomes), telemetry(first, turns, reasoning),
                    incompletePairs, spend, resolved);
        }
    }
}
