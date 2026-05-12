package jobs;

import play.jobs.Every;
import play.jobs.Job;
import services.ConfigService;
import services.ConversationQueue;
import services.EventLogger;

/**
 * Periodically evicts idle {@link ConversationQueue.QueueState} entries to keep
 * the in-memory map bounded (JCLAW-286). Threshold is read from config
 * {@code conversation.queue.idleEvictionMs} (default 1h).
 */
@Every("1h")
public class ConversationQueueEvictionJob extends Job<Void> {

    private static final long DEFAULT_IDLE_MS = 3_600_000L;

    @Override
    public void doJob() {
        var raw = ConfigService.get("conversation.queue.idleEvictionMs");
        long idleMs;
        try {
            idleMs = raw != null ? Long.parseLong(raw) : DEFAULT_IDLE_MS;
        } catch (NumberFormatException _) {
            idleMs = DEFAULT_IDLE_MS;
        }
        var evicted = ConversationQueue.evictIdle(idleMs);
        if (evicted > 0) {
            EventLogger.info("queue", "Evicted %d idle conversation queue state(s)".formatted(evicted));
        }
    }
}
