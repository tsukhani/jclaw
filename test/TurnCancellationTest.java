import agents.ToolContext;
import agents.TurnCancellation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** JCLAW-1385: the per-turn cancel-action registry every stop path runs. */
class TurnCancellationTest extends UnitTest {

    @Test
    void anActionRegisteredAfterTheCancelRunsAtOnce() throws Exception {
        var flag = new AtomicBoolean(false);
        TurnCancellation.cancel(flag);
        var ran = new CountDownLatch(1);
        try (var _ = TurnCancellation.register(flag, ran::countDown)) {
            assertTrue(ran.await(5, TimeUnit.SECONDS), "a late registration still runs");
        }
    }

    @Test
    void aDeregisteredActionNeverRuns() throws Exception {
        var flag = new AtomicBoolean(false);
        var ran = new AtomicInteger();
        try (var _ = TurnCancellation.register(flag, ran::incrementAndGet)) {
            // closed before any cancel
        }
        var control = new CountDownLatch(1);
        try (var _ = TurnCancellation.register(flag, control::countDown)) {
            TurnCancellation.cancel(flag);
            assertTrue(control.await(5, TimeUnit.SECONDS), "the still-registered action ran");
        }
        assertEquals(0, ran.get(), "the closed registration must not run");
        assertTrue(flag.get());
    }

    @Test
    void anActionRunsOnceAcrossRepeatedCancels() throws Exception {
        var flag = new AtomicBoolean(false);
        var ran = new AtomicInteger();
        var first = new CountDownLatch(1);
        try (var _ = TurnCancellation.register(flag, () -> {
            ran.incrementAndGet();
            first.countDown();
        })) {
            TurnCancellation.cancel(flag);
            TurnCancellation.cancel(flag);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            // A second cancel finds nothing registered; give a stray run time to show up.
            var settle = new CountDownLatch(1);
            try (var _ = TurnCancellation.register(new AtomicBoolean(true), settle::countDown)) {
                assertTrue(settle.await(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, ran.get());
    }

    @Test
    void aThrowingActionDoesNotStopTheOthers() throws Exception {
        var flag = new AtomicBoolean(false);
        var ran = new CountDownLatch(2);
        try (var _ = TurnCancellation.register(flag, ran::countDown);
             var _ = TurnCancellation.register(flag, () -> { throw new IllegalStateException("boom"); });
             var _ = TurnCancellation.register(flag, ran::countDown)) {
            TurnCancellation.cancel(flag);
            assertTrue(ran.await(5, TimeUnit.SECONDS), "both healthy actions ran despite the throw");
        }
    }

    @Test
    void actionsRunOffTheCancellingThread() throws Exception {
        var flag = new AtomicBoolean(false);
        var name = new java.util.concurrent.atomic.AtomicReference<String>();
        var ran = new CountDownLatch(1);
        try (var _ = TurnCancellation.register(flag, () -> {
            name.set(Thread.currentThread().getName());
            ran.countDown();
        })) {
            TurnCancellation.cancel(flag);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
        }
        assertEquals("turn-cancel", name.get());
    }

    @Test
    void onCancelReachesTheScopesTurnFlagAndIsANoOpWithoutOne() throws Exception {
        var flag = new AtomicBoolean(false);
        var ran = new CountDownLatch(1);
        ToolContext.withScope(1L, null, null, flag, () -> {
            try (var _ = ToolContext.onCancel(ran::countDown)) {
                TurnCancellation.cancel(flag);
                assertTrue(ran.await(5, TimeUnit.SECONDS), "the scope's turn flag reached the action");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        var unflagged = new AtomicInteger();
        ToolContext.withScope(1L, null, null, () -> {
            try (var _ = ToolContext.onCancel(unflagged::incrementAndGet)) {
                // a scope with no turn flag: nothing can ever run this
            }
            return null;
        });
        try (var _ = ToolContext.onCancel(unflagged::incrementAndGet)) {
            // no scope at all
        }
        var control = new CountDownLatch(1);
        try (var _ = TurnCancellation.register(new AtomicBoolean(true), control::countDown)) {
            assertTrue(control.await(5, TimeUnit.SECONDS));
        }
        assertEquals(0, unflagged.get());
    }

    @Test
    void everyActionRunsAtOnceSoASlowOneHoldsNoOtherBack() throws Exception {
        var flag = new AtomicBoolean(false);
        var started = new CountDownLatch(3);
        var met = new CountDownLatch(3);
        Runnable waitForTheOthers = () -> {
            started.countDown();
            try {
                if (started.await(5, TimeUnit.SECONDS)) met.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (var _ = TurnCancellation.register(flag, waitForTheOthers);
             var _ = TurnCancellation.register(flag, waitForTheOthers);
             var _ = TurnCancellation.register(flag, waitForTheOthers)) {
            TurnCancellation.cancel(flag);
            assertTrue(met.await(10, TimeUnit.SECONDS), "all three actions were running at the same time");
        }
    }
}
