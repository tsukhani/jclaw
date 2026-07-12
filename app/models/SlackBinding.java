package models;

import channels.ChannelTransport;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

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
public class SlackBinding extends AgentBoundBinding {

    /**
     * Slack bot token ({@code xoxb-...}). Globally unique on Slack's side; we
     * mirror that with a DB constraint so an operator pasting the same token
     * into two bindings fails fast.
     */
    @Column(name = "bot_token", nullable = false, unique = true)
    @JsonIgnore // JCLAW-730: credential — never serialize in the clear (see maskedBotToken()).
    public String botToken;

    /**
     * Slack signing secret for HMAC verification of inbound Events/interactivity
     * /slash requests. Required for the HTTP (Events API) transport; still stored
     * for SOCKET bindings (harmless, and lets the operator switch transports
     * without re-entering it).
     */
    @Column(name = "signing_secret", nullable = false)
    @JsonIgnore // JCLAW-730: credential — never serialize in the clear (see maskedSigningSecret()).
    public String signingSecret;

    /**
     * App-level token ({@code xapp-...}) used to open a Socket Mode WebSocket
     * (JCLAW-351). Null for HTTP (Events API) transport bindings.
     */
    @Column(name = "app_token")
    @JsonIgnore // JCLAW-730: credential — never serialize in the clear (see maskedAppToken()).
    public String appToken;

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

    /**
     * Per-binding override for the reply-targeting policy (JCLAW-354 reply-thread
     * modes). NULL falls back to the channel-wide default, mirroring
     * {@link TelegramBinding#replyToMode}.
     */
    @Column(name = "reply_to_mode")
    public String replyToMode;

    public static SlackBinding findByBotToken(String botToken) {
        return SlackBinding.find("botToken", botToken).first();
    }

    public static SlackBinding findByAgent(Agent agent) {
        return SlackBinding.find("agent", agent).first();
    }

    /**
     * Resolve a Slack binding by walking the {@link Agent#parentAgent} chain
     * (JCLAW-723: shared logic in {@link AgentBoundBinding#findByAgentOrAncestor}).
     * Delivery-side inheritance so a subagent's outbound
     * {@code message(channel="slack", ...)} reaches the bot its root ancestor owns.
     * CRUD callers must keep using {@link #findByAgent} (exact row identity).
     */
    public static SlackBinding findByAgentOrAncestor(Agent agent) {
        return findByAgentOrAncestor(agent, SlackBinding::findByAgent);
    }

    public static List<SlackBinding> findAllEnabled() {
        return SlackBinding.find("enabled = true").fetch();
    }

    public static List<SlackBinding> findAllEnabledByTransport(ChannelTransport transport) {
        return SlackBinding.find("enabled = true AND transport = ?1", transport).fetch();
    }

    /** JCLAW-730: masked {@link #botToken} for any display/log path — the raw
     *  field is {@code @JsonIgnore} so callers reach for this safe form. */
    public String maskedBotToken() {
        return mask(botToken);
    }

    /** JCLAW-730: masked {@link #signingSecret} for any display/log path. */
    public String maskedSigningSecret() {
        return mask(signingSecret);
    }

    /** JCLAW-730: masked {@link #appToken} for any display/log path. */
    public String maskedAppToken() {
        return mask(appToken);
    }

    /** Show only the first 4 characters of a secret, matching
     *  {@code ConfigService.maskValue}'s house style; blank/null → null. */
    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        return secret.length() > 4 ? secret.substring(0, 4) + "****" : "****";
    }
}
