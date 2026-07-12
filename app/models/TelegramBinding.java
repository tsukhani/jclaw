package models;

import channels.ChannelTransport;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import services.Tx;

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
// JCLAW-204: Hibernate L2 cache via Caffeine. The polling-runner hot path
// calls findById per inbound message; annotating here gives the same
// transparent caching that Agent enjoys (JCLAW-205). findByBotToken is
// admin-only (uniqueness check on create/edit) so it does NOT warrant a
// secondary-key Caches.named layer — re-scoped per the JCLAW-204 comment
// thread, the only meaningful win is L2 here.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class TelegramBinding extends AgentBoundBinding {

    /**
     * Bot token as issued by BotFather, e.g. {@code 123456:ABC-DEF...}. Telegram
     * enforces global uniqueness on its side; we mirror that with a DB constraint so
     * accidental double-binds (same operator adding the same token twice) fail fast.
     */
    @Column(name = "bot_token", nullable = false, unique = true)
    @JsonIgnore // JCLAW-730: credential — never serialize in the clear (see maskedBotToken()).
    public String botToken;

    /**
     * Numeric Telegram user id (string-typed because the Telegram SDK exposes it
     * that way and our comparisons stay stringwise). Peer-level authorization: only
     * messages where {@code from.id} equals this value are dispatched.
     */
    @Column(name = "telegram_user_id", nullable = false)
    public String telegramUserId;

    @Column(nullable = false)
    public ChannelTransport transport = ChannelTransport.POLLING;

    /**
     * Shared secret for webhook transport; null for polling bindings. Auto-
     * generated (JCLAW-339) — the operator never types it. Used both as the
     * {@code /…/{secret}} path segment and Telegram's {@code secret_token}.
     */
    @Column(name = "webhook_secret")
    @JsonIgnore // JCLAW-730: credential — never serialize in the clear (see maskedWebhookSecret()).
    public String webhookSecret;

    /**
     * Public base URL (scheme + host, no path) Telegram should reach this
     * instance at, e.g. {@code https://host.tailnet.ts.net}. The fixed contract
     * path {@code /api/webhooks/telegram/{id}/{secret}} is appended when building
     * the full webhook URL for display and registration. Null for polling
     * bindings.
     */
    @Column(name = "webhook_base_url")
    public String webhookBaseUrl;

    /**
     * JCLAW-378: per-binding override for the reply-targeting policy. NULL falls
     * back to the JVM-wide {@code telegram.replyTo.mode} config default — so
     * existing bindings behave unchanged. Accepted values mirror
     * {@link channels.TelegramChannel}'s reply-mode constants:
     * {@code off} | {@code first} | {@code all}. Resolved at the send path via
     * {@link channels.TelegramChannel#effectiveReplyToMode(String)}.
     */
    @Column(name = "reply_to_mode")
    public String replyToMode;

    /**
     * JCLAW-378: per-binding override for the delivery-failure notifier policy.
     * NULL falls back to the JVM-wide {@code telegram.notifier.policy} config
     * default. Accepted values: {@code reply} (send the user a "couldn't
     * deliver" message) | {@code silent} (suppress it; the failure is still
     * logged). Resolved in {@link channels.TelegramStreamingSink}'s
     * delivery-failure path.
     */
    @Column(name = "error_reply_policy")
    public String errorReplyPolicy;

    /**
     * JCLAW-378: per-binding override for the per-conversation delivery-failure
     * notifier cooldown window, in milliseconds. NULL falls back to the
     * JVM-wide {@code telegram.notifier.cooldownMs} config default. A non-positive
     * stored value is treated as absent by the resolver (falls back to config).
     */
    @Column(name = "notifier_cooldown_ms")
    public Long notifierCooldownMs;

    public static TelegramBinding findByBotToken(String botToken) {
        return TelegramBinding.find("botToken", botToken).first();
    }

    /**
     * JCLAW-378: the per-binding setting overrides, as a thread-safe snapshot.
     * Each field is the binding's override or null when the binding falls back
     * to the global config default. {@code cooldownMs} carries a stored value
     * only when it is strictly positive — a non-positive stored value is
     * normalized to null here so the resolver treats it as "use the config
     * default".
     *
     * @param replyToMode      reply-targeting override ({@code off|first|all}) or null
     * @param errorReplyPolicy notifier policy override ({@code reply|silent}) or null
     * @param cooldownMs        notifier cooldown override in ms (>0) or null
     */
    public record SettingOverrides(String replyToMode, String errorReplyPolicy, Long cooldownMs) {
        /** Empty snapshot — every field null, so every consumer falls back to config. */
        public static final SettingOverrides EMPTY = new SettingOverrides(null, null, null);
    }

    /**
     * JCLAW-378: look up the per-binding {@link SettingOverrides} for a bot token,
     * from any thread context. Returns {@link SettingOverrides#EMPTY} when the
     * token is blank or no binding exists, so callers never need a null check —
     * a missing binding resolves to "use config defaults everywhere". Wrapped in
     * {@link services.Tx} because the streaming/send paths run on virtual threads
     * with no ambient JPA transaction.
     */
    public static SettingOverrides overridesForToken(String botToken) {
        if (botToken == null || botToken.isBlank()) return SettingOverrides.EMPTY;
        return Tx.run(() -> {
            var b = findByBotToken(botToken);
            if (b == null) return SettingOverrides.EMPTY;
            Long cd = (b.notifierCooldownMs != null && b.notifierCooldownMs > 0)
                    ? b.notifierCooldownMs : null;
            return new SettingOverrides(b.replyToMode, b.errorReplyPolicy, cd);
        });
    }

    public static TelegramBinding findByAgent(Agent agent) {
        return TelegramBinding.find("agent", agent).first();
    }

    /**
     * Resolve a Telegram binding by walking the {@link Agent#parentAgent} chain
     * (JCLAW-723: shared logic in {@link AgentBoundBinding#findByAgentOrAncestor}).
     * Delivery-side inheritance so a subagent's outbound
     * {@code message(channel="telegram", ...)} reaches the bot its root ancestor
     * owns. CRUD callers must keep using {@link #findByAgent} (exact row identity).
     */
    public static TelegramBinding findByAgentOrAncestor(Agent agent) {
        return findByAgentOrAncestor(agent, TelegramBinding::findByAgent);
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

    /**
     * JCLAW-372: resolve the agent that should serve a given forum topic.
     * Returns the {@link TelegramTopicBinding} override agent when this
     * (chatId, threadId) is mapped, otherwise this binding's default
     * {@link #agent}. A null {@code threadId} (a non-topic message) always
     * resolves to the default — a non-topic message has no topic to override.
     *
     * <p>Pure and queryable: it reads one row via
     * {@link TelegramTopicBinding#findByBindingAndTopic} and never mutates.
     * Never returns null for a persisted binding (the default agent is
     * non-null by schema).
     *
     * <p>DORMANT (JCLAW-372): no dispatch site calls this yet. The wiring is a
     * documented follow-up — both inbound dispatch sites currently pass the
     * binding's default agent ({@code sendAgent}) straight to
     * {@link agents.AgentRunner#processInboundForAgentStreaming}. To activate,
     * replace that agent with {@code binding.resolveAgentForTopic(chatId,
     * threadId)} at:
     * <ul>
     *   <li>{@link channels.TelegramPollingRunner} — where {@code sendAgent}
     *       feeds {@code processInboundForAgentStreaming} alongside
     *       {@code merged.messageThreadId()}; and</li>
     *   <li>{@link controllers.WebhookTelegramController} — the parallel call
     *       using {@code message.messageThreadId()}.</li>
     * </ul>
     * Both sites already have the chat id and thread id in scope; the
     * {@code TelegramStreamingSink} and conversation peer key are unaffected
     * (the override only changes which agent runs the turn).
     */
    public Agent resolveAgentForTopic(String chatId, Integer threadId) {
        var override = TelegramTopicBinding.findByBindingAndTopic(this, chatId, threadId);
        return override != null ? override.agent : agent;
    }

    /** JCLAW-730: masked {@link #botToken} for any display/log path — the raw
     *  field is {@code @JsonIgnore} so callers reach for this safe form. */
    public String maskedBotToken() {
        return mask(botToken);
    }

    /** JCLAW-730: masked {@link #webhookSecret} for any display/log path. */
    public String maskedWebhookSecret() {
        return mask(webhookSecret);
    }

    /** Show only the first 4 characters of a secret, matching
     *  {@code ConfigService.maskValue}'s house style; blank/null → null. */
    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        return secret.length() > 4 ? secret.substring(0, 4) + "****" : "****";
    }
}
