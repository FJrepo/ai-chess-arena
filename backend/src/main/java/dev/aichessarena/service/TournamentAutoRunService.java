package dev.aichessarena.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Tournament.AutoRunStatus;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.entity.TournamentParticipant.ControlType;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** All decisions and writes are serialized by the tournament row lock, including manual starts. */
@ApplicationScoped
public class TournamentAutoRunService {
    @Inject
    OpenRouterKeyStore keys;

    @Inject
    TournamentRepository tournaments;
    @Inject
    GameRepository games;
    @Inject
    GameEngineService engine;
    @Inject
    GameLifecycleService lifecycle;
    @Inject
    TournamentRunScheduler scheduler;
    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    static final Comparator<Game> BRACKET_ORDER = Comparator
            .comparingInt((Game g) -> -TournamentService.roundSizeForName(g.bracketRound))
            .thenComparing(g -> g.bracketPosition, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(g -> g.seriesGameNumber)
            .thenComparing(g -> g.id);

    private static final Comparator<Game> RESUME_ORDER = Comparator
            .comparing((Game g) -> g.resumeRequestedAt)
            .thenComparing(g -> g.id);

    /** Validates and applies settings without changing run state or scheduling games. */
    public static void applyRunSettings(Tournament t, JsonNode body, boolean allowExactBudget) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        body.fieldNames().forEachRemaining(field -> {
            if (!Set.of("maxConcurrentGames", "budgetUsd").contains(field)) {
                throw new IllegalArgumentException("Unknown run setting: " + field);
            }
        });
        if (body.has("maxConcurrentGames")) {
            JsonNode value = body.get("maxConcurrentGames");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 8) {
                throw new IllegalArgumentException("maxConcurrentGames must be an integer between 1 and 8");
            }
            t.maxConcurrentGames = value.intValue();
        }
        if (body.has("budgetUsd")) {
            JsonNode value = body.get("budgetUsd");
            if (value.isNull()) {
                t.budgetUsd = null;
            } else {
                BigDecimal budget;
                if (value.isNumber()) {
                    budget = value.decimalValue();
                } else if (allowExactBudget && value.isTextual()
                        && value.asText().matches("[0-9]{1,17}(\\.[0-9]{1,2})?")) {
                    budget = new BigDecimal(value.asText());
                } else {
                    throw new IllegalArgumentException(allowExactBudget
                            ? "budgetUsd must be a number, an exact decimal string or null"
                            : "budgetUsd must be a number or null");
                }
                if (budget.signum() <= 0 || budget.stripTrailingZeros().scale() > 2
                        || budget.compareTo(new BigDecimal("99999999999999999.99")) > 0) {
                    throw new IllegalArgumentException("budgetUsd must be positive with at most 2 decimal places");
                }
                t.budgetUsd = budget.setScale(2);
            }
        }
    }

    @Transactional
    public Tournament configure(UUID id, JsonNode body, boolean resume) {
        Tournament t = editable(id);
        if (resume && keys != null) keys.requireKey();
        applyRunSettings(t, body, false);
        if (resume) {
            t.autoRunStatus = AutoRunStatus.RUNNING;
            t.autoRunPauseReason = null;
        }
        t.updatedAt = LocalDateTime.now();
        if (t.autoRunStatus == AutoRunStatus.RUNNING && !pauseIfBudgetReached(t)) {
            List<Game> running = games.findByTournamentId(id).stream()
                    .filter(g -> g.status == GameStatus.IN_PROGRESS).sorted(BRACKET_ORDER).toList();
            for (int i = t.maxConcurrentGames; i < running.size(); i++) {
                pauseGame(running.get(i).id, "Concurrency limit reduced to " + t.maxConcurrentGames);
            }
        }
        changed(id);
        return t;
    }

    @Transactional
    public Tournament pause(UUID id) {
        Tournament t = editable(id);
        pauseRun(t, "Paused by user");
        return t;
    }

    private Tournament editable(UUID id) {
        Tournament t = tournaments.findByIdForUpdate(id);
        if (t == null) throw new WebApplicationException("Tournament not found", 404);
        TournamentService.requireEditableSettings(t);
        if (t.status != TournamentStatus.IN_PROGRESS || games.findByTournamentId(id).isEmpty()) {
            throw new WebApplicationException("Generate the bracket before running the tournament", 409);
        }
        return t;
    }

    /** Validates a locked start and reports whether a manual resume should stay paused and queue. */
    boolean shouldQueueResume(Game game, boolean automatic, boolean queueIfFull) {
        Tournament t = game.tournament;
        if (automatic && (t == null || t.autoRunStatus != AutoRunStatus.RUNNING
                || !(queuedResume(game) || eligible(game)))) {
            throw new GameStateConflictException("Game is not eligible for auto-run");
        }
        if (t == null || t.autoRunStatus == AutoRunStatus.OFF || t.autoRunStatus == AutoRunStatus.FINISHED) {
            return false;
        }
        if (!automatic && queuedResume(game)) {
            if (queueIfFull) return true;
            throw new GameStateConflictException("A resume is already queued for this game");
        }
        if (t.autoRunStatus != AutoRunStatus.RUNNING) {
            throw new GameStateConflictException("Resume the tournament run first");
        }
        if (budgetReached(t, spend(t.id))) {
            throw new GameStateConflictException("Tournament budget reached; raise the budget and resume the run");
        }
        long freeSlots = t.maxConcurrentGames - games.countRunning(t.id);
        if (freeSlots <= 0) {
            if (queueIfFull && game.status == GameStatus.PAUSED) return true;
            throw new GameStateConflictException("Tournament concurrency limit reached");
        }
        if (!automatic && games.count("tournament.id = ?1 and status = ?2 and resumeRequestedAt is not null",
                t.id, GameStatus.PAUSED) >= freeSlots) {
            if (queueIfFull && game.status == GameStatus.PAUSED) return true;
            throw new GameStateConflictException("Available slots are reserved for queued resumes");
        }
        return false;
    }

    /** Checks every request, including provider/invalid-answer retries, using fresh locked state. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean beforeRequest(UUID gameId) {
        Game game = games.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS || GameLifecycleService.isTournamentCancelled(game)
                || GameRunOwnership.staleWorker(game)) {
            return false;
        }
        Tournament t = game.tournament;
        if (t == null || t.autoRunStatus == AutoRunStatus.OFF || t.autoRunStatus == AutoRunStatus.FINISHED) return true;
        return t.autoRunStatus == AutoRunStatus.RUNNING && !pauseIfBudgetReached(t);
    }

    /** Joins attempt recording, so its spend and the resulting pause commit together. */
    @Transactional
    public void afterAttempt(Game game) {
        if (game.tournament != null && game.tournament.autoRunStatus == AutoRunStatus.RUNNING) {
            pauseIfBudgetReached(game.tournament);
        }
    }

    @Transactional
    public void schedule(UUID id) {
        Tournament t = tournaments.findByIdForUpdate(id);
        if (t == null || t.autoRunStatus != AutoRunStatus.RUNNING) return;
        if (t.status == TournamentStatus.CANCELLED) {
            lifecycle.clearResumeRequests(id);
            t.autoRunStatus = AutoRunStatus.OFF;
            t.autoRunPauseReason = null;
            return;
        }
        List<Game> bracket = games.findByTournamentId(id);
        if (t.status == TournamentStatus.COMPLETED
                || (!bracket.isEmpty() && bracket.stream().allMatch(GameLifecycleService::isTerminal))) {
            lifecycle.clearResumeRequests(id);
            t.autoRunStatus = AutoRunStatus.FINISHED;
            t.autoRunPauseReason = null;
            return;
        }
        if (keys != null && keys.key().isBlank()) {
            pauseRun(t, "Connect OpenRouter to continue this tournament");
            return;
        }
        if (pauseIfBudgetReached(t)) return;
        long occupiedSlots = games.countRunning(id);
        for (Game game : bracket.stream().filter(TournamentAutoRunService::queuedResume).sorted(RESUME_ORDER).toList()) {
            if (occupiedSlots >= t.maxConcurrentGames) return;
            // Each request takes one slot, reserved for the next tick if its previous loop is still stopping.
            engine.startScheduledGame(game.id);
            occupiedSlots++;
        }
        for (Game game : bracket.stream().filter(TournamentAutoRunService::eligible).sorted(BRACKET_ORDER).toList()) {
            if (occupiedSlots >= t.maxConcurrentGames) break;
            if (engine.startScheduledGame(game.id) != null) occupiedSlots++;
        }
    }

    private static boolean queuedResume(Game game) {
        return game.status == GameStatus.PAUSED && game.resumeRequestedAt != null;
    }

    static boolean eligible(Game game) {
        return game.status == GameStatus.WAITING && game.whiteParticipant != null && game.blackParticipant != null
                && game.whiteParticipant.controlType != ControlType.HUMAN
                && game.blackParticipant.controlType != ControlType.HUMAN;
    }

    private BigDecimal spend(UUID id) {
        // Aggregate in the DB: do not reuse games cached earlier by a long-lived loop/request.
        return games.getEntityManager().createQuery(
                "select coalesce(sum(g.totalCostUsd), 0) from Game g where g.tournament.id = :id", BigDecimal.class)
                .setParameter("id", id).getSingleResult();
    }

    private boolean budgetReached(Tournament t, BigDecimal spent) {
        return t.budgetUsd != null && spent.compareTo(t.budgetUsd) >= 0;
    }

    private boolean pauseIfBudgetReached(Tournament t) {
        BigDecimal spent = spend(t.id);
        if (!budgetReached(t, spent)) return false;
        pauseRun(t, "Budget of $" + t.budgetUsd.toPlainString() + " reached ($"
                + spent.setScale(2, RoundingMode.HALF_UP).toPlainString() + " spent)");
        return true;
    }

    private void pauseRun(Tournament t, String reason) {
        t.autoRunStatus = AutoRunStatus.PAUSED;
        t.autoRunPauseReason = reason;
        t.updatedAt = LocalDateTime.now();
        for (Game game : games.findByTournamentId(t.id)) {
            if (game.status == GameStatus.IN_PROGRESS) pauseGame(game.id, reason);
        }
        changed(t.id);
    }

    private void pauseGame(UUID gameId, String reason) {
        Runnable stop = engine.captureStop(gameId);
        lifecycle.pauseWithReason(gameId, reason);
        AfterCommit.run(transactionRegistry, stop);
    }

    private void changed(UUID id) {
        AfterCommit.run(transactionRegistry, () -> scheduler.changed(id));
    }
}
