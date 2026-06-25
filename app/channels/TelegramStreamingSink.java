package channels;

import models.Agent;
import models.Conversation;
import models.TelegramBinding;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import play.Play;
import services.EventLogger;
import services.Tx;
import utils.VirtualThreads;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
 * thread pushes tokens; {@link #flush()} runs on {@link #scheduler()}. All
 * mutable state is guarded by {@code stateLock}.
 */
public final class TelegramStreamingSink implements ChannelStreamingSink {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

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
     * JCLAW-375: ack-reaction lifecycle config + emoji. Opt-in via
     * {@code telegram.ackReaction} ({@code off} default | {@code on}). When on,
     * the sink places {@link #ACK_WORKING} on the triggering message at turn
     * start, swaps to {@link #ACK_SUCCESS} on seal, or {@link #ACK_ERROR} on
     * error. The reacted-to message is the sink's {@code replyToMessageId} (the
     * inbound message id); no-op when that is null or the feature is off.
     */
    private static final String CFG_ACK_REACTION = "telegram.ackReaction";
    private static final String ACK_ON = "on";
    private static final String ACK_WORKING = "👀";
    private static final String ACK_SUCCESS = "✅";
    private static final String ACK_ERROR = "❌";

    /**
     * Single-thread scheduler used ONLY to dispatch throttled flush tasks.
     * The scheduler thread itself never performs network I/O — it spawns a
     * fresh virtual thread for each flush. Keeping one scheduler thread
     * preserves the "one scheduled flush per sink in flight" invariant (via
     * {@link #scheduledFlush} comparison), while virtual-thread-per-flush
     * gives cross-sink parallelism so N concurrent streams don't serialize
     * behind a single carrier (JCLAW-95).
     *
     * <p>Lazily-initialized, re-initializable scheduler. Direct static init would
     * mean that {@link #shutdown} leaves a dead scheduler reference for the
     * rest of the JVM lifetime — a problem during testing where
     * {@code JobLifecycleTest} invokes {@code ShutdownJob.doJob()} in the
     * middle of the suite, and subsequent tests still need a live scheduler.
     * The AtomicReference pattern lets the next {@link #scheduler()} call
     * transparently recreate a fresh executor after shutdown.
     */
    private static final AtomicReference<ScheduledExecutorService> SCHEDULER_REF = new AtomicReference<>();

    private static ScheduledExecutorService scheduler() {
        var s = SCHEDULER_REF.get();
        if (s != null && !s.isShutdown()) return s;
        var fresh = VirtualThreads.newSingleThreadScheduledExecutor();
        if (SCHEDULER_REF.compareAndSet(s, fresh)) return fresh;
        fresh.shutdown();
        return SCHEDULER_REF.get();
    }

    /**
     * Time-bounded drain of the scheduler. Called from
     * {@link jobs.ShutdownJob} at application stop. Without this the static
     * scheduler accumulates a carrier thread per dev hot reload. Re-init on
     * next access ensures later code paths still work.
     */
    public static void shutdown() {
        var s = SCHEDULER_REF.getAndSet(null);
        if (s != null) VirtualThreads.gracefulShutdown(s, "telegram-streaming-sink");
    }

    private final String botToken;
    private final String chatId;
    private final Agent agent;

    /**
     * JCLAW-369: inbound message id to reply to (null disables reply
     * targeting) and forum-topic thread id to scope sends into (null = no
     * topic). Both default to null through the existing constructors so every
     * legacy call site keeps today's behavior; the parent wires the inbound
     * values in after merge. The reply badge is applied to the placeholder /
     * planner-first message per {@link TelegramChannel#replyToMode()}, and the
     * thread id is set on the placeholder send, the planner send, and the
     * typing heartbeat (General topic included for typing, omitted on sends).
     */
    private final Integer replyToMessageId;
    private final Integer messageThreadId;
    /**
     * Conversation the sink is streaming into. Kept as a nullable field so
     * tests and admin paths that construct a sink without a conversation
     * (e.g. pure-logic unit tests) still compile; checkpoint persistence
     * is a no-op when null.
     */
    private final Long conversationId;

    // Why: flush()/seal()/update() run on virtual threads (telegram-stream-flush
    // / telegram-typing); under JEP-444 a synchronized block pins the carrier,
    // ReentrantLock parks cleanly. Mirrors LlmProvider.StreamAccumulator.reasoningLock.
    private final ReentrantLock stateLock = new ReentrantLock();

    private final StringBuilder pending = new StringBuilder();
    private Integer messageId = null;
    private long lastSentAt = 0;
    private String lastSentText = "";
    private boolean streamCapReached = false;
    private ScheduledFuture<?> scheduledFlush;
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    /**
     * Non-null while a flush is in flight; {@code null} when idle. The future
     * is created under the {@link #stateLock} at the top of
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

    /**
     * JCLAW-342: hard cap on the typing heartbeat's total lifetime. The
     * heartbeat is normally cancelled at seal / onCancel; this TTL is the
     * safety net for a turn that hangs without ever sealing, so the indicator
     * can't run forever. OpenClaw's typing driver uses 60s.
     */
    private static final long TYPING_HEARTBEAT_MAX_MS = 60_000;

    /**
     * JCLAW-342: stop the heartbeat after this many consecutive sendChatAction
     * 401s in one turn — a revoked/invalid token otherwise 401-spams every
     * {@value #TYPING_HEARTBEAT_MS}ms for the whole turn. Public so the
     * default-package unit test can reference the threshold.
     */
    public static final int TYPING_AUTH_FAILURE_LIMIT = 3;

    /** Scheduled handle for the typing-heartbeat task, or null if not running. */
    private ScheduledFuture<?> typingHeartbeat;

    /** JCLAW-342: per-sink heartbeat TTL; defaults to {@link #TYPING_HEARTBEAT_MAX_MS},
     *  lowered by tests via {@link #setTypingHeartbeatMaxMsForTest(long)}. */
    private volatile long typingHeartbeatMaxMs = TYPING_HEARTBEAT_MAX_MS;

    /** JCLAW-342: consecutive sendChatAction 401s in the current heartbeat run;
     *  reset on (re)start and on a successful send. */
    private final AtomicInteger consecutiveTypingAuthFailures = new AtomicInteger();

    /**
     * Test-friendly constructor: no conversation id, so checkpoint
     * persistence is a no-op. Production call sites should use
     * {@link #TelegramStreamingSink(String, String, Agent, Long)}.
     *
     * @param botToken the Telegram bot token used to authenticate API calls
     * @param chatId   the Telegram chat id (target of edits)
     * @param agent    the bound agent (used for markdown rendering hints and
     *                 logging attribution)
     */
    public TelegramStreamingSink(String botToken, String chatId, Agent agent) {
        this(botToken, chatId, agent, null, null);
    }

    /**
     * JCLAW-369 full constructor — adds the optional inbound reply target and
     * forum-topic thread id over
     * {@link #TelegramStreamingSink(String, String, Agent, Long, String)}.
     * Both null reproduces the legacy behavior exactly; the existing
     * constructors delegate here with both null so all current call sites
     * compile unchanged.
     *
     * @param botToken         the Telegram bot token
     * @param chatId           the Telegram chat id
     * @param agent            the bound agent
     * @param conversationId   persisted Conversation id (enables checkpoint
     *                         persistence); null disables it
     * @param chatType         Telegram's {@code chat.type} string; nullable
     * @param replyToMessageId inbound {@code message_id} to reply to per the
     *                         {@link TelegramChannel#replyToMode()} policy;
     *                         null disables reply targeting
     * @param messageThreadId  forum-topic {@code message_thread_id} to scope
     *                         sends + typing into; null = no topic
     */
    public TelegramStreamingSink(String botToken, String chatId, Agent agent,
                                 Long conversationId, String chatType,
                                 Integer replyToMessageId, Integer messageThreadId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.agent = agent;
        this.conversationId = conversationId;
        this.replyToMessageId = replyToMessageId;
        this.messageThreadId = messageThreadId;
        // JCLAW-105: one-time breadcrumb so operators can attribute streaming
        // activity to chat categories. category=channel matches the existing
        // convention in TelegramChannel.
        EventLogger.info(LOG_CATEGORY, agentName(), LOG_SOURCE,
                "Streaming start (chat.type=%s)"
                        .formatted(chatType != null ? chatType : "unknown"));
        // JCLAW-375: opt-in ack lifecycle. On turn start, place a "working"
        // reaction (👀) on the triggering message so the user sees the bot
        // picked up their message before the first token lands. seal swaps it
        // to ✅, errorFallback to ❌. No-op when the feature is off or there is
        // no message to react to (a fresh sink per turn means this fires once).
        ackTurnStart();
    }

    /**
     * @param botToken       the Telegram bot token
     * @param chatId         the Telegram chat id
     * @param agent          the bound agent
     * @param conversationId persisted Conversation id used for checkpoint
     *                       persistence; null disables it (test path)
     */
    public TelegramStreamingSink(String botToken, String chatId, Agent agent, Long conversationId) {
        this(botToken, chatId, agent, conversationId, null);
    }

    /**
     * Full constructor (JCLAW-105).
     *
     * <p>The streaming path is always placeholder + edit: one
     * {@code sendMessage} on first flush, subsequent flushes do
     * {@code editMessageText}, and seal swaps the placeholder to the final
     * HTML. The DRAFT-transport experiment (JCLAW-103) was removed in
     * JCLAW-121 — Telegram's Bot API has no working draft-clear method, so
     * a streamed draft left stale duplicate bubbles in the user's compose
     * area until client-side cache expiry.
     *
     * @param botToken       the Telegram bot token
     * @param chatId         the Telegram chat id
     * @param agent          the bound agent
     * @param conversationId persisted Conversation id (enables checkpoint
     *                       persistence)
     * @param chatType       Telegram's {@code chat.type} string ({@code "private"}
     *                       / {@code "group"} / {@code "supergroup"} /
     *                       {@code "channel"}); recorded as a structured-log
     *                       breadcrumb so operators can attribute streaming
     *                       activity to chat categories. Nullable; defaults
     *                       to {@code "unknown"} in logs.
     */
    public TelegramStreamingSink(String botToken, String chatId, Agent agent,
                                 Long conversationId, String chatType) {
        this(botToken, chatId, agent, conversationId, chatType, null, null);
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Accept a token batch from the streaming LLM. Schedules a throttled
     * flush if the accumulated preview would exceed the previously-flushed
     * text; stops accepting if the live-stream cap is hit (seal will then
     * deliver via the planner).
     *
     * @param chunk the new token batch to append; null / empty is a no-op
     */
    public void update(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        // JCLAW-98: first visible content means the placeholder is about to
        // replace the typing indicator anyway — stop the heartbeat so we
        // don't waste API calls. Idempotent; safe to call on every update.
        cancelTypingHeartbeatLocked();
        stateLock.lock();
        try {
            if (sealed.get() || streamCapReached) return;
            pending.append(chunk);
            if (pending.length() > MAX_LIVE_STREAM_CHARS) {
                streamCapReached = true;
                cancelScheduledLocked();
                return;
            }
            scheduleFlushLocked();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Finalize the stream with the complete response. Either replaces the
     * placeholder text with the formatted HTML version (happy path) or
     * deletes the placeholder and delegates to
     * {@link TelegramChannel#sendMessage(String, String, String, Agent)} for
     * media-rich / oversize responses.
     *
     * @param finalResponse the complete LLM response text
     */
    public void seal(String finalResponse) {
        if (!sealed.compareAndSet(false, true)) return;
        // JCLAW-98: terminal path — the final edit / planner send will
        // replace the typing indicator. Cancel before the await so no
        // stray heartbeat fires between here and message delivery.
        cancelTypingHeartbeatLocked();
        stateLock.lock();
        try {
            cancelScheduledLocked();
        } finally {
            stateLock.unlock();
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
            // JCLAW-369: the planner delivery is the turn's real response, so
            // forward the reply target + topic thread the same way the
            // placeholder would have carried them.
            if (!TelegramChannel.forToken(botToken).sendTurn(chatId, finalResponse, agent,
                    replyToMessageId, messageThreadId)) {
                notifyDeliveryFailure();
            }
            clearStreamCheckpoint();
            ackSuccess();
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
            // JCLAW-369: same reply/topic forwarding as the needsPlanner branch.
            if (!TelegramChannel.forToken(botToken).sendTurn(chatId, finalResponse, agent,
                    replyToMessageId, messageThreadId)) {
                notifyDeliveryFailure();
            }
            clearStreamCheckpoint();
            ackSuccess();
            return;
        }
        try {
            editMessage(html, true);
        } catch (Exception e) {
            // Benign: Telegram rejects no-op edits with "message is not
            // modified" when the new HTML is byte-identical to what's
            // already displayed — which happens whenever the markdown→HTML
            // conversion is a pass-through (plain-text responses like "4"
            // or "Hello" have identical markdown and HTML forms). The
            // message is already correct on screen; logging this as a
            // warning confuses operators into thinking delivery failed.
            if (!isMessageNotModified(e)) {
                EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
                        "Streaming seal edit failed (plain text remains visible): "
                                + e.getMessage());
            }
        }
        clearStreamCheckpoint();
        ackSuccess();
    }

    /** Telegram's "message is not modified" error — a benign no-op, not a
     *  failure. Returned when the new {@code editMessageText} content plus
     *  reply markup are byte-identical to the currently-displayed message. */
    private static boolean isMessageNotModified(Exception e) {
        if (!(e instanceof TelegramApiRequestException tare)) return false;
        var msg = tare.getMessage();
        return msg != null && msg.contains("message is not modified");
    }

    /**
     * Called when the turn was cancelled (JCLAW-181 follow-up: typing-indicator
     * leak on {@code /stop}). Quiesces the sink without sending anything new:
     * cancels the typing heartbeat, drops any pending flush, marks the sink
     * sealed so late tokens become no-ops, and clears stream-checkpoint state
     * so the recovery job does not try to resume an abandoned turn.
     *
     * <p>Distinct from {@link #errorFallback}: no error message is sent and
     * any partial placeholder is left in place. The {@code /stop} slash command
     * already sent its own "Stopped." acknowledgement on a separate sink, and
     * the partial placeholder (when one exists) is informative — it shows the
     * user what was generated up to the cancel point.
     *
     * <p>Idempotent: a second call after seal/error/cancel is a no-op.
     */
    public void cancel() {
        if (!sealed.compareAndSet(false, true)) return;
        cancelTypingHeartbeatLocked();
        stateLock.lock();
        try {
            cancelScheduledLocked();
        } finally {
            stateLock.unlock();
        }
        // Match the seal/errorFallback ordering — a flush already mid-HTTP
        // could land an edit after we return; awaiting it keeps lifecycle
        // assertions in tests deterministic.
        awaitInFlightFlush();
        clearStreamCheckpoint();
    }

    /**
     * Called on LLM-side errors. Deletes the placeholder (if any) and sends
     * a short user-facing error message as a fresh Telegram message.
     *
     * @param e the error that ended the streaming run
     */
    public void errorFallback(Exception e) {
        if (!sealed.compareAndSet(false, true)) return;
        cancelTypingHeartbeatLocked();
        stateLock.lock();
        try {
            cancelScheduledLocked();
        } finally {
            stateLock.unlock();
        }
        // Same race window as seal(): an in-flight flush's edit could land
        // after our delete, resurrecting the placeholder with stale text.
        awaitInFlightFlush();
        if (messageId != null) deletePlaceholderSafely();
        // JCLAW-369: the error reply replaces the placeholder for this turn, so
        // it carries the same reply target + topic thread.
        TelegramChannel.forToken(botToken).sendTurn(chatId,
                "Sorry, an error occurred processing your message.", agent,
                replyToMessageId, messageThreadId);
        clearStreamCheckpoint();
        ackError();
        EventLogger.error(LOG_CATEGORY, agentName(), LOG_SOURCE,
                "Streaming error: " + (e != null ? e.getMessage() : "(null)"));
    }

    // ── Visible for tests ──────────────────────────────────────────────
    // Public only because the Play-test classpath places tests in the
    // unnamed package, and they can't see package-private channels.*
    // members. Treat these as test-only API surface.

    /**
     * Strip markdown image tokens from a live-preview string.
     *
     * @param text the live preview to strip
     * @return {@code text} with {@code ![alt](url)} tokens removed
     */
    public static String stripImageRefs(String text) {
        if (text == null) return "";
        return IMAGE_MD.matcher(text).replaceAll("");
    }

    public Integer messageIdForTest() { return messageId; }
    /** JCLAW-369: round-trip accessors for the inbound reply target / topic thread. */
    public Integer replyToMessageIdForTest() { return replyToMessageId; }
    public Integer messageThreadIdForTest() { return messageThreadId; }
    public boolean streamCapReachedForTest() { return streamCapReached; }
    public boolean sealedForTest() { return sealed.get(); }
    public String lastSentTextForTest() { return lastSentText; }
    public long lastSentAtForTest() { return lastSentAt; }
    public long currentThrottleMsForTest() { return currentThrottleMs; }
    /** True while the typing-indicator heartbeat (JCLAW-98) is scheduled. */
    public boolean typingHeartbeatActiveForTest() {
        stateLock.lock();
        try {
            return typingHeartbeat != null && !typingHeartbeat.isDone();
        } finally {
            stateLock.unlock();
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
        stateLock.lock();
        try {
            if (typingHeartbeat != null && !typingHeartbeat.isDone()) return;
            consecutiveTypingAuthFailures.set(0); // JCLAW-342: fresh run
            // JCLAW-342: hard TTL — the heartbeat self-stops after
            // typingHeartbeatMaxMs even if seal()/onCancel never fires, so a
            // hung turn can't leave the "typing…" indicator running forever.
            final long deadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(typingHeartbeatMaxMs);
            // initialDelay=0 so the indicator shows up on the first tick
            // without a 4s wait, but still lives inside the tracked future
            // (not a separate fire-and-forget VT) — so a fast-path cancel
            // from seal() / update() can suppress the first pulse if it
            // hasn't landed yet. Each tick spawns a VT so the scheduler
            // thread stays free for other sinks' flushes.
            typingHeartbeat = scheduler().scheduleAtFixedRate(
                    () -> {
                        if (System.nanoTime() >= deadlineNanos) {
                            cancelTypingHeartbeatLocked(); // JCLAW-342: TTL reached
                            return;
                        }
                        // JCLAW-369: scope the typing indicator to the forum
                        // topic when present. Unlike sends, the chat-action API
                        // accepts the General topic (thread id 1), so pass
                        // messageThreadId straight through without the General
                        // strip. JCLAW-342: record the outcome so repeated 401s
                        // stop the heartbeat instead of spamming every tick.
                        Thread.ofVirtual().name("telegram-typing").start(() ->
                                recordTypingOutcome(TelegramChannel.sendTypingAction(
                                        botToken, chatId, messageThreadId)));
                    },
                    0L, TYPING_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        } finally {
            stateLock.unlock();
        }
    }

    /** Cancel the typing heartbeat if running. Idempotent. */
    private void cancelTypingHeartbeatLocked() {
        stateLock.lock();
        try {
            if (typingHeartbeat != null) {
                typingHeartbeat.cancel(false);
                typingHeartbeat = null;
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * JCLAW-342: react to a typing-send outcome reported by the heartbeat VT.
     * Counts consecutive 401s and stops the heartbeat for this turn once
     * {@link #TYPING_AUTH_FAILURE_LIMIT} is hit (a revoked/invalid token
     * otherwise 401-spams {@code sendChatAction} every tick); a successful send
     * resets the counter, and a transient failure leaves it unchanged.
     * Public so the default-package unit test can drive the breaker without
     * live Bot API calls.
     */
    public void recordTypingOutcome(TelegramChannel.TypingActionOutcome outcome) {
        if (outcome == TelegramChannel.TypingActionOutcome.UNAUTHORIZED) {
            if (consecutiveTypingAuthFailures.incrementAndGet() >= TYPING_AUTH_FAILURE_LIMIT) {
                EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
                        "Stopping typing heartbeat after %d consecutive sendChatAction 401s (chat %s)"
                                .formatted(TYPING_AUTH_FAILURE_LIMIT, chatId));
                cancelTypingHeartbeatLocked();
            }
        } else if (outcome == TelegramChannel.TypingActionOutcome.SENT) {
            consecutiveTypingAuthFailures.set(0);
        }
    }

    /** Visible for tests: lower the heartbeat TTL so the self-stop path is
     *  exercisable without a 60s wait (JCLAW-342). */
    public void setTypingHeartbeatMaxMsForTest(long ms) {
        this.typingHeartbeatMaxMs = ms;
    }

    // ── JCLAW-375: ack-reaction lifecycle ──────────────────────────────

    /** True when the operator opted into the ack-reaction lifecycle. */
    static boolean ackReactionEnabled() {
        var raw = Play.configuration.getProperty(CFG_ACK_REACTION, "off");
        return raw != null && raw.trim().equalsIgnoreCase(ACK_ON);
    }

    /**
     * Place {@code emoji} on the triggering message when the ack lifecycle is
     * enabled and a reply target exists. Routes through the merged
     * {@link TelegramChannel#setMessageReaction}, which already swallows API
     * failures (returns false, logs) — so a missing {@code can_set_message_reaction}
     * permission never throws into the streaming path. No-op when the feature is
     * off or {@link #replyToMessageId} is null.
     */
    private void ackReaction(String emoji) {
        if (!ackReactionEnabled() || replyToMessageId == null) return;
        TelegramChannel.setMessageReaction(botToken, chatId, replyToMessageId, emoji);
    }

    private void ackTurnStart() { ackReaction(ACK_WORKING); }

    private void ackSuccess() { ackReaction(ACK_SUCCESS); }

    private void ackError() { ackReaction(ACK_ERROR); }

    private void scheduleFlushLocked() {
        if (scheduledFlush != null && !scheduledFlush.isDone()) return;
        long wait = Math.max(0, currentThrottleMs - (System.currentTimeMillis() - lastSentAt));
        // Scheduler thread only spawns the flush; the flush itself runs on a
        // fresh virtual thread so cross-sink flushes don't serialize (JCLAW-95).
        scheduledFlush = scheduler().schedule(
                () -> Thread.ofVirtual().name("telegram-stream-flush").start(this::flush),
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
        stateLock.lock();
        try {
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
        } finally {
            stateLock.unlock();
        }
        try {
            if (wasFirstSend) {
                messageId = sendPlaceholder(toShow);
                // JCLAW-384: the placeholder message id is the streamed reply
                // bubble — record it in the per-bot bot-sent-id cache so a later
                // reaction on the bot's own reply is recognized (notify=own).
                // The sink owns its send path entirely (placeholder + edits +
                // final), bypassing TelegramChannel's direct send methods that
                // would otherwise populate the cache. Recorded once on the first
                // send; subsequent edits keep the same id.
                TelegramChannel.recordSentMessage(botToken, chatId, messageId);
                // JCLAW-95: persist the checkpoint so a crash between here and
                // seal() leaves a recoverable breadcrumb.
                persistStreamCheckpoint();
            } else {
                editMessage(toShow, false);
            }
            stateLock.lock();
            try {
                lastSentText = toShow;
                lastSentAt = System.currentTimeMillis();
                // If more tokens arrived during the call, schedule the next flush
                // on a fresh virtual thread (same pattern as scheduleFlushLocked).
                if (pending.length() > lastSentText.length() && !sealed.get() && !streamCapReached) {
                    scheduledFlush = scheduler().schedule(
                            () -> Thread.ofVirtual().name("telegram-stream-flush").start(this::flush),
                            currentThrottleMs, TimeUnit.MILLISECONDS);
                }
            } finally {
                stateLock.unlock();
            }
        } catch (Exception e) {
            // Non-fatal: retry on next flush window. See recordFlushFailure
            // for the 429 adaptive-throttle ratchet (JCLAW-100).
            recordFlushFailure(e);
        } finally {
            // Clear the guard under the same lock order the setter used, then
            // signal waiters outside the locked region — completeNow()
            // on a future is lock-free and joiners are on foreign threads.
            CompletableFuture<Void> done;
            stateLock.lock();
            try {
                done = flushInFlight;
                flushInFlight = null;
            } finally {
                stateLock.unlock();
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
     *
     * @param e the exception thrown by the failed flush attempt
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
            EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
                    "Telegram 429 (retry_after=%ds); cadence %d → %d ms"
                            .formatted(tare.getParameters().getRetryAfter(),
                                    previous, currentThrottleMs));
            return;
        }
        // "message is not modified" is a benign no-op — the equality guard
        // at the top of flush() catches most cases, but a race where pending
        // grew then shrank between schedule and execute can still produce
        // a byte-identical edit. Not worth warning on.
        if (isMessageNotModified(e)) return;
        EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
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
        } catch (TimeoutException | ExecutionException _) {
            // TimeoutException: defensive cap — flush exceeded the p99 latency
            // budget; proceed anyway. Worst case is an out-of-order edit which
            // the retry path can recover. Logging would be noisy.
            // ExecutionException: flush() swallows exceptions and always
            // completes normally, so this is effectively unreachable — kept
            // for API completeness in case future refactors propagate.
        }
    }

    /**
     * D2: the {@link LinkPreviewOptions} to attach to a streaming send/edit, or
     * null to leave Telegram's default preview-on behavior. Mirrors
     * {@code TelegramChannel.linkPreviewOptions()} (private there) but reads the
     * public {@link TelegramChannel#suppressLinkPreview()} accessor so the live
     * placeholder + every streaming edit honor {@code telegram.linkPreview=off}
     * the same way the non-streaming send paths do — previously the live drafts
     * still rendered URL preview cards until the final seal edit. Public so the
     * default-package test can assert the config-read contract, mirroring
     * {@link TelegramChannel#suppressLinkPreview()}'s own public-for-tests scope.
     */
    public static LinkPreviewOptions streamingLinkPreviewOptions() {
        if (!TelegramChannel.suppressLinkPreview()) return null;
        return LinkPreviewOptions.builder()
                .isDisabled(true)
                .build();
    }

    private Integer sendPlaceholder(String plainText) throws TelegramApiException {
        var client = TelegramChannel.forToken(botToken).client();
        var builder = SendMessage.builder()
                .chatId(chatId)
                .text(plainText)
                .disableNotification(false);
        // JCLAW-369: the placeholder is the turn's first (and only live)
        // message, so the `first`/`all` reply modes both target it; `off`
        // returns null and the badge is omitted. The topic thread id is set
        // on the send (General stripped); follow-up edits inherit both — the
        // Bot API edit path can't change reply/thread anyway.
        var reply = TelegramChannel.replyParamsForSink(botToken, replyToMessageId);
        if (reply != null) builder.replyParameters(reply);
        var threadId = TelegramChannel.sendThreadIdForSink(messageThreadId);
        if (threadId != null) builder.messageThreadId(threadId);
        // D2: honor telegram.linkPreview=off on the live placeholder, matching
        // the non-streaming send paths in TelegramChannel.
        var linkPreview = streamingLinkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        var message = client.execute(builder.build());
        return message.getMessageId();
    }

    /**
     * Per-conversation rate limiter for {@link #notifyDeliveryFailure}
     * (JCLAW-106). Maps conversationId → last-notifier-fire-ms so a
     * conversation hitting repeated delivery failures only sees one
     * "please retry" message per {@link #notifierCooldownMs()} window.
     *
     * <p>Static + JVM-scope rather than per-sink because each turn gets a
     * fresh sink, and without shared state the rate limit would fire on
     * every turn. Resets on JVM restart — acceptable because a restart-time
     * failure storm is already bounded by Telegram's own outbound rate
     * limits, and the user genuinely needs to know delivery broke.
     *
     * <p>Race-tolerant: two concurrent tryFireNotifier calls for the same
     * conversation can both pass the check if their reads interleave with
     * the put. Worst case is two adjacent notifications, which is still a
     * better UX than missing one entirely. The {@code ConcurrentHashMap}
     * itself is thread-safe; what's approximate here is the 60-second
     * window, not the structure.
     */
    private static final ConcurrentHashMap<Long, Long>
            LAST_NOTIFIER_FIRE_MS = new ConcurrentHashMap<>();
    /** Fallback cooldown when {@code telegram.notifier.cooldownMs} is unset/invalid. */
    private static final long DEFAULT_NOTIFIER_RATE_LIMIT_MS = 60_000;

    /**
     * Error-reply policy + cooldown (JCLAW-362). Replaces the previously
     * hardcoded 60 s cooldown with operator-configurable values, read from
     * {@code conf/application.conf}:
     *
     * <ul>
     *   <li>{@code telegram.notifier.policy} — {@code reply} (default) sends
     *       the user a "couldn't deliver" message; {@code silent} suppresses
     *       it (the failure is still logged). Channel/config level because the
     *       per-binding {@code TelegramBinding} model is owned by another lane
     *       this wave; the per-binding override is a follow-up.</li>
     *   <li>{@code telegram.notifier.cooldownMs} — per-conversation rate-limit
     *       window in milliseconds (default {@value #DEFAULT_NOTIFIER_RATE_LIMIT_MS}).</li>
     * </ul>
     */
    private static final String CFG_NOTIFIER_POLICY = "telegram.notifier.policy";
    private static final String CFG_NOTIFIER_COOLDOWN_MS = "telegram.notifier.cooldownMs";
    private static final String POLICY_SILENT = "silent";

    /** Resolve the configured cooldown window, falling back on a missing/invalid value. */
    static long notifierCooldownMs() {
        var raw = Play.configuration.getProperty(CFG_NOTIFIER_COOLDOWN_MS);
        if (raw == null || raw.isBlank()) return DEFAULT_NOTIFIER_RATE_LIMIT_MS;
        try {
            long ms = Long.parseLong(raw.trim());
            return ms > 0 ? ms : DEFAULT_NOTIFIER_RATE_LIMIT_MS;
        } catch (NumberFormatException _) {
            return DEFAULT_NOTIFIER_RATE_LIMIT_MS;
        }
    }

    /**
     * JCLAW-378: effective cooldown for a bot token — the per-binding
     * {@link models.TelegramBinding#notifierCooldownMs} override (when >0)
     * else the {@link #notifierCooldownMs()} config default. A null/non-positive
     * override falls back to config (the override snapshot already normalizes a
     * non-positive stored value to null). Public for the default-package test
     * seam, mirroring the {@code *ForTest} convention.
     */
    public static long effectiveNotifierCooldownMs(String botToken) {
        var override = TelegramBinding.overridesForToken(botToken).cooldownMs();
        return override != null ? override : notifierCooldownMs();
    }

    /** True when the operator opted out of the user-facing delivery-failure reply. */
    static boolean notifierSilent() {
        var raw = Play.configuration.getProperty(CFG_NOTIFIER_POLICY, "reply");
        return raw != null && raw.trim().equalsIgnoreCase(POLICY_SILENT);
    }

    /**
     * JCLAW-378: effective silent verdict for a bot token — the per-binding
     * {@link models.TelegramBinding#errorReplyPolicy} override (when set) else
     * the {@link #notifierSilent()} config default. {@code silent} suppresses the
     * reply; any other non-blank override (e.g. {@code reply}) forces the reply
     * on even when the config default is silent. A null/blank override falls back
     * to config. Public for the default-package test seam.
     */
    public static boolean effectiveNotifierSilent(String botToken) {
        var override = TelegramBinding.overridesForToken(botToken).errorReplyPolicy();
        if (override == null || override.isBlank()) return notifierSilent();
        return override.trim().equalsIgnoreCase(POLICY_SILENT);
    }

    /**
     * Tell the user their response couldn't be delivered (JCLAW-106). Called
     * from seal()'s three send paths when {@link TelegramChannel#sendMessage}
     * returns {@code false} after retries. Rate-limited per conversation so
     * a Telegram outage doesn't spam the chat. The notifier itself uses
     * {@link TelegramChannel#sendMessage} directly — bypassing this sink —
     * because the sink is already sealed and would refuse further input.
     * If the notifier's own send fails, we log a warning and give up:
     * the user will notice their bot stopped replying anyway.
     *
     * <p>JCLAW-362: honours the {@code telegram.notifier.policy} setting —
     * under {@code silent} the failure is logged but no chat message is sent.
     */
    private void notifyDeliveryFailure() {
        if (effectiveNotifierSilent(botToken)) {
            EventLogger.info(LOG_CATEGORY, agentName(), LOG_SOURCE,
                    "Delivery failure (policy=silent — notification suppressed for chat "
                            + chatId + ")");
            return;
        }
        if (!tryFireNotifier(conversationId, effectiveNotifierCooldownMs(botToken))) {
            EventLogger.info(LOG_CATEGORY, agentName(), LOG_SOURCE,
                    "Delivery failure (rate-limited — notification suppressed for chat "
                            + chatId + ")");
            return;
        }
        boolean sent = false;
        try {
            sent = TelegramChannel.forToken(botToken).sendText(chatId,
                    "I finished generating a response but couldn't deliver it to this chat. "
                            + "Please try again.").ok();
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
                    "Delivery-failure notifier itself failed: " + e.getMessage());
        }
        if (sent) {
            EventLogger.info(LOG_CATEGORY, agentName(), LOG_SOURCE,
                    "Delivery failure notification sent to chat " + chatId);
        }
    }

    /**
     * Returns true if the notifier should fire for {@code conversationId}
     * right now. Updates the last-fired timestamp in that case. Public
     * for test seam; not part of the sink's public API.
     *
     * @param conversationId the conversation key to rate-limit on. Null
     *                       conversationIds (test sinks without a
     *                       conversation context) always return false —
     *                       we never notify without a conversation anchor
     *                       because the rate limiter has no key to apply
     *                       against.
     * @return true when the caller is allowed to fire the notifier now
     */
    public static boolean tryFireNotifier(Long conversationId) {
        return tryFireNotifier(conversationId, notifierCooldownMs());
    }

    /**
     * JCLAW-378: rate-limit decision against an explicit {@code cooldownMs}
     * window — used by {@link #notifyDeliveryFailure} so a binding's per-binding
     * cooldown override (resolved via {@link #effectiveNotifierCooldownMs}) is
     * honoured. The no-arg {@link #tryFireNotifier(Long)} delegates here with the
     * config default. Null conversationIds always return false (no key to rate-
     * limit against). Public for the test seam.
     */
    public static boolean tryFireNotifier(Long conversationId, long cooldownMs) {
        if (conversationId == null) return false;
        long now = System.currentTimeMillis();
        var prev = LAST_NOTIFIER_FIRE_MS.get(conversationId);
        if (prev != null && (now - prev) < cooldownMs) return false;
        // Opportunistic sweep: a stamp older than the cooldown can never block a
        // future fire (it would pass the check above and be overwritten), so it's
        // dead weight. Evict it here to keep the map bounded — one entry per
        // conversation otherwise lives until JVM restart. Behavior is unchanged:
        // a surviving stamp for this conversation is already stale, and we re-put
        // it immediately below.
        LAST_NOTIFIER_FIRE_MS.entrySet().removeIf(e -> (now - e.getValue()) >= cooldownMs);
        LAST_NOTIFIER_FIRE_MS.put(conversationId, now);
        return true;
    }

    /** Clear the notifier rate limiter. Visible for tests only. */
    public static void clearNotifierRateLimiterForTest() {
        LAST_NOTIFIER_FIRE_MS.clear();
    }

    private void editMessage(String text, boolean html) throws TelegramApiException {
        var client = TelegramChannel.forToken(botToken).client();
        var builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text);
        if (html) builder.parseMode(ParseMode.HTML);
        // D2: honor telegram.linkPreview=off on every streaming edit (live draft
        // + the final seal edit), matching the non-streaming edit path in
        // TelegramChannel.editMessageText. Previously the live drafts rendered
        // URL preview cards until the seal.
        var linkPreview = streamingLinkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
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
            EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
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
            EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
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
            EventLogger.warn(LOG_CATEGORY, agentName(), LOG_SOURCE,
                    "Failed to clear stream checkpoint: " + e.getMessage());
        }
    }

    private String agentName() {
        return agent != null ? agent.name : null;
    }
}
