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
public final class SlackStreamingSink implements ChannelStreamingSink {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "slack";
    private static final long APPEND_THROTTLE_MS = 500L;
    private static final String STATUS_TYPING = "is typing...";

    /** Slack streaming + fallback operations, injectable so tests don't hit the API. */
    public interface Slacker {
        /** Start a native stream carrying the first content; return its ts, or null. */
        String startStream(String channelId, String threadTs, String recipientUserId, String initialMarkdown);
        /** Append a markdown delta to the stream; return true on success. */
        boolean appendStream(String channelId, String ts, String markdownDelta);
        /** Finalize the stream; return true on success. */
        boolean stopStream(String channelId, String ts);
        /** Set (or clear, with "") the assistant-thread "is typing…" status line. */
        void setStatus(String channelId, String threadTs, String status);
        /** Off-thread fallback: post the reply once (text is mrkdwn-formatted by the sender). */
        void postFallback(String channelId, String text, String threadTs);
    }

    /** JCLAW-441: a live Slacker bound to one agent's bot token. The streaming +
     *  fallback calls all carry {@code botToken} so the reply posts as that
     *  binding's bot, not the legacy app-global identity. */
    private static Slacker live(String botToken) {
        return new Slacker() {
            @Override public String startStream(String c, String th, String u, String init) { return SlackChannel.startStream(c, th, u, init, botToken); }
            @Override public boolean appendStream(String c, String ts, String d) { return SlackChannel.appendStream(c, ts, d, botToken); }
            @Override public boolean stopStream(String c, String ts) { return SlackChannel.stopStream(c, ts, botToken); }
            @Override public void setStatus(String c, String th, String s) { SlackChannel.setAssistantStatus(c, th, s, botToken); }
            @Override public void postFallback(String c, String text, String th) { SlackChannel.sendMessage(c, text, th, botToken); }
        };
    }

    private final String channelId;
    private final String threadTs;
    private final String recipientUserId;
    private final Slacker slacker;
    private final long throttleMs;
    private final StringBuilder pending = new StringBuilder();
    private String streamTs;     // native stream ts; null until first token (lazy start)
    private boolean canStream;   // assistant thread + recipient present
    private boolean startAttempted;
    private boolean nativeMode;
    private boolean statusSet;   // true while the "is typing…" status is showing
    private long lastFlushMs;

    public SlackStreamingSink(String channelId, String threadTs, String recipientUserId, String botToken) {
        this(channelId, threadTs, recipientUserId, live(botToken), APPEND_THROTTLE_MS);
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

    /** Show the "is typing…" status (assistant thread). The stream message itself is
     *  created lazily on the first token (see {@link #update}) so it's never empty. */
    public void begin() {
        boolean thread = threadTs != null && !threadTs.isBlank();
        if (thread) {
            // Status line cue, set before the LLM runs so it shows during "thinking".
            slacker.setStatus(channelId, threadTs, STATUS_TYPING);
            statusSet = true;
            canStream = recipientUserId != null && !recipientUserId.isBlank();
        }
        lastFlushMs = System.currentTimeMillis();
    }

    /** JCLAW-442: {@link ChannelStreamingSink} typing cue. Slack's is the
     *  assistant-thread status line set by {@link #begin()} — there is no
     *  background heartbeat thread (unlike Telegram). */
    @Override
    public void startTypingHeartbeat() {
        begin();
    }

    /** JCLAW-442: {@link ChannelStreamingSink} cancellation (e.g. /stop) — clear
     *  the "is typing…" status; no live stream to tear down beyond that. */
    @Override
    public void cancel() {
        clearStatus();
    }

    /** Per-token-batch hook: lazily start the stream with the first content (so the
     *  message is never empty), then coalesce + throttled appendStream of deltas. */
    public void update(String token) {
        if (token == null || token.isEmpty()) return;
        pending.append(token);
        if (!canStream) return;
        if (streamTs == null) {
            if (startAttempted) return; // start failed earlier → fall back at seal
            startAttempted = true;
            streamTs = slacker.startStream(channelId, threadTs, recipientUserId, pending.toString());
            nativeMode = streamTs != null;
            if (nativeMode) pending.setLength(0);
            lastFlushMs = System.currentTimeMillis();
            return;
        }
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
        clearStatus();
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
        clearStatus();
    }

    private void clearStatus() {
        if (statusSet) {
            slacker.setStatus(channelId, threadTs, "");
            statusSet = false;
        }
    }

    private void flush() {
        if (pending.isEmpty()) return;
        if (slacker.appendStream(channelId, streamTs, pending.toString())) {
            pending.setLength(0);
        }
    }
}
