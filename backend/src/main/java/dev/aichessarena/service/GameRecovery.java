package dev.aichessarena.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Puts games that were running before a restart into a resumable state. */
@ApplicationScoped
public class GameRecovery {

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    TournamentAutoRunService autoRuns;

    @Inject
    TournamentRunScheduler scheduler;

    void onStart(@Observes StartupEvent event) {
        autoRuns.pauseRunsOrphanedByRestart();
        gameLifecycleService.pauseGamesOrphanedByRestart();
        scheduler.recoveryComplete();
    }
}
