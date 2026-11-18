package dev.aichessarena.service;

import dev.aichessarena.service.GameLifecycleService.Interruption;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Continues games whose loop did not survive: at startup every game that was running, and on the scheduler's safety
 * tick a game whose loop stopped renewing its lease. Work never sent to the provider resumes; a game with a model
 * request in flight pauses for the operator, so a possibly charged request is never sent twice
 * ({@link TurnPolicy#afterInterruption}).
 */
@ApplicationScoped
public class GameRecovery {

    private static final Logger LOG = Logger.getLogger(GameRecovery.class);

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameEngineService engine;

    @Inject
    TournamentRunScheduler scheduler;

    void onStart(@Observes StartupEvent event) {
        recover(gameLifecycleService.gamesInterruptedByRestart(), Interruption.RESTART);
        // Auto-runs stay as they were: scheduling (queued resumes, then waiting games) starts once recovery is done.
        scheduler.recoveryComplete();
    }

    void recoverExpiredLeases() {
        recover(gameLifecycleService.gamesWithExpiredLease(), Interruption.EXPIRED_LEASE);
    }

    /**
     * Each game is first claimed in this process, so neither a manual start nor a human move can start a second loop
     * while it is decided; the decision itself runs under the tournament and game locks.
     */
    void recover(Collection<UUID> gameIds, Interruption cause) {
        for (UUID gameId : gameIds) {
            GameEngineService.GameLoop loop = engine.tryClaimLoop(gameId);
            if (loop == null) {
                continue; // a loop of this process owns the game
            }
            TurnPolicy.Action action = null;
            try {
                action = gameLifecycleService.recoverInterruptedGame(gameId, loop.token(), cause);
            } catch (RuntimeException e) {
                LOG.errorf(e, "Could not recover game %s", gameId);
            }
            if (action == TurnPolicy.Action.RESUME) {
                engine.spawnGameLoop(gameId, loop, null, null);
            } else {
                engine.releaseLoop(gameId, loop);
            }
        }
    }
}
