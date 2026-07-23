package controllers;

import play.Play;
import play.mvc.Http;
import services.EventLogger;
import utils.ApiResponses;
import utils.PlayConfig;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JCLAW-783: shared pre-auth webhook ingress gate. Factors the Telegram webhook's
 * ingress hardening — a per-source rate limit plus a max-body-bytes cap, both
 * applied BEFORE any body read / JSON parse / DB lookup — into one reusable
 * component so the Slack and WhatsApp entrypoints get the same protection they
 * previously lacked (Slack did {@code findSlackBindingById} + an unbounded
 * {@code readAllBytes}, WhatsApp did {@code readRawBody} + a full
 * {@code JsonParser.parseString} + {@code findByPhoneNumberId}, all before the
 * HMAC compare).
 *
 * <p>Keyed on an arbitrary String so both id-keyed (Slack: the URL binding id) and
 * IP-keyed (WhatsApp: the source IP, since its routing id is only in the body)
 * callers share one fixed-window counter. Limits are read from
 * {@code <prefix>.rate-limit.max} / {@code .window-seconds} and
 * {@code <prefix>.max-body-bytes}, each defaulting to the same values as the
 * Telegram gate ({@code telegram.webhook.*}). The Telegram controller keeps its
 * own {@link channels.TelegramWebhookRateLimiter} unchanged.
 *
 * <p>Single-JVM by design, like {@link channels.TelegramWebhookRateLimiter}: the
 * counter lives in process memory and resets on restart. JClaw runs as one JVM.
 */
public final class WebhookIngressGate {

    private WebhookIngressGate() {}

    private static final String CATEGORY_CHANNEL = "channel";

    // Config-key suffixes appended to the per-channel prefix (e.g. "slack.webhook").
    private static final String CFG_RATE_LIMIT_MAX = ".rate-limit.max";
    private static final String CFG_RATE_LIMIT_WINDOW_SECONDS = ".rate-limit.window-seconds";
    private static final String CFG_MAX_BODY_BYTES = ".max-body-bytes";
    private static final String CFG_TRUSTED_PROXY = ".trusted-proxy";

    // Defaults mirror the Telegram gate so an operator who configures nothing gets
    // the same 60 req / 60 s / 1 MiB envelope on every channel.
    private static final int DEFAULT_RATE_LIMIT_MAX = 60;
    private static final long DEFAULT_RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final long DEFAULT_MAX_BODY_BYTES = 1_048_576L;

    /** Returned when no client IP can be resolved, so an IP-keyed limiter still has
     *  a non-null key ({@link ConcurrentHashMap} forbids null keys). */
    private static final String UNKNOWN_IP = "unknown";

    // ── fixed-window rate limiter (String-keyed twin of TelegramWebhookRateLimiter) ──

    private static final class Window {
        final AtomicLong windowStartMs;
        final AtomicInteger count;
        Window(long startMs) {
            this.windowStartMs = new AtomicLong(startMs);
            this.count = new AtomicInteger(0);
        }
    }

    private static final ConcurrentHashMap<String, Window> WINDOWS = new ConcurrentHashMap<>();

    /**
     * Record a request for {@code key} and report whether it is within the limit.
     * Returns {@code true} when allowed, {@code false} once {@code key} has exceeded
     * {@code max} requests inside the current {@code windowSeconds} fixed window.
     * The window check + increment runs under {@link ConcurrentHashMap#compute} so
     * concurrent virtual-thread deliveries for one key stay consistent.
     */
    public static boolean allow(String key, int max, long windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        Window w = WINDOWS.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new Window(now);
            }
            long start = existing.windowStartMs.get();
            if (now - start >= windowMs) {
                existing.windowStartMs.set(now);
                existing.count.set(0);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return w.count.get() <= max;
    }

    /** Visible for tests: clear all per-key counters so static state doesn't leak
     *  across cases (call from {@code @BeforeEach}). */
    public static void resetForTest() {
        WINDOWS.clear();
    }

    // ── the gate applied at the top of a webhook entrypoint ──

