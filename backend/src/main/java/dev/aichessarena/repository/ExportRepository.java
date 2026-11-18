package dev.aichessarena.repository;

import dev.aichessarena.dto.TournamentExportDto.Attempt;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.OpeningMode;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.Session;

/**
 * Export-only scalar projections. No persistence entities, raw answers or private prompts are selected.
 * Cursors fetch at most 128 rows at a time; only allowlisted transient snapshots survive each batch.
 * The response still occupies memory proportional to the exported data.
 */
@ApplicationScoped
public class ExportRepository {
    @Inject
    Session session;

    private static final String TOURNAMENT_SELECT = """
            select t.id as id,
                   t.name as name,
                   t.status as status,
                   t.createdAt as createdAt,
                   t.updatedAt as updatedAt,
                   t.format as format,
                   t.drawPolicy as drawPolicy,
                   t.moveTimeoutSeconds as moveTimeoutSeconds,
                   t.maxAttempts as maxAttempts,
                   t.matchupBestOf as matchupBestOf,
                   t.finalsBestOf as finalsBestOf,
                   t.chatMode as chatMode,
                   t.banterLevel as banterLevel,
                   t.resignationAllowed as resignationAllowed,
                   t.legalMoveHints as legalMoveHints,
                   t.adjudicationEnabled as adjudicationEnabled,
                   t.maxOutputTokens as maxOutputTokens,
                   t.reasoningEffort as reasoningEffort,
                   t.openingMode as openingMode,
                   t.openingId as openingId,
                   t.autoRunStatus as autoRunStatus,
                   t.maxConcurrentGames as maxConcurrentGames,
                   t.budgetUsd as budgetUsd
            from Tournament t
            """;

    private static final String PARTICIPANT_SELECT = """
            select p.id as id,
                   p.playerName as playerName,
                   p.modelId as modelId,
                   p.controlType as controlType,
                   p.seed as seed,
                   p.reasoningEffort as reasoningEffort
            from TournamentParticipant p
            """;

    private static final String GAME_SELECT = """
            select g.id as id,
                   g.whitePlayerName as whitePlayerName,
                   g.whiteModelId as whiteModelId,
                   g.blackPlayerName as blackPlayerName,
                   g.blackModelId as blackModelId,
                   g.status as status,
                   g.result as result,
                   g.resultReason as resultReason,
                   g.currentFen as currentFen,
                   g.openingId as openingId,
                   g.openingName as openingName,
                   g.openingEco as openingEco,
                   g.bracketRound as bracketRound,
                   g.bracketPosition as bracketPosition,
                   g.seriesId as seriesId,
                   g.seriesGameNumber as seriesGameNumber,
                   g.seriesBestOf as seriesBestOf,
                   g.totalCostUsd as totalCostUsd,
                   g.createdAt as createdAt,
                   g.startedAt as startedAt,
                   g.completedAt as completedAt,
                   g.whiteParticipant.id as whiteParticipantId,
                   g.blackParticipant.id as blackParticipantId,
                   g.advancedParticipant.id as advancedParticipantId
            from Game g
            """;

    private static final String MOVE_SELECT = """
            select m.id as id,
                   m.moveNumber as moveNumber,
                   m.color as color,
                   m.san as san,
                   m.fen as fen,
                   m.modelId as modelId,
                   m.promptVersion as promptVersion,
                   m.promptHash as promptHash,
                   m.promptTokens as promptTokens,
                   m.completionTokens as completionTokens,
                   m.costUsd as costUsd,
                   m.responseTimeMs as responseTimeMs,
                   m.retryCount as retryCount,
                   m.isOverride as isOverride,
                   m.evaluationCp as evaluationCp,
                   m.evaluationMate as evaluationMate,
                   m.createdAt as createdAt,
                   m.chatMode as chatMode,
                   m.isBook as isBook,
                   m.game.id as gameId
            from Move m
            """;

    private static final String CHAT_SELECT = """
            select c.id as id,
                   c.moveNumber as moveNumber,
                   c.senderModel as senderModel,
                   c.senderColor as senderColor,
                   c.message as message,
                   c.createdAt as createdAt,
                   c.game.id as gameId
            from ChatMessage c
            """;

    private static final String ATTEMPT_SELECT = """
            select a.id as id,
                   a.moveNumber as moveNumber,
                   a.color as color,
                   a.attemptNumber as attemptNumber,
                   a.modelId as modelId,
                   a.outcome as outcome,
                   a.promptTokens as promptTokens,
                   a.cachedPromptTokens as cachedPromptTokens,
                   a.cacheWriteTokens as cacheWriteTokens,
                   a.completionTokens as completionTokens,
                   a.reasoningTokens as reasoningTokens,
                   a.effectiveMaxOutputTokens as effectiveMaxOutputTokens,
                   a.modelMaxCompletionTokens as modelMaxCompletionTokens,
                   a.reasoningEffort as reasoningEffort,
                   a.responseTimeMs as responseTimeMs,
                   a.costUsd as costUsd,
                   a.createdAt as createdAt,
                   a.game.id as gameId
            from MoveAttempt a
            """;

