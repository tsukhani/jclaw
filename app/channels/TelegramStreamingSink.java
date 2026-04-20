package channels;

import models.Agent;
import models.Conversation;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import services.EventLogger;
import services.Tx;
import utils.VirtualThreads;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Throttled edit-loop sink for streaming an LLM response into a Telegram
 * message. Ported from OpenClaw's {@code draft-stream} pattern with
 * jclaw-specific simplifications (no lane model — a single preview message
 * accumulates the entire response across tool rounds).
 *
 * <p><b>Lifecycle</b>
 * <ol>
 *   <li>Constructed by the caller with (botToken, chatId, agent).
 *   <li>{@link #update(String)} is called on every LLM token batch. The first
 *       call sends a placeholder {@code sendMessage}; subsequent calls batch
 *       into {@code pending} and schedule an {@code editMessageText} flush
 *       that is throttled to the Telegram rate limit ({@value #THROTTLE_MS}
 *       ms per message).
 *   <li>{@link #seal(String)} is called on normal completion with the full
 *       assembled response. The sink delivers the final formatted HTML —
 *       either via one last edit (if the response still fits into the live
 *       preview) or by deleting the placeholder and handing the whole
 *       response to {@link TelegramOutboundPlanner} (if it has overflowed the
 *       4096-char cap, or if it contains image/file references that require
 *       media-message delivery).
 *   <li>{@link #errorFallback(Exception)} is called on LLM-side errors. The
 *       placeholder is deleted and a plain error message is sent.
 *   <li>On queue-interrupt cancellation the sink is abandoned — no callback
 *       fires and the partial draft stays on Telegram as the user saw it
 *       (intentional; matches the ACs on JCLAW-94).
 * </ol>
 *
 * <p><b>Design decisions carried over from OpenClaw</b>
 * <ul>
 *   <li>Stream as plain text; convert to HTML only at seal. Half-open
 *       Markdown ({@code **bold...}) would otherwise fail the Telegram
 *       parser with a 400.</li>
 *   <li>Strip markdown image tokens ({@code ![alt](url)}) from the live
 *       stream. Images and workspace files are rendered by the seal-time
 *       {@link TelegramOutboundPlanner} as separate media messages.</li>
 *   <li>4096-char overflow stops live streaming — do not try to seal-and-
 *       continue across multiple messages during the stream. The sealed path
 *       handles chunking.</li>
 * </ul>
 *
 * <p>Concurrency: {@link #update(String)} runs on whichever LLM streaming
 * thread pushes tokens; {@link #flush()} runs on {@link #SCHEDULER}. All
 * mutable state is guarded by {@code this}.
 */
public final class TelegramStreamingSink {

    /** Telegram's {@code editMessageText} per-message rate limit is ~1/sec. */
    private static final long THROTTLE_MS = 1000;

    /**
     * Telegram's single-message hard cap. Beyond this the live stream stops
     * and the seal path delivers via {@link TelegramOutboundPlanner} (which
     * chunks at 4000 chars).
     */
    private static final int MAX_LIVE_STREAM_CHARS = 4096;

    /** Minimum content before we bother with a visible edit; avoids empty/whitespace flashes. */
    private static final int MIN_FLUSH_CHARS = 1;

    /** Matches markdown image tokens: {@code ![alt](url)}. */
    private static final Pattern IMAGE_MD = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

    /**
     * Single-thread scheduler used ONLY to dispatch throttled flush tasks.
     * The scheduler thread itself never performs network I/O — it spawns a
     * fresh virtual thread for each flush. Keeping one scheduler thread
     * preserves the "one scheduled flush per sink in flight" invariant (via
     * {@link #scheduledFlush} comparison), while virtual-thread-per-flush
     * gives cross-sink parallelism so N concurrent streams don't serialize
     * behind a single carrier (JCLAW-95).
     */
    private static final ScheduledExecutorService SCHEDULER =
            VirtualThreads.newSingleThreadScheduledExecutor();

    private final String botToken;
    private final String chatId;
    private final Agent agent;
    /**
     * Conversation the sink is streaming into. Kept as a nullable field so
     * tests and admin paths that construct a sink without a conversation
     * (e.g. pure-logic unit tests) still compile; checkpoint persistence
     * is a no-op when null.
     */
    private final Long conversationId;

    private final StringBuilder pending = new StringBuilder();
    private Integer messageId = null;
    private long lastSentAt = 0;
    private String lastSentText = "";
    private boolean streamCapReached = false;
    private ScheduledFuture<?> scheduledFlush;
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    /**
     * {@code true} while a flush's network call is in progress (between the
     * first sync block and the post-network sync block). Volatile so
     * {@link #seal(String)} on a different thread can observe it without
     * taking the monitor. Needed to prevent a race where seal's final HTML
     * edit is submitted to Telegram before an in-flight plain-text flush
     * completes — Telegram processes edits in network-arrival order, not
     * submission order, so an HTML seal that wins the race on submission
     * can still be overwritten by a slower plain-text flush.
     */
    private volatile boolean flushInFlight = false;

    /**
     * Bounded spin-wait deadline for {@link #seal(String)} observing
     * {@link #flushInFlight}. 2s is long enough to cover p99 Telegram edit
     * latency but short enough that a stuck flush doesn't hold up seal
     * indefinitely.
     */
    private static final long SEAL_INFLIGHT_WAIT_MS = 2_000;

    /**
     * Test-friendly constructor: no conversation id, so checkpoint
     * persistence is a no-op. Production call sites should use
     * {@link #TelegramStreamingSink(String, String, Agent, Long)}.
     */
    public TelegramStreamingSink(String botToken, String chatId, Agent agent) {
        this(botToken, chatId, agent, null);
    }

    public TelegramStreamingSink(String botToken, String chatId, Agent agent, Long conversationId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.agent = agent;
        this.conversationId = conversationId;
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Accept a token batch from the streaming LLM. Schedules a throttled
     * flush if the accumulated preview would exceed the previously-flushed
     * text; stops accepting if the live-stream cap is hit (seal will then
     * deliver via the planner).
     */
    public void update(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        synchronized (this) {
            if (sealed.get() || streamCapReached) return;
            pending.append(chunk);
            if (pending.length() > MAX_LIVE_STREAM_CHARS) {
                streamCapReached = true;
                cancelScheduledLocked();
                return;
            }
            scheduleFlushLocked();
        }
    }

    /**
     * Finalize the stream with the complete response. Either replaces the
     * placeholder text with the formatted HTML version (happy path) or
     * deletes the placeholder and delegates to
     * {@link TelegramChannel#sendMessage(String, String, String, Agent)} for
     * media-rich / oversize responses.
     */
    public void seal(String finalResponse) {
        if (!sealed.compareAndSet(false, true)) return;
        synchronized (this) {
            cancelScheduledLocked();
        }
        // Wait for any in-flight flush to complete so its plain-text edit
        // can't race past our HTML final edit.
        awaitInFlightFlush();
        if (finalResponse == null) finalResponse = "";

        // If we never got to send a placeholder (no tokens, or instant cap
        // overflow) OR the final response requires the planner (images,
        // workspace files, oversize), fall through to the planner's
        // per-segment delivery. The planner handles HTML formatting,
        // chunking at 4000 chars, and photo/document uploads.
        boolean needsPlanner = messageId == null
                || streamCapReached
                || containsMediaOrFileRefs(finalResponse)
                || finalResponse.length() > MAX_LIVE_STREAM_CHARS;

        if (needsPlanner) {
            if (messageId != null) deletePlaceholderSafely();
            TelegramChannel.sendMessage(botToken, chatId, finalResponse, agent);
            clearStreamCheckpoint();
            return;
        }

        // Happy path: single-message HTML edit. The placeholder is still
        // displayed as plain streaming text; this edit swaps it for the
        // formatted version.
        var html = TelegramMarkdownFormatter.toHtml(finalResponse);
        if (html.length() > MAX_LIVE_STREAM_CHARS) {
            // HTML expansion (wrapping <b> / <a> / etc.) can push us past
            // the cap even when the raw markdown fit — fall back to planner.
            deletePlaceholderSafely();
            TelegramChannel.sendMessage(botToken, chatId, finalResponse, agent);
            clearStreamCheckpoint();
            return;
        }
        try {
            editMessage(html, true);
        } catch (Exception e) {
            EventLogger.warn("channel", agentName(), "telegram",
                    "Streaming seal edit failed (plain text remains visible): "
                            + e.getMessage());
        }
        clearStreamCheckpoint();
    }

    /**
     * Called on LLM-side errors. Deletes the placeholder (if any) and sends
     * a short user-facing error message as a fresh Telegram message.
     */
    public void errorFallback(Exception e) {
        if (!sealed.compareAndSet(false, true)) return;
        synchronized (this) {
            cancelScheduledLocked();
        }
        // Same race window as seal(): an in-flight flush's edit could land
        // after our delete, resurrecting the placeholder with stale text.
        awaitInFlightFlush();
        if (messageId != null) deletePlaceholderSafely();
        TelegramChannel.sendMessage(botToken, chatId,
                "Sorry, an error occurred processing your message.", agent);
        clearStreamCheckpoint();
        EventLogger.error("channel", agentName(), "telegram",
                "Streaming error: " + (e != null ? e.getMessage() : "(null)"));
    }

    // ── Visible for tests ──────────────────────────────────────────────
    // Public only because the Play-test classpath places tests in the
    // unnamed package, and they can't see package-private channels.*
    // members. Treat these as test-only API surface.

    /** Strip markdown image tokens from a live-preview string. */
    public static String stripImageRefs(String text) {
        if (text == null) return "";
        return IMAGE_MD.matcher(text).replaceAll("");
    }

    public Integer messageIdForTest() { return messageId; }
    public boolean streamCapReachedForTest() { return streamCapReached; }
    public boolean sealedForTest() { return sealed.get(); }
    public String lastSentTextForTest() { return lastSentText; }
    public long lastSentAtForTest() { return lastSentAt; }

    // ── Internals ──────────────────────────────────────────────────────

    private void scheduleFlushLocked() {
        if (scheduledFlush != null && !scheduledFlush.isDone()) return;
        long wait = Math.max(0, THROTTLE_MS - (System.currentTimeMillis() - lastSentAt));
        // Scheduler thread only spawns the flush; the flush itself runs on a
        // fresh virtual thread so cross-sink flushes don't serialize (JCLAW-95).
        scheduledFlush = SCHEDULER.schedule(
                () -> Thread.ofVirtual().start(this::flush),
                wait, TimeUnit.MILLISECONDS);
    }

    private void cancelScheduledLocked() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
    }

    private void flush() {
        String toShow;
        boolean wasFirstSend;
        synchronized (this) {
            if (sealed.get() || streamCapReached) return;
            // Re-entrance guard (JCLAW-95): virtual-thread-per-flush means a
            // newly-scheduled flush could fire while a previous one is still
            // mid-HTTP for this same sink. Skip and rely on the in-flight
            // flush's post-network reschedule to pick up the pending.
            if (flushInFlight) return;
            toShow = stripImageRefs(pending.toString());
            if (toShow.length() < MIN_FLUSH_CHARS) return;
            if (toShow.equals(lastSentText)) return;
            wasFirstSend = (messageId == null);
            flushInFlight = true;
        }
        try {
            if (wasFirstSend) {
                messageId = sendPlaceholder(toShow);
                // JCLAW-95: persist the checkpoint so a crash between here and
                // seal() leaves a recoverable breadcrumb.
                persistStreamCheckpoint();
            } else {
                editMessage(toShow, false);
            }
            synchronized (this) {
                lastSentText = toShow;
                lastSentAt = System.currentTimeMillis();
                // If more tokens arrived during the call, schedule the next flush
                // on a fresh virtual thread (same pattern as scheduleFlushLocked).
                if (pending.length() > lastSentText.length() && !sealed.get() && !streamCapReached) {
                    scheduledFlush = SCHEDULER.schedule(
                            () -> Thread.ofVirtual().start(this::flush),
                            THROTTLE_MS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            // Non-fatal: retry on next flush window. A 429 (rate limit) is
            // most likely — the throttle should prevent it, but if the LLM
            // pushes through multiple retries or the server clock drifts,
            // we'd rather drop one frame than surface an error.
            EventLogger.warn("channel", agentName(), "telegram",
                    "Streaming flush failed (will retry): " + e.getMessage());
        } finally {
            flushInFlight = false;
        }
    }

    /**
     * Spin-wait (bounded) for any in-flight flush to complete. Called from
     * {@link #seal(String)} and {@link #errorFallback(Exception)} so their
     * final network call isn't racing against a slower flush whose edit
     * lands on Telegram after ours — Telegram applies edits in the order
     * they arrive at its servers, not the order we submit them.
     */
    private void awaitInFlightFlush() {
        long deadline = System.currentTimeMillis() + SEAL_INFLIGHT_WAIT_MS;
        while (flushInFlight && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Integer sendPlaceholder(String plainText) throws Exception {
        var client = TelegramChannel.forToken(botToken).client();
        var send = SendMessage.builder()
                .chatId(chatId)
                .text(plainText)
                .disableNotification(false)
                .build();
        var message = client.execute(send);
        return message.getMessageId();
    }

    private void editMessage(String text, boolean html) throws Exception {
        var client = TelegramChannel.forToken(botToken).client();
        var builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text);
        if (html) builder.parseMode(ParseMode.HTML);
        client.execute(builder.build());
    }

    private void deletePlaceholderSafely() {
        try {
            var client = TelegramChannel.forToken(botToken).client();
            client.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
            messageId = null;
        } catch (Exception e) {
            EventLogger.warn("channel", agentName(), "telegram",
                    "Placeholder delete failed: " + e.getMessage());
        }
    }

    /**
     * Rough detector for content that the planner needs to handle. If the
     * response contains markdown image syntax or the workspace-file
     * {@code [label](<path>)} convention, we defer to the planner at seal
     * so images/files land as proper media messages.
     */
    private static boolean containsMediaOrFileRefs(String response) {
        return response != null
                && (IMAGE_MD.matcher(response).find()
                        || response.contains("](<"));
    }

    /**
     * JCLAW-95: write the placeholder (messageId, chatId) onto the
     * conversation row so a JVM restart mid-stream can find and finalize
     * the orphan. No-op when the sink was constructed without a
     * conversation id (test/admin paths).
     */
    private void persistStreamCheckpoint() {
        if (conversationId == null || messageId == null) return;
        try {
            final Integer mid = messageId;
            final String cid = chatId;
            final Long conv = conversationId;
            Tx.run(() -> {
                var row = (Conversation) Conversation.findById(conv);
                if (row != null) {
                    row.activeStreamMessageId = mid;
                    row.activeStreamChatId = cid;
                    row.save();
                }
            });
        } catch (Exception e) {
            // Non-fatal — we lose crash recovery for this stream but the
            // live session keeps working. Log and proceed.
            EventLogger.warn("channel", agentName(), "telegram",
                    "Failed to persist stream checkpoint: " + e.getMessage());
        }
    }

    /**
     * JCLAW-95: clear the checkpoint on normal seal / error so the recovery
     * job doesn't re-process an already-finalized stream.
     */
    private void clearStreamCheckpoint() {
        if (conversationId == null) return;
        try {
            final Long conv = conversationId;
            Tx.run(() -> {
                var row = (Conversation) Conversation.findById(conv);
                if (row != null && row.activeStreamMessageId != null) {
                    row.activeStreamMessageId = null;
                    row.activeStreamChatId = null;
                    row.save();
                }
            });
        } catch (Exception e) {
            EventLogger.warn("channel", agentName(), "telegram",
                    "Failed to clear stream checkpoint: " + e.getMessage());
        }
    }

    private String agentName() {
        return agent != null ? agent.name : null;
    }
}
