package dev.aichessarena.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aichessarena.dto.BenchmarkRunDto;
import dev.aichessarena.dto.BenchmarkRunDto.Summary;
import dev.aichessarena.entity.BenchmarkDefinition;
import dev.aichessarena.entity.BenchmarkDefinition.Opening;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class BenchmarkRunService {
    private static final List<String> COLORS = List.of("WHITE", "BLACK");
    private static final Set<String> CREATE_FIELDS = Set.of(
            "name", "modelIds", "targetStrengths", "legalMoveHints", "reasoningEffort", "chatMode", "runSettings");

    @Inject TournamentRepository tournaments;
    @Inject GameRepository games;
    @Inject TournamentService tournamentService;
    @Inject TournamentAutoRunService autoRuns;
    @Inject OpenRouterKeyStore keys;
    @Inject StockfishOpponentService engine;
    @Inject TournamentRunScheduler scheduler;
    @Inject TransactionSynchronizationRegistry transactions;
    @Inject AnalyticsGameOutcomeClassifier outcomes;

    @Transactional
    public BenchmarkRunDto create(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Request body must be a JSON object");
        body.fieldNames().forEachRemaining(field -> {
            if (!CREATE_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown benchmark setting: " + field);
        });

        Tournament run = new Tournament();
        run.name = requiredText(body, "name", 120);
        List<String> modelIds = modelIds(body.path("modelIds"));
        List<Integer> strengths = strengths(body.path("targetStrengths"));
        if (!body.path("legalMoveHints").isBoolean()) {
            throw new IllegalArgumentException("legalMoveHints must be true or false");
        }
        run.legalMoveHints = body.path("legalMoveHints").booleanValue();
        String effort = requiredText(body, "reasoningEffort", 16);
        run.reasoningEffort = "default".equals(effort) ? null : ReasoningEffort.parse(effort);
        try {
            run.chatMode = ChatMode.valueOf(requiredText(body, "chatMode", 16));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("chatMode must be OFF, OPTIONAL or MANDATORY");
        }
        JsonNode runSettings = body.path("runSettings");
        if (!runSettings.hasNonNull("budgetUsd") || !runSettings.hasNonNull("maxConcurrentGames")) {
            throw new IllegalArgumentException("runSettings must include budgetUsd and maxConcurrentGames");
        }
        TournamentAutoRunService.applyRunSettings(run, runSettings, true);

        keys.requireKey();
        String engineSha256;
        try {
            engineSha256 = engine.benchmarkEngineSha256();
        } catch (IOException e) {
            throw new GameStateConflictException("Install the Stockfish opponent executable before creating a benchmark");
        }

        for (String modelId : modelIds) {
            TournamentParticipant model = new TournamentParticipant();
            model.playerName = modelId;
            model.modelId = modelId;
            tournamentService.prepareParticipant(model);
        }

        run.status = Tournament.TournamentStatus.IN_PROGRESS;
        run.autoRunStatus = Tournament.AutoRunStatus.RUNNING;
        run.benchmarkDefinition = new BenchmarkDefinition(StockfishLadderOpenings.SUITE, StockfishLadderOpenings.OPENINGS,
                modelIds, strengths, COLORS, engineSha256, StockfishOpponentService.SEARCH_NODES,
                StockfishOpponentService.THREADS, StockfishOpponentService.HASH_MIB);
        tournaments.persist(run);
        List<Game> created = createGames(run);
        games.persist(created);
        UUID id = run.id;
        AfterCommit.run(transactions, () -> scheduler.changed(id));
        return report(run, created);
    }

    @Transactional
    public List<Summary> list() {
        return tournaments.list("benchmarkDefinition is not null order by createdAt desc, id").stream()
                .map(BenchmarkRunService::summary).toList();
    }

    @Transactional
    public BenchmarkRunDto get(UUID id) {
        games.beginSnapshotRead();
        Tournament run = tournaments.findById(id);
        if (run == null || !run.isBenchmark()) throw new NotFoundException("Benchmark run not found");
        return report(run, games.findByTournamentId(id));
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

    private static Summary summary(Tournament run) {
        return new Summary(run.id, run.name, run.createdAt, run.autoRunStatus, run.autoRunPauseReason);
    }

    private BenchmarkRunDto report(Tournament run, List<Game> runGames) {
        BenchmarkDefinition definition = run.benchmarkDefinition;
        List<Game> ordered = runGames.stream().sorted(pairOrder()).toList();
        return new BenchmarkRunDto(summary(run), definition, run.legalMoveHints,
                run.reasoningEffort == null ? "default" : run.reasoningEffort.apiValue(), run.chatMode,
                run.budgetUsd.toPlainString(), run.maxConcurrentGames, BenchmarkResults.spend(ordered),
                definition.modelIds().stream().map(modelId -> BenchmarkResults.model(modelId, definition,
                        ordered.stream().filter(game -> modelId.equals(game.benchmarkModelId())).toList(), outcomes))
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
            if (!model.isTextual() || !model.textValue().matches("[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+")
                    || model.textValue().length() > 255) {
                throw new IllegalArgumentException("Each model ID must be a provider/model identifier");
            }
            ids.add(model.textValue());
        }
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Model IDs must be distinct");
        return List.copyOf(ids);
    }

    private static String requiredText(JsonNode body, String field, int maxLength) {
        JsonNode node = body.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().strip().length() > maxLength) {
            throw new IllegalArgumentException(field + " must be text of 1 to " + maxLength + " characters");
        }
        return node.textValue().strip();
    }
}
