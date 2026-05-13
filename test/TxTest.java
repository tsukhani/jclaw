import org.junit.jupiter.api.*;
import play.db.jpa.JPA;
import play.libs.F;
import play.test.*;
import models.EventLog;
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
}
