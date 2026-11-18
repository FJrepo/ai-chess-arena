package dev.aichessarena.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aichessarena.dto.BenchmarkRunDto;
import dev.aichessarena.dto.BenchmarkRunDto.Status;
import dev.aichessarena.dto.BenchmarkRunDto.Summary;
import dev.aichessarena.entity.BenchmarkDefinition;
import dev.aichessarena.entity.BenchmarkDefinition.Opening;
import dev.aichessarena.entity.BenchmarkDefinition.Rerun;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.ResolvedModel;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class BenchmarkRunService {
    private static final List<String> COLORS = List.of("WHITE", "BLACK");
    private static final Set<String> CREATE_FIELDS = Set.of(
            "clientRequestId", "name", "modelIds", "targetStrengths", "legalMoveHints", "reasoningEffort", "chatMode",
            "runSettings");
    private static final Pattern REQUEST_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public record Creation(BenchmarkRunDto run, boolean created) {}

    @Inject TournamentRepository tournaments;
    @Inject GameRepository games;
    @Inject TournamentService tournamentService;
    @Inject TournamentAutoRunService autoRuns;
    @Inject OpenRouterKeyStore keys;
    @Inject StockfishOpponentService engine;
    @Inject TournamentRunScheduler scheduler;
    @Inject TransactionSynchronizationRegistry transactions;
    @Inject AnalyticsGameOutcomeClassifier outcomes;
    @Inject MoveAttemptRepository attempts;
    @Inject PromptService prompts;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Creation create(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Request body must be a JSON object");
        body.fieldNames().forEachRemaining(field -> {
            if (!CREATE_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown benchmark setting: " + field);
        });

        Tournament run = new Tournament();
        run.clientRequestId = requestId(body);
        run.name = requiredText(body, "name", 120);
        List<String> modelIds = modelIds(body.path("modelIds"));
        List<Integer> strengths = strengths(body.path("targetStrengths"));
        if (!body.path("legalMoveHints").isBoolean()) {
            throw new IllegalArgumentException("legalMoveHints must be true or false");
        }
        run.legalMoveHints = body.path("legalMoveHints").booleanValue();
        run.reasoningEffort = effort(body);
        run.chatMode = chatMode(body);
        JsonNode runSettings = body.path("runSettings");
        if (!runSettings.hasNonNull("budgetUsd") || !runSettings.hasNonNull("maxConcurrentGames")) {
            throw new IllegalArgumentException("runSettings must include budgetUsd and maxConcurrentGames");
        }
        TournamentAutoRunService.applyRunSettings(run, runSettings, true);

        Creation replay = replay(run.clientRequestId, null);
        if (replay != null) return replay;

        var fingerprint = fingerprint(strengths);
        run.benchmarkDefinition = new BenchmarkDefinition(StockfishLadderOpenings.SUITE, StockfishLadderOpenings.OPENINGS,
                modelIds, strengths, COLORS, fingerprint.sha256(), fingerprint.networks(),
                StockfishOpponentService.SEARCH_NODES, StockfishOpponentService.THREADS, StockfishOpponentService.HASH_MIB,
                promptVersion(run), null, null);
        return createRun(run);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Creation rerun(UUID baseId, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Request body must be a JSON object");
        body.fieldNames().forEachRemaining(field -> {
            if (!Set.of("clientRequestId", "change", "expectedRunSettings").contains(field)) {
                throw new IllegalArgumentException("Unknown rerun setting: " + field);
            }
        });
        UUID requestId = requestId(body);
        Rerun change = parseChange(baseId, body.path("change"));
        Tournament expected = body.has("expectedRunSettings") ? requiredRunSettings(body.get("expectedRunSettings")) : null;
        Creation replay = replay(requestId, change);
        if (replay != null) return replay;

        try {
            Tournament source = QuarkusTransaction.requiringNew().call(() -> requireRun(baseId));
            Tournament preview = rerunDraft(source, requestId, change, expected);
            var saved = source.benchmarkDefinition;
            var fingerprint = fingerprint(saved.targetStrengths());
            if (!fingerprint.sha256().equals(saved.engineSha256()) || !fingerprint.networks().equals(saved.engineNetworks())) {
                throw new GameStateConflictException("The installed Stockfish build or NNUE weights differ from the source run.");
            }
            prepareModels(preview.benchmarkDefinition.modelIds());

            // Verify the engine and models before locking the source. Its saved matchup definition is immutable,
            // but a concurrent resume can change the budget and concurrency until we take the lock.
            var created = QuarkusTransaction.requiringNew().call(() -> {
                Tournament base = requireBenchmark(tournaments.findByIdForUpdate(baseId));
                if (replayId(requestId, change) != null) return null;
                return persistRun(rerunDraft(base, requestId, change, expected));
            });
            if (created != null) return new Creation(created, true);
            return replay(requestId, change);
        } catch (RuntimeException failure) {
            return replayOrThrow(requestId, change, failure);
        }
    }

    private Tournament rerunDraft(Tournament base, UUID requestId, Rerun change, Tournament expected) {
        BenchmarkDefinition saved = base.benchmarkDefinition;
        if (expected != null && (expected.maxConcurrentGames != base.maxConcurrentGames
                || expected.budgetUsd.compareTo(base.budgetUsd) != 0)) {
            throw new GameStateConflictException("The source run limits changed. Refresh and review the rerun settings.");
        }
        if (!Objects.equals(saved.promptVersion(), promptVersion(base))) {
            throw new GameStateConflictException("The source run's prompt version is unavailable.");
        }

        Tournament run = new Tournament();
        run.clientRequestId = requestId;
        String suffix = " (rerun)";
        run.name = base.name.substring(0, Math.min(base.name.length(), 120 - suffix.length())).stripTrailing() + suffix;
        run.legalMoveHints = base.legalMoveHints;
        run.reasoningEffort = base.reasoningEffort;
        run.chatMode = base.chatMode;
        run.budgetUsd = base.budgetUsd;
        run.maxConcurrentGames = base.maxConcurrentGames;
        run.maxAttempts = base.maxAttempts;
        run.moveTimeoutSeconds = base.moveTimeoutSeconds;
        run.maxOutputTokens = base.maxOutputTokens;
        List<String> modelIds = applyChange(base, run, change);
        run.benchmarkDefinition = new BenchmarkDefinition(saved.suite(), saved.openings(), modelIds,
                saved.targetStrengths(), saved.modelColors(), saved.engineSha256(), saved.engineNetworks(),
                saved.searchNodes(), saved.threads(), saved.hashMiB(), promptVersion(run), change, saved.openingSeed());
        return run;
    }

    private static Tournament requiredRunSettings(JsonNode settings) {
        if (!settings.hasNonNull("budgetUsd") || !settings.hasNonNull("maxConcurrentGames")) {
            throw new IllegalArgumentException("Run settings must include budgetUsd and maxConcurrentGames");
        }
        Tournament run = new Tournament();
        TournamentAutoRunService.applyRunSettings(run, settings, true);
        return run;
    }

    private static Rerun parseChange(UUID baseId, JsonNode change) {
        if (!change.isObject() || change.size() > 1) {
            throw new IllegalArgumentException("change must be an object containing zero or one setting");
        }
        if (change.isEmpty()) return new Rerun(baseId, null, null, null);
        String field = change.fieldNames().next();
        JsonNode value = change.get(field);
        return switch (field) {
            case "modelId" -> {
                if (!value.isObject() || value.size() != 2 || !validModelId(value.path("from")) || !validModelId(value.path("to"))) {
                    throw new IllegalArgumentException("modelId must specify from and to as provider/model IDs");
                }
                yield new Rerun(baseId, field, value.get("from").textValue(), value.get("to").textValue());
            }
            case "legalMoveHints" -> {
                if (!value.isBoolean()) throw new IllegalArgumentException("legalMoveHints must be true or false");
                yield new Rerun(baseId, field, null, value.asText());
            }
            case "reasoningEffort" -> new Rerun(baseId, field, null, effortValue(effort(change)));
            case "chatMode" -> new Rerun(baseId, field, null, chatMode(change).name());
            case "budgetUsd" -> {
                Tournament settings = new Tournament();
                TournamentAutoRunService.applyRunSettings(settings, change, true);
                if (settings.budgetUsd == null) throw new IllegalArgumentException("budgetUsd is required");
                yield new Rerun(baseId, field, null, settings.budgetUsd.toPlainString());
            }
            default -> throw new IllegalArgumentException("Unknown rerun setting: " + field);
        };
    }

    private static List<String> applyChange(Tournament base, Tournament run, Rerun change) {
        List<String> ids = base.benchmarkDefinition.modelIds();
        if (change.setting() == null) return ids;
        String value = change.value();
        String before;
        switch (change.setting()) {
            case "modelId" -> {
                var lineup = new ArrayList<>(ids);
                int slot = lineup.indexOf(change.previousModelId());
                if (slot < 0) throw new IllegalArgumentException("The model to replace is absent from the source run");
                if (ids.contains(value)) throw new IllegalArgumentException("Choose a model ID outside the source lineup");
                lineup.set(slot, value);
                return List.copyOf(lineup);
            }
            case "legalMoveHints" -> {
                before = Boolean.toString(base.legalMoveHints);
                run.legalMoveHints = Boolean.parseBoolean(value);
            }
            case "reasoningEffort" -> {
                before = effortValue(base.reasoningEffort);
                run.reasoningEffort = "default".equals(value) ? null : ReasoningEffort.parse(value);
            }
            case "chatMode" -> {
                before = base.chatMode.name();
                run.chatMode = ChatMode.valueOf(value);
            }
            case "budgetUsd" -> {
                before = base.budgetUsd.setScale(2).toPlainString();
                run.budgetUsd = new BigDecimal(value);
            }
            default -> throw new IllegalStateException("Unsupported validated rerun setting");
        }
        if (before.equals(value)) throw new IllegalArgumentException("Choose a different value or make a plain rerun");
        return ids;
    }

    private static UUID requestId(JsonNode body) {
        String value = body.path("clientRequestId").textValue();
        if (value == null || !REQUEST_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("clientRequestId must be a UUID");
        }
        return UUID.fromString(value);
    }

    private static ReasoningEffort effort(JsonNode body) {
        String value = requiredText(body, "reasoningEffort", 16);
        return "default".equals(value) ? null : ReasoningEffort.parse(value);
    }

    static String effortValue(ReasoningEffort effort) {
        return effort == null ? "default" : effort.apiValue();
    }

    private static ChatMode chatMode(JsonNode body) {
        try {
            return ChatMode.valueOf(requiredText(body, "chatMode", 16));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("chatMode must be OFF, OPTIONAL or MANDATORY");
        }
    }

    private String promptVersion(Tournament run) {
        return prompts.getSystemPromptVersion(run.chatMode) + (run.legalMoveHints ? "" : PromptService.NO_HINTS_VERSION_SUFFIX);
    }

    private StockfishOpponentService.EngineFingerprint fingerprint(List<Integer> strengths) {
        keys.requireKey();
        try {
            return engine.benchmarkFingerprint(strengths);
        } catch (IOException | java.util.concurrent.TimeoutException e) {
            throw new GameStateConflictException("Could not verify Stockfish and its NNUE weights. Check the installed engine.");
        }
    }

    private void prepareModels(List<String> modelIds) {
        for (String modelId : modelIds) {
            TournamentParticipant model = new TournamentParticipant();
            model.playerName = modelId;
            model.modelId = modelId;
            tournamentService.prepareParticipant(model);
        }
    }

    private Creation createRun(Tournament run) {
        prepareModels(run.benchmarkDefinition.modelIds());
        try {
            return new Creation(QuarkusTransaction.requiringNew().call(() -> persistRun(run)), true);
        } catch (RuntimeException failure) {
            return replayOrThrow(run.clientRequestId, null, failure);
        }
    }

    private Creation replayOrThrow(UUID requestId, Rerun change, RuntimeException failure) {
        // A duplicate insert waits for the other transaction. Re-read only after ours rolls back.
        Creation replay = replay(requestId, change);
        if (replay != null) return replay;
        throw failure;
    }

    private Creation replay(UUID requestId, Rerun change) {
        UUID id = replayId(requestId, change);
        return id == null ? null : new Creation(get(id), false);
    }

    private UUID replayId(UUID requestId, Rerun change) {
        Tournament existing = tournaments.findByClientRequestId(requestId);
        if (existing == null) return null;
        if (!existing.isBenchmark() || !Objects.equals(existing.benchmarkDefinition.rerun(), change)) {
            throw new GameStateConflictException("Creation request ID belongs to a different creation request");
        }
        return existing.id;
    }

    private BenchmarkRunDto persistRun(Tournament run) {
        run.status = Tournament.TournamentStatus.IN_PROGRESS;
        run.autoRunStatus = Tournament.AutoRunStatus.RUNNING;
        tournaments.persistAndFlush(run);
        List<Game> created = createGames(run);
        created.forEach(tournamentService::persistGameWithOpening);
        UUID id = run.id;
        AfterCommit.run(transactions, () -> scheduler.changed(id));
        return report(run, created, Map.of());
    }

    @Transactional
    public List<Summary> list() {
        return tournaments.list("benchmarkDefinition is not null order by createdAt desc, id").stream()
                .map(BenchmarkRunService::summary).toList();
    }

    @Transactional
    public BenchmarkRunDto get(UUID id) {
        games.beginSnapshotRead();
        return report(requireRun(id), games.findByTournamentId(id));
    }

    Tournament requireRun(UUID id) {
        return requireBenchmark(tournaments.findById(id));
    }

    private static Tournament requireBenchmark(Tournament run) {
        if (run == null || !run.isBenchmark()) throw new NotFoundException("Benchmark run not found");
        return run;
    }

    @Transactional
    public BenchmarkRunDto resume(UUID id, JsonNode settings) {
        Tournament run = autoRuns.configureBenchmark(id, settings);
        return report(run, games.findByTournamentId(id));
    }

    @Transactional
    public BenchmarkRunDto pause(UUID id) {
        Tournament run = autoRuns.pauseBenchmark(id);
        return report(run, games.findByTournamentId(id));
    }

    @Transactional
    public BenchmarkRunDto cancel(UUID id) {
        Tournament run = tournamentService.cancelBenchmark(id);
        return report(run, games.findByTournamentId(id));
    }

    static Summary summary(Tournament run) {
        Status status = run.status == Tournament.TournamentStatus.CANCELLED
                ? Status.CANCELLED : Status.valueOf(run.autoRunStatus.name());
        return new Summary(run.id, run.name, run.createdAt, status, run.autoRunPauseReason,
                run.benchmarkDefinition.rerun() == null ? null : run.benchmarkDefinition.rerun().baseId());
    }

    private BenchmarkRunDto report(Tournament run, List<Game> runGames) {
        Map<String, List<String>> resolutions = run.benchmarkDefinition.modelIds().stream().anyMatch(id -> id.startsWith("~"))
                ? attempts.findResolvedBenchmarkModels(run.id).stream().collect(
                        Collectors.groupingBy(ResolvedModel::alias, Collectors.mapping(ResolvedModel::modelId, Collectors.toList())))
                : Map.of();
        return report(run, runGames, resolutions);
    }

    private BenchmarkRunDto report(Tournament run, List<Game> runGames, Map<String, List<String>> resolutions) {
        BenchmarkDefinition definition = run.benchmarkDefinition;
        List<Game> ordered = runGames.stream().sorted(pairOrder()).toList();
        return new BenchmarkRunDto(summary(run), definition, run.legalMoveHints,
                effortValue(run.reasoningEffort), run.chatMode,
                run.budgetUsd.toPlainString(), run.maxConcurrentGames, BenchmarkResults.spend(ordered),
                definition.modelIds().stream().map(modelId -> BenchmarkResults.model(modelId,
                        resolutions.getOrDefault(modelId, List.of()), definition, ordered.stream().filter(game -> modelId.equals(game.benchmarkModelId())).toList(), outcomes))
                        .toList());
    }

    static Comparator<Game> pairOrder() {
        return Comparator.comparing((Game game) -> game.openingId)
                .thenComparing(game -> game.stockfishElo)
                .thenComparing(Game::benchmarkModelId)
                .thenComparing(Game::benchmarkModelColor, Comparator.reverseOrder());
    }

    private static List<Game> createGames(Tournament run) {
        BenchmarkDefinition definition = run.benchmarkDefinition;
        List<Game> created = new ArrayList<>();
        for (Opening opening : definition.openings()) {
            for (int target : definition.targetStrengths()) {
                for (String model : definition.modelIds()) {
                    UUID pairId = UUID.randomUUID();
                    created.add(createGame(run, opening, target, model, pairId, true));
                    created.add(createGame(run, opening, target, model, pairId, false));
                }
            }
        }
        return created;
    }

    private static Game createGame(Tournament run, Opening opening, int target, String model, UUID pairId,
                                   boolean modelIsWhite) {
        Game game = new Game();
        game.tournament = run;
        game.benchmarkPairId = pairId;
        game.openingId = opening.id();
        game.openingName = opening.name();
        game.stockfishElo = target;
        game.whiteModelId = modelIsWhite ? model : null;
        game.blackModelId = modelIsWhite ? null : model;
        game.whitePlayerName = modelIsWhite ? model : "Stockfish";
        game.blackPlayerName = modelIsWhite ? "Stockfish" : model;
        OpeningBookService.apply(game, opening.moves());
        return game;
    }

    private static List<Integer> strengths(JsonNode array) {
        if (!array.isArray() || array.size() != 2) {
            throw new IllegalArgumentException("targetStrengths must contain exactly two distinct integers");
        }
        var strengths = new ArrayList<Integer>();
        for (JsonNode level : array) {
            if (!level.isIntegralNumber() || !level.canConvertToInt() || level.intValue() < StockfishOpponentService.MIN_ELO
                    || level.intValue() > StockfishOpponentService.MAX_ELO) {
                throw new IllegalArgumentException("Each target strength must be an integer from "
                        + StockfishOpponentService.MIN_ELO + " to " + StockfishOpponentService.MAX_ELO);
            }
            strengths.add(level.intValue());
        }
        if (strengths.getFirst().equals(strengths.getLast())) {
            throw new IllegalArgumentException("Both target strengths must be different");
        }
        return strengths.stream().sorted().toList();
    }

    private static List<String> modelIds(JsonNode array) {
        if (!array.isArray() || array.isEmpty() || array.size() > 8) {
            throw new IllegalArgumentException("modelIds must contain one to eight model IDs");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode model : array) {
            if (!validModelId(model)) {
                throw new IllegalArgumentException("Each model ID must be a provider/model identifier");
            }
            ids.add(model.textValue());
        }
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Model IDs must be distinct");
        return List.copyOf(ids);
    }

    private static boolean validModelId(JsonNode model) {
        return model.isTextual() && model.textValue().length() <= 255
                && model.textValue().matches("~?[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+");
    }

    private static String requiredText(JsonNode body, String field, int maxLength) {
        JsonNode node = body.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().strip().length() > maxLength) {
            throw new IllegalArgumentException(field + " must be text of 1 to " + maxLength + " characters");
        }
        return node.textValue().strip();
    }
}
