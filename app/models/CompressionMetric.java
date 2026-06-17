package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import play.db.jpa.Model;

import java.time.Instant;

/**
 * One compression-pipeline event (JCLAW-467). Powers the per-agent compression
 * metrics dashboard — token savings, ratios by content type, algorithm usage,
 * inflation-guard trips, and CCR cache hit rate — by storing one row per event
 * and aggregating at read time. Writes are best-effort (a failed metric never
 * disrupts an agent run).
 */
@Entity
@Table(name = "compression_metrics", indexes = {
        @Index(name = "ix_cm_agent_created", columnList = "agent_id, created_at"),
        @Index(name = "ix_cm_kind_created", columnList = "kind, created_at")
})
public class CompressionMetric extends Model {

    /** Which pipeline outcome this row records. */
    public enum Kind { COMPRESSION, INFLATION_GUARD, CCR_RETRIEVAL }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Kind kind;

    /** Owning agent id (as a string); null for agent-less events such as CCR retrieval. */
    @Column(name = "agent_id", length = 255)
    public String agentId;

    @Column(name = "model_id", length = 255)
    public String modelId;

    /** Inbound channel of the conversation (chat only; null for task fires / agent-less events). */
    @Column(length = 32)
    public String channel;

    /** {@code JSON}/{@code CODE}/{@code LOG}/{@code TEXT}; null for CCR retrieval. */
    @Column(name = "content_type", length = 20)
    public String contentType;

    @Column(length = 40)
    public String algorithm;

    @Column(name = "tokens_before", nullable = false)
    public int tokensBefore;

    @Column(name = "tokens_after", nullable = false)
    public int tokensAfter;

    /** CCR_RETRIEVAL only: whether the hash resolved to a stored original. */
    @Column(name = "ccr_hit")
    public Boolean ccrHit;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
