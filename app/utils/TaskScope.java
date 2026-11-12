package utils;

import com.google.errorprone.annotations.MustBeClosed;
import io.opentelemetry.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fan-out scope over virtual threads with all-or-nothing semantics: the first task to
 * fail cancels the scope and {@link #join()} rethrows that failure. Closing the scope
 * cancels it and waits for every forked task to end, so none outlives the
 * try-with-resources block.
 *
 * <p>Canceling never interrupts a fork, because an interrupt closes the FileChannel
 * under the H2 database a fork may be writing. It sets {@link #isCancelled()} and marks
 * each future canceled. A fork that loops checks {@link #isCancelled()} to stop early;
 * any other fork runs to its end. {@link #close()} waits for every fork and cannot be
 * interrupted, so every wait inside a fork must end on its own, through its own timeout
 * or something certain to release it.
 *
 * <p>Stands in for {@code java.util.concurrent.StructuredTaskScope}, which is a preview
 * API in JDK 25 (JEP 505) and unusable here: the play1 fork's ECJ compiler has no
 * {@code --enable-preview} path at {@code java.source=25}, so it emits an unmarked
 * classfile where Gradle's javac emits a preview-marked one that the app JVM then
 * refuses to load. Revisit when structured concurrency leaves preview, knowing that
 * {@code StructuredTaskScope} interrupts its unfinished subtasks when it is canceled
 * (under the default joiner, on the first failure) and when it closes, so it suits only
 * forks that never touch H2. See JCLAW-1155 for the spike output.
 *
 * <p>Homogeneous by design: every task returns {@code T}. Forking tasks of differing
 * types is the one shape this does not cover — use plain futures there.
 *
 * <p>Confined to the thread that opened it: fork every task, join once, then read the
 * futures. {@link #fork}, {@link #join} and {@link #close} are not safe to call
 * concurrently with each other; {@link #isCancelled()} is safe from any thread.
 *
 * @param <T> result type shared by every task forked into the scope
 */
public final class TaskScope<T> implements AutoCloseable {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final CompletionService<T> completion = new ExecutorCompletionService<>(pool);
    private final List<Future<T>> forked = new ArrayList<>();
    private volatile boolean cancelled;
    private boolean joined;

    /** Leaking one leaks its virtual-thread pool, so the compiler requires the caller to close it. */
    @MustBeClosed
    public TaskScope() {
        // Empty: exists only so @MustBeClosed has a constructor to bind.
    }

    /** Start {@code task} on its own virtual thread. */
    public Future<T> fork(Callable<T> task) {
        // Same boundary docs/agents/wall-clock.md documents for ScopedValue: the pool's
        // threads inherit no OTel context, so the forking thread's span is carried in explicitly.
        var future = completion.submit(Context.current().wrap(cancellingOnFailure(task)));
        forked.add(future);
        return future;
    }

    /**
     * Whether a fork has failed, {@link #join()} has seen a failure, or the scope is
     * closing. Once true it stays true.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Wait for every forked task. On a normal return all of them succeeded, so each
     * {@link #fork} future is safe to read with {@link Future#resultNow()}.
     *
     * @throws ExecutionException wrapping the first task failure; the scope is canceled
     *                            and the futures of the tasks still running are marked
     *                            canceled, but those tasks are not interrupted
     * @throws InterruptedException if the joining thread is interrupted while waiting
     * @throws IllegalStateException on a second call — the completion queue is already drained,
     *                               so it would park forever instead of failing
     */
    public void join() throws ExecutionException, InterruptedException {
        if (joined) throw new IllegalStateException("TaskScope.join() may be called once per scope");
        joined = true;
        for (int i = 0; i < forked.size(); i++) {
            try {
                // Completion order, not submission order — a failure three forks along
                // has to cancel the rest now, not once the tasks ahead of it finish.
                completion.take().get();
            } catch (ExecutionException e) {
                cancelAll();
                throw e;
            }
        }
    }

    /**
     * Cancel the scope and wait for every forked task to end. An interrupt of the closing
     * thread does not cut the wait short; it is re-asserted once the wait is over.
     */
    @Override
    public void close() {
        cancelAll();
        // ExecutorService.close() minus its shutdownNow(), which interrupts every fork
        // when the closing thread is itself interrupted.
        pool.shutdown();
        boolean interrupted = false;
        boolean terminated = false;
        while (!terminated) {
            try {
                terminated = pool.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException _) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Callable<T> cancellingOnFailure(Callable<T> task) {
        return () -> {
            try {
                return task.call();
            } catch (Throwable t) {
                cancelled = true;
                throw t;
            }
        };
    }

    private void cancelAll() {
        cancelled = true;
        forked.forEach(future -> future.cancel(false));
    }
}