    public record Snapshot(Tournament tournament, List<Game> games, Map<UUID, List<Attempt>> attempts) {}

    public Snapshot read(UUID id, boolean singleGame, boolean includeChat, boolean includeAttempts) {
        String tournamentFilter = singleGame
                ? "where t.id = (select g.tournament.id from Game g where g.id = :id)"
                : "where t.id = :id and t.benchmarkDefinition is null";
        Tournament tournament = session.createQuery(TOURNAMENT_SELECT + tournamentFilter, Tuple.class)
                .setParameter("id", id).getResultList().stream().map(this::tournament).findFirst().orElse(null);
        if (tournament == null && !singleGame) throw new NotFoundException();
        if (tournament != null) {
            readRows(PARTICIPANT_SELECT + "where p.tournament.id = :id order by p.seed, p.id",
                    tournament.id, row -> tournament.participants.add(participant(row)));
        }
        Map<UUID, Game> games = new LinkedHashMap<>();
        readRows(GAME_SELECT + (singleGame ? "where g.id = :id" : "where g.tournament.id = :id"), id, row -> {
            Game game = game(row);
            game.tournament = tournament;
            games.put(game.id, game);
            if (tournament != null) tournament.games.add(game);
        });
        if (singleGame && games.isEmpty()) throw new NotFoundException();
        String childFilter = singleGame ? "where %s.game.id = :id" : "where %s.game.tournament.id = :id";
        readRows(MOVE_SELECT + childFilter.formatted("m"), id, row -> {
            Game game = games.get(row.get("gameId", UUID.class));
            if (game != null) {
                Move move = move(row);
                move.game = game;
                game.moves.add(move);
            }
        });
        if (includeChat) {
            readRows(CHAT_SELECT + childFilter.formatted("c") + " order by c.createdAt, c.id", id, row -> {
                Game game = games.get(row.get("gameId", UUID.class));
                if (game != null) game.chatMessages.add(chat(row));
            });
        }
        Map<UUID, List<Attempt>> attempts = new LinkedHashMap<>();
        if (includeAttempts) {
            readRows(ATTEMPT_SELECT + childFilter.formatted("a")
                    + " order by a.moveNumber, a.color desc, a.createdAt, a.attemptNumber, a.id", id, row -> {
                UUID gameId = row.get("gameId", UUID.class);
                if (games.containsKey(gameId)) attempts.computeIfAbsent(gameId, key -> new ArrayList<>()).add(attempt(row));
            });
        }
        return new Snapshot(tournament, new ArrayList<>(games.values()), attempts);
    }

    private void readRows(String hql, UUID id, Consumer<Tuple> consume) {
        try (var rows = session.createQuery(hql, Tuple.class).setParameter("id", id)
                .setFetchSize(128).getResultStream()) {
            rows.forEach(consume);
        }
    }

    private static TournamentParticipant participantId(UUID id) {
        if (id == null) return null;
        TournamentParticipant participant = new TournamentParticipant();
        participant.id = id;
        return participant;
    }

    private Tournament tournament(Tuple row) {
        Tournament t = new Tournament();
        t.id = row.get("id", UUID.class);
        t.name = row.get("name", String.class);
        t.status = row.get("status", Tournament.TournamentStatus.class);
        t.createdAt = row.get("createdAt", LocalDateTime.class);
        t.updatedAt = row.get("updatedAt", LocalDateTime.class);
        t.format = row.get("format", Tournament.TournamentFormat.class);
        t.drawPolicy = row.get("drawPolicy", Tournament.DrawPolicy.class);
        t.moveTimeoutSeconds = row.get("moveTimeoutSeconds", Integer.class);
        t.maxAttempts = row.get("maxAttempts", Integer.class);
        t.matchupBestOf = row.get("matchupBestOf", Integer.class);
        t.finalsBestOf = row.get("finalsBestOf", Integer.class);
        t.chatMode = row.get("chatMode", ChatMode.class);
        t.banterLevel = row.get("banterLevel", BanterLevel.class);
        t.resignationAllowed = row.get("resignationAllowed", Boolean.class);
        t.legalMoveHints = row.get("legalMoveHints", Boolean.class);
        t.adjudicationEnabled = row.get("adjudicationEnabled", Boolean.class);
        t.maxOutputTokens = row.get("maxOutputTokens", Integer.class);
        t.reasoningEffort = row.get("reasoningEffort", ReasoningEffort.class);
        t.openingMode = row.get("openingMode", OpeningMode.class);
        t.openingId = row.get("openingId", String.class);
        t.autoRunStatus = row.get("autoRunStatus", Tournament.AutoRunStatus.class);
        t.maxConcurrentGames = row.get("maxConcurrentGames", Integer.class);
        t.budgetUsd = row.get("budgetUsd", BigDecimal.class);
        return t;
    }

