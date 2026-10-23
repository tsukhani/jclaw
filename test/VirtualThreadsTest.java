import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.VirtualThreads;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-129: regression test for the shared graceful-shutdown helper that
 * backs the ShutdownJob wiring for TelegramPollingRunner, TelegramStreamingSink,
 * and ApiChatController.
 */
class VirtualThreadsTest extends UnitTest {

    @Test
    void gracefulShutdown_drainsIdleExecutorImmediately() {
        // Empty queue → shutdown should complete well inside the 5s budget.
        ScheduledExecutorService exec = VirtualThreads.newSingleThreadScheduledExecutor();
        long start = System.nanoTime();
        VirtualThreads.gracefulShutdown(exec, "test-idle");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(exec.isShutdown(), "executor must be marked shutdown");
        assertTrue(exec.isTerminated(), "executor must be terminated for idle case");
        assertTrue(elapsedMs < 1000,
                "idle drain should take well under 1s, took " + elapsedMs + "ms");
    }

    @Test
    void gracefulShutdown_waitsForInFlightTaskToComplete() {
        // Submit a task that completes quickly; shutdown must wait for it, not
        // force-terminate. Confirms the happy path of awaitTermination.
        ScheduledExecutorService exec = VirtualThreads.newSingleThreadScheduledExecutor();
        var ran = new AtomicInteger(0);
        var done = new CountDownLatch(1);
        exec.submit(() -> {
            try {
                Thread.sleep(50);
                ran.incrementAndGet();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        VirtualThreads.gracefulShutdown(exec, "test-inflight");

        assertTrue(exec.isShutdown());
        assertTrue(exec.isTerminated());
        assertEquals(1, ran.get(), "in-flight task must run to completion");
    }

    @Test
    void gracefulShutdown_handlesAlreadyShutdownExecutor() {
        // Defensive: calling shutdown twice must not throw or hang. ShutdownJob
        // may run after a test or hook has already shut an executor down.
        ScheduledExecutorService exec = VirtualThreads.newSingleThreadScheduledExecutor();
        exec.shutdown();
        try { exec.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }

        VirtualThreads.gracefulShutdown(exec, "test-double-shutdown");

        assertTrue(exec.isShutdown());
        assertTrue(exec.isTerminated());
    }

    // ---- runAll: the boot's channel fan-out ----

    @Test
    void runAll_startsEveryTaskAtOnce() {
        var allRunning = new CyclicBarrier(3);
        var finished = new AtomicInteger();
        Runnable meetTheOthers = () -> {
            try {
                allRunning.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("tasks ran one at a time", e);
            }
            finished.incrementAndGet();
        };

        VirtualThreads.runAll("test-fanout", List.of(meetTheOthers, meetTheOthers, meetTheOthers));

        assertEquals(3, finished.get());
    }

    @Test
    void runAll_rethrowsAFailureOnlyAfterTheOtherTasksFinish() {
        var slowOneFinished = new AtomicBoolean();
        Runnable fails = () -> {
            throw new IllegalStateException("telegram is down");
        };
        Runnable slow = () -> {
            sleepUninterrupted(200);
            slowOneFinished.set(true);
        };

        var thrown = assertThrows(IllegalStateException.class, () -> VirtualThreads.runAll("test-fail", List.of(fails, slow)));

        assertEquals("telegram is down", thrown.getMessage());
        assertTrue(slowOneFinished.get(), "runAll returned before the other task finished");
    }

    @Test
    void runAll_reportsEveryFailureWithTheFirstInListOrderOnTop() {
        Runnable telegram = () -> {
            sleepUninterrupted(100);
            throw new IllegalStateException("telegram is down");
        };
        Runnable slack = () -> {
            throw new IllegalArgumentException("slack refused the token");
        };

        var thrown = assertThrows(IllegalStateException.class, () -> VirtualThreads.runAll("test-two", List.of(telegram, slack)));

        assertEquals("telegram is down", thrown.getMessage());
        assertEquals(List.of("slack refused the token"),
                Arrays.stream(thrown.getSuppressed()).map(Throwable::getMessage).toList());
    }

    @Test
    void runAll_rethrowsAnErrorAsItself() {
        Runnable broken = () -> {
            throw new LinkageError("class failed to load");
        };

        var thrown = assertThrows(LinkageError.class, () -> VirtualThreads.runAll("test-error", List.of(broken)));

        assertEquals("class failed to load", thrown.getMessage());
    }

    @Test
    void runAll_neverInterruptsATaskWhenTheCallerIsInterrupted() throws Exception {
        var taskStarted = new CountDownLatch(1);
        var taskSawInterrupt = new AtomicBoolean();
        var taskFinished = new AtomicBoolean();
        var callerFlagAfter = new AtomicBoolean();
        Runnable task = () -> {
            taskStarted.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                taskSawInterrupt.set(true);
            }
            taskFinished.set(true);
        };
        var caller = Thread.ofPlatform().start(() -> {
            VirtualThreads.runAll("test-interrupt", List.of(task));
            callerFlagAfter.set(Thread.currentThread().isInterrupted());
        });

        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(5000);

        assertFalse(caller.isAlive());
        assertFalse(taskSawInterrupt.get(), "the task was interrupted");
        assertTrue(taskFinished.get());
        assertTrue(callerFlagAfter.get(), "the caller's interrupt was not restored");
    }

    @Test
    void runAll_carriesTheCallersTraceContextAndNamesItsThreads() {
        var key = ContextKey.<String>named("test-key");
        var seen = new AtomicReference<String>();
        var threadName = new AtomicReference<String>();
        try (var _ = Context.current().with(key, "turn-42").makeCurrent()) {
            VirtualThreads.runAll("test-context", List.of(() -> {
                seen.set(Context.current().get(key));
                threadName.set(Thread.currentThread().getName());
            }));
        }

        assertEquals("turn-42", seen.get());
        assertEquals("test-context-0", threadName.get());
    }

    private static void sleepUninterrupted(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException("interrupted", e);
        }
    }

    @Test
    void gracefulShutdown_worksForPlainExecutorNotOnlyScheduled() {
        // Helper is typed on ExecutorService to accept plain thread-pools too.
        // Regression guard: if someone later swaps a call site from scheduled
        // to plain, the helper signature must still fit.
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        VirtualThreads.gracefulShutdown(exec, "test-plain");
        assertTrue(exec.isShutdown());
        assertTrue(exec.isTerminated());
    }
}
