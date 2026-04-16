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

    /** JSON-serialized usage metrics (tokens, cost, duration) from the LLM response. */
    @Column(name = "usage_json", columnDefinition = "TEXT")
    public String usageJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public static List<Message> findRecent(Conversation conversation, int limit) {
        return Message.find("conversation = ?1 ORDER BY createdAt DESC", conversation)
                .fetch(limit);
    }
}
