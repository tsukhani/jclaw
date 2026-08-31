import jakarta.persistence.EntityManager;
import models.EventLog;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.libs.F;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link Tx} — the transaction helper that adapts between Play's
 * request-thread ambient transaction and the "opened-from-a-virtual-thread"
 * case where we must start one ourselves.
 */
class TxTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void runReturnsValueFromSupplier() {
        var result = Tx.run(() -> "hello");
        assertEquals("hello", result);
    }

    @Test
    void runRunnableExecutes() {
        var ran = new AtomicBoolean(false);
        Tx.run(() -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void runPersistsInNewTransactionWhenNoneActive() {
        // Test thread has no ambient transaction when starting fresh here
        // (Fixtures.deleteDatabase already returned). Tx.run must open one.
        Tx.run(() -> {
            var log = new EventLog();
            log.level = "INFO";
            log.category = "tx-test";
            log.message = "persisted";
            log.timestamp = Instant.now();
            log.save();
        });

        // Read back in a fresh transaction. Persistence only succeeds if the
        // first Tx.run actually committed.
        List<EventLog> rows = Tx.run(() -> EventLog.<EventLog>find(
                "category = ?1", "tx-test").fetch());
        assertEquals(1, rows.size());
        assertEquals("persisted", rows.getFirst().message);
    }

    @Test
    void runNestsInsideAmbientTransactionWithoutOpeningNew() {
        // The fast path at Tx.java:18 — when already inside a transaction,
        // the block runs directly without invoking JPA.withTransaction.
        // We can't easily observe "did we open a new tx?", but we CAN verify
        // the observable behavior: no exception, and JPA.isInsideTransaction
        // reports true throughout.
        var sawAmbient = new AtomicBoolean(false);
        Tx.run(() -> {
            // Inside the outer (possibly new) transaction
            assertTrue(JPA.isInsideTransaction(), "outer block must be in a tx");
            Tx.run(() -> {
                // Inside the nested call — must see the SAME transaction
                sawAmbient.set(JPA.isInsideTransaction());
            });
        });
        assertTrue(sawAmbient.get(), "nested Tx.run must observe ambient transaction");
    }

    @Test
    void runPropagatesRuntimeExceptionUnchanged() {
        // RuntimeException path at Tx.java:21-22 / 28-29 — a RuntimeException
        // from the block must re-surface AS-IS (not wrapped) so callers can
        // catch specific subtypes like IllegalStateException cleanly.
        var thrown = assertThrows(IllegalStateException.class, () -> Tx.run(() -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals("boom", thrown.getMessage());
        assertNull(thrown.getCause(), "RuntimeException must not be wrapped");
        // Rollback visibility of a persist-then-throw sequence is a JPA
        // property, not a Tx.run property — and it can't be observed inside
        // this test's own ambient transaction. Covered by integration tests.
    }

    // --- afterCommit: eviction ordering (JCLAW-1042) ---

    @Test
    void afterCommitRunsImmediatelyWhenThereIsNoTransactionToWaitFor() throws Exception {
        // The arm that can go quietly wrong. Tx.run joins an ambient transaction rather than
        // opening its own, so a caller cannot tell whether it has already committed; if this
        // arm registered a synchronization instead of running, the eviction would be dropped
        // outright rather than delayed — a permanently stale cache entry, not a briefly one.
        // Driven from a plain thread, which has no JPA context regardless of what the harness
        // holds, so the branch under test is the no-transaction one either way.
        var insideTx = new AtomicBoolean(true);
        var ran = new AtomicBoolean(false);
        var probe = new Thread(() -> {
            insideTx.set(JPA.isInsideTransaction());
            Tx.afterCommit(() -> ran.set(true));
        });
        probe.start();
        probe.join();
        assertFalse(insideTx.get(), "precondition: a plain thread has no ambient JPA transaction");
        assertTrue(ran.get(), "with no transaction to wait for, the action must run immediately");
    }

    @Test
    void afterCommitDefersUntilTheTransactionCommits() {
        // Fresh, thread-unbound EntityManager so the commit boundary is this test's to drive —
        // the same device ConfigServiceTest uses for scheduleRollbackEviction, and for the same
        // reason: driving the harness's own transaction from a test body would disturb it.
        var ran = new AtomicBoolean(false);
        EntityManager em = JPA.newEntityManager("default");
        try {
            em.getTransaction().begin();
            Tx.afterCommit(em.unwrap(Session.class), () -> ran.set(true));
            assertFalse(ran.get(), "the action must not run while the transaction is still open");
            em.getTransaction().commit();
        } finally {
            if (em.isOpen()) em.close();
        }
        assertTrue(ran.get(), "the action must run once the transaction commits");
    }

    @Test
    void afterCommitDoesNotRunOnRollback() {
        // A rolled-back write invalidated nothing, so evicting for it would discard a live
        // cache entry for no reason.
        var ran = new AtomicBoolean(false);
        EntityManager em = JPA.newEntityManager("default");
        try {
            em.getTransaction().begin();
            Tx.afterCommit(em.unwrap(Session.class), () -> ran.set(true));
            em.getTransaction().rollback();
        } finally {
            if (em.isOpen()) em.close();
        }
        assertFalse(ran.get(), "a rolled-back transaction must not fire the after-commit action");
    }

    @Test
    void runWrapsCheckedExceptionInRuntimeException() {
        // Checked Throwable path at Tx.java:22-23 / 29-30 — anything that's
        // not a RuntimeException gets wrapped so callers only need one catch.
        // Explicit F.Function0 typing disambiguates from the Runnable overload
        // (Runnable.run cannot throw checked exceptions).
        F.Function0<Object> throwing = () -> { throw new Exception("checked"); };
        var thrown = assertThrows(RuntimeException.class, () -> Tx.run(throwing));
        assertNotNull(thrown.getCause());
        assertEquals("checked", thrown.getCause().getMessage());
    }

    // ---- JCLAW-1144: shutdown teardown must not reach the database ----

    /**
     * Tx.run warns when a shutdown component reaches the database, so teardown that depends
     * on the DB names itself instead of surfacing as an unexplained "JDBC begin transaction
     * failed". These cover the predicate rather than the wiring: driving the real path means
     * calling EventLogger.markShuttingDown(), a one-way process-global latch that would send
     * every concurrently-running test class's logging file-only for the rest of the JVM.
     */
    @Test
    void tripwireFiresForShutdownThreadsDuringShutdown() {
        assertTrue(Tx.isShutdownTeardownThread(true, "shutdown-tailscale-funnel"));
        assertTrue(Tx.isShutdownTeardownThread(true, "shutdown-db-scheduler"));
    }

    @Test
    void tripwireStaysSilentBeforeShutdownStarts() {
        // The thread name alone must not trigger it — nothing is being torn down yet.
        assertFalse(Tx.isShutdownTeardownThread(false, "shutdown-tailscale-funnel"));
    }

    @Test
    void tripwireStaysSilentForOrdinaryThreadsDuringShutdown() {
        // Request and agent threads legitimately finish DB work while shutdown runs.
        assertFalse(Tx.isShutdownTeardownThread(true, "play-vthread-262"));
        assertFalse(Tx.isShutdownTeardownThread(true, "agent-tool-parallel"));
        assertFalse(Tx.isShutdownTeardownThread(true, ""));
        assertFalse(Tx.isShutdownTeardownThread(true, null));
    }
}