    private TournamentParticipant participant(Tuple row) {
        TournamentParticipant p = new TournamentParticipant();
        p.id = row.get("id", UUID.class);
        p.playerName = row.get("playerName", String.class);
        p.modelId = row.get("modelId", String.class);
        p.controlType = row.get("controlType", TournamentParticipant.ControlType.class);
        p.seed = row.get("seed", Integer.class);
        p.reasoningEffort = row.get("reasoningEffort", ReasoningEffort.class);
        return p;
    }

    private Game game(Tuple row) {
        Game g = new Game();
        g.id = row.get("id", UUID.class);
        g.whitePlayerName = row.get("whitePlayerName", String.class);
        g.whiteModelId = row.get("whiteModelId", String.class);
        g.blackPlayerName = row.get("blackPlayerName", String.class);
        g.blackModelId = row.get("blackModelId", String.class);
        g.status = row.get("status", Game.GameStatus.class);
        g.result = row.get("result", Game.GameResult.class);
        g.resultReason = row.get("resultReason", Game.ResultReason.class);
        g.currentFen = row.get("currentFen", String.class);
        g.openingId = row.get("openingId", String.class);
        g.openingName = row.get("openingName", String.class);
        g.openingEco = row.get("openingEco", String.class);
        g.bracketRound = row.get("bracketRound", String.class);
        g.bracketPosition = row.get("bracketPosition", Integer.class);
        g.seriesId = row.get("seriesId", UUID.class);
        g.seriesGameNumber = row.get("seriesGameNumber", Integer.class);
        g.seriesBestOf = row.get("seriesBestOf", Integer.class);
        g.totalCostUsd = row.get("totalCostUsd", BigDecimal.class);
        g.createdAt = row.get("createdAt", LocalDateTime.class);
        g.startedAt = row.get("startedAt", LocalDateTime.class);
        g.completedAt = row.get("completedAt", LocalDateTime.class);
        g.whiteParticipant = participantId(row.get("whiteParticipantId", UUID.class));
        g.blackParticipant = participantId(row.get("blackParticipantId", UUID.class));
        g.advancedParticipant = participantId(row.get("advancedParticipantId", UUID.class));
        return g;
    }

    private Move move(Tuple row) {
        Move m = new Move();
        m.id = row.get("id", UUID.class);
        m.moveNumber = row.get("moveNumber", Integer.class);
        m.color = row.get("color", String.class);
        m.san = row.get("san", String.class);
        m.fen = row.get("fen", String.class);
        m.modelId = row.get("modelId", String.class);
        m.promptVersion = row.get("promptVersion", String.class);
        m.promptHash = row.get("promptHash", String.class);
        m.promptTokens = row.get("promptTokens", Integer.class);
        m.completionTokens = row.get("completionTokens", Integer.class);
        m.costUsd = row.get("costUsd", BigDecimal.class);
        m.responseTimeMs = row.get("responseTimeMs", Long.class);
        m.retryCount = row.get("retryCount", Integer.class);
        m.isOverride = row.get("isOverride", Boolean.class);
        m.evaluationCp = row.get("evaluationCp", Integer.class);
        m.evaluationMate = row.get("evaluationMate", Integer.class);
        m.createdAt = row.get("createdAt", LocalDateTime.class);
        m.chatMode = row.get("chatMode", ChatMode.class);
        m.isBook = row.get("isBook", Boolean.class);
        return m;
    }

    private ChatMessage chat(Tuple row) {
        ChatMessage c = new ChatMessage();
        c.id = row.get("id", UUID.class);
        c.moveNumber = row.get("moveNumber", Integer.class);
        c.senderModel = row.get("senderModel", String.class);
        c.senderColor = row.get("senderColor", String.class);
        c.message = row.get("message", String.class);
        c.createdAt = row.get("createdAt", LocalDateTime.class);
        return c;
    }

    private Attempt attempt(Tuple row) {
        ReasoningEffort effort = row.get("reasoningEffort", ReasoningEffort.class);
        return new Attempt(
                row.get("id", UUID.class),
                row.get("moveNumber", Integer.class),
                row.get("color", String.class),
                row.get("attemptNumber", Integer.class),
                row.get("modelId", String.class),
                row.get("outcome", MoveAttempt.Outcome.class).name(),
                row.get("promptTokens", Integer.class),
                row.get("cachedPromptTokens", Integer.class),
                row.get("cacheWriteTokens", Integer.class),
                row.get("completionTokens", Integer.class),
                row.get("reasoningTokens", Integer.class),
                row.get("effectiveMaxOutputTokens", Integer.class),
                row.get("modelMaxCompletionTokens", Integer.class),
                effort == null ? null : effort.apiValue(),
                row.get("responseTimeMs", Long.class),
                row.get("costUsd", BigDecimal.class),
                row.get("createdAt", LocalDateTime.class));
    }
}
