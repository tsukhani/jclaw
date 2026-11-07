package channels;

import org.jspecify.annotations.Nullable;
import services.AttachmentService;
import services.EventLogger;
import services.Tx;
import utils.ChannelErrorTemplates;
import utils.ErrorRendering;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>One lock serializes every call (JF-197b). {@link agents.AgentRunner#runStreaming}
 * drives update/seal/error in sequence, but a stop comes from the turn's thread
 * while a token batch can still be in {@link #update} on the model stream's reader
 * thread, starting the stream: the stop then waits for it, and closes the stream
 * that start opened.
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
    /** What a stop the person's own {@code /stop} answered leaves in a draft that showed only tool progress. */
    private static final String STOPPED_TEXT = "Stopped.";

    /** Slack streaming + fallback operations, injectable so tests don't hit the API. */
    public interface Slacker {
        /** Start a native stream carrying the first content; return its ts, or null. */
        @Nullable String startStream(String channelId, @Nullable String threadTs,
                                     @Nullable String recipientUserId, String initialMarkdown);
        /** Append a markdown delta to the stream; return true on success. */
        boolean appendStream(String channelId, String ts, String markdownDelta);
        /** Finalize the stream; return true on success. */
        boolean stopStream(String channelId, String ts);
        /** Set (or clear, with "") the assistant-thread "is typing…" status line. */
        void setStatus(String channelId, @Nullable String threadTs, String status);
        /** Off-thread fallback: post the reply once (text is mrkdwn-formatted by the sender). */
        void postFallback(String channelId, String text, @Nullable String threadTs);
        /** JCLAW-346: post the draft-preview placeholder; return its ts, or null. */
        @Nullable String postMessage(String channelId, String text, @Nullable String threadTs);
        /** JCLAW-346: edit a message's text (chat.update); return true on success. */
        boolean editMessage(String channelId, String ts, String text);
        /** Upload a file (the bot token is bound at construction); return true on success. */
        boolean uploadFile(String peerId, @Nullable String threadTs, File file, String displayName,
                           @Nullable String caption);
    }

    /** JCLAW-441: a live Slacker bound to one agent's bot token. The streaming +
     *  fallback calls all carry {@code botToken} so the reply posts as that
     *  binding's bot, not the legacy app-global identity. */
    private static Slacker live(String botToken, @Nullable String teamId) {
        return new Slacker() {
            @Override public @Nullable String startStream(String c, @Nullable String th,
                                                          @Nullable String u, String init) {
                return SlackChannel.startStream(c, th, u, teamId, init, botToken);
            }
            @Override public boolean appendStream(String c, String ts, String d) { return SlackChannel.appendStream(c, ts, d, botToken); }
            @Override public boolean stopStream(String c, String ts) { return SlackChannel.stopStream(c, ts, botToken); }
            @Override public void setStatus(String c, @Nullable String th, String s) {
                SlackChannel.setAssistantStatus(c, th, s, botToken);
            }
            @Override public void postFallback(String c, String text, @Nullable String th) {
                SlackChannel.sendMessage(c, text, th, botToken);
            }
            @Override public @Nullable String postMessage(String c, String text, @Nullable String th) {
                return SlackChannel.postText(c, text, th, botToken);
            }
            @Override public boolean editMessage(String c, String ts, String text) { return SlackChannel.editMessage(c, ts, text, botToken); }
            @Override public boolean uploadFile(String peer, @Nullable String th, File f, String name,
                                                @Nullable String cap) {
                return SlackFileUploader.upload(botToken, peer, th, f, name, cap);
            }
        };
    }

    private final String channelId;
    private final @Nullable String threadTs;
    private final @Nullable String recipientUserId;
    private final Slacker slacker;
    private final long throttleMs;
    // JCLAW-345: seal-time outbound file upload. Null on the test-injected path
    // (those tests don't exercise uploads), which disables the dispatch.
    private final @Nullable String botToken;
    private final @Nullable String agentName;
    private final StringBuilder pending = new StringBuilder();
    private @Nullable String streamTs;  // native stream ts; null until first token (lazy start)
    private boolean canStream;   // assistant thread + recipient present
    private boolean startAttempted;
    private boolean nativeMode;
    private boolean statusSet;   // true while the "is typing…" status is showing
    private long lastFlushMs;
    // JCLAW-346: off-thread draft-preview state (used when native streaming is
    // unavailable — channel messages / threads without a recipient).
    private @Nullable String draftTs;        // chat.update message ts; null until the first post
    private @Nullable String lastDraftText;  // dedup consecutive identical edits
    private boolean draftStopped;  // stop the live loop (length cap / post-or-edit failure)
    private final ReentrantLock lock = new ReentrantLock();
    private boolean turnStopped;   // JF-197b: a stop ended the turn, so every later call is a no-op
    private boolean finished;      // the reply or the error notice went out, so a later stop owes nothing
    private @Nullable String toolDraftText;  // the last tool-progress text the draft showed
    private final List<String> toolLines = new ArrayList<>();
    // Uuids of this turn's tool-produced generated attachments (generate_image's image,
    // diarize voice clips, a finished generate_video clip), fed by the runner's
    // per-tool-call callback and uploaded at seal. A plain list: the lock guards it, as
    // it does every other field.
    private final List<String> generatedAttachmentUuids = new ArrayList<>();

    /** Production: stream as the binding's bot, in its workspace {@code teamId}; {@code agentName}
     *  drives the seal-time upload of any files the agent linked in its reply (JCLAW-345). */
    public SlackStreamingSink(String channelId, @Nullable String threadTs, @Nullable String recipientUserId,
                              String botToken, @Nullable String teamId, String agentName) {
        this(channelId, threadTs, recipientUserId, live(botToken, teamId), APPEND_THROTTLE_MS, botToken, agentName);
    }

    /** Test seam: inject the Slacker and append throttle (0 = flush every update). */
    public SlackStreamingSink(String channelId, @Nullable String threadTs, @Nullable String recipientUserId,
                              Slacker slacker, long throttleMs) {
        this(channelId, threadTs, recipientUserId, slacker, throttleMs, null, null);
    }

    private SlackStreamingSink(String channelId, @Nullable String threadTs, @Nullable String recipientUserId,
                               Slacker slacker, long throttleMs,
                               @Nullable String botToken, @Nullable String agentName) {
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
        lock.lock();
        try {
            boolean thread = threadTs != null && !threadTs.isBlank();
            if (thread) {
                // Status line cue, set before the LLM runs so it shows during "thinking".
                slacker.setStatus(channelId, threadTs, STATUS_TYPING);
                statusSet = true;
                canStream = recipientUserId != null && !recipientUserId.isBlank();
            }
            lastFlushMs = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
    }

    /** JCLAW-442: {@link ChannelStreamingSink} typing cue. Slack's is the
     *  assistant-thread status line set by {@link #begin()} — there is no
     *  background heartbeat thread (unlike Telegram). */
    @Override
    public void startTypingHeartbeat() {
        begin();
    }

    /** JCLAW-442: {@link ChannelStreamingSink} cancellation with no stop behind it
     *  (interrupt mode) — clear the "is typing…" status; a stop calls {@link #stop}. */
    @Override
    public void cancel() {
        lock.lock();
        try {
            clearStatus();
        } finally {
            lock.unlock();
        }
    }

    /** JF-197b: close the native stream with what it already shows, or leave the draft as it stood, then post
     *  {@code notice}, when there is one, in the reply's thread. Unflushed tokens are dropped, as Telegram's are.
     *  A draft that shows only tool progress becomes the notice, or "Stopped." for a stop that owes none, as an
     *  error's notice replaces it: left alone, "Working…" would stay in the channel for good. When that edit fails,
     *  the notice is posted as above. */
    @Override
    public void stop(@Nullable String notice) {
        lock.lock();
        try {
            if (turnStopped || finished) return;
            turnStopped = true;
            clearStatus();
            if (nativeMode && streamTs != null) {
                slacker.stopStream(channelId, streamTs);
            } else if (draftShowsOnlyToolProgress()
                    && slacker.editMessage(channelId, Objects.requireNonNull(draftTs),
                            notice != null ? notice : STOPPED_TEXT)) {
                return;
            }
            // A rate-limited edit leaves the draft as it was: the notice still goes in the thread.
            if (notice != null) slacker.postFallback(channelId, notice, threadTs);
        } finally {
            lock.unlock();
        }
    }

    /** Per-token-batch hook. Native (assistant thread + recipient) lazily starts a
     *  chat.startStream and appends throttled deltas; off-thread it drives a
     *  chat.update draft preview (JCLAW-346). */
    public void update(String token) {
        lock.lock();
        try {
            if (turnStopped || token == null || token.isEmpty()) return;
            pending.append(token);
            if (canStream) {
                updateNative();
            } else {
                updateDraft();
            }
        } finally {
            lock.unlock();
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

    /** Honor the injected throttle (tests pass 0 → flush every update); in
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
        lock.lock();
        try {
            if (turnStopped) return;
            finished = true;
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
            // Upload this turn's tool-generated media, which is delivered out-of-band and so
            // is NOT linked in the prose above (dispatchFiles never sees it).
            deliverGeneratedAttachments();
            clearStatus();
        } finally {
            lock.unlock();
        }
    }

    /** Error: append a notice to the native stream, edit the draft in place, or
     *  post one — then finalize. */
    public void errorFallback(Exception e) {
        lock.lock();
        try {
            if (turnStopped) return;
            finished = true;
            EventLogger.record("WARN", LOG_CATEGORY, null, LOG_SOURCE,
                    "Streaming error: %s".formatted(e != null ? e.getMessage() : "unknown"),
                    ChannelErrorTemplates.operatorDetail(e));
            // Rich, then converted: ErrorRendering.RICH emits CommonMark (**bold**), and Slack's
            // mrkdwn uses *single* asterisks — sending the former renders the asterisks literally.
            // SlackMarkdownFormatter is the same converter seal() puts its content through.
            String msg = "⚠️ " + SlackMarkdownFormatter.format(ChannelErrorTemplates.render(
                    ChannelErrorTemplates.forChannelReader(), ErrorRendering.RICH,
                    SlackOutboundPlanner.CAPTION_MAX));
            if (nativeMode && streamTs != null) {
                slacker.appendStream(channelId, streamTs, "\n\n" + msg);
                slacker.stopStream(channelId, streamTs);
            } else if (draftTs != null) {
                slacker.editMessage(channelId, draftTs, msg);
            } else {
                slacker.postFallback(channelId, msg, threadTs);
            }
            clearStatus();
        } finally {
            lock.unlock();
        }
    }

    /** JCLAW-346: off-thread tool-progress preview. Renders a "Working…" list of the
     *  last few completed tool calls into the draft message, until the assistant's
     *  text turn begins (pending non-empty). No-op in native mode / once text flows. */
    @Override
    public void toolProgress(@Nullable String toolName) {
        lock.lock();
        try {
            if (turnStopped || canStream || draftStopped || toolName == null || toolName.isBlank()) return;
            if (!pending.isEmpty()) return; // the real reply has started → it owns the draft
            toolLines.add(toolName);
            while (toolLines.size() > MAX_TOOL_LINES) {
                toolLines.remove(0);
            }
            var sb = new StringBuilder("Working…");
            for (var line : toolLines) {
                sb.append("\n• ").append(line);
            }
            var text = sb.toString();
            sendOrEditDraft(text);
            // Only once the draft shows it: a failed edit leaves the draft reading the last text that went out.
            if (text.equals(lastDraftText)) toolDraftText = text;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void collectGeneratedAttachments(List<String> attachmentUuids) {
        lock.lock();
        try {
            generatedAttachmentUuids.addAll(attachmentUuids);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Upload this turn's tool-generated attachments as Slack files. They are
     * persisted out-of-band and the generating tool tells the model NOT to link
     * them in prose (a fabricated URL would render broken on the web transport),
     * so {@link SlackOutboundPlanner#dispatchFiles} — which only sees text links —
     * never sends them, while the web UI still renders them from the DB/SSE. Each
     * file is resolved to disk in a short JPA transaction, then uploaded through
     * the {@link Slacker} seam on a spawned virtual thread (mirroring
     * {@code dispatchFiles}) so a large upload never blocks the turn. No-op when
     * the turn produced none. Slack renders each file by type natively, so there's
     * no per-kind routing as on Telegram.
     */
    private void deliverGeneratedAttachments() {
        if (generatedAttachmentUuids.isEmpty()) return;
        var files = new ArrayList<AttachmentService.ResolvedGeneratedFile>();
        for (var uuid : generatedAttachmentUuids) {
            var media = Tx.run(() -> AttachmentService.resolveGeneratedForSend(uuid));
            if (media != null) files.add(media);
        }
        if (files.isEmpty()) return;
        Thread.ofVirtual().name("slack-upload").start(() -> {
            for (var m : files) {
                slacker.uploadFile(channelId, threadTs, m.file(), m.displayName(), null);
            }
        });
    }

    /** Whether the draft, as last written, shows tool progress and no reply text, whatever tokens wait unflushed. */
    private boolean draftShowsOnlyToolProgress() {
        return draftTs != null && toolDraftText != null && toolDraftText.equals(lastDraftText);
    }

    private void clearStatus() {
        if (statusSet) {
            slacker.setStatus(channelId, threadTs, "");
            statusSet = false;
        }
    }

    private void flush() {
        if (pending.isEmpty()) return;
        var ts = Objects.requireNonNull(streamTs, "flush() runs only after the stream started");
        if (slacker.appendStream(channelId, ts, pending.toString())) {
            pending.setLength(0);
        }
    }
}
