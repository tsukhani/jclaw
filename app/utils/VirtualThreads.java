package utils;

import services.EventLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Factory methods for virtual-thread executors used across the application.
 */
public final class VirtualThreads {

    private VirtualThreads() {}

    /** Create a single-thread scheduled executor backed by a virtual thread. */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().unstarted(r));
    }

    /**
     * Time-bounded drain of an executor. Calls {@code shutdown()} and then
     * waits up to 5 seconds for in-flight tasks to complete; on timeout falls
     * back to {@code shutdownNow()} and logs a warning. Thread-interrupt
     * preserves interrupted status so callers up the stack can react. Never
     * throws — shutdown must not block JVM exit.
     *
     * @param executor the executor to drain
     * @param name short identifier used in the timeout warning (e.g.
     *             "telegram-cooldown-reconcile")
     */
    public static void gracefulShutdown(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                EventLogger.warn("shutdown", null, "executor",
                        "%s did not terminate within 5s; forced shutdown".formatted(name));
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
