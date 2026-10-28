package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LLM-generated summary of a prefix of a conversation's history (JCLAW-38).
 * When the active context approaches the model's input window,
 * {@code services.SessionCompactor} rolls older turns into a single
 * compressed narrative stored in {@link #summary}. The conversation's
 * {@link Conversation#compactionSince} watermark is bumped so the prefix
 * stops being shipped to the LLM on subsequent turns, while the original
 * {@code Message} rows stay intact for the sidebar / scrollback.
 *
 * <p>Each row is one segment: a summary of the rows from {@link #coversFrom} to {@link #coversTo}, written once and
 * never rewritten (JF-161). The prompt carries every live segment, oldest first; a merge of the oldest replaces them
 * with one new row and marks them with {@link #mergedIntoId}, keeping them in the table.
 */
@Entity
@Table(name = "session_compaction", indexes = {
        @Index(name = "idx_session_compaction_conversation", columnList = "conversation_id"),
        @Index(name = "idx_session_compaction_conv_compacted_at", columnList = "conversation_id,compacted_at")
})
public class SessionCompaction extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
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

    /** When the first summarized row was written; null on a summary written before JF-161 the upgrade could not date. */
    @Column(name = "covers_from")
    public Instant coversFrom;

    /** When the last summarized row was written; null as {@link #coversFrom} is. */
    @Column(name = "covers_to")
    public Instant coversTo;

    /** The segment this one was merged into; null while it is live. No FK, so a bulk delete needs no row order. */
    @Column(name = "merged_into_id")
    public Long mergedIntoId;

    @PrePersist
    void onCreate() {
        var now = AppClock.now();
        createdAt = now;
        if (compactedAt == null) compactedAt = now;
    }

    /** Most recent row for {@code conversation}, merged or not, or {@code null} if none exist yet. */
    public static SessionCompaction findLatest(Conversation conversation) {
        return SessionCompaction.find(
                "conversation = ?1 ORDER BY compactedAt DESC, id DESC", conversation).first();
    }

    // A merge result is written after the segments it follows, so neither its id nor its compactedAt orders it.
    private static final Comparator<SessionCompaction> OLDEST_FIRST = Comparator
            .comparing((SessionCompaction s) -> s.coversFrom, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(s -> s.coversTo, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(s -> s.id);

    /**
     * The segments the prompt carries for {@code conversation}, oldest first: those not merged and written after its
     * last {@code /reset} or {@code /new} ({@link Conversation#contextSince}).
     */
    public static List<SessionCompaction> findLive(Conversation conversation) {
        List<SessionCompaction> rows = conversation.contextSince == null
                ? find("conversation = ?1 AND mergedIntoId IS NULL", conversation).fetch()
                : find("conversation = ?1 AND mergedIntoId IS NULL AND compactedAt > ?2", conversation,
                        conversation.contextSince).fetch();
        var live = new ArrayList<SessionCompaction>(rows.size());
        for (Object row : rows) live.add((SessionCompaction) row);
        live.sort(OLDEST_FIRST);
        return live;
    }
}
