package utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fan-out scope over virtual threads with all-or-nothing semantics: the first task to
 * fail cancels its siblings and {@link #join()} rethrows that failure. Closing the
 * scope cancels whatever is still running, so no forked task outlives the
 * try-with-resources block.
 *
 * <p>Stands in for {@code java.util.concurrent.StructuredTaskScope}, which is a preview
 * API in JDK 25 (JEP 505) and unusable here: the play1 fork's ECJ compiler has no
 * {@code --enable-preview} path at {@code java.source=25}, so it emits an unmarked
 * classfile where Gradle's javac emits a preview-marked one that the app JVM then
 * refuses to load. Revisit when structured concurrency finalises — JDK 26 at the
 * earliest. See JCLAW-1155 for the spike output.
 *
 * <p>Homogeneous by design: every task returns {@code T}. Forking tasks of differing
 * types is the one shape this does not cover — use plain futures there.
 *
 * <p>Confined to the thread that opened it: fork every task, join once, then read the
 * futures. {@link #fork}, {@link #join} and {@link #close} are not safe to call
 * concurrently with each other.
 *
 * @param <T> result type shared by every task forked into the scope
 */
public final class TaskScope<T> implements AutoCloseable {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final CompletionService<T> completion = new ExecutorCompletionService<>(pool);
    private final List<Future<T>> forked = new ArrayList<>();

    /** Start {@code task} on its own virtual thread. */
    public Future<T> fork(Callable<T> task) {
        var future = completion.submit(task);
        forked.add(future);
        return future;
    }

    /**
     * Wait for every forked task. On a normal return all of them succeeded, so each
     * {@link #fork} future is safe to read with {@link Future#resultNow()}.
     *
     * @throws ExecutionException wrapping the first task failure; the siblings that
     *                            were still running have been cancelled
     * @throws InterruptedException if the joining thread is interrupted while waiting
     */
    public void join() throws ExecutionException, InterruptedException {
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

    @Override
    public void close() {
        cancelAll();
        // Waits for the cancelled tasks to actually end, which is what makes the scope
        // structured; a task that swallows its interrupt blocks here, as it would under
        // StructuredTaskScope.
        pool.close();
    }

    private void cancelAll() {
        forked.forEach(future -> future.cancel(true));
    }
}
