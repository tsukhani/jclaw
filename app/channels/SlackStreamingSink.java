package channels;

import services.EventLogger;

/**
 * JCLAW-341: streams an LLM response into a single Slack message. Posts a
 * placeholder immediately (the visible "working" cue — Slack has no bot typing
 * indicator over the Events API), edits it via throttled {@code chat.update} as
 * tokens arrive, and seals with the final mrkdwn-formatted text.
 *
 * <p>Slack rate-limits {@code chat.update} (~1/sec sustained), so edits are
 * coalesced to at most once per {@code throttleMs}, which ratchets up on a
 * failed/rate-limited edit. Streaming edits carry the RAW accumulated text; only
 * the seal is mrkdwn-formatted — a half-open {@code **} or {@code ```} mid-stream
 * would otherwise render oddly (same rationale as {@link TelegramStreamingSink}).
 *
 * <p>Single-threaded by contract: {@link AgentRunner#runStreaming} invokes
 * onToken/onComplete/onError sequentially on its own virtual thread, and
 * {@link #begin()} runs (with a happens-before via thread start) before any of
 * them — so no synchronization is needed.
 */
public final class SlackStreamingSink {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "slack";
    private static final long THROTTLE_MIN_MS = 1500L;
    private static final long THROTTLE_MAX_MS = 5000L;
    private static final long THROTTLE_STEP_MS = 1000L;
    private static final String PLACEHOLDER = "_…_";

    /** Slack post/update operations, injectable so tests don't hit the API. */
    public interface Slacker {
        /** Post {@code text}; return the new message ts, or null on failure. */
        String post(String channelId, String text, String threadTs);
        /** Edit message {@code ts}; return true on success. */
        boolean update(String channelId, String ts, String text);
    }

    private static final Slacker LIVE = new Slacker() {
        @Override public String post(String c, String text, String th) {
            return SlackChannel.postReturningTs(c, text, th);
        }
        @Override public boolean update(String c, String ts, String text) {
            return SlackChannel.updateMessage(c, ts, text).ok();
        }
    };

    private final String channelId;
    private final String threadTs;
    private final Slacker slacker;
    private final StringBuilder full = new StringBuilder();
    private String ts;             // placeholder message ts; null if the post failed
    private long lastFlushMs;
    private long throttleMs;

    public SlackStreamingSink(String channelId, String threadTs) {
        this(channelId, threadTs, LIVE, THROTTLE_MIN_MS);
    }

    /** Test seam: inject the Slacker and an explicit throttle (0 = flush every update). */
    public SlackStreamingSink(String channelId, String threadTs, Slacker slacker, long throttleMs) {
        this.channelId = channelId;
        this.threadTs = threadTs;
        this.slacker = slacker;
        this.throttleMs = throttleMs;
    }

    /** Post the placeholder so the user sees an immediate working indicator. */
    public void begin() {
        ts = slacker.post(channelId, PLACEHOLDER, threadTs);
        lastFlushMs = System.currentTimeMillis();
    }

    /** Per-token-batch hook: append + throttled edit with the raw accumulated text. */
    public void update(String token) {
        if (token == null || token.isEmpty()) return;
        full.append(token);
        if (ts == null) return;
        long now = System.currentTimeMillis();
        if (now - lastFlushMs >= throttleMs) {
            flush(full.toString());
            lastFlushMs = now;
        }
    }

    /** Completion: one final edit carrying the full mrkdwn-formatted response. */
    public void seal(String fullText) {
        String formatted = SlackMarkdownFormatter.format(fullText);
        if (formatted.isBlank()) formatted = "_(no response)_";
        if (ts != null) {
            slacker.update(channelId, ts, formatted);
        } else {
            // Placeholder never posted (transient API error) — post the reply fresh.
            slacker.post(channelId, formatted, threadTs);
        }
    }

    /** Error: surface a short notice in place of the placeholder. */
    public void errorFallback(Exception e) {
        EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                "Streaming error: %s".formatted(e != null ? e.getMessage() : "unknown"));
        String msg = "⚠️ Sorry — something went wrong handling that.";
        if (ts != null) slacker.update(channelId, ts, msg);
        else slacker.post(channelId, msg, threadTs);
    }

    private void flush(String text) {
        if (!slacker.update(channelId, ts, text) && throttleMs < THROTTLE_MAX_MS) {
            throttleMs = Math.min(throttleMs + THROTTLE_STEP_MS, THROTTLE_MAX_MS);
        }
    }
}
