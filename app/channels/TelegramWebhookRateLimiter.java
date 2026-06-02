package channels;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M1 webhook ingress hardening: a simple, thread-safe, in-memory fixed-window
 * request counter keyed by {@code bindingId}. The webhook controller consults
 * this BEFORE the secret-token compare so a wrong-secret flood against one
 * binding is rejected with a cheap HTTP 429 rather than paying the
 * {@link java.security.MessageDigest} compares (and DB-backed binding load
 * upstream) on every request.
 *
 * <p>Single-JVM by design — the limiter lives in process memory and resets on
 * restart. JClaw runs as one JVM, so a shared in-memory counter is the right
 * granularity; a distributed limiter would be over-engineering here.
 *
 * <p>Window semantics are a classic fixed window: each binding tracks a window
 * start timestamp and a count. When a request arrives after the window has
 * elapsed, the window rolls over (start reset to now, count reset to 1) and the
 * request is allowed. Within an active window, the count increments and the
 * request is rejected once it would exceed {@code max}. Fixed-window can admit
 * up to ~2x {@code max} across a window boundary; that burst tolerance is
 * acceptable for abuse-throttling (vs. precise quota enforcement).
 */
public final class TelegramWebhookRateLimiter {

    private TelegramWebhookRateLimiter() {}

    /** Per-binding counter. {@code windowStartMs} marks the current window's
     *  opening; {@code count} is the number of requests seen in that window. */
    private static final class Window {
        final AtomicLong windowStartMs;
        final AtomicInteger count;
        Window(long startMs) {
            this.windowStartMs = new AtomicLong(startMs);
            this.count = new AtomicInteger(0);
        }
    }

    private static final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    /**
     * Record a request for {@code bindingId} and report whether it is within
     * the limit. Returns {@code true} when the request is allowed, {@code false}
     * when the binding has exceeded {@code max} requests inside the current
     * {@code windowSeconds} window (the caller should reject with HTTP 429).
     *
     * <p>The whole window check + increment runs under
     * {@link ConcurrentHashMap#compute} so concurrent webhook deliveries for the
     * same binding (the receive path runs on virtual threads) observe a
     * consistent count without an external lock.
     */
    public static boolean allow(long bindingId, int max, long windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        Window w = windows.compute(bindingId, (k, existing) -> {
            if (existing == null) {
                existing = new Window(now);
            }
            long start = existing.windowStartMs.get();
            if (now - start >= windowMs) {
                // Window elapsed — roll over to a fresh window.
                existing.windowStartMs.set(now);
                existing.count.set(0);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return w.count.get() <= max;
    }

    /** Visible for tests: clear all per-binding counters so static state does
     *  not leak across test cases (call from {@code @BeforeEach}). */
    public static void resetForTest() {
        windows.clear();
    }
}
