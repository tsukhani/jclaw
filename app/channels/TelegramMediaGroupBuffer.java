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
 * JCLAW-136: reassembles {@link TelegramChannel.InboundMessage} updates that
 * share a {@code media_group_id} into a single merged inbound. Telegram
 * delivers one Update per photo in a multi-photo album; each carries its
 * own photo array plus (on the first Update only) the shared caption.
 * Without reassembly the bot would reply N times to an N-photo album —
 * once per Update — which is visibly broken UX.
 *
 * <p>Implementation: in-memory map keyed by {@code media_group_id}. Each
 * bucket accumulates pending attachments and the first-seen caption, and
 * carries a scheduled flush task that dispatches after a short idle
 * window. Later arrivals for the same group append and reset the timer.
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

    private static final ConcurrentHashMap<String, Bucket> buffers = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "telegram-media-group-flush");
                t.setDaemon(true);
                return t;
            });

    private TelegramMediaGroupBuffer() {}

    private static final class Bucket {
        final String mediaGroupId;
        final List<TelegramChannel.PendingAttachment> attachments = new ArrayList<>();
        String text = "";
        TelegramChannel.InboundMessage firstMessage;
        ScheduledFuture<?> flushTask;
        java.util.function.Consumer<TelegramChannel.InboundMessage> dispatcher;
        Bucket(String mediaGroupId) { this.mediaGroupId = mediaGroupId; }
    }

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
    public static void add(TelegramChannel.InboundMessage incoming,
                           java.util.function.Consumer<TelegramChannel.InboundMessage> dispatcher) {
        var groupId = incoming.mediaGroupId();
        if (groupId == null) {
            dispatcher.accept(incoming);
            return;
        }
        buffers.compute(groupId, (k, existing) -> {
            if (existing == null) existing = new Bucket(k);
            if (existing.firstMessage == null) existing.firstMessage = incoming;
            existing.attachments.addAll(incoming.attachments());
            if ((existing.text == null || existing.text.isEmpty())
                    && incoming.text() != null && !incoming.text().isEmpty()) {
                existing.text = incoming.text();
            }
            existing.dispatcher = dispatcher;
            if (existing.flushTask != null) existing.flushTask.cancel(false);
            existing.flushTask = scheduler.schedule(() -> flush(k),
                    IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return existing;
        });
    }

    /**
     * Remove + dispatch the bucket for {@code groupId}. Normally fired by
     * the scheduler after the idle window; exposed package-private for
     * tests via {@link #flushForTest(String)}.
     */
    private static void flush(String groupId) {
        var bucket = buffers.remove(groupId);
        if (bucket == null || bucket.firstMessage == null || bucket.dispatcher == null) return;
        var first = bucket.firstMessage;
        var merged = new TelegramChannel.InboundMessage(
                first.chatId(), first.chatType(), bucket.text,
                first.fromId(), first.fromUsername(),
                List.copyOf(bucket.attachments), null);
        EventLogger.info("channel", null, "telegram",
                "Media group %s flushing %d attachments as one inbound".formatted(
                        groupId, bucket.attachments.size()));
        try {
            bucket.dispatcher.accept(merged);
        } catch (Throwable t) {
            EventLogger.error("channel", null, "telegram",
                    "Media group dispatcher error: %s".formatted(t.getMessage()));
        }
    }

    /** Visible for tests: flush immediately without waiting for the idle timer. */
    public static void flushForTest(String groupId) {
        flush(groupId);
    }
}
