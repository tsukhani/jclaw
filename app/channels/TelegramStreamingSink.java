package channels;

import models.Agent;
import models.Conversation;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import services.EventLogger;
import services.Tx;
import utils.VirtualThreads;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *       into {@code pending} and schedule an {@code editMessageText} flush.
 *       The cadence adapts to Telegram's per-chat rate limit: starts at
 *       {@value #THROTTLE_MIN_MS} ms and ratchets up by
 *       {@value #THROTTLE_STEP_MS} ms on each observed 429, capped at
 *       {@value #THROTTLE_MAX_MS} ms.
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

    /**
     * Adaptive edit cadence for streaming previews (JCLAW-100).
     *
     * <p>Telegram's per-chat editMessageText rate limit is soft — aggressive
     * cadences get 429s whose retry-after header tells you how long to wait.
     * OpenClaw's draft-stream treats 250 ms as the empirical floor; 1000 ms
     * is the historical "always-safe" value. Rather than guess, we start at
     * {@link #THROTTLE_MIN_MS} and ratchet up by {@link #THROTTLE_STEP_MS}
     * every time Telegram responds with a 429 retry-after, capped at
     * {@link #THROTTLE_MAX_MS}. No decay — each new sink starts fresh at
     * the minimum, so a chat that 429'd on the previous turn gets another
     * chance to go fast this turn.
     */
    private static final long THROTTLE_MIN_MS = 250;
    private static final long THROTTLE_STEP_MS = 250;
    private static final long THROTTLE_MAX_MS = 1000;

    /** Current cadence for this sink. Monotonically non-decreasing until seal. */
    private volatile long currentThrottleMs = THROTTLE_MIN_MS;

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
    /**
     * Non-null while a flush is in flight; {@code null} when idle. The future
     * is created under the {@code synchronized (this)} block at the top of
     * {@link #flush()} and completed (to {@code null}) in its {@code finally}
     * after the network call returns, so a concurrent {@link #seal(String)}
     * can wait on it directly instead of polling. Keeps re-entrance guarding
     * simple — {@code flushInFlight != null} means "someone else is busy".
     */
    private volatile CompletableFuture<Void> flushInFlight = null;

    /**
     * Bounded await cap for {@link #seal(String)} observing
     * {@link #flushInFlight}. 2s is long enough to cover p99 Telegram edit
     * latency but short enough that a stuck flush doesn't hold up seal
     * indefinitely.
     */
    private static final long SEAL_INFLIGHT_WAIT_MS = 2_000;

    /**
     * Cadence for the "typing" chat-action heartbeat (JCLAW-98). Telegram's
     * own indicator lasts ~5s per call; 4s keeps it continuous with a
     * one-second margin for network jitter.
     */
    private static final long TYPING_HEARTBEAT_MS = 4_000;

    /** Scheduled handle for the typing-heartbeat task, or null if not running. */
    private ScheduledFuture<?> typingHeartbeat;

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
        // JCLAW-98: first visible content means the placeholder is about to
        // replace the typing indicator anyway — stop the heartbeat so we
        // don't waste API calls. Idempotent; safe to call on every update.
        cancelTypingHeartbeatLocked();
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
        // JCLAW-98: terminal path — the final edit / planner send will
        // replace the typing indicator. Cancel before the await so no
        // stray heartbeat fires between here and message delivery.
        cancelTypingHeartbeatLocked();
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
        cancelTypingHeartbeatLocked();
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
    public long currentThrottleMsForTest() { return currentThrottleMs; }
    /** True while the typing-indicator heartbeat (JCLAW-98) is scheduled. */
    public boolean typingHeartbeatActiveForTest() {
        synchronized (this) {
            return typingHeartbeat != null && !typingHeartbeat.isDone();
        }
    }

    // ── Internals ──────────────────────────────────────────────────────

    /**
     * JCLAW-98: start a "• • • typing" indicator on the user's Telegram
     * client that stays visible until the first real message lands.
     * Fires one {@code sendChatAction} immediately, then re-fires every
     * {@value #TYPING_HEARTBEAT_MS}ms on a virtual thread.
     *
     * <p>Called by {@code AgentRunner.processInboundForAgentStreaming}
     * <i>after</i> sink construction and <i>before</i> {@code runStreaming}
     * kicks off the LLM call. Safe to call once per sink; subsequent calls
     * are no-ops. Cancelled automatically by the first {@link #update}
     * call, by {@link #seal}, or by {@link #errorFallback}.
     */
    public void startTypingHeartbeat() {
        if (sealed.get()) return;
        synchronized (this) {
            if (typingHeartbeat != null && !typingHeartbeat.isDone()) return;
            // initialDelay=0 so the indicator shows up on the first tick
            // without a 4s wait, but still lives inside the tracked future
            // (not a separate fire-and-forget VT) — so a fast-path cancel
            // from seal() / update() can suppress the first pulse if it
            // hasn't landed yet. Each tick spawns a VT so the scheduler
            // thread stays free for other sinks' flushes.
            typingHeartbeat = SCHEDULER.scheduleAtFixedRate(
                    () -> Thread.ofVirtual().start(() ->
                            TelegramChannel.sendTypingAction(botToken, chatId)),
                    0L, TYPING_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Cancel the typing heartbeat if running. Idempotent. */
    private void cancelTypingHeartbeatLocked() {
        synchronized (this) {
            if (typingHeartbeat != null) {
                typingHeartbeat.cancel(false);
                typingHeartbeat = null;
            }
        }
    }

    private void scheduleFlushLocked() {
        if (scheduledFlush != null && !scheduledFlush.isDone()) return;
        long wait = Math.max(0, currentThrottleMs - (System.currentTimeMillis() - lastSentAt));
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
            if (flushInFlight != null) return;
            toShow = stripImageRefs(pending.toString());
            if (toShow.length() < MIN_FLUSH_CHARS) return;
            if (toShow.equals(lastSentText)) return;
            wasFirstSend = (messageId == null);
            flushInFlight = new CompletableFuture<>();
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
                            currentThrottleMs, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            // Non-fatal: retry on next flush window. See recordFlushFailure
            // for the 429 adaptive-throttle ratchet (JCLAW-100).
            recordFlushFailure(e);
        } finally {
            // Clear the guard under the same lock order the setter used, then
            // signal waiters outside the synchronized block — completeNow()
            // on a future is lock-free and joiners are on foreign threads.
            CompletableFuture<Void> done;
            synchronized (this) {
                done = flushInFlight;
                flushInFlight = null;
            }
            if (done != null) done.complete(null);
        }
    }

    /**
     * Extract rate-limit signal from a flush failure and ratchet
     * {@link #currentThrottleMs} up by {@link #THROTTLE_STEP_MS} (capped at
     * {@link #THROTTLE_MAX_MS}) when Telegram returned a 429 with a
     * retry-after. Non-429 failures are logged and leave the cadence
     * untouched. Public so unit tests in the default package can exercise
     * the ratchet without standing up the network path — matches the
     * existing {@code *ForTest} convention elsewhere in this class.
     */
    public void recordFlushFailure(Exception e) {
        if (e instanceof TelegramApiRequestException tare
                && tare.getParameters() != null
                && tare.getParameters().getRetryAfter() != null
                && tare.getParameters().getRetryAfter() > 0) {
            long previous = currentThrottleMs;
            if (previous < THROTTLE_MAX_MS) {
                currentThrottleMs = Math.min(previous + THROTTLE_STEP_MS, THROTTLE_MAX_MS);
            }
            EventLogger.warn("channel", agentName(), "telegram",
                    "Telegram 429 (retry_after=%ds); cadence %d → %d ms"
                            .formatted(tare.getParameters().getRetryAfter(),
                                    previous, currentThrottleMs));
            return;
        }
        EventLogger.warn("channel", agentName(), "telegram",
                "Streaming flush failed (will retry): " + e.getMessage());
    }

    /**
     * Wait (bounded) for any in-flight flush to complete. Called from
     * {@link #seal(String)} and {@link #errorFallback(Exception)} so their
     * final network call isn't racing against a slower flush whose edit
     * lands on Telegram after ours — Telegram applies edits in the order
     * they arrive at its servers, not the order we submit them.
     *
     * <p>Implemented via a {@link CompletableFuture} the flush completes
     * on exit (JCLAW-100): seal wakes the instant the flush returns rather
     * than polling every 20 ms. If the flush is genuinely stuck the 2 s
     * cap still applies so a hung HTTP call can't hold seal indefinitely.
     */
    private void awaitInFlightFlush() {
        var done = flushInFlight;
        if (done == null) return;
        try {
            done.get(SEAL_INFLIGHT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException _) {
            // Defensive cap — flush exceeded the p99 latency budget. Proceed
            // anyway; the worst-case outcome is an out-of-order edit, which
            // the retry path can recover. Logging would be noisy here.
        } catch (ExecutionException _) {
            // flush() itself swallows exceptions and always completes the
            // future normally, so this branch is effectively unreachable —
            // kept for API completeness in case future refactors propagate.
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
