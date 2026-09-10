package utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import play.Logger;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Minimal thread-safe circuit breaker for guarding a failure-prone subsystem
 * (e.g. a background LLM extraction call, a provider chat call, an MCP server).
 * Trips OPEN when the failure rate — or, when configured, the slow-call rate —
 * over a rolling window of recent outcomes exceeds a threshold; after a cooldown
 * it admits a bounded number of probes (HALF_OPEN) and closes once they all
 * succeed, or re-opens on the first one that does not.
 *
 * <p>Built for fire-and-forget callers: {@link #allowRequest()} is cheap and
 * never blocks, so an open breaker degrades the protected work to a no-op rather
 * than queueing or throwing. Callers report the outcome back via
 * {@link #recordSuccess()} / {@link #recordSuccess(long)} / {@link #recordFailure()}.
 *
 * <p>The window is a fixed-size ring of outcomes (ok / slow / fail). A rate is
 * only evaluated once at least {@code minVolume} samples have accrued, so a
 * single early failure can't trip the breaker, and only when a non-ok outcome
 * arrives — an ok sample can never push a rate up.
 *
 * <p>A slow call is not a failure: it does not count toward the failure rate,
 * only toward its own. Slow-call detection is off unless both
 * {@link Config#slowCallDurationMillis()} and {@link Config#slowCallRateThreshold()}
 * are positive.
 *
 * <p>Instances are independent and carry no global state — construct one per
 * guarded subsystem, or share by name through {@link CircuitBreakers}. Tests
 * construct their own so they never perturb a shared one (play1 runs tests
 * concurrently).
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Why the breaker moved. Carried on a {@link Transition} so a listener can log it. */
    public enum Reason {
        FAILURE_RATE, SLOW_CALL_RATE, COOLDOWN_ELAPSED,
        PROBE_SUCCEEDED, PROBE_FAILED, PROBE_SLOW,
        MANUAL_TRIP, MANUAL_RESET;

        /** The two an operator causes, which must never read back as the breaker moving by itself. */
        public boolean manual() {
            return this == MANUAL_TRIP || this == MANUAL_RESET;
        }
    }

    private static final byte OK = 0;
    private static final byte SLOW = 1;
    private static final byte FAIL = 2;

    /**
     * Tuning for one breaker. Build the common shape with {@link #of} and widen it
     * with the {@code with*} methods rather than counting positional arguments.
     *
     * @param windowSize             number of recent outcomes a rate is computed over
     * @param failureRateThreshold   trip OPEN when failures/samples reaches this (0.0–1.0)
     * @param minVolume              minimum samples before a rate is evaluated
     * @param cooldownMillis         time OPEN before HALF_OPEN probes are admitted
     * @param halfOpenPermits        probes admitted per HALF_OPEN window; all must succeed to close
     * @param slowCallDurationMillis a recorded duration at or above this is slow; 0 disables
     * @param slowCallRateThreshold  trip OPEN when slow/samples reaches this; 0 disables
     */
    public record Config(int windowSize, double failureRateThreshold, int minVolume, long cooldownMillis,
                         int halfOpenPermits, long slowCallDurationMillis, double slowCallRateThreshold) {

        public Config {
            if (windowSize < 1) throw new IllegalArgumentException("windowSize must be >= 1");
            minVolume = Math.max(1, minVolume);
            cooldownMillis = Math.max(0L, cooldownMillis);
            halfOpenPermits = Math.max(1, halfOpenPermits);
            slowCallDurationMillis = Math.max(0L, slowCallDurationMillis);
        }

        /** The pre-JCLAW-1171 shape: one half-open probe, no slow-call detection. */
        public static Config of(int windowSize, double failureRateThreshold, int minVolume, long cooldownMillis) {
            return new Config(windowSize, failureRateThreshold, minVolume, cooldownMillis, 1, 0L, 0.0);
        }

        public Config withHalfOpenPermits(int permits) {
            return new Config(windowSize, failureRateThreshold, minVolume, cooldownMillis,
                    permits, slowCallDurationMillis, slowCallRateThreshold);
        }

        public Config withSlowCalls(long durationMillis, double rateThreshold) {
            return new Config(windowSize, failureRateThreshold, minVolume, cooldownMillis,
                    halfOpenPermits, durationMillis, rateThreshold);
        }

        public boolean slowCallsEnabled() {
            return slowCallDurationMillis > 0L && slowCallRateThreshold > 0.0;
        }
    }

    /**
     * Point-in-time view of the window, for an ops endpoint or a transition log line.
     *
     * @param reason what moved the breaker into {@code state}; null before it has ever moved
     */
    public record Stats(State state, int samples, int failures, int slowCalls, @Nullable Reason reason) {

        public double failureRate() {
            return samples == 0 ? 0.0 : (double) failures / samples;
        }

        public double slowCallRate() {
            return samples == 0 ? 0.0 : (double) slowCalls / samples;
        }
    }

    public record Transition(State from, State to, Reason reason, Stats stats) {}

    private final Config config;
    private final LongSupplier nanoTime;
    private final long cooldownNanos;

    private final byte[] window;      // ring buffer of OK / SLOW / FAIL
    private int count;                // samples in the window (caps at windowSize)
    private int cursor;               // next write index
    private int failures;             // FAIL samples currently in the window
    private int slowCalls;            // SLOW samples currently in the window

    private State state = State.CLOSED;
    private @Nullable Reason lastReason;
    private long openedAtNanos;
    private int halfOpenInFlight;     // probes admitted and not yet reported
    private int halfOpenSuccesses;    // probes that came back ok this HALF_OPEN window

    private volatile @Nullable Consumer<Transition> listener;

    public CircuitBreaker(int windowSize, double failureRateThreshold, int minVolume, long cooldownMillis) {
        this(Config.of(windowSize, failureRateThreshold, minVolume, cooldownMillis));
    }

    public CircuitBreaker(Config config) {
        this(config, System::nanoTime);
    }

    /** {@code nanoTime} is a test seam — it is a monotonic source, never a wall clock, so AppClock does not apply. */
    public CircuitBreaker(Config config, LongSupplier nanoTime) {
        this.config = config;
        this.nanoTime = nanoTime;
        this.cooldownNanos = config.cooldownMillis() * 1_000_000L;
        this.window = new byte[config.windowSize()];
    }

    /**
     * Register (or clear, with {@code null}) the state-transition listener. It is
     * dispatched after the monitor is released, so it may write to the database.
     */
    public void setTransitionListener(@Nullable Consumer<Transition> listener) {
        this.listener = listener;
    }

    /**
     * @return {@code true} if the caller may proceed. CLOSED always proceeds;
     *         OPEN moves to HALF_OPEN once the cooldown has elapsed; HALF_OPEN
     *         proceeds while unreported probes remain within
     *         {@link Config#halfOpenPermits()}.
     */
    public boolean allowRequest() {
        Transition transition = null;
        boolean allowed;
        synchronized (this) {
            if (state == State.OPEN && nanoTime.getAsLong() - openedAtNanos >= cooldownNanos) {
                transition = toHalfOpen();
            }
            allowed = switch (state) {
                case CLOSED -> true;
                case HALF_OPEN -> takePermit();
                case OPEN -> false;
            };
        }
        dispatch(transition);
        return allowed;
    }

    public void recordSuccess() {
        recordSuccess(0L);
    }

    /** @param durationMillis how long the call took; at or above the threshold it is a slow call, not a failure. */
    public void recordSuccess(long durationMillis) {
        var slow = config.slowCallsEnabled() && durationMillis >= config.slowCallDurationMillis();
        Transition transition;
        synchronized (this) {
            transition = recordOutcome(slow ? SLOW : OK);
        }
        dispatch(transition);
    }

    public void recordFailure() {
        Transition transition;
        synchronized (this) {
            transition = recordOutcome(FAIL);
        }
        dispatch(transition);
    }

    /** Force OPEN and restart the cooldown — the operator's kill switch. */
    public void trip() {
        Transition transition;
        synchronized (this) {
            transition = toOpen(Reason.MANUAL_TRIP);
        }
        dispatch(transition);
    }

    /** Force CLOSED and clear the outcome window — the operator's all-clear. */
    public void reset() {
        Transition transition;
        synchronized (this) {
            transition = toClosed(Reason.MANUAL_RESET);
        }
        dispatch(transition);
    }

    public synchronized @NonNull State state() {
        return state;
    }

    public synchronized @NonNull Stats stats() {
        return new Stats(state, count, failures, slowCalls, lastReason);
    }

    public Config config() {
        return config;
    }

    private @Nullable Transition recordOutcome(byte outcome) {
        if (state == State.HALF_OPEN) {
            return probeReported(outcome);
        }
        push(outcome);
        if (outcome == OK || state != State.CLOSED || count < config.minVolume()) {
            return null;
        }
        if ((double) failures / count >= config.failureRateThreshold()) {
            return toOpen(Reason.FAILURE_RATE);
        }
        if (config.slowCallsEnabled() && (double) slowCalls / count >= config.slowCallRateThreshold()) {
            return toOpen(Reason.SLOW_CALL_RATE);
        }
        return null;
    }

    /** A probe that came back slow is still a degraded subsystem, so it re-opens rather than counting toward closing. */
    private @Nullable Transition probeReported(byte outcome) {
        halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
        if (outcome == FAIL) return toOpen(Reason.PROBE_FAILED);
        if (outcome == SLOW) return toOpen(Reason.PROBE_SLOW);
        halfOpenSuccesses++;
        return halfOpenSuccesses >= config.halfOpenPermits() ? toClosed(Reason.PROBE_SUCCEEDED) : null;
    }

    private boolean takePermit() {
        if (halfOpenSuccesses + halfOpenInFlight >= config.halfOpenPermits()) return false;
        halfOpenInFlight++;
        return true;
    }

    private void push(byte outcome) {
        if (count == window.length) {
            var evicted = window[cursor];
            if (evicted == FAIL) failures--;
            else if (evicted == SLOW) slowCalls--;
        }
        window[cursor] = outcome;
        if (outcome == FAIL) failures++;
        else if (outcome == SLOW) slowCalls++;
        cursor = (cursor + 1) % window.length;
        if (count < window.length) count++;
    }

    private Transition toOpen(Reason reason) {
        var from = state;
        state = State.OPEN;
        lastReason = reason;
        openedAtNanos = nanoTime.getAsLong();
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
        return new Transition(from, state, reason, stats());
    }

    private Transition toClosed(Reason reason) {
        var from = state;
        state = State.CLOSED;
        lastReason = reason;
        count = 0;
        cursor = 0;
        failures = 0;
        slowCalls = 0;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
        return new Transition(from, state, reason, stats());
    }

    private Transition toHalfOpen() {
        var from = state;
        state = State.HALF_OPEN;
        lastReason = Reason.COOLDOWN_ELAPSED;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
        return new Transition(from, state, Reason.COOLDOWN_ELAPSED, stats());
    }

    private void dispatch(@Nullable Transition transition) {
        var target = listener;
        if (transition == null || target == null) return;
        try {
            target.accept(transition);
        } catch (RuntimeException e) {
            Logger.warn(e, "Circuit breaker transition listener failed (%s -> %s)",
                    transition.from(), transition.to());
        }
    }
}
