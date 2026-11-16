package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AfterCommitTest {

    @Test
    void anActiveTransactionSendsAfterItsCommitOnly() {
        List<String> sent = new ArrayList<>();
        Registry active = new Registry(Status.STATUS_ACTIVE);

        AfterCommit.run(active, () -> sent.add("update"));
        assertEquals(List.of(), sent);
        active.complete(Status.STATUS_COMMITTED);
        assertEquals(List.of("update"), sent);

        Registry rolledBack = new Registry(Status.STATUS_ACTIVE);
        AfterCommit.run(rolledBack, () -> sent.add("rolled back"));
        rolledBack.complete(Status.STATUS_ROLLEDBACK);
        assertEquals(List.of("update"), sent);
    }

    @Test
    void withoutAnActiveTransactionItSendsAtOnceUnlessItWillRollBack() {
        List<String> sent = new ArrayList<>();

        AfterCommit.run(null, () -> sent.add("outside CDI"));
        AfterCommit.run(new Registry(Status.STATUS_NO_TRANSACTION), () -> sent.add("no transaction"));
        // A transaction-phase observer still runs on the thread of the transaction that has just committed.
        AfterCommit.run(new Registry(Status.STATUS_COMMITTED), () -> sent.add("already committed"));
        AfterCommit.run(new Registry(Status.STATUS_MARKED_ROLLBACK), () -> sent.add("will roll back"));
        // An after-completion callback of a transaction that rolled back: its change never happened.
        AfterCommit.run(new Registry(Status.STATUS_ROLLING_BACK), () -> sent.add("rolling back"));
        AfterCommit.run(new Registry(Status.STATUS_ROLLEDBACK), () -> sent.add("rolled back"));

        assertEquals(List.of("outside CDI", "no transaction", "already committed"), sent);
    }

    @Test
    void aFailingUpdateIsLoggedAndSwallowed() {
        Registry active = new Registry(Status.STATUS_ACTIVE);
        AfterCommit.run(active, () -> {
            throw new IllegalStateException("connection closed");
        });

        active.complete(Status.STATUS_COMMITTED);
    }

    private static final class Registry implements TransactionSynchronizationRegistry {
        private final int status;
        private final List<Synchronization> synchronizations = new ArrayList<>();

        Registry(int status) {
            this.status = status;
        }

        void complete(int outcome) {
            synchronizations.forEach(sync -> sync.afterCompletion(outcome));
        }

        @Override
        public Object getTransactionKey() {
            return status == Status.STATUS_NO_TRANSACTION ? null : "tx";
        }

        @Override
        public void putResource(Object key, Object value) {
        }

        @Override
        public Object getResource(Object key) {
            return null;
        }

        @Override
        public void registerInterposedSynchronization(Synchronization sync) {
            if (status != Status.STATUS_ACTIVE) {
                throw new IllegalStateException("No transaction is running");
            }
            synchronizations.add(sync);
        }

        @Override
        public int getTransactionStatus() {
            return status;
        }

        @Override
        public void setRollbackOnly() {
        }

        @Override
        public boolean getRollbackOnly() {
            return status == Status.STATUS_MARKED_ROLLBACK;
        }
    }
}
