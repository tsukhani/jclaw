package models;

import channels.ChannelTransport;
import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * One Telegram bot bound to one JClaw agent for messages from exactly one
 * Telegram user. The 1:1:1 model (bot ↔ agent, bot ↔ user) replaces the pre-v0.9.23
 * shared-bot + allowlist design: now each user has a private bot that only reaches
 * the agent the operator wired it to.
 *
 * <p>Inbound routing is binding-first: the polling runner (or webhook controller)
 * identifies the binding by the bot token that received the update, verifies the
 * sender's Telegram user id matches {@link #telegramUserId}, and dispatches to
 * {@link #agent} directly — bypassing {@link agents.AgentRouter}'s 3-tier fallback
 * for the telegram channel.
 */
@Entity
@Table(name = "telegram_binding", indexes = {
        @Index(name = "idx_telegram_binding_user", columnList = "telegram_user_id")
})
public class TelegramBinding extends Model {

    /**
     * Bot token as issued by BotFather, e.g. {@code 123456:ABC-DEF...}. Telegram
     * enforces global uniqueness on its side; we mirror that with a DB constraint so
     * accidental double-binds (same operator adding the same token twice) fail fast.
     */
    @Column(name = "bot_token", nullable = false, unique = true)
    public String botToken;

    /**
     * Agent uniqueness is a privacy constraint, not a modelling choice: planned
     * agent memory is scoped by agentId only, so binding one agent to multiple
     * end-users would share memories across them. Enforced here at the schema,
     * in {@link controllers.ApiTelegramBindingsController}, and in the frontend
     * autocomplete (hides already-bound agents).
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    public Agent agent;

    /**
     * Numeric Telegram user id (string-typed because the Telegram SDK exposes it
     * that way and our comparisons stay stringwise). Peer-level authorization: only
     * messages where {@code from.id} equals this value are dispatched.
     */
    @Column(name = "telegram_user_id", nullable = false)
    public String telegramUserId;

    @Column(nullable = false)
    public ChannelTransport transport = ChannelTransport.POLLING;

    /** Shared secret for webhook transport; null for polling bindings. */
    @Column(name = "webhook_secret")
    public String webhookSecret;

    /** Public HTTPS URL Telegram should POST to; null for polling bindings. */
    @Column(name = "webhook_url")
    public String webhookUrl;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

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

    public static TelegramBinding findByBotToken(String botToken) {
        return TelegramBinding.find("botToken", botToken).first();
    }

    public static TelegramBinding findByAgent(Agent agent) {
        return TelegramBinding.find("agent", agent).first();
    }

    public static TelegramBinding findEnabledByAgentAndUser(Agent agent, String telegramUserId) {
        return TelegramBinding.find(
                "agent = ?1 AND telegramUserId = ?2 AND enabled = true",
                agent, telegramUserId).first();
    }

    public static List<TelegramBinding> findAllEnabled() {
        return TelegramBinding.find("enabled = true").fetch();
    }

    public static List<TelegramBinding> findAllEnabledByTransport(ChannelTransport transport) {
        return TelegramBinding.find("enabled = true AND transport = ?1", transport).fetch();
    }
}
