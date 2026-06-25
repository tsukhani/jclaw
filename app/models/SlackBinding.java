package models;

import channels.ChannelTransport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * One Slack app (bot) bound to one JClaw agent (JCLAW-441). Mirrors
 * {@link TelegramBinding}'s per-agent model so Slack works the same way:
 * each agent owns its own Slack app + credentials, so multiple Slack bots can
 * coexist on one instance, one per agent. Replaces the pre-441 app-global
 * {@code ChannelConfig("slack")} (single workspace token).
 *
 * <p>Inbound routing is binding-first: each app's Event Subscription Request
 * URL is {@code /api/webhooks/slack/{bindingId}}, so the controller identifies
 * the binding by the {@code bindingId} path segment, verifies the Slack
 * signature against this binding's {@link #signingSecret}, and dispatches to
 * {@link #agent} directly. Socket Mode (JCLAW-351) opens one WebSocket per
 * binding using {@link #appToken}.
 *
 * <p>Unlike Telegram there is no separate per-URL secret: Slack request
 * authenticity comes from the HMAC signature over the raw body keyed by the
 * signing secret, so {@code bindingId} (which app) + {@code signingSecret}
 * (is it really Slack) is the whole story.
 */
@Entity
@Table(name = "slack_binding", indexes = {
        @Index(name = "idx_slack_binding_team", columnList = "team_id")
})
// Mirror TelegramBinding (JCLAW-204): the webhook/socket hot path calls
// findById per inbound event, so L2-cache this entity for the same transparent
// caching Agent and TelegramBinding enjoy.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class SlackBinding extends Model {

    /**
     * Slack bot token ({@code xoxb-...}). Globally unique on Slack's side; we
     * mirror that with a DB constraint so an operator pasting the same token
     * into two bindings fails fast.
     */
    @Column(name = "bot_token", nullable = false, unique = true)
    public String botToken;

    /**
     * Slack signing secret for HMAC verification of inbound Events/interactivity
     * /slash requests. Required for the HTTP (Events API) transport; still stored
     * for SOCKET bindings (harmless, and lets the operator switch transports
     * without re-entering it).
     */
    @Column(name = "signing_secret", nullable = false)
    public String signingSecret;

    /**
     * App-level token ({@code xapp-...}) used to open a Socket Mode WebSocket
     * (JCLAW-351). Null for HTTP (Events API) transport bindings.
     */
    @Column(name = "app_token")
    public String appToken;

    /**
     * Agent uniqueness is a privacy constraint, not a modelling choice: agent
     * memory is scoped by agentId only, so binding one agent to multiple bots
     * (and thus multiple Slack workspaces/owners) would share memories across
     * them. Enforced here at the schema, in
     * {@link controllers.ApiSlackBindingsController}, and in the frontend.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    public Agent agent;

    /**
     * Slack user id ({@code U...}) of the owner allowed to DM this bot.
     * Peer-level authorization for the DM/access-control story (JCLAW-354):
     * when set, only this user's DMs are served. Nullable so a binding can be
     * created before the owner is resolved.
     */
    @Column(name = "owner_user_id")
    public String ownerUserId;

    // HTTP = Slack Events API receiver (the foundation default — what works
    // today). SOCKET (JCLAW-351) opens a WebSocket and needs no public URL.
    @Column(nullable = false)
    public ChannelTransport transport = ChannelTransport.HTTP;

    /**
     * Public base URL (scheme + host, no path) Slack should reach this instance
     * at, e.g. {@code https://host.tailnet.ts.net}. The fixed contract path
     * {@code /api/webhooks/slack/{id}} is appended when building the Request URL
     * for display. Null for SOCKET bindings (no public URL needed).
     */
    @Column(name = "webhook_base_url")
    public String webhookBaseUrl;

    /**
     * The bot's own Slack user id ({@code U...}), cached from {@code auth.test}
     * at bind/test time. Used by the bot-loop guard (JCLAW-357) to drop the
     * bot's own messages. Null until the first successful {@code auth.test}.
     */
    @Column(name = "bot_user_id")
    public String botUserId;

    /**
     * Slack team/workspace id ({@code T...}), cached from {@code auth.test}.
     * Lets the receiver sanity-check the {@code team_id} in an inbound payload
     * against the binding it resolved.
     */
    @Column(name = "team_id")
    public String teamId;

    @Column(nullable = false)
    public boolean enabled = true;

    /**
     * Per-binding override for the reply-targeting policy (JCLAW-354 reply-thread
     * modes). NULL falls back to the channel-wide default, mirroring
     * {@link TelegramBinding#replyToMode}.
     */
    @Column(name = "reply_to_mode")
    public String replyToMode;

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

    public static SlackBinding findByBotToken(String botToken) {
        return SlackBinding.find("botToken", botToken).first();
    }

    public static SlackBinding findByAgent(Agent agent) {
        return SlackBinding.find("agent", agent).first();
    }

    /**
     * Resolve a Slack binding by walking the {@link Agent#parentAgent} chain —
     * the calling agent's own binding when present, else the nearest ancestor's,
     * else null. Mirrors {@link TelegramBinding#findByAgentOrAncestor}: subagents
     * have no binding of their own, so a child's outbound
     * {@code message(channel="slack", ...)} reaches the bot its root ancestor
     * owns. Bounded 64-hop walk guards against a cyclic {@code parent_agent_id}.
     * CRUD callers (admin add/remove) must use {@link #findByAgent} — exact row
     * identity, no inherited match.
     */
    public static SlackBinding findByAgentOrAncestor(Agent agent) {
        var cur = agent;
        int hops = 0;
        while (cur != null && hops++ < 64) {
            var binding = findByAgent(cur);
            if (binding != null) return binding;
            cur = cur.parentAgent;
        }
        return null;
    }

    public static List<SlackBinding> findAllEnabled() {
        return SlackBinding.find("enabled = true").fetch();
    }

    public static List<SlackBinding> findAllEnabledByTransport(ChannelTransport transport) {
        return SlackBinding.find("enabled = true AND transport = ?1", transport).fetch();
    }
}
