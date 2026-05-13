import org.junit.jupiter.api.*;
import play.test.*;
import utils.VirtualThreads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
            } catch (InterruptedException e) {
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
        try { exec.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        VirtualThreads.gracefulShutdown(exec, "test-double-shutdown");

        assertTrue(exec.isShutdown());
        assertTrue(exec.isTerminated());
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
