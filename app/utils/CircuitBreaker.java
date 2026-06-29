package utils;

/**
 * Minimal thread-safe circuit breaker for guarding a failure-prone subsystem
 * (e.g. a background LLM extraction call). Trips OPEN when the failure rate over
 * a rolling window of recent outcomes exceeds a threshold; after a cooldown it
 * admits a single probe (HALF_OPEN) and closes on success or re-opens on
 * failure.
 *
 * <p>Built for fire-and-forget callers: {@link #allowRequest()} is cheap and
 * never blocks, so an open breaker degrades the protected work to a no-op rather
 * than queueing or throwing. Callers report the outcome back via
 * {@link #recordSuccess()} / {@link #recordFailure()}.
 *
 * <p>The window is a fixed-size ring of outcomes (true = failure). The rate is
 * only evaluated once at least {@code minVolume} samples have accrued, so a
 * single early failure can't trip the breaker.
 *
 * <p>Instances are independent and carry no global state — construct one per
 * guarded subsystem. Tests construct their own so they never perturb a shared
 * one (play1 runs tests concurrently).
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int windowSize;
    private final double failureRateThreshold;
    private final int minVolume;
    private final long cooldownNanos;

    private final boolean[] window;   // ring buffer; true = failure
    private int count;                // samples in the window (caps at windowSize)
    private int cursor;               // next write index
    private int failures;             // failures currently in the window

    private State state = State.CLOSED;
    private long openedAtNanos;

    /**
     * @param windowSize           number of recent outcomes the rate is computed over
     * @param failureRateThreshold trip OPEN when failures/samples reaches this (0.0–1.0)
     * @param minVolume            minimum samples before the rate is evaluated
     * @param cooldownMillis       time OPEN before a HALF_OPEN probe is admitted
     */
    public CircuitBreaker(int windowSize, double failureRateThreshold, int minVolume, long cooldownMillis) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize must be >= 1");
        this.windowSize = windowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.minVolume = Math.max(1, minVolume);
        this.cooldownNanos = Math.max(0L, cooldownMillis) * 1_000_000L;
        this.window = new boolean[windowSize];
    }

    /**
     * @return {@code true} if the caller may proceed. CLOSED always proceeds;
     *         OPEN proceeds as a single probe (transitioning to HALF_OPEN) only
     *         once the cooldown has elapsed; HALF_OPEN blocks further probes
     *         until the in-flight one reports its outcome.
     */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && System.nanoTime() - openedAtNanos >= cooldownNanos) {
            state = State.HALF_OPEN;
            return true;
        }
        return state == State.CLOSED;
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            reset();          // probe succeeded — close and clear history
            return;
        }
        push(false);
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip();           // probe failed — re-open and restart the cooldown
            return;
        }
        push(true);
        if (state == State.CLOSED && count >= minVolume
                && (double) failures / count >= failureRateThreshold) {
            trip();
        }
    }

    public synchronized State state() {
        return state;
    }

    private void push(boolean failure) {
        if (count == windowSize && window[cursor]) {
            failures--;       // the sample we're overwriting was a failure
        }
        window[cursor] = failure;
        if (failure) failures++;
        cursor = (cursor + 1) % windowSize;
        if (count < windowSize) count++;
    }

    private void trip() {
        state = State.OPEN;
        openedAtNanos = System.nanoTime();
    }

    private void reset() {
        state = State.CLOSED;
        count = 0;
        cursor = 0;
        failures = 0;
    }
}
