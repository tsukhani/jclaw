package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;

/**
 * LLM-generated summary of a prefix of a conversation's history (JCLAW-38).
 * When the active context approaches the model's input window,
 * {@code services.SessionCompactor} rolls older turns into a single
 * compressed narrative stored in {@link #summary}. The conversation's
 * {@link Conversation#compactionSince} watermark is bumped so the prefix
 * stops being shipped to the LLM on subsequent turns, while the original
 * {@code Message} rows stay intact for the sidebar / scrollback.
 *
 * <p>There may be multiple rows per conversation — each additional
 * compaction summarizes the prior summary plus the turns that accumulated
 * since. The "current" summary is always the most recent row by
 * {@link #compactedAt}.
 */
@Entity
@Table(name = "session_compaction", indexes = {
        @Index(name = "idx_session_compaction_conversation", columnList = "conversation_id"),
        @Index(name = "idx_session_compaction_conv_compacted_at", columnList = "conversation_id,compacted_at")
})
public class SessionCompaction extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    public Conversation conversation;

    /** Number of Message rows that were folded into this summary. */
    @Column(name = "turn_count", nullable = false)
    public int turnCount;

    /** Rough token count of {@link #summary} (char/4 heuristic). */
    @Column(name = "summary_tokens", nullable = false)
    public int summaryTokens;

    /** {@code "providerName/modelId"} used to produce the summary. */
    @Column(nullable = false, length = 255)
    public String model;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String summary;

    @Column(name = "compacted_at", nullable = false, updatable = false)
    public Instant compactedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        if (compactedAt == null) compactedAt = now;
    }

    /**
     * Most recent compaction for {@code conversation}, or {@code null} if
     * none exist yet. Used by {@code AgentRunner} to append the summary to
     * the system prompt on every subsequent turn.
     */
    public static SessionCompaction findLatest(Conversation conversation) {
        return SessionCompaction.find(
                "conversation = ?1 ORDER BY compactedAt DESC", conversation).first();
    }
}
