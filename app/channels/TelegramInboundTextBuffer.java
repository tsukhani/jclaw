package channels;

import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * M2 inbound long-message reassembly: Telegram clients auto-split a long paste
 * (&gt;4096 chars) into several consecutive plain-text messages. Without
 * reassembly JClaw dispatches each split piece as a SEPARATE agent turn, so a
 * single pasted block becomes N disjoint prompts. This buffer coalesces those
 * pieces back into one inbound.
 *
 * <p>Mirrors {@link TelegramMediaGroupBuffer}'s structure: an in-memory map of
 * buckets, a single-threaded daemon {@link ScheduledExecutorService} that fires
 * an idle-timeout flush, and the same {@code add(message, dispatch)} callback
 * shape. The differences are the key and the eligibility/threshold rules below.
 *
 * <p>Eligibility: ONLY plain-text messages — {@code text != null}, no
 * attachments, and no {@code media_group_id}. Messages with attachments or a
 * media-group id are NOT eligible and stay on the existing
 * {@link TelegramMediaGroupBuffer} path unchanged.
 *
 * <p>Keying: {@code (chatId, messageThreadId, fromId)} so two users pasting
 * concurrently in the same chat/topic don't get their pieces interleaved.
 *
 * <p>Threshold: a piece whose text length is BELOW {@code coalesce-threshold}
 * (default 4000) dispatches IMMEDIATELY — a normal short message pays no added
 * latency, exactly as today. A piece at/above the threshold is buffered and
 * (re)starts an idle timer of {@code coalesce-window-ms} (default 750); later
 * pieces for the same key append in arrival order and restart the timer. On
 * timeout the buffered pieces' text is concatenated (join with empty string —
 * Telegram splits mid-text, so direct concatenation reconstructs the original)
 * into ONE merged inbound that keeps the FIRST piece's metadata.
 *
 * <p>Trade-off: the at/above-threshold heuristic assumes Telegram only splits
 * when a message would exceed 4096 chars, so the FIRST split piece is itself
 * &ge; the threshold. A genuine single message that merely happens to be long
 * pays one idle-window of latency before dispatch — acceptable for the (rare)
 * long-message case, and never for normal sub-threshold traffic.
 */
public final class TelegramInboundTextBuffer {

    /** Default {@code telegram.inbound.coalesce-threshold}: only buffer pieces
     *  this long or longer; shorter messages dispatch immediately. Telegram's
     *  hard message limit is 4096, so 4000 stays comfortably below the split
     *  boundary while excluding ordinary chat lines. */
    static final int DEFAULT_COALESCE_THRESHOLD = 4000;

    /** Default {@code telegram.inbound.coalesce-window-ms}: idle window after
     *  the last buffered piece before the merged inbound flushes. Short enough
     *  to stay responsive, long enough to tolerate the gap between the client's
     *  auto-split sends. */
    static final long DEFAULT_COALESCE_WINDOW_MS = 750L;

    private static final String CFG_THRESHOLD = "telegram.inbound.coalesce-threshold";
    private static final String CFG_WINDOW_MS = "telegram.inbound.coalesce-window-ms";

