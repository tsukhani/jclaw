package utils;

import io.opentelemetry.context.Context;
import org.jspecify.annotations.NonNull;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Virtual-thread executors and fan-out used across the application.
 */
public final class VirtualThreads {

    private VirtualThreads() {}

    /**
     * Runs {@code tasks} at once on virtual threads named {@code name-N} and returns when every one
     * has finished. A task is never interrupted, not even when the caller is interrupted while it
     * waits (the caller's flag is set again on return), since tasks may be inside an H2 transaction.
     *
     * @throws RuntimeException the first failure in list order (an {@link Error} is rethrown as
     *         itself), with the others added to it as suppressed
     */
    public static void runAll(String name, List<Runnable> tasks) {
        var failures = new ArrayList<Throwable>();
        boolean interrupted = false;
        try {
            // close() returns at once: every task has finished before it runs, and the flag is clear.
            try (var pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-", 0).factory())) {
                var runs = new ArrayList<Future<?>>();
                for (var task : tasks) runs.add(pool.submit(Context.current().wrap(task)));
                for (var run : runs) {
                    while (true) {
                        try {
                            run.get();
                            break;
                        } catch (ExecutionException e) {
                            failures.add(e.getCause() != null ? e.getCause() : e);
                            break;
                        } catch (InterruptedException _) {
                            interrupted = true;
                        }
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failures.isEmpty()) return;
        var first = failures.getFirst();
        for (var other : failures.subList(1, failures.size())) first.addSuppressed(other);
        if (first instanceof RuntimeException re) throw re;
        if (first instanceof Error error) throw error;
        throw new IllegalStateException(first);
    }

    /** Create a single-thread scheduled executor backed by a virtual thread. */
    public static @NonNull ScheduledExecutorService newSingleThreadScheduledExecutor() {
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
    public static void gracefulShutdown(@NonNull ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                EventLogger.warn("shutdown", null, "executor",
                        "%s did not terminate within 5s; forced shutdown".formatted(name));
            }
        } catch (InterruptedException _) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