    /**
     * Pre-auth gate for a webhook entrypoint: rate-limit {@code key} and reject an
     * over-Content-Length body, BOTH before the caller reads the body, parses it,
     * or touches the DB. Throws HTTP 429 / 413 on breach (via {@link ApiResponses});
     * returns the resolved max-body-bytes so the caller can run the post-read
     * backstop with {@link #enforceReadLength}.
     *
     * @param channel  event-log channel name (for the audit line)
     * @param key      rate-limit key (Slack: binding id; WhatsApp: source IP)
     * @param prefix   config prefix, e.g. {@code "slack.webhook"}
     * @param clientIp resolved client IP, logged on a breach
     */
    public static long enforcePreAuth(String channel, String key, String prefix, String clientIp) {
        int max = PlayConfig.intOr(prefix + CFG_RATE_LIMIT_MAX, DEFAULT_RATE_LIMIT_MAX);
        long window = PlayConfig.longOr(prefix + CFG_RATE_LIMIT_WINDOW_SECONDS, DEFAULT_RATE_LIMIT_WINDOW_SECONDS);
        if (!allow(key, max, window)) {
            EventLogger.warn(CATEGORY_CHANNEL, null, channel,
                    "Rate-limited webhook (key %s) from %s".formatted(key, clientIp));
            ApiResponses.error(429, "rate_limited", "Too Many Requests");
        }
        long maxBodyBytes = PlayConfig.longOr(prefix + CFG_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);
        if (contentLengthExceeds(maxBodyBytes)) {
            EventLogger.warn(CATEGORY_CHANNEL, null, channel,
                    "Oversized webhook body (Content-Length) key %s from %s".formatted(key, clientIp));
            ApiResponses.error(413, "payload_too_large", "Payload Too Large");
        }
        return maxBodyBytes;
    }

    /**
     * Post-read backstop for a chunked / unset / lying Content-Length: reject with
     * HTTP 413 when the body actually read exceeds {@code maxBodyBytes}. Kept
     * separate from {@link #enforcePreAuth} because the raw body is only in hand
     * after the caller reads it.
     */
    public static void enforceReadLength(String channel, String key, String clientIp,
                                         String rawBody, long maxBodyBytes) {
        if (rawBody.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
            EventLogger.warn(CATEGORY_CHANNEL, null, channel,
                    "Oversized webhook body (read length) key %s from %s".formatted(key, clientIp));
            ApiResponses.error(413, "payload_too_large", "Payload Too Large");
        }
    }

    /**
     * True when the request's {@code Content-Length} header is present and exceeds
     * {@code maxBodyBytes}. A missing / unparseable header returns false (the
     * read-length backstop catches those).
     */
    public static boolean contentLengthExceeds(long maxBodyBytes) {
        var header = Http.Request.current().headers.get("content-length");
        if (header == null || header.value() == null) return false;
        try {
            return Long.parseLong(header.value().trim()) > maxBodyBytes;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /**
     * Resolve the real client IP for logging / IP-keying. With
     * {@code <prefix>.trusted-proxy=false} (default) the socket peer is
     * authoritative; with {@code true} — JClaw sits behind a trusted reverse proxy
     * — the left-most {@code X-Forwarded-For} entry wins. Never returns null/blank:
     * falls back to {@value #UNKNOWN_IP} so an IP-keyed limiter always has a usable
     * key.
     */
    public static String resolveClientIp(String prefix) {
        var req = Http.Request.current();
        if (trustedProxy(prefix)) {
            var xff = req.headers.get("x-forwarded-for");
            if (xff != null && xff.value() != null && !xff.value().isBlank()) {
                return xff.value().split(",")[0].trim();
            }
        }
        return req.remoteAddress != null && !req.remoteAddress.isBlank() ? req.remoteAddress : UNKNOWN_IP;
    }

    private static boolean trustedProxy(String prefix) {
        var raw = Play.configuration.getProperty(prefix + CFG_TRUSTED_PROXY, "false");
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }
}
