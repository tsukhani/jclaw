package controllers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JCLAW-741: per-source failed-login throttle. A simple, thread-safe,
 * in-memory fixed-window counter of <em>failed</em> login attempts keyed by
 * client IP. {@link ApiAuthController#login} consults {@link #allow} before
 * doing any work; once a source exceeds {@code maxFailures} inside the current
 * window it is locked out with HTTP 429 until the window elapses, so a
 * brute-force flood against the single admin password neither guesses freely
 * nor forces unbounded 600k-iteration PBKDF2 verifies (a CPU-exhaustion
 * amplifier). A successful login clears the source's counter immediately, so a
 * legitimate operator who eventually types the right password is never
 * penalised for earlier typos.
 *
 * <p>Single-JVM by design — mirrors {@link channels.TelegramWebhookRateLimiter}.
 * JClaw runs as one JVM, so an in-memory counter is the right granularity; a
 * distributed limiter would be over-engineering. State resets on restart.
 *
 * <p>Fixed-window semantics: each source tracks a window-start timestamp and a
 * failure count. {@link #recordFailure} rolls the window over once it has
 * elapsed (start reset to now, count reset to 1); within an active window it
 * increments. {@link #allow} is a pure read — a stale window reads as allowed,
 * and the next failure opens a fresh one.
 */
public final class LoginRateLimiter {

    private LoginRateLimiter() {}

    private static final class Window {
        final AtomicLong windowStartMs;
        final AtomicInteger count;
        Window(long startMs) {
            this.windowStartMs = new AtomicLong(startMs);
            this.count = new AtomicInteger(0);
        }
    }

    private static final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Whether a login attempt from {@code key} is currently allowed. Returns
     * {@code false} once the source has recorded {@code maxFailures} failures
     * inside the active {@code windowSeconds} window (the caller rejects with
     * HTTP 429). A stale window — one that opened more than {@code windowSeconds}
     * ago — reads as allowed. Pure read: it does not mutate the counter, so
     * lock-out attempts do not extend the window.
     */
    public static boolean allow(String key, int maxFailures, long windowSeconds) {
        Window w = windows.get(key);
        if (w == null) return true;
        long now = System.currentTimeMillis();
        if (now - w.windowStartMs.get() >= windowSeconds * 1000L) return true;
        return w.count.get() < maxFailures;
    }

    /**
     * Record a failed login for {@code key}. Runs under
     * {@link ConcurrentHashMap#compute} so concurrent login attempts for the
     * same source observe a consistent count without an external lock. Rolls
     * the window over when the prior one has elapsed.
     */
    public static void recordFailure(String key, long windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs.get() >= windowMs) {
                Window fresh = new Window(now);
                fresh.count.set(1);
                return fresh;
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

    /** Clear a source's counter after a successful login so earlier typos do
     *  not count against a now-authenticated operator. */
    public static void recordSuccess(String key) {
        windows.remove(key);
    }
}
