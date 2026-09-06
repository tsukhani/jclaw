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
 * failing task takes its siblings down, and that leaving the block takes down
 * whatever is left — because that is what the ad-hoc executor sites it replaces
 * never did.
 */
class TaskScopeTest extends UnitTest {

    /**
     * A task that blocks until cancelled, recording whether the interrupt arrived.
     * Untimed {@code await()} parks outside the virtual-thread timer-queue path that
     * JDK-8373224 starves, so the block is a park rather than a sleep.
     */
    private static final class Blocker {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch never = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();

        String call() throws InterruptedException {
            running.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return "unreachable";
        }
    }

    @Test
    void allTasksSucceed() throws Exception {
        try (var scope = new TaskScope<String>()) {
            var a = scope.fork(() -> "a");
            var b = scope.fork(() -> "b");

            scope.join();

            assertEquals("a", a.resultNow());
            assertEquals("b", b.resultNow());
        }
    }

    @Test
    void firstFailureCancelsTheSiblingsStillRunning() throws Exception {
        var blocker = new Blocker();

        // Preemptive: a scope that failed to cancel would park join() forever rather
        // than fail, and a hung test tells nobody anything.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var scope = new TaskScope<String>()) {
                var sibling = scope.fork(blocker::call);
                blocker.running.await();
                scope.fork(() -> {
                    throw new IllegalStateException("boom");
                });

                var thrown = assertThrows(ExecutionException.class, scope::join);

                assertEquals("boom", thrown.getCause().getMessage());
                assertTrue(sibling.isCancelled(), "the blocked sibling must be cancelled");
            }
        });
        assertTrue(blocker.interrupted.get(), "cancellation must interrupt the sibling, not just mark it");
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
    void closeCancelsTasksLeftRunning() {
        var blocker = new Blocker();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var scope = new TaskScope<String>()) {
                scope.fork(blocker::call);
                blocker.running.await();
            }
        });

        assertTrue(blocker.interrupted.get(), "close() must cancel a task nobody joined");
    }
}
