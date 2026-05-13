package utils;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared platform-thread scheduler for delayed retry waits.
 *
 * <p>Why platform threads: under JDK-8373224, many concurrent virtual threads
 * parked in {@link Thread#sleep} starve the ForkJoinPool work queue and inflate
 * tail latency. Hand the wait off to a platform-thread scheduler so a VT caller
 * unmounts cleanly during the delay while the actual timed wait happens on a
 * carrier-free platform thread.
 *
 * <p>Single-thread, daemon, named — adequate for the low-volume retry-wait
 * use case (channel delivery retry, LLM 429 backoff). The scheduler itself
 * only blocks for the timer; the scheduled {@link Callable} runs synchronously
 * on the scheduler thread, so callers should keep the work brief (one I/O call,
 * not a chain of them).
 */
public final class RetryScheduler {

    private RetryScheduler() {}

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "jclaw-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    /**
     * Schedule {@code task} to run after {@code delayMs} milliseconds on the
     * shared platform-thread scheduler. Returns a {@link CompletableFuture} the
     * caller can {@code get()} on; a virtual-thread caller will unmount during
     * the wait.
     */
    // S1181: catch(Throwable) is intentional — this is a generic retry boundary
    // that must surface Errors (OOM, StackOverflow, etc.) to the caller's get()
    // rather than let them die silently on the daemon scheduler thread.
    @SuppressWarnings("java:S1181")
    public static <T> CompletableFuture<T> schedule(Callable<T> task, long delayMs) {
        var future = new CompletableFuture<T>();
        SCHEDULER.schedule(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return future;
    }
}
