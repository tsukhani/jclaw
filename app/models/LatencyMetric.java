package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import play.db.jpa.Model;

import java.time.Instant;

/**
 * One latency-segment sample (JCLAW-515). Powers the time-windowed Chat
 * Performance dashboard — per-segment p50/p90/p99/p999 percentiles filterable by
 * a 7d/30d/All window plus agent and channel — by storing one row per recorded
 * segment and re-aggregating into {@code HdrHistogram}s at read time (see
 * {@code utils.LatencyStats#aggregate}).
 *
 * <p>The in-memory {@code utils.LatencyStats} histogram is unchanged: it still
 * feeds the live snapshot endpoint and the load-test harness. These rows are the
 * additive persisted layer. Writes are best-effort and batched off the agent-turn
 * path by {@code services.LatencyMetricRecorder}; a dropped sample never disrupts
 * a turn. {@code jobs.LatencyMetricCleanupJob} prunes rows past the retention TTL.
 */
@Entity
@Table(name = "latency_metrics", indexes = {
        @Index(name = "ix_lm_created", columnList = "created_at"),
        @Index(name = "ix_lm_agent_created", columnList = "agent_id, created_at"),
        @Index(name = "ix_lm_channel_created", columnList = "channel, created_at")
})
public class LatencyMetric extends Model {

    /** Owning agent id (as a string); null for agent-less recordings (e.g. dispatcher_wait). */
    @Column(name = "agent_id", length = 255)
    public String agentId;

    /** Inbound transport: web, telegram, task, … ({@code LatencyStats.UNKNOWN_CHANNEL} when blank). */
    @Column(length = 32)
    public String channel;

    /** Latency phase: total, ttft, stream_body, prologue, terminal_tail, tool_exec, … */
    @Column(length = 40, nullable = false)
    public String segment;

    /** Recorded duration in milliseconds (clamped to {@code >= 0}). */
    @Column(name = "latency_ms", nullable = false)
    public long latencyMs;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
