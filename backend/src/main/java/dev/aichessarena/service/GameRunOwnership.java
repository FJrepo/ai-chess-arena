package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Durable state of game loops: which loop owns a game (a fencing token with a lease) and whether one of its model
 * requests is in flight. Every write happens under the tournament and game row locks
 * ({@link GameRepository#findByIdForUpdate}).
 *
 * <p>A loop runs with its token bound to {@link #CURRENT}. Each write path of a loop checks {@link #staleWorker} for
 * the game it locked, so a loop that lost its game (for example to restart recovery) can never commit a move, record
 * an attempt or change the game's status. Other callers (requests, the scheduler, recovery) are not loops and are not
 * fenced.
 */
@ApplicationScoped
public class GameRunOwnership {

    private static final Logger LOG = Logger.getLogger(GameRunOwnership.class);

    /** How much longer than the longest single model request a lease lasts without renewal. */
    static final Duration LEASE_MARGIN = Duration.ofMinutes(5);

    /** The game and token of the loop running on this thread; unbound outside game loops. */
    static final ScopedValue<Owner> CURRENT = ScopedValue.newInstance();

    record Owner(UUID gameId, UUID token) {}

    @Inject
    GameRepository games;

    @ConfigProperty(name = "openrouter.request-safety-cap", defaultValue = "PT10M")
    Duration requestSafetyCap = Duration.ofMinutes(10);

    /** Whether this thread is a game loop that no longer owns the locked {@code game}. */
    static boolean staleWorker(Game game) {
        if (!CURRENT.isBound()) {
            return false;
        }
        Owner owner = CURRENT.get();
        boolean stale = owner.gameId().equals(game.id) && !owner.token().equals(game.runOwner);
        if (stale) {
            LOG.warnf("Game %s: discarding a write from a game loop that no longer owns the game", game.id);
        }
        return stale;
    }

    /** Rejects a write by a game loop that no longer owns the locked {@code game}. */
    static void requireOwner(Game game) {
        if (staleWorker(game)) {
            throw new GameStateConflictException("Another game loop owns this game now");
        }
    }

    /** Gives the locked game to the loop with {@code token}, or to no loop for null. */
    void claim(Game game, UUID token) {
        game.runOwner = token;
        game.runLeaseUntil = token == null ? null : leaseFromNow();
    }

    /** Extends the lease when the calling loop owns the locked game (checked with {@link #staleWorker} first). */
    void renew(Game game) {
        if (CURRENT.isBound() && CURRENT.get().gameId().equals(game.id) && game.runOwner != null) {
            game.runLeaseUntil = leaseFromNow();
        }
    }

    static boolean leaseExpired(Game game, Instant now) {
        return game.runOwner != null && game.runLeaseUntil != null && !game.runLeaseUntil.isAfter(now);
    }

    /**
     * Takes an in-progress game without a live owner for a new loop (after a human move). Returns false when the game
     * is not in progress or another loop's lease still holds it.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean acquire(UUID gameId, UUID token) {
        Game game = games.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS) {
            return false;
        }
        if (game.runOwner != null && !leaseExpired(game, Instant.now())) {
            LOG.warnf("Game %s is still leased by another game loop", gameId);
            return false;
        }
        claim(game, token);
        return true;
    }

    /**
     * Records, and commits, that a model request is about to be sent: the last check before the provider is called.
     * Returns false, and nothing may be sent, when the game is no longer in progress or the calling loop lost it.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean markRequestSent(UUID gameId) {
        Game game = games.findByIdForUpdate(gameId);
        if (game == null || game.status != GameStatus.IN_PROGRESS || staleWorker(game)) {
            return false;
        }
        game.requestSentAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        renew(game);
        return true;
    }

    /** The answer to the request in flight is recorded in the caller's transaction. */
    static void answerRecorded(Game game) {
        game.requestSentAt = null;
    }

    /** Gives up ownership when the loop with {@code token} ends; a later owner keeps the game. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void release(UUID gameId, UUID token) {
        Game game = games.findByIdForUpdate(gameId);
        if (game != null && token.equals(game.runOwner)) {
            claim(game, null);
        }
    }

    private Instant leaseFromNow() {
        return Instant.now().plus(requestSafetyCap).plus(LEASE_MARGIN).truncatedTo(ChronoUnit.MICROS);
    }
}
