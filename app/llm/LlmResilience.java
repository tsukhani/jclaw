package llm;

import llm.LlmProvider.LlmException;
import org.jspecify.annotations.Nullable;
import play.Logger;
import services.BreakerAlarms;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.PlayConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
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
        return CircuitBreakers.find(breakerName(providerName))
                .orElseGet(() -> registerBreaker(providerName));
    }

    private static CircuitBreaker registerBreaker(String providerName) {
        var name = breakerName(providerName);
        var breaker = CircuitBreakers.get(name, config());
        breaker.setTransitionListener(
                BreakerAlarms.listener(name, "LLM provider '" + providerName + "'"));
        return breaker;
    }

    /**
     * How long a stream may produce nothing at all before the sweep abandons it; 0 disables.
     *
     * <p>Generous, and separate from the inter-chunk budget: a cold local model loading weights
     * legitimately takes minutes before its first token, while OkHttp's read timeout is no
     * backstop at all here, because SSE keepalive comments are reads and reset it (JCLAW-1181).
     */
    private static long firstChunkBudgetNanos() {
        return PlayConfig.longOr("llm.breaker.first-chunk-seconds", 600) * 1_000_000_000L;
    }

    /**
     * The fail-fast an open breaker raises, in the one class {@code chatWithFailover} triggers on.
     *
     * <p>An operator's isolation and an autonomous trip are different classes (JCLAW-1170), so a
     * turn that failed because somebody pulled the handle never reads as the provider breaking.
     * The distinction lasts only as long as the manual trip does: once the cooldown elapses and a
     * probe fails, the breaker is open on evidence and reports the ordinary failure.
     */
    public static LlmException.ServerError openBreakerFailure(String providerName) {
        if (breakerFor(providerName).stats().reason() == CircuitBreaker.Reason.MANUAL_TRIP) {
            return new LlmException.ManuallyIsolated("Circuit breaker for " + providerName
                    + " was opened by the operator: not calling it until it is restored");
        }
        return new LlmException.ServerError(
                "Circuit breaker open for " + providerName + ": not calling it until it recovers");
    }

    /**
     * The failure an abandoned stream is ended with, whether it went quiet before its first
     * chunk or after one (JCLAW-1181, JCLAW-1183). {@code quietMillis} is how long it had
     * produced nothing, which is also what separates the two budgets in the message.
     *
     * <p>Deliberately not a {@link LlmException.ServerError}: {@code StreamingAgentRunner}
     * re-streams once on that class, which would buy the caller a second full budget of silence.
     */
    public static LlmException abandonedStreamFailure(String providerName, long quietMillis) {
        return new LlmException("No response from %s for %d s: abandoning the stream"
                .formatted(providerName, quietMillis / 1000L));
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
        var guard = new StreamGuard(breaker, System::nanoTime, firstChunkBudgetNanos());
        if (guard.stallBudgetNanos > 0L || guard.firstChunkBudgetNanos > 0L) watch(guard);
        return guard;
    }

    /** Test seam: the same guard on a caller-supplied monotonic source, so a stall test needs no sleep. */
    public static StreamGuard streamGuardForTest(CircuitBreaker breaker, LongSupplier nanoTime,
                                                 long firstChunkBudgetMillis) {
        return new StreamGuard(breaker, nanoTime, firstChunkBudgetMillis * 1_000_000L);
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
            LIVE.forEach(StreamGuard::checkDeadlines);
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
     * <p>Two budgets, because a stream that has not started and a stream that has stopped are
     * different facts. Before the first chunk the deadline runs from the dispatch and is generous,
     * since a cold local model loading weights legitimately takes minutes; after it, the gap
     * between chunks is what is measured. JCLAW-1181: the pre-stream case needs its own budget
     * because OkHttp's read timeout does not cover it — SSE keepalive comments are reads, so a
     * provider emitting nothing else resets it for ever.
     *
     * <p>The two are charged differently — a stall mid-answer is a slow call, not a failure, since
     * the stream did deliver content, while silence throughout is a failure — but both end the
     * stream. A stream past either budget is holding a socket and a parked thread nothing will
     * reclaim, so {@link #onAbandoned} runs on both paths (JCLAW-1183): recording an outcome does
     * not unpark a thread waiting on the stream's own completion.
     *
     * <p>A stream that never terminates reports nothing on its own, which would both hide
     * the outage and strand a HALF_OPEN permit for good. {@link #checkDeadlines} is the way
     * out, called from the sweep rather than from the stream's own thread: that thread is
     * parked inside the transport's untimed await for the whole stall.
     */
    public static final class StreamGuard {

        private final CircuitBreaker breaker;
        private final LongSupplier nanoTime;
        private final long stallBudgetNanos;
        private final long firstChunkBudgetNanos;
        private final long dispatchNanos;

        private final AtomicBoolean reported = new AtomicBoolean();
        // armed rather than a sentinel stamp: nanoTime's origin is arbitrary and may be 0.
        private volatile boolean armed;
        private volatile long lastChunkNanos;
        private volatile long worstGapNanos;
        private volatile @Nullable LongConsumer abandon;

        private StreamGuard(CircuitBreaker breaker, LongSupplier nanoTime, long firstChunkBudgetNanos) {
            this.breaker = breaker;
            this.nanoTime = nanoTime;
            this.firstChunkBudgetNanos = firstChunkBudgetNanos;
            this.dispatchNanos = nanoTime.getAsLong();
            this.stallBudgetNanos = breaker.config().slowCallsEnabled()
                    ? breaker.config().slowCallDurationMillis() * 1_000_000L
                    : 0L;
        }

        /**
         * Install what ends the caller's turn and aborts the transport when the sweep abandons
         * this stream, given the milliseconds it had been quiet. Until the dispatch installs it a
         * stream that has produced nothing is left alone: an outcome recorded with nobody to
         * release trades a hang for a quieter hang.
         */
        public void onAbandoned(LongConsumer abandon) {
            this.abandon = abandon;
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
         * Charge a stream that is past one of its budgets while it is still in flight, so an
         * outage of streams that never end still opens the breaker.
         *
         * @return {@code true} when this call is what recorded the outcome
         */
        public boolean checkDeadlines() {
            if (reported.get()) return false;
            return armed ? chargeStall() : chargeSilence();
        }

        private boolean chargeStall() {
            if (stallBudgetNanos == 0L) return false;
            var gap = nanoTime.getAsLong() - lastChunkNanos;
            if (gap < stallBudgetNanos || !claim()) return false;
            breaker.recordSuccess(gap / 1_000_000L);
            // Unlike the silent case this charges whether or not anyone installed a release: the
            // stream did deliver content, so the breaker learns something either way.
            var release = abandon;
            if (release != null) release.accept(gap / 1_000_000L);
            return true;
        }

        private boolean chargeSilence() {
            var release = abandon;
            if (firstChunkBudgetNanos == 0L || release == null) return false;
            var silent = nanoTime.getAsLong() - dispatchNanos;
            if (silent < firstChunkBudgetNanos || !claim()) return false;
            // A failure rather than a slow success: nothing succeeded, and pinning it to the
            // slow-call rate would put the unbounded hang back behind llm.breaker.stall-seconds.
            breaker.recordFailure();
            release.accept(silent / 1_000_000L);
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
