package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "conversation", indexes = {
        @Index(name = "idx_conversation_agent_channel_peer", columnList = "agent_id,channel_type,peer_id")
})
public class Conversation extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(name = "channel_type", nullable = false)
    public String channelType;

    @Column(name = "peer_id")
    public String peerId;

    @Column(name = "message_count", nullable = false)
    public int messageCount = 0;

    @Column(length = 100)
    public String preview;

    /**
     * Number of times an LLM-generated title has been successfully written to
     * {@link #preview}. Gates {@code ConversationService.requestTitleGeneration}
     * with a cap of {@link #MAX_TITLE_GENERATIONS}: the first generation
     * produces the initial title (after the user's opening turn), the second
     * refreshes it once more when the conversation has gained real content,
     * and any subsequent requests short-circuit. Prevents the sidebar-click
     * rewrite loop while still allowing one "the conversation has grown since
     * the first turn" re-title. Defaults {@code 0} so pre-migration rows still
     * get their full allotment of generations.
     */
    @Column(name = "title_generation_count", nullable = false)
    public int titleGenerationCount = 0;

    /** Upper bound on how many times a conversation's title will be LLM-regenerated. */
    public static final int MAX_TITLE_GENERATIONS = 2;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @OneToMany(mappedBy = "conversation")
    @OrderBy("createdAt ASC")
    public List<Message> messages;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static Conversation findByAgentChannelPeer(Agent agent, String channelType, String peerId) {
        if (peerId == null) {
            return Conversation.find("agent = ?1 AND channelType = ?2 AND peerId IS NULL",
                    agent, channelType).first();
        }
        return Conversation.find("agent = ?1 AND channelType = ?2 AND peerId = ?3",
                agent, channelType, peerId).first();
    }

    public static List<Conversation> findByChannel(String channelType) {
        return findByChannel(channelType, 100);
    }

    public static List<Conversation> findByChannel(String channelType, int limit) {
        return Conversation.find("channelType = ?1 ORDER BY updatedAt DESC", channelType).fetch(limit);
    }
}
