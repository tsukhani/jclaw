package services.grapheval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The bounded thread pool the case and sequence harnesses ask their models through. */
final class FanOut {

    private FanOut() {}

    /** Runs {@code tasks} on at most {@code concurrency} threads, returning results in task order. */
    static <T> List<T> run(List<Callable<T>> tasks, int concurrency) {
        var results = new ArrayList<T>(tasks.size());
        try {
            if (concurrency <= 1) {
                for (var task : tasks) results.add(task.call());
                return results;
            }
            try (var pool = Executors.newFixedThreadPool(concurrency)) {
                var futures = new ArrayList<Future<T>>();
                for (var task : tasks) futures.add(pool.submit(task));
                for (var future : futures) results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("graph eval interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("graph eval task failed", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("graph eval task failed", e);
        }
    }
}
