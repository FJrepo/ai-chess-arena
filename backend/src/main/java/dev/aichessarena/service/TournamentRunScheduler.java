package dev.aichessarena.service;

import dev.aichessarena.entity.Tournament.AutoRunStatus;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.enterprise.context.control.ActivateRequestContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/** Notifications are hints; persistent run state and the safety tick are the source of truth. */
@ApplicationScoped
public class TournamentRunScheduler {
    private static final Logger LOG = Logger.getLogger(TournamentRunScheduler.class);
    @Inject
    TournamentAutoRunService runs;
    @Inject
    TournamentRepository tournaments;
    @Inject
    GameEngineService engine;
    @Inject
    Event<RunChanged> changes;
    private volatile boolean recovered;

    public record RunChanged(UUID tournamentId) {}

    void recoveryComplete() {
        recovered = true;
    }

    void changed(UUID id) {
        if (recovered) changes.fireAsync(new RunChanged(id));
    }

    @ActivateRequestContext
    void onChange(@ObservesAsync RunChanged event) {
        if (recovered) schedule(event.tournamentId());
    }

    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @ActivateRequestContext
    void tick() {
        if (!recovered) return;
        try {
            engine.sweepRetainedConversations();
        } catch (RuntimeException e) {
            LOG.warn("Retained conversation cleanup failed; the safety tick will retry", e);
        }
        for (var t : tournaments.list("autoRunStatus", AutoRunStatus.RUNNING)) schedule(t.id);
    }

    private void schedule(UUID id) {
        try {
            runs.schedule(id);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Auto-run scheduling failed for tournament %s; the safety tick will retry", id);
        }
    }
}
