package channels;

import services.EventLogger;
import java.util.ArrayList;
import java.util.List;

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
 * recipient user id. Off-thread (channel messages, or threads without a recipient)
 * the sink falls back to a {@code chat.update} draft preview (JCLAW-346): it posts
 * a placeholder on the first token, edits it with the accumulating raw text on a
 * throttle, then does one final edit with the mrkdwn-formatted reply at completion.
 * This streams progressively off-thread at the cost of the {@code (edited)} tag.
 * Only when no token ever arrives (or the draft post fails) does it post once via
 * {@link SlackChannel#sendMessage}.
 *
 * <p>Single-threaded by contract: {@link AgentRunner#runStreaming} drives
 * update/seal/error sequentially on one virtual thread, and {@link #begin()} runs
 * before it (happens-before via thread start), so no synchronization is needed.
 */
public final class SlackStreamingSink implements ChannelStreamingSink {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "slack";
    private static final long APPEND_THROTTLE_MS = 500L;
    /** JCLAW-346: chat.update is more tightly rate-limited than the native
     *  appendStream, so the off-thread draft loop edits at most this often. */
    private static final long DRAFT_THROTTLE_MS = 1200L;
    /** Stop live draft edits past this length; the final seal edit still delivers
     *  the full text (avoids spamming huge chat.update calls). */
    private static final int DRAFT_PREVIEW_MAX = 3500;
    private static final int MAX_TOOL_LINES = 8;
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
        /** JCLAW-346: post the draft-preview placeholder; return its ts, or null. */
        String postMessage(String channelId, String text, String threadTs);
        /** JCLAW-346: edit a message's text (chat.update); return true on success. */
        boolean editMessage(String channelId, String ts, String text);
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
            @Override public String postMessage(String c, String text, String th) { return SlackChannel.postText(c, text, th, botToken); }
            @Override public boolean editMessage(String c, String ts, String text) { return SlackChannel.editMessage(c, ts, text, botToken); }
        };
    }

    private final String channelId;
    private final String threadTs;
    private final String recipientUserId;
    private final Slacker slacker;
    private final long throttleMs;
    // JCLAW-345: seal-time outbound file upload. Null on the test-injected path
    // (those tests don't exercise uploads), which disables the dispatch.
    private final String botToken;
    private final String agentName;
    private final StringBuilder pending = new StringBuilder();
    private String streamTs;     // native stream ts; null until first token (lazy start)
    private boolean canStream;   // assistant thread + recipient present
    private boolean startAttempted;
    private boolean nativeMode;
    private boolean statusSet;   // true while the "is typing…" status is showing
    private long lastFlushMs;
    // JCLAW-346: off-thread draft-preview state (used when native streaming is
    // unavailable — channel messages / threads without a recipient).
    private String draftTs;        // chat.update message ts; null until the first post
    private String lastDraftText;  // dedup consecutive identical edits
    private boolean draftStopped;  // stop the live loop (length cap / post-or-edit failure)
    private final List<String> toolLines = new ArrayList<>();

    /** Production: stream as the binding's bot; {@code agentName} drives the
     *  seal-time upload of any files the agent linked in its reply (JCLAW-345). */
    public SlackStreamingSink(String channelId, String threadTs, String recipientUserId,
                              String botToken, String agentName) {
        this(channelId, threadTs, recipientUserId, live(botToken), APPEND_THROTTLE_MS, botToken, agentName);
    }

    /** Test seam: inject the Slacker and append throttle (0 = flush every update). */
    public SlackStreamingSink(String channelId, String threadTs, String recipientUserId,
                              Slacker slacker, long throttleMs) {
        this(channelId, threadTs, recipientUserId, slacker, throttleMs, null, null);
    }

    private SlackStreamingSink(String channelId, String threadTs, String recipientUserId,
                               Slacker slacker, long throttleMs, String botToken, String agentName) {
        this.channelId = channelId;
        this.threadTs = threadTs;
        this.recipientUserId = recipientUserId;
        this.slacker = slacker;
        this.throttleMs = throttleMs;
        this.botToken = botToken;
        this.agentName = agentName;
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

    /** Per-token-batch hook. Native (assistant thread + recipient) lazily starts a
     *  chat.startStream and appends throttled deltas; off-thread it drives a
     *  chat.update draft preview (JCLAW-346). */
    public void update(String token) {
        if (token == null || token.isEmpty()) return;
        pending.append(token);
        if (canStream) {
            updateNative();
        } else {
            updateDraft();
        }
    }

    private void updateNative() {
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

    /** JCLAW-346: off-thread draft preview — post a placeholder on the first token,
     *  then throttled chat.update edits of the accumulated (raw) text. The final
     *  formatted text lands on {@link #seal}. */
    private void updateDraft() {
        if (draftStopped) return;
        long now = System.currentTimeMillis();
        if (draftTs == null || now - lastFlushMs >= draftThrottleMs()) {
            sendOrEditDraft(pending.toString());
            lastFlushMs = now;
        }
    }

    /** Honour the injected throttle (tests pass 0 → flush every update); in
     *  production raise to {@link #DRAFT_THROTTLE_MS} so chat.update stays under its
     *  tighter rate limit than the native appendStream path. */
    private long draftThrottleMs() {
        return throttleMs == 0 ? 0 : Math.max(throttleMs, DRAFT_THROTTLE_MS);
    }

    /** Post the draft placeholder (first call) or edit it (subsequent), with dedup
     *  + a length cap. Sets {@link #draftStopped} on overflow / post-or-edit failure
     *  so {@link #seal} does the final edit or falls back. */
    private void sendOrEditDraft(String text) {
        if (text == null || text.isBlank()) return;
        if (text.length() > DRAFT_PREVIEW_MAX) {
            draftStopped = true;
            return;
        }
        if (draftTs == null) {
            draftTs = slacker.postMessage(channelId, text, threadTs);
            lastDraftText = text;
            if (draftTs == null) draftStopped = true; // post failed → fall back at seal
            return;
        }
        if (text.equals(lastDraftText)) return;        // dedup
        if (slacker.editMessage(channelId, draftTs, text)) {
            lastDraftText = text;
        } else {
            draftStopped = true; // edit failed → stop; seal still attempts a final edit
        }
    }

    /** Completion: finalize the native stream, the draft preview (a last formatted
     *  edit), or — when neither posted anything — post the full reply once. */
    public void seal(String fullText) {
        if (nativeMode && streamTs != null) {
            flush();
            slacker.stopStream(channelId, streamTs);
        } else if (draftTs != null) {
            // Replace the raw live preview with clean mrkdwn-formatted text.
            slacker.editMessage(channelId, draftTs, SlackMarkdownFormatter.format(fullText));
        } else {
            // No native stream and no draft posted (no tokens / draft post failed):
            // post the full formatted reply once.
            slacker.postFallback(channelId, fullText, threadTs);
        }
        // JCLAW-345: upload any files the agent linked in its reply (the prose was
        // already streamed above). No-op on the test-injected path (botToken/agentName
        // null) and when the reply has no workspace-file links.
        SlackOutboundPlanner.dispatchFiles(channelId, threadTs, agentName, fullText, botToken);
        clearStatus();
    }

    /** Error: append a notice to the native stream, edit the draft in place, or
     *  post one — then finalize. */
    public void errorFallback(Exception e) {
        EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                "Streaming error: %s".formatted(e != null ? e.getMessage() : "unknown"));
        String msg = "⚠️ Sorry — something went wrong handling that.";
        if (nativeMode && streamTs != null) {
            slacker.appendStream(channelId, streamTs, "\n\n" + msg);
            slacker.stopStream(channelId, streamTs);
        } else if (draftTs != null) {
            slacker.editMessage(channelId, draftTs, msg); // edit the draft in place
        } else {
            slacker.postFallback(channelId, msg, threadTs);
        }
        clearStatus();
    }

    /** JCLAW-346: off-thread tool-progress preview. Renders a "Working…" list of the
     *  last few completed tool calls into the draft message, until the assistant's
     *  text turn begins (pending non-empty). No-op in native mode / once text flows. */
    @Override
    public void toolProgress(String toolName) {
        if (canStream || draftStopped || toolName == null || toolName.isBlank()) return;
        if (!pending.isEmpty()) return; // the real reply has started → it owns the draft
        toolLines.add(toolName);
        while (toolLines.size() > MAX_TOOL_LINES) {
            toolLines.remove(0);
        }
        var sb = new StringBuilder("Working…");
        for (var line : toolLines) {
            sb.append("\n• ").append(line);
        }
        sendOrEditDraft(sb.toString());
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
