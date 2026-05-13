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

    /**
     * Context watermark for session compaction (JCLAW-38). When a turn
     * approaches the model's context window, {@code SessionCompactor}
     * summarizes the older Message rows into a {@link SessionCompaction}
     * row and bumps this timestamp to the {@code createdAt} of the first
     * kept (post-summary) message. {@link services.ConversationService#loadRecentMessages}
     * honors the max of {@link #contextSince} and this field, so
     * {@code /reset} (JCLAW-26) and compaction stack without overwriting
     * each other — the tighter of the two always wins.
     *
     * <p>Older Message rows remain in the DB and in the UI sidebar /
     * scrollback; only the LLM-facing view of history is truncated.
     */
    @Column(name = "compaction_since")
    public Instant compactionSince;

    /**
     * Conversation-scoped model override (JCLAW-108). When both
     * {@link #modelProviderOverride} and {@link #modelIdOverride} are
     * non-null, {@code AgentRunner.resolveModelInfo} uses them in place of
     * the agent's default {@code modelProvider}/{@code modelId}. Either
     * both are set or both are null — a half-set override is undefined
     * behavior (see {@code ConversationService.setModelOverride} for the
     * atomic setter).
     *
     * <p>Null means "inherit from agent," which is also the default for
     * every conversation created before JCLAW-108 (no backfill). The
     * override is written by {@code /model NAME} and cleared by
     * {@code /model reset}; JCLAW-107's {@code /model} (no args) reads it
     * when displaying the current model.
     */
    @Column(name = "model_provider_override")
    public String modelProviderOverride;

    /** See {@link #modelProviderOverride}. */
    @Column(name = "model_id_override")
    public String modelIdOverride;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    // Hibernate PersistentBag isn't Serializable, but JClaw never serializes
    // JPA entities off-heap (no session replication, no caching). The
    // Serializable on GenericModel is incidental — fields are JPA-tracked.
    @SuppressWarnings("java:S1948")
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
