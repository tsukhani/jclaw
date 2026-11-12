import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.TaskScope;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-1155: the fan-out scope that stands in for the still-preview
 * {@code StructuredTaskScope}. The contract under test is cancellation — that a
 * failing task cancels the scope, and that leaving the block waits for whatever is
 * left — and, since a fork may hold H2 (JF-332), that none of it interrupts a fork.
 */
class TaskScopeTest extends UnitTest {

    /**
     * A fork that parks until the test releases it, recording whether an interrupt
     * arrived and whether the scope was canceled by the time it woke. Untimed
     * {@code await()} parks outside the virtual-thread timer-queue path that
     * JDK-8373224 starves, so the block is a park rather than a sleep.
     */
    private static final class Blocker {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean sawCancelled = new AtomicBoolean();

        String call(TaskScope<String> scope) throws InterruptedException {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            if (Thread.currentThread().isInterrupted()) interrupted.set(true);
            sawCancelled.set(scope.isCancelled());
            return "released";
        }
    }

    /**
     * A fork that runs until the scope is canceled, as a polling loop does, then parks until
     * {@link #releaseOnceParkedIn} sees the closing thread blocked inside {@code close()}. A
     * {@code close()} that does not wait therefore returns while the fork is still parked.
     * The loop spins because {@link TaskScope#isCancelled()} has nothing to wake a parked
     * waiter, and stops on an interrupt too, so a regression ends it.
     */
    private static final class Watcher {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch sawCancel = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();

        String call(TaskScope<String> scope) {
            running.countDown();
            while (!scope.isCancelled() && !Thread.currentThread().isInterrupted()) Thread.yield();
            sawCancel.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                interrupted.set(true);
            }
            // await() returns normally, interrupt still pending, when the release wins the race.
            if (Thread.currentThread().isInterrupted()) interrupted.set(true);
            ended.countDown();
            return "stopped";
        }

        /** Once the scope is canceled, release the fork as soon as {@code closer} parks. */
        void releaseOnceParkedIn(Thread closer) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    sawCancel.await();
                    while (release.getCount() > 0 && !parked(closer)) Thread.onSpinWait();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    release.countDown();
                }
            });
        }

        private static boolean parked(Thread thread) {
            var state = thread.getState();
            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        }
    }

    @Test
    void allTasksSucceed() throws Exception {
        TaskScope<String> closed;
        try (var scope = new TaskScope<String>()) {
            closed = scope;
            var a = scope.fork(() -> "a");
            var b = scope.fork(() -> "b");

            scope.join();

            assertFalse(scope.isCancelled(), "a scope whose forks all succeeded is not canceled");
            assertEquals("a", a.resultNow());
            assertEquals("b", b.resultNow());
        }
        assertTrue(closed.isCancelled(), "closing the scope cancels it");
    }

    @Test
    void aFailingForkDoesNotInterruptASiblingStillRunning() {
        var blocker = new Blocker();

        // Preemptive: a scope that cannot end its forks parks close() forever, and a hung
        // test tells nobody anything.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var scope = new TaskScope<String>()) {
                try {
                    var sibling = scope.fork(() -> blocker.call(scope));
                    blocker.running.await();
                    scope.fork(() -> {
                        throw new IllegalStateException("boom");
                    });

                    var thrown = assertThrows(ExecutionException.class, scope::join);

                    assertEquals("boom", thrown.getCause().getMessage());
                    assertTrue(scope.isCancelled(), "the failure join() saw must cancel the scope");
                    assertTrue(sibling.isCancelled(), "the parked sibling's future must be canceled");
                } finally {
                    // close() waits for the sibling, so a failed assertion must still free it.
                    blocker.release.countDown();
                }
            }
        });
        assertFalse(blocker.interrupted.get(), "cancellation must never interrupt a fork that may hold H2");
        assertTrue(blocker.sawCancelled.get(), "the released sibling must see the scope canceled");
    }

    @Test
    void aFailingForkCancelsTheScopeBeforeAnyoneJoins() {
        var watcher = new Watcher();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var scope = new TaskScope<String>()) {
                scope.fork(() -> watcher.call(scope));
                scope.fork(() -> {
                    throw new IllegalStateException("boom");
                });
                try {
                    // No join(): only the failing fork itself can have canceled the scope.
                    watcher.sawCancel.await();

                    assertTrue(scope.isCancelled(), "a failing fork must cancel the scope");
                } finally {
                    watcher.release.countDown();
                }
            }
        });
        assertFalse(watcher.interrupted.get(), "the failing fork must not interrupt its sibling");
    }

    @Test
    void aSecondJoinFailsInsteadOfParking() throws Exception {
        try (var scope = new TaskScope<String>()) {
            scope.fork(() -> "a");
            scope.join();
            assertThrows(IllegalStateException.class, scope::join);
        }
    }

    @Test
    void closeWaitsForAForkStillRunningWithoutInterruptingIt() {
        var watcher = new Watcher();

        boolean forkEndedFirst;
        try {
            forkEndedFirst = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try (var scope = new TaskScope<String>()) {
                    scope.fork(() -> watcher.call(scope));
                    watcher.running.await();
                    watcher.releaseOnceParkedIn(Thread.currentThread());
                }
                return watcher.ended.getCount() == 0;
            });
        } finally {
            // A red run must not leave the fork parked.
            watcher.release.countDown();
        }

        assertTrue(forkEndedFirst, "close() must not return before the fork ends");
        assertFalse(watcher.interrupted.get(), "close() must cancel by flag, never by interrupt");
    }

    @Test
    void closeOnAnInterruptedThreadStillWaitsAndKeepsTheInterrupt() {
        var watcher = new Watcher();
        var forkEndedFirst = new AtomicBoolean();

        boolean stillInterrupted;
        try {
            stillInterrupted = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try (var scope = new TaskScope<String>()) {
                    scope.fork(() -> watcher.call(scope));
                    watcher.running.await();
                    watcher.releaseOnceParkedIn(Thread.currentThread());
                    Thread.currentThread().interrupt();
                }
                forkEndedFirst.set(watcher.ended.getCount() == 0);
                // Reads and clears, so the interrupt does not leak into JUnit's executor thread.
                return Thread.interrupted();
            });
        } finally {
            watcher.release.countDown();
        }

        assertTrue(stillInterrupted, "close() must hand the caller's interrupt back");
        assertTrue(forkEndedFirst.get(), "an interrupted close() must still wait for the fork");
        assertFalse(watcher.interrupted.get(), "an interrupted closer must not pass its interrupt to the fork");
    }
}
