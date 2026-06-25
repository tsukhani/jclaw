package channels;

import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import play.Play;

/**
 * JCLAW-387 B1: forwarded-message coalescing lane. When a user forwards a
 * BURST of consecutive messages to the bot, Telegram delivers one Update per
 * forwarded message. Without coalescing JClaw runs N agent turns for a single
 * "forward these 5 messages" gesture — visibly wrong UX and N× the cost. This
 * buffer debounces a burst of consecutive forwards from the SAME sender into
 * ONE agent turn.
 *
 * <p>The map / scheduler / idle-timer / flush machinery lives in the shared
 * {@link IdleDebounceBuffer} (JCLAW-397); this lane supplies only the merge rule
 * and the eligibility signal below.
 *
 * <p>Detection lives at the dispatch site, not here: the parsed
 * {@link InboundMessage} doesn't carry the forward fields, so
 * the caller passes an explicit {@code isForward} boolean derived from
 * {@code message.getForwardOrigin()} / {@code getForwardDate()} on the raw SDK
 * {@code Message}. A non-forward message NEVER enters this buffer — it stays on
 * the existing text-reassembly / media-group path unchanged.
 *
 * <p>Keying: {@code (chatId, messageThreadId, fromId)} — same shape as the text
 * buffer — so two users forwarding concurrently into the same chat/topic don't
 * get their bursts interleaved. {@code fromId} here is the FORWARDING user (the
 * person operating the bot), which is exactly what {@code InboundMessage.fromId()}
 * already carries.
 *
 * <p>Merge: the first forward opens a bucket and starts an idle timer of
 * {@code telegram.inbound.forward-coalesce-window-ms} (default 1000). Each later
 * forward for the same key appends its text/caption (newline-separated, since
 * forwarded messages are distinct messages rather than one split blob) and
 * restarts the timer. On flush the buffered pieces concatenate into ONE merged
 * inbound that keeps the FIRST piece's metadata. Attachments are accumulated too,
 * so forwarding a mix of text + media in one burst still collapses to one turn.
 *
 * <p>Trade-off: a single forward (burst of one) pays one idle-window of latency
 * before dispatch. That is acceptable — a forward is a deliberate, non-latency-
 * sensitive gesture, and the alternative (dispatch-immediately-unless-second-
 * arrives) can't be done without buffering the first piece anyway. Normal typed
 * messages are unaffected: they never reach this buffer.
 */
public final class TelegramForwardCoalesceBuffer {

    /** Default {@code telegram.inbound.forward-coalesce-window-ms}: idle window
     *  after the last buffered forward before the merged inbound flushes. ~1 s
     *  comfortably covers the gap between the client's per-message forward sends
     *  while staying responsive for a single forward. */
    static final long DEFAULT_FORWARD_COALESCE_WINDOW_MS = 1000L;

    private static final String CFG_WINDOW_MS = "telegram.inbound.forward-coalesce-window-ms";

    private static final class Bucket {
        final StringBuilder text = new StringBuilder();
        final List<PendingAttachment> attachments = new ArrayList<>();
        InboundMessage firstMessage;
    }

    private static final IdleDebounceBuffer<Bucket> BUFFER = new IdleDebounceBuffer<>(
            "telegram-forward-coalesce-flush", "Forward coalesce",
            Bucket::new,
            TelegramForwardCoalesceBuffer::accumulate,
            TelegramForwardCoalesceBuffer::merge,
            TelegramForwardCoalesceBuffer::forwardCoalesceWindowMs);

    private TelegramForwardCoalesceBuffer() {}

    /**
     * Buffer key for {@code (chatId, messageThreadId, fromId)}. Threads and
     * senders are kept distinct so concurrent forward bursts don't merge.
     */
    static String bufferKey(InboundMessage m) {
        return m.chatId() + "|" + m.messageThreadId() + "|" + m.fromId();
    }

    /**
     * Buffer {@code incoming} (a forwarded message) for burst coalescing. The
     * first forward for a key opens a bucket and schedules the idle flush; later
     * forwards for the same key append and reset the timer. When the window
     * elapses the buffered pieces concatenate into one merged inbound (first
     * piece's metadata + joined text + accumulated attachments) and
     * {@code dispatcher} is invoked ONCE.
     *
     * <p>Callers MUST only route forwarded messages here (see the dispatch-site
     * {@code isForward} gate); non-forwarded messages belong on the text /
     * media-group path.
     */
    public static void add(InboundMessage incoming,
                           Consumer<InboundMessage> dispatcher) {
        BUFFER.offer(bufferKey(incoming), incoming, dispatcher);
    }

    private static boolean accumulate(Bucket bucket, InboundMessage incoming, boolean freshBucket) {
        if (bucket.firstMessage == null) bucket.firstMessage = incoming;
        if (incoming.text() != null && !incoming.text().isEmpty()) {
            // Forwards are distinct messages, not one split blob — separate
            // them with a blank line so the agent reads them as a list.
            if (!bucket.text.isEmpty()) bucket.text.append("\n\n");
            bucket.text.append(incoming.text());
        }
        if (incoming.attachments() != null) bucket.attachments.addAll(incoming.attachments());
        return true;
    }

    private static InboundMessage merge(Bucket bucket) {
        var first = bucket.firstMessage;
        if (first == null) return null;
        // Keep the FIRST piece's metadata verbatim (messageId, threadId,
        // botMentioned, replyContext, sender + chat fields); text is the joined
        // forwarded bodies, attachments the accumulation of the whole burst.
        // No media-group id: a forward burst is not a single album.
        var merged = new InboundMessage(
                first.chatId(), first.chatType(), bucket.text.toString(),
                first.fromId(), first.fromUsername(), first.fromDisplayName(),
                first.botMentioned(), List.copyOf(bucket.attachments), null,
                first.messageId(), first.messageThreadId(), first.replyContext());
        EventLogger.info("channel", null, "telegram",
                "Forward coalesce buffer flushing burst as one inbound (%d chars, %d attachments)".formatted(
                        merged.text().length(), bucket.attachments.size()));
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

    /** Idle window in ms before a buffered forward burst flushes, read from
     *  {@code telegram.inbound.forward-coalesce-window-ms} (default 1000).
     *  Unparseable / unset values fall back to the default. */
    static long forwardCoalesceWindowMs() {
        var raw = Play.configuration.getProperty(CFG_WINDOW_MS);
        if (raw == null || raw.isBlank()) return DEFAULT_FORWARD_COALESCE_WINDOW_MS;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            return DEFAULT_FORWARD_COALESCE_WINDOW_MS;
        }
    }
}
