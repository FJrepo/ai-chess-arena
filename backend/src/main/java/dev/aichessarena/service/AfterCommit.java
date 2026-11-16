package dev.aichessarena.service;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

/**
 * Runs live updates once the current transaction has committed, and never after a rollback. Updates must not
 * reach viewers before the state they announce is visible: a viewer that reloads on an update would otherwise read
 * the old state. A method joined into an outer transaction announces with the outer commit. Values an update needs
 * are read by the caller before, while the entities are still attached.
 */
final class AfterCommit {

    private static final Logger LOG = Logger.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    /**
     * Runs {@code update} after the commit of the active transaction. A transaction already marked for rollback, or
     * rolling or rolled back, never sends it. Without an active transaction it runs at once: no transaction at all
     * (or outside CDI, registry null), or a thread still associated with one that has already committed (for example
     * a transaction-phase observer), where nothing is left to wait for. The registry describes the calling thread's
     * transaction only, so this must be called on the thread that made the change.
     */
    static void run(TransactionSynchronizationRegistry registry, Runnable update) {
        int status = registry == null ? Status.STATUS_NO_TRANSACTION : registry.getTransactionStatus();
        if (status == Status.STATUS_MARKED_ROLLBACK || status == Status.STATUS_ROLLING_BACK
                || status == Status.STATUS_ROLLEDBACK) {
            return;
        }
        if (status != Status.STATUS_ACTIVE) {
            update.run();
            return;
        }
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // Nothing to do before the commit.
            }

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    return;
                }
                try {
                    update.run();
                } catch (RuntimeException e) {
                    // The change is committed; a failed live update must not look like a failed change.
                    LOG.warnf(e, "Could not send a live update after commit");
                }
            }
        });
    }
}
