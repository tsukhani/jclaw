package channels;

import services.EventLogger;

import java.util.List;

/**
 * M2 inbound long-message reassembly: Telegram clients auto-split a long paste
 * (&gt;4096 chars) into several consecutive plain-text messages. Without
 * reassembly JClaw dispatches each split piece as a SEPARATE agent turn, so a
 * single pasted block becomes N disjoint prompts. This buffer coalesces those
 * pieces back into one inbound.
 *
 * <p>The map / scheduler / idle-timer / flush machinery lives in the shared
 * {@link IdleDebounceBuffer} (JCLAW-397); this lane supplies only the text-merge
 * policy and the eligibility/threshold rules below.
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

    private static final class Bucket {
        final StringBuilder text = new StringBuilder();
        InboundMessage firstMessage;
    }

    private static final IdleDebounceBuffer<Bucket> BUFFER = new IdleDebounceBuffer<>(
            "telegram-inbound-text-flush", "Inbound text",
            Bucket::new,
            TelegramInboundTextBuffer::accumulate,
            TelegramInboundTextBuffer::merge,
            TelegramInboundTextBuffer::coalesceWindowMs);

    private TelegramInboundTextBuffer() {}

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
        BUFFER.offer(bufferKey(incoming), incoming, dispatcher);
    }

    // dispatchNow is "no open bucket AND below threshold" — a normal short
    // message needing no reassembly. A short piece that arrives while a bucket
    // IS already open is the TAIL of an in-progress client split (the last split
    // piece is the remainder, almost always < threshold) and MUST append, not
    // dispatch on its own — otherwise the tail becomes a separate turn and the
    // reassembly drops the end of the user's message. Running inside the shared
    // buffer's compute() keeps the decide-then-append atomic against concurrent
    // split pieces on the virtual-threaded receive path.
    private static boolean accumulate(Bucket bucket, InboundMessage incoming, boolean freshBucket) {
        if (freshBucket && incoming.text().length() < coalesceThreshold()) {
            return false; // dispatched immediately by the shared buffer
        }
        if (bucket.firstMessage == null) bucket.firstMessage = incoming;
        bucket.text.append(incoming.text());
        return true;
    }

    private static InboundMessage merge(Bucket bucket) {
        var first = bucket.firstMessage;
        if (first == null) return null;
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
        return merged;
    }

    /** Visible for tests: flush immediately without waiting for the idle timer.
     *  Keyed by a representative message (same {@code (chatId, threadId,
     *  fromId)} as the buffered pieces). */
    public static void flushForTest(InboundMessage representative) {
        BUFFER.flushForTest(bufferKey(representative));
    }

    /** Visible for tests: clear all buffered state between test cases. */
    public static void resetForTest() {
        BUFFER.resetForTest();
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
        return readLong(CFG_WINDOW_MS, DEFAULT_COALESCE_WINDOW_MS);
    }

    private static int readInt(String key, int fallback) {
        var raw = play.Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    private static long readLong(String key, long fallback) {
        var raw = play.Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
