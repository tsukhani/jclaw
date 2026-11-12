package services.evals;

import org.jspecify.annotations.Nullable;
import utils.TaskScope;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
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
     * <p>A case whose {@code fn} throws loses the sweep, so the cases still waiting for a
     * slot never start: they would only spend model calls on a report nobody receives.
     * The cases already running finish, because stopping one by interrupt can kill the H2
     * database its agent turn is writing. Under {@link EvalCapture} each is an
     * {@code AgentRunner.runForTask} turn, bounded by the round cap {@code chat.maxToolRounds}
     * and each model stream's {@code llm.breaker.first-chunk-seconds} and
     * {@code llm.breaker.stall-abort-seconds} budgets. The exception thrown carries the first
     * case failure as its cause.
     */
    public static <T> List<T> mapCasesBounded(List<EvalCase> cases, int maxConcurrency, Function<EvalCase, T> fn) {
        var permits = new Semaphore(Math.max(1, maxConcurrency));
        var firstFailure = new AtomicReference<@Nullable Throwable>();
        try (var scope = new TaskScope<T>()) {
            var futures = cases.stream()
                    .map(testCase -> scope.fork(() -> {
                        permits.acquire();
                        try {
                            if (firstFailure.get() != null || scope.isCancelled()) {
                                throw new CancellationException("Skipped: another eval case already failed");
                            }
                            try {
                                return fn.apply(testCase);
                            } catch (Throwable e) {
                                // Recorded before the finally frees the slot, so the case that takes it is skipped.
                                firstFailure.compareAndSet(null, e);
                                throw e;
                            }
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
            // A skipped case can complete before the failure that skipped it; close() has
            // waited for every case by now, so the record is final.
            var failure = firstFailure.get();
            throw new IllegalStateException("Eval case failed outside scoring", failure != null ? failure : e.getCause());
        }
    }
}