    private static final ConcurrentHashMap<String, Bucket> buffers = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "telegram-inbound-text-flush");
                t.setDaemon(true);
                return t;
            });

    private TelegramInboundTextBuffer() {}

    private static final class Bucket {
        final String key;
        final StringBuilder text = new StringBuilder();
        InboundMessage firstMessage;
        ScheduledFuture<?> flushTask;
        java.util.function.Consumer<InboundMessage> dispatcher;
        Bucket(String key) { this.key = key; }
    }

    /**
     * Buffer key for {@code (chatId, messageThreadId, fromId)}. Threads and
     * senders are kept distinct so concurrent long pastes don't merge.
     */
    static String bufferKey(InboundMessage m) {
        return m.chatId() + "|" + m.messageThreadId() + "|" + m.fromId();
    }

    /** True when {@code m} is a plain-text message eligible for reassembly:
     *  non-null text, no attachments, no media-group id. */
    public static boolean isEligible(InboundMessage m) {
        return m.text() != null
                && (m.attachments() == null || m.attachments().isEmpty())
                && m.mediaGroupId() == null;
    }

    /**
     * Buffer {@code incoming} for long-message reassembly, or dispatch it
     * immediately when it doesn't need buffering.
     *
     * <p>A piece below {@code telegram.inbound.coalesce-threshold} with NO open
     * bucket dispatches synchronously through {@code dispatcher} (no added
     * latency). A piece at/above the threshold opens (or extends) a bucket under
     * its {@code (chatId, threadId, fromId)} key and (re)starts an idle timer of
     * {@code telegram.inbound.coalesce-window-ms}. A piece below the threshold
     * that arrives while a bucket is already open is the split TAIL and is
     * appended too. When the window elapses the buffered pieces concatenate into
     * one merged inbound (first piece's metadata + joined text) and
     * {@code dispatcher} is invoked ONCE.
     *
     * <p>Callers must already have confirmed {@link #isEligible(InboundMessage)};
     * non-eligible messages belong on the {@link TelegramMediaGroupBuffer} path.
     */
    public static void add(InboundMessage incoming,
                           java.util.function.Consumer<InboundMessage> dispatcher) {
        var key = bufferKey(incoming);
        int threshold = coalesceThreshold();
        // dispatchNow flips true ONLY when there is no open bucket for this key
        // AND the piece is below threshold — a normal short message needing no
        // reassembly. A short piece that arrives while a bucket IS already open
        // is the TAIL of an in-progress client split (the last split piece is
        // the remainder, almost always < threshold) and MUST append, not
        // dispatch on its own — otherwise the tail becomes a separate turn and
        // the reassembly drops the end of the user's message. The decide-then-
        // append runs inside compute() so concurrent split pieces (the receive
        // path is virtual-threaded) can't race the check against the append.
        boolean[] dispatchNow = {false};
        buffers.compute(key, (k, existing) -> {
            if (existing == null && incoming.text().length() < threshold) {
                dispatchNow[0] = true;
                return null; // no bucket created; dispatched immediately below
            }
            if (existing == null) existing = new Bucket(k);
            if (existing.firstMessage == null) existing.firstMessage = incoming;
            existing.text.append(incoming.text());
            existing.dispatcher = dispatcher;
            if (existing.flushTask != null) existing.flushTask.cancel(false);
            existing.flushTask = scheduler.schedule(() -> flush(k),
                    coalesceWindowMs(), TimeUnit.MILLISECONDS);
            return existing;
        });
        if (dispatchNow[0]) {
            // Normal-length message, no open buffer: dispatch immediately,
            // exactly as today (zero added latency for ordinary traffic).
            dispatcher.accept(incoming);
        }
    }

    /**
     * Remove + dispatch the bucket for {@code key}. Normally fired by the
     * scheduler after the idle window; exposed package-private for tests via
     * {@link #flushForTest(InboundMessage)}.
     */
    // Catches Throwable on purpose: this runs on the scheduler thread, so an
    // unhandled Error from the dispatcher would kill the timer's worker and
    // permanently stop inbound-text flushes for the JVM lifetime.
    @SuppressWarnings("java:S1181")
    private static void flush(String key) {
        var bucket = buffers.remove(key);
        if (bucket == null || bucket.firstMessage == null || bucket.dispatcher == null) return;
        var first = bucket.firstMessage;
        // Keep the FIRST piece's metadata verbatim (messageId, threadId,
        // botMentioned, replyContext, sender fields, chat fields); only the
        // text is the concatenation of all buffered pieces. Attachments are
        // empty by construction (eligible pieces carry none) and there is no
        // media-group id.
        var merged = new InboundMessage(
                first.chatId(), first.chatType(), bucket.text.toString(),
                first.fromId(), first.fromUsername(), first.fromDisplayName(),
                first.botMentioned(), List.<PendingAttachment>of(), null,
                first.messageId(), first.messageThreadId(), first.replyContext());
        EventLogger.info("channel", null, "telegram",
                "Inbound text buffer flushing %d chars as one inbound".formatted(
                        merged.text().length()));
        try {
            bucket.dispatcher.accept(merged);
        } catch (Throwable t) {
            EventLogger.error("channel", null, "telegram",
                    "Inbound text dispatcher error: %s".formatted(t.getMessage()));
        }
    }

    /** Visible for tests: flush immediately without waiting for the idle timer.
     *  Keyed by a representative message (same {@code (chatId, threadId,
     *  fromId)} as the buffered pieces). */
    public static void flushForTest(InboundMessage representative) {
        flush(bufferKey(representative));
    }

    /** Visible for tests: clear all buffered state between test cases. */
    public static void resetForTest() {
        buffers.clear();
    }

    /** Threshold below which a message dispatches immediately, read from
     *  {@code telegram.inbound.coalesce-threshold} (default 4000). Unparseable
     *  / unset values fall back to the default. */
    static int coalesceThreshold() {
        return readInt(CFG_THRESHOLD, DEFAULT_COALESCE_THRESHOLD);
    }

    /** Idle window in ms before a buffered group flushes, read from
     *  {@code telegram.inbound.coalesce-window-ms} (default 750). */
    static long coalesceWindowMs() {
        return readInt(CFG_WINDOW_MS, (int) DEFAULT_COALESCE_WINDOW_MS);
    }

    private static int readInt(String key, int fallback) {
        var raw = play.Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
