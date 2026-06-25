package channels;

import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JCLAW-136: reassembles {@link InboundMessage} updates that
 * share a {@code media_group_id} into a single merged inbound. Telegram
 * delivers one Update per photo in a multi-photo album; each carries its
 * own photo array plus (on the first Update only) the shared caption.
 * Without reassembly the bot would reply N times to an N-photo album —
 * once per Update — which is visibly broken UX.
 *
 * <p>The map / scheduler / idle-timer / flush machinery lives in the shared
 * {@link IdleDebounceBuffer} (JCLAW-397); this lane supplies only the
 * media-group merge policy. Each bucket accumulates pending attachments and the
 * first-seen caption; later arrivals for the same group append and reset the
 * idle timer.
 *
 * <p>Trade-off: a very slow network could split an album across the idle
 * window, producing two dispatches. Good enough for v1 — the cost of a
 * duplicate dispatch is a repeated reply, not data loss.
 */
public final class TelegramMediaGroupBuffer {

    /** Idle window after which the buffered group flushes. Kept short enough
     *  to feel responsive for users sending quick albums, long enough to
     *  tolerate webhook delivery jitter on typical networks. */
    static final long IDLE_TIMEOUT_MS = 1500;

    private static final class Bucket {
        final List<PendingAttachment> attachments = new ArrayList<>();
        String mediaGroupId;
        String text = "";
        InboundMessage firstMessage;
    }

    private static final IdleDebounceBuffer<Bucket> BUFFER = new IdleDebounceBuffer<>(
            "telegram-media-group-flush", "Media group",
            Bucket::new,
            TelegramMediaGroupBuffer::accumulate,
            TelegramMediaGroupBuffer::merge,
            () -> IDLE_TIMEOUT_MS);

    private TelegramMediaGroupBuffer() {}

    /**
     * Buffer {@code incoming} against its {@code media_group_id}. The first
     * arrival for a given group creates the bucket and schedules a flush;
     * later arrivals append attachments and reset the idle timer. When the
     * window elapses, {@code dispatcher} is invoked once with the merged
     * message.
     *
     * <p>If {@code incoming.mediaGroupId()} is {@code null}, there's nothing
     * to reassemble — the message dispatches immediately through the same
     * callback.
     */
    public static void add(InboundMessage incoming,
                           Consumer<InboundMessage> dispatcher) {
        var groupId = incoming.mediaGroupId();
        if (groupId == null) {
            dispatcher.accept(incoming);
            return;
        }
        BUFFER.offer(groupId, incoming, dispatcher);
    }

    private static boolean accumulate(Bucket bucket, InboundMessage incoming, boolean freshBucket) {
        if (bucket.firstMessage == null) bucket.firstMessage = incoming;
        if (bucket.mediaGroupId == null) bucket.mediaGroupId = incoming.mediaGroupId();
        bucket.attachments.addAll(incoming.attachments());
        if ((bucket.text == null || bucket.text.isEmpty())
                && incoming.text() != null && !incoming.text().isEmpty()) {
            bucket.text = incoming.text();
        }
        return true;
    }

    private static InboundMessage merge(Bucket bucket) {
        var first = bucket.firstMessage;
        if (first == null) return null;
        // JCLAW-397: build the merged inbound with the FULL sender/message shape
        // so an album keeps the first piece's fromDisplayName / botMentioned /
        // messageId / messageThreadId / replyContext. The previous 7-arg ctor
        // silently dropped them, diverging from the text and forward lanes.
        // mediaGroupId is null — the group has been consumed into one inbound.
        var merged = new InboundMessage(
                first.chatId(), first.chatType(), bucket.text,
                first.fromId(), first.fromUsername(), first.fromDisplayName(),
                first.botMentioned(), List.copyOf(bucket.attachments), null,
                first.messageId(), first.messageThreadId(), first.replyContext());
        EventLogger.info("channel", null, "telegram",
                "Media group %s flushing %d attachments as one inbound".formatted(
                        bucket.mediaGroupId, bucket.attachments.size()));
        return merged;
    }

    /** Visible for tests: flush immediately without waiting for the idle timer. */
    public static void flushForTest(String groupId) {
        BUFFER.flushForTest(groupId);
    }

    /** Visible for tests: clear all buffered state between test cases. */
    public static void resetForTest() {
        BUFFER.resetForTest();
    }
}
