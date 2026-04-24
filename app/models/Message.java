package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "message", indexes = {
        @Index(name = "idx_message_conversation", columnList = "conversation_id"),
        @Index(name = "idx_message_conversation_created", columnList = "conversation_id,created_at")
})
public class Message extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    public Conversation conversation;

    @Column(nullable = false)
    public String role;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "tool_calls", columnDefinition = "TEXT")
    public String toolCalls;

    @Column(name = "tool_results", columnDefinition = "TEXT")
    public String toolResults;

    /**
     * JCLAW-170: optional structured JSON payload for tool-result rows, keyed
     * off the {@code toolResults} tool_call_id. Rendered by the chat UI as
     * rich widgets (e.g. clickable search-result chips with favicons);
     * absent for tools that don't produce structured output. The LLM never
     * sees this column — it only rehydrates the plain-text {@link #content}.
     */
    @Column(name = "tool_result_structured", columnDefinition = "TEXT")
    public String toolResultStructured;

    /**
     * Streamed reasoning / extended-thinking text for assistant turns that
     * ran with thinking enabled. Persisted so the collapsible thinking
     * bubble renders the same content after a conversation reload as it did
     * during the live stream. Aggregated across every LLM round in the turn
     * to match the visible live bubble (see JCLAW-76 for the round-folding
     * behaviour on the token counts). Null for user/tool rows and for
     * assistant turns that emitted no reasoning.
     */
    @Column(columnDefinition = "TEXT")
    public String reasoning;

    /** JSON-serialized usage metrics (tokens, cost, duration) from the LLM response. */
    @Column(name = "usage_json", columnDefinition = "TEXT")
    public String usageJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    /**
     * Files attached to this message (JCLAW-25). Populated on user turns that
     * arrived with uploads; {@code null}/empty for assistant and tool rows and
     * for user turns without attachments. Cascade deletes the rows and the
     * on-disk storage-path references fall out with them.
     */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    public List<MessageAttachment> attachments;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public static List<Message> findRecent(Conversation conversation, int limit) {
        return findRecent(conversation, limit, null);
    }

    /**
     * Fetch up to {@code limit} most-recent messages for {@code conversation},
     * optionally bounded below by {@code since} — messages with
     * {@code createdAt < since} are excluded. Used by {@code /reset}
     * (JCLAW-26) where a watermark on the Conversation row hides pre-reset
     * messages from LLM context without deleting them.
     */
    public static List<Message> findRecent(Conversation conversation, int limit, Instant since) {
        if (since == null) {
            return Message.find("conversation = ?1 ORDER BY createdAt DESC", conversation)
                    .fetch(limit);
        }
        // Inclusive ">=" so a post-reset user message that lands in the
        // same clock tick as {@code contextSince} (low-resolution Linux
        // clocks can collide within a microsecond) is still included.
        // /reset no longer persists its ack (see Commands.executeReset),
        // so there's no equal-to-watermark ack to exclude.
        return Message.find(
                "conversation = ?1 AND createdAt >= ?2 ORDER BY createdAt DESC",
                conversation, since).fetch(limit);
    }
}
