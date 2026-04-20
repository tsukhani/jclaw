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

    /**
     * Streaming checkpoint (JCLAW-95). Set when the Telegram streaming sink
     * sends a placeholder message for this conversation; cleared when the
     * stream seals or errors. Non-null on startup means the prior JVM crashed
     * mid-stream — {@code TelegramStreamingRecoveryJob} edits the orphaned
     * placeholder with an interrupt note and clears the field.
     *
     * <p>Scoped to Conversation (not a separate entity) because the
     * 1:1:1 binding model (one bot per agent per user, JCLAW-89) guarantees at
     * most one active stream per conversation row at any time.
     */
    @Column(name = "active_stream_message_id")
    public Integer activeStreamMessageId;

    /**
     * Companion to {@link #activeStreamMessageId}: the Telegram chat id the
     * placeholder lives in. Stored explicitly so the recovery job doesn't
     * have to re-derive it from the peerId column (in practice they're the
     * same value, but making the coupling explicit survives any future
     * peerId-semantics shift).
     */
    @Column(name = "active_stream_chat_id")
    public String activeStreamChatId;

    /**
     * Context watermark for {@code /reset} (JCLAW-26). When non-null,
     * {@link services.ConversationService#loadRecentMessages} excludes any
     * message whose {@code createdAt} predates this value — so the LLM sees
     * only post-reset history. Older messages remain in the DB (and remain
     * visible in the web sidebar) but stop being shipped to the model.
     *
     * <p>Cleared by no command: {@code /reset} overwrites with a fresh
     * {@code Instant.now()}, {@code /new} creates a new row where this is
     * null by default, and history never un-resets.
     */
    @Column(name = "context_since")
    public Instant contextSince;

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

    /**
     * Resolve the current conversation for a (agent, channelType, peerId) tuple.
     * Returns the MOST RECENTLY CREATED row — critical for JCLAW-26's
     * {@code /new} where a freshly-created row for the same tuple must win
     * subsequent lookups over the prior row. Before JCLAW-26 there was at
     * most one row per tuple for non-web channels so ordering was moot; now
     * {@code /new} can produce multiple rows and we always want the newest.
     *
     * <p>Web bypasses this path (see {@code AgentRunner.resolveConversationAndAcquireQueue}),
     * so the ordering change only affects Telegram / other inbound-driven channels.
     */
    public static Conversation findByAgentChannelPeer(Agent agent, String channelType, String peerId) {
        if (peerId == null) {
            return Conversation.find("agent = ?1 AND channelType = ?2 AND peerId IS NULL ORDER BY id DESC",
                    agent, channelType).first();
        }
        return Conversation.find("agent = ?1 AND channelType = ?2 AND peerId = ?3 ORDER BY id DESC",
                agent, channelType, peerId).first();
    }

    public static List<Conversation> findByChannel(String channelType) {
        return findByChannel(channelType, 100);
    }

    public static List<Conversation> findByChannel(String channelType, int limit) {
        return Conversation.find("channelType = ?1 ORDER BY updatedAt DESC", channelType).fetch(limit);
    }
}
