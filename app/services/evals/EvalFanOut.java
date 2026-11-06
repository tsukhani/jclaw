package services.evals;

import utils.TaskScope;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * The bounded fan-out {@link EvalCapture} puts a model behind (JCLAW-883).
 *
 * <p>Kept apart from {@link EvalRunner} because {@link TaskScope} carries the caller's
 * OpenTelemetry context into each forked task, and the offline CLI runs on a classpath
 * with no OpenTelemetry on it (JF-157). {@code EvalRunnerTest.theOfflineScorerStandsAlone}
 * keeps this class out of what the CLI loads.
 */
public final class EvalFanOut {

    /**
     * Cases allowed in front of a model at once when the caller does not say.
     * Four is deliberately modest: a sweep is a background quality check, not a
     * throughput test, and a provider that rate-limits under it would turn agent
     * quality into a function of how many cases the suite happens to contain.
     */
    public static final int DEFAULT_CONCURRENCY = 4;

    private EvalFanOut() {}

    /**
     * Apply {@code fn} to every case on its own virtual thread, with at most
     * {@code maxConcurrency} running at once, preserving suite order in the result.
     * An unbounded sweep would hammer a provider into rate-limiting, and the
     * resulting retries and timeouts would score as agent failures.
     *
     * <p>A case whose {@code fn} throws cancels the cases still in flight: the sweep is
     * already lost at that point, and the survivors would only spend model calls on a
     * report nobody receives.
     */
    public static <T> List<T> mapCasesBounded(List<EvalCase> cases, int maxConcurrency, Function<EvalCase, T> fn) {
        var permits = new Semaphore(Math.max(1, maxConcurrency));
        try (var scope = new TaskScope<T>()) {
            var futures = cases.stream()
                    .map(testCase -> scope.fork(() -> {
                        permits.acquire();
                        try {
                            return fn.apply(testCase);
                        } finally {
                            permits.release();
                        }
                    }))
                    .toList();
            scope.join();
            return futures.stream().map(Future::resultNow).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Eval run interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Eval case failed outside scoring", e.getCause());
        }
    }
}
