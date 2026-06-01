package channels;

import services.EventLogger;

/**
 * JCLAW-341: streams an LLM response into Slack using the native streaming API
 * ({@code chat.startStream} → {@code chat.appendStream} → {@code chat.stopStream}).
 * This gives a live "is typing…" indicator and progressive text WITHOUT the
 * {@code (edited)} tag (it's a purpose-built streaming message, not repeated
 * edits), and Slack renders the {@code markdown_text} deltas as markdown natively
 * — so no mrkdwn conversion is needed on this path.
 *
 * <p>Native streaming requires the app to be a Slack AI Assistant (Agents & AI
 * Apps) with {@code assistant:write}, a reply thread ({@code thread_ts}), and the
 * recipient user id. When any of those is missing or {@code startStream} fails,
 * the sink degrades to a single formatted {@code chat.postMessage} at completion
 * (via {@link SlackChannel#sendMessage}, which mrkdwn-formats) — no indicator, no
 * streaming, but a clean formatted reply with no {@code (edited)} tag.
 *
 * <p>Single-threaded by contract: {@link AgentRunner#runStreaming} drives
 * update/seal/error sequentially on one virtual thread, and {@link #begin()} runs
 * before it (happens-before via thread start), so no synchronization is needed.
 */
public final class SlackStreamingSink {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "slack";
    private static final long APPEND_THROTTLE_MS = 500L;

    /** Slack streaming + fallback operations, injectable so tests don't hit the API. */
    public interface Slacker {
        /** Start a native stream; return its ts, or null on failure / unavailable. */
        String startStream(String channelId, String threadTs, String recipientUserId);
        /** Append a markdown delta to the stream; return true on success. */
        boolean appendStream(String channelId, String ts, String markdownDelta);
        /** Finalize the stream; return true on success. */
        boolean stopStream(String channelId, String ts);
        /** Off-thread fallback: post the reply once (text is mrkdwn-formatted by the sender). */
        void postFallback(String channelId, String text, String threadTs);
    }

    private static final Slacker LIVE = new Slacker() {
        @Override public String startStream(String c, String th, String u) { return SlackChannel.startStream(c, th, u); }
        @Override public boolean appendStream(String c, String ts, String d) { return SlackChannel.appendStream(c, ts, d); }
        @Override public boolean stopStream(String c, String ts) { return SlackChannel.stopStream(c, ts); }
        @Override public void postFallback(String c, String text, String th) { SlackChannel.sendMessage(c, text, th); }
    };

    private final String channelId;
    private final String threadTs;
    private final String recipientUserId;
    private final Slacker slacker;
    private final long throttleMs;
    private final StringBuilder pending = new StringBuilder();
    private String streamTs;     // native stream ts; null = fallback mode
    private boolean nativeMode;
    private long lastFlushMs;

    public SlackStreamingSink(String channelId, String threadTs, String recipientUserId) {
        this(channelId, threadTs, recipientUserId, LIVE, APPEND_THROTTLE_MS);
    }

    /** Test seam: inject the Slacker and append throttle (0 = flush every update). */
    public SlackStreamingSink(String channelId, String threadTs, String recipientUserId,
                              Slacker slacker, long throttleMs) {
        this.channelId = channelId;
        this.threadTs = threadTs;
        this.recipientUserId = recipientUserId;
        this.slacker = slacker;
        this.throttleMs = throttleMs;
    }

    /** Start a native stream when a thread + recipient are present; else fall back. */
    public void begin() {
        if (threadTs != null && !threadTs.isBlank() && recipientUserId != null && !recipientUserId.isBlank()) {
            streamTs = slacker.startStream(channelId, threadTs, recipientUserId);
            nativeMode = streamTs != null;
        }
        lastFlushMs = System.currentTimeMillis();
    }

    /** Per-token-batch hook: coalesce + throttled appendStream of the markdown delta. */
    public void update(String token) {
        if (token == null || token.isEmpty() || !nativeMode) return;
        pending.append(token);
        long now = System.currentTimeMillis();
        if (now - lastFlushMs >= throttleMs) {
            flush();
            lastFlushMs = now;
        }
    }

    /** Completion: flush the tail + stop the stream, or post the full reply once. */
    public void seal(String fullText) {
        if (nativeMode && streamTs != null) {
            flush();
            slacker.stopStream(channelId, streamTs);
        } else {
            slacker.postFallback(channelId, fullText, threadTs);
        }
    }

    /** Error: append a notice to the stream (or post one) and finalize. */
    public void errorFallback(Exception e) {
        EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                "Streaming error: %s".formatted(e != null ? e.getMessage() : "unknown"));
        String msg = "⚠️ Sorry — something went wrong handling that.";
        if (nativeMode && streamTs != null) {
            slacker.appendStream(channelId, streamTs, "\n\n" + msg);
            slacker.stopStream(channelId, streamTs);
        } else {
            slacker.postFallback(channelId, msg, threadTs);
        }
    }

    private void flush() {
        if (pending.length() == 0) return;
        if (slacker.appendStream(channelId, streamTs, pending.toString())) {
            pending.setLength(0);
        }
    }
}
