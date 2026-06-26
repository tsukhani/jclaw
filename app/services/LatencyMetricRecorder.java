package services;

import models.LatencyMetric;
import play.Logger;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Async batched writer for per-segment latency samples (JCLAW-515). Mirrors
 * {@link EventLogger}: {@link #enqueue} appends to an in-memory queue off the
 * agent-turn path, and a DB write is only paid once {@link #BATCH_SIZE} samples
 * accumulate (amortized — and {@code LatencyTrace.end()} fires after the terminal
 * SSE frame, so even the batch-tripping turn pays it off the user-visible path).
 * {@code jobs.LatencyMetricFlushJob} drains the tail on a ~30s timer.
 *
 * <p>Tail loss on a hard crash (or a flush racing JPA teardown at shutdown) is
 * acceptable here: these are aggregate latency stats, not an audit log.
 */
public final class LatencyMetricRecorder {

    private LatencyMetricRecorder() {}

    private static final ConcurrentLinkedQueue<LatencyMetric> pending = new ConcurrentLinkedQueue<>();

    // A turn emits ~14 segment samples, so a larger batch than EventLogger's keeps
    // the inline flush off most turns while still bounding the in-memory backlog.
    private static final int BATCH_SIZE = 100;

    /** Queue one sample. Cheap (constructs a detached entity, no DB); flushes inline
     *  only when the batch threshold trips. */
    public static void enqueue(String agentId, String channel, String segment, long latencyMs) {
        var m = new LatencyMetric();
        m.agentId = agentId;
        m.channel = channel;
        m.segment = segment;
        m.latencyMs = Math.max(0L, latencyMs);
        pending.add(m);
        if (pending.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /** Drain and persist the queue in a single transaction. Called inline at the
     *  batch threshold and periodically by {@code jobs.LatencyMetricFlushJob}. A flush
     *  attempted while the JPA layer is tearing down (graceful shutdown) just fails the
     *  Tx and logs a warn below — acceptable for best-effort stats, and decoupled from
     *  EventLogger's shutdown flag so concurrent tests can't suppress persistence. */
    public static void flush() {
        var batch = new ArrayList<LatencyMetric>();
        LatencyMetric m;
        while ((m = pending.poll()) != null) batch.add(m);
        if (batch.isEmpty()) return;
        try {
            Tx.run(() -> { for (var row : batch) row.save(); });
        } catch (Exception ex) {
            Logger.warn("Failed to flush %d latency metrics: %s", batch.size(), ex.getMessage());
        }
    }

    /** Discard queued samples without persisting. Test isolation only. */
    public static void clear() {
        pending.clear();
    }
}
