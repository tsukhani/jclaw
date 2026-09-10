package llm;

import llm.LlmProvider.LlmException;
import org.jspecify.annotations.Nullable;
import play.Logger;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.PlayConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Per-provider circuit breaker for both chat paths: {@link #guard} for the synchronous
 * one (JCLAW-1167) and {@link #beginStream} for the streaming one (JCLAW-1169).
 *
 * <p>{@link #guard} wraps a whole {@link LlmProvider#chat} call — the retry loop
 * inside it included — so an exhausted chat records exactly one failure. Recording
 * per attempt instead would multiply every failure by four and silently move the
 * configured threshold.
 *
 * <p>An open breaker throws before the wire call, in microseconds rather than after
 * four attempts and their backoff, which is what leaves
 * {@link LlmProvider#chatWithFailover} enough of the turn to reach the secondary.
 * The failure is an {@link LlmException.ServerError}: the breaker only opens because
 * the provider was failing, and failover triggers on any {@code LlmException}.
 *
 * <p>Breakers are shared by provider name through {@link CircuitBreakers}, so one
 * provider's outage cannot reach another's.
 */
public final class LlmResilience {

    private LlmResilience() {}

    /** How often {@link #sweep} looks for a stream that has gone quiet; the cadence of the cancellation poll. */
    private static final long SWEEP_MILLIS = 5_000L;

    /** Streams being watched for a stall. Empty — and the sweeper unstarted — when the budget is off. */
    private static final Set<StreamGuard> LIVE = ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean SWEEPING = new AtomicBoolean();

    // A stalled stream parks its own virtual thread inside an untimed await, so the sweep cannot
    // run there; a platform thread also keeps the timed wait off the VT scheduler (JDK-8373224).
    // newSingleThreadScheduledExecutor creates the thread on first submit, so an install with the
    // stall budget off never starts one.
    private static final ScheduledExecutorService STALL_SWEEPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "llm-stream-stall-sweeper");
                t.setDaemon(true);
                return t;
            });

    /** Registry name for a provider's chat breaker; prefixed so it cannot collide with an MCP server's. */
    public static String breakerName(String providerName) {
        return "llm:" + providerName;
    }

    /**
     * Breaker tuning from {@code llm.breaker.*}. Read on every lookup but applied only
     * where the name is first minted ({@link CircuitBreakers#get}), so an edit reaches a
     * provider that has not been called yet and a restart reaches the rest.
     *
     * <p>The slow-call rule reaches only the streaming path: {@link #guard} reports through
     * {@link CircuitBreaker#recordSuccess()}, which carries no duration and is never slow.
     */
    public static CircuitBreaker.Config config() {
        return CircuitBreaker.Config.of(
                        PlayConfig.intOr("llm.breaker.window", 100),
                        PlayConfig.intOr("llm.breaker.failure-rate", 50) / 100.0,
                        PlayConfig.intOr("llm.breaker.min-calls", 20),
                        PlayConfig.longOr("llm.breaker.wait-seconds", 60) * 1000L)
                .withHalfOpenPermits(PlayConfig.intOr("llm.breaker.half-open-probes", 3))
                .withSlowCalls(PlayConfig.longOr("llm.breaker.stall-seconds", 30) * 1000L,
                        PlayConfig.intOr("llm.breaker.slow-rate", 50) / 100.0);
    }

    public static CircuitBreaker breakerFor(String providerName) {
        return CircuitBreakers.get(breakerName(providerName), config());
    }

    /** The fail-fast an open breaker raises, in the one class {@code chatWithFailover} triggers on. */
    public static LlmException.ServerError openBreakerFailure(String providerName) {
        return new LlmException.ServerError(
                "Circuit breaker open for " + providerName + ": not calling it until it recovers");
    }

    /**
     * Run one whole chat call under {@code providerName}'s breaker, recording a single
     * outcome for it.
     *
     * @throws LlmException.ServerError immediately, without running {@code call}, when the
     *                                  breaker is open
     */
    public static <T> T guard(String providerName, Supplier<T> call) {
        var breaker = breakerFor(providerName);
        if (!breaker.allowRequest()) throw openBreakerFailure(providerName);
        var reported = false;
        try {
            var result = call.get();
            breaker.recordSuccess();
            reported = true;
            return result;
        } catch (LlmException e) {
            // JCLAW-1166: a ClientError is a request this codebase got wrong, so charging it to
            // the provider would trip the breaker on our own bug rather than on an outage.
            if (!(e instanceof LlmException.ClientError)) {
                breaker.recordFailure();
                reported = true;
            }
            throw e;
        } finally {
            // A HALF_OPEN probe holds a permit until it reports an outcome; stranding one
            // wedges the breaker shut for good, since nothing re-enters HALF_OPEN from
            // HALF_OPEN. A provider that answered 4xx is answering, so report success.
            if (!reported && breaker.state() == CircuitBreaker.State.HALF_OPEN) breaker.recordSuccess();
        }
    }

    /**
     * Admit one streaming chat under {@code providerName}'s breaker.
     *
     * @return the guard the stream reports its chunks and its outcome to, or {@code null}
     *         when the breaker is open and the caller must fail without reaching the wire
     */
    public static @Nullable StreamGuard beginStream(String providerName) {
        var breaker = breakerFor(providerName);
        if (!breaker.allowRequest()) return null;
        var guard = new StreamGuard(breaker, System::nanoTime);
        if (guard.stallBudgetNanos > 0L) watch(guard);
        return guard;
    }

    /** Test seam: the same guard on a caller-supplied monotonic source, so a stall test needs no sleep. */
    public static StreamGuard streamGuardForTest(CircuitBreaker breaker, LongSupplier nanoTime) {
        return new StreamGuard(breaker, nanoTime);
    }

    private static void watch(StreamGuard guard) {
        LIVE.add(guard);
        if (SWEEPING.compareAndSet(false, true)) {
            STALL_SWEEPER.scheduleWithFixedDelay(
                    LlmResilience::sweep, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    // scheduleWithFixedDelay drops the task for good on the first throw, so nothing may escape.
    private static void sweep() {
        try {
            LIVE.forEach(StreamGuard::checkStall);
        } catch (RuntimeException e) {
            Logger.warn(e, "Stalled-stream sweep failed");
        }
    }

    /**
     * One streaming chat's breaker outcome, reported exactly once (JCLAW-1169).
     *
     * <p>End-to-end duration cannot separate a healthy long generation from a dead
     * stream — a 90-second answer and a 90-second gap measure the same. What separates
     * them is the gap between chunks, so that is what is folded into the slow-call
     * duration the breaker sees.
     *
     * <p>The gap is armed by the first chunk, not by the dispatch: before anything has
     * streamed no byte has arrived either, which is the case OkHttp's 180s read timeout
     * already fails. Timing out a cold local model still loading weights would be a
     * regression, not a stall. What the read timeout cannot see is the provider that
     * keeps the socket busy while producing nothing useful — every read resets it — and
     * that is the case this measures.
     *
     * <p>A stream that never terminates reports nothing on its own, which would both hide
     * the outage and strand a HALF_OPEN permit for good. {@link #checkStall} is the way
     * out, called from the sweep rather than from the stream's own thread: that thread is
     * parked inside the transport's untimed await for the whole stall.
     */
    public static final class StreamGuard {

        private final CircuitBreaker breaker;
        private final LongSupplier nanoTime;
        private final long stallBudgetNanos;

        private final AtomicBoolean reported = new AtomicBoolean();
        // armed rather than a sentinel stamp: nanoTime's origin is arbitrary and may be 0.
        private volatile boolean armed;
        private volatile long lastChunkNanos;
        private volatile long worstGapNanos;

        private StreamGuard(CircuitBreaker breaker, LongSupplier nanoTime) {
            this.breaker = breaker;
            this.nanoTime = nanoTime;
            this.stallBudgetNanos = breaker.config().slowCallsEnabled()
                    ? breaker.config().slowCallDurationMillis() * 1_000_000L
                    : 0L;
        }

        /** A chunk reached the caller: fold the gap since the previous one and re-arm. */
        public void chunk() {
            var now = nanoTime.getAsLong();
            fold(now);
            lastChunkNanos = now;
            armed = true;
        }

        /** The stream closed cleanly: one success, slow when its worst gap blew the stall budget. */
        public void succeeded() {
            fold(nanoTime.getAsLong());
            if (claim()) breaker.recordSuccess(worstGapNanos / 1_000_000L);
        }

        /** The stream failed: one failure, unless it failed on a request this codebase got wrong. */
        public void failed(Throwable error) {
            if (!claim()) return;
            if (error instanceof LlmException.ClientError) {
                // The 4xx rule from guard(), with the same HALF_OPEN caveat: a provider that
                // answered is answering, and its probe must hand the permit back either way.
                if (breaker.state() == CircuitBreaker.State.HALF_OPEN) breaker.recordSuccess();
                return;
            }
            breaker.recordFailure();
        }

        /**
         * Charge a stream that has gone quiet past the budget while it is still in flight,
         * so an outage of streams that never end still opens the breaker.
         *
         * @return {@code true} when this call is what recorded the outcome
         */
        public boolean checkStall() {
            if (stallBudgetNanos == 0L || !armed || reported.get()) return false;
            var gap = nanoTime.getAsLong() - lastChunkNanos;
            if (gap < stallBudgetNanos || !claim()) return false;
            breaker.recordSuccess(gap / 1_000_000L);
            return true;
        }

        private void fold(long now) {
            if (!armed) return;
            var gap = now - lastChunkNanos;
            if (gap > worstGapNanos) worstGapNanos = gap;
        }

        private boolean claim() {
            if (!reported.compareAndSet(false, true)) return false;
            LIVE.remove(this);
            return true;
        }
    }
}
