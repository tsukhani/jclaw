package controllers;

import agents.AgentRunner;
import channels.InboundCallback;
import channels.InboundMessage;
import channels.TelegramChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.Agent;
import models.TelegramBinding;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import utils.WebhookUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook receiver for per-user Telegram bindings (JCLAW-89). The route carries
 * the {@code bindingId} so the controller can look up the matching
 * {@link TelegramBinding} without a global "current telegram config" read; the
 * {@code secret} path segment is the per-binding {@code webhook_secret} for
 * signature verification. Unknown or disabled bindings get dropped at 404/403
 * rather than being silently accepted.
 */
public class WebhookTelegramController extends Controller {

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CATEGORY_CHANNEL = "channel";
    private static final String THREAD_NAME_PROCESS = "webhook-telegram-process";

    /**
     * JCLAW-371: deserialize the webhook JSON body into an SDK {@link Update}
     * so the identity-aware {@code parseUpdate(Update, username, userId)} overload
     * can flag whether the bot was directly addressed. The polling path already
     * receives SDK Updates; this gives the webhook path the same shape.
     */
    private static final ObjectMapper JACKSON = new ObjectMapper();

    /** Snapshot of the fields {@link #webhook} needs off the request thread. */
    private record BindingCtx(Long bindingId, String botToken, String telegramUserId,
                              Agent agent, String webhookSecret, boolean enabled) {}

    // M1 webhook ingress hardening config keys (read via Play.configuration so
    // the documented defaults below hold until an operator overrides them).
    private static final String CFG_RATE_LIMIT_MAX = "telegram.webhook.rate-limit.max";
    private static final String CFG_RATE_LIMIT_WINDOW_SECONDS = "telegram.webhook.rate-limit.window-seconds";
    private static final String CFG_MAX_BODY_BYTES = "telegram.webhook.max-body-bytes";
    private static final String CFG_TRUSTED_PROXY = "telegram.webhook.trusted-proxy";
    private static final int DEFAULT_RATE_LIMIT_MAX = 60;
    private static final long DEFAULT_RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final long DEFAULT_MAX_BODY_BYTES = 1_048_576L;

    @SuppressWarnings("java:S2259")
    public static void webhook(Long bindingId, String secret) {
        BindingCtx ctx = loadBindingCtx(bindingId);
        if (ctx == null) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Webhook for unknown binding id=%s".formatted(bindingId));
            notFound();
        }
        if (!ctx.enabled()) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Webhook for disabled binding %d".formatted(bindingId));
            ok();
        }

        // M1: pre-auth rate limit. Runs AFTER the binding is loaded (so we have
        // a bindingId to key on) but BEFORE verifySecret, so a wrong-secret
        // flood against one binding is rejected cheaply with HTTP 429 instead of
        // paying the constant-time secret compares on every request. The real
        // client IP is resolved for accurate logging (it does not gate).
        String clientIp = resolveClientIp();
        if (!channels.TelegramWebhookRateLimiter.allow(bindingId, rateLimitMax(), rateLimitWindowSeconds())) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Rate-limited webhook for binding %d from %s".formatted(bindingId, clientIp));
            error(429, "Too Many Requests");
        }

        // M1: body-size limit. Check Content-Length first so an oversized POST
        // is rejected before the body is read at all; the read-length backstop
        // below guards a missing / lying Content-Length header.
        long maxBodyBytes = maxBodyBytes();
        if (contentLengthExceeds(maxBodyBytes)) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Oversized webhook body (Content-Length) for binding %d from %s".formatted(bindingId, clientIp));
            error(413, "Payload Too Large");
        }

        if (!verifySecret(ctx, secret, bindingId)) {
            unauthorized("Invalid signature");
        }

        try {
            var rawBody = WebhookUtil.readRawBody();
            // Backstop the Content-Length check against the actual read length
            // (a chunked / unset Content-Length request bypasses the early guard).
            if (rawBody.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
                EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                        "Oversized webhook body (read length) for binding %d from %s".formatted(bindingId, clientIp));
                error(413, "Payload Too Large");
            }
            dispatchUpdate(ctx, rawBody, bindingId);
        } catch (play.mvc.results.Result r) {
            // 413 from the read-length backstop above: rethrow so Play renders
            // it; do not swallow it as a generic parse error.
            throw r;
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Webhook parse error for binding %d: %s".formatted(bindingId, e.getMessage()));
        }

        ok();
    }

    /**
     * M1: read the rate-limit ceiling ({@code telegram.webhook.rate-limit.max},
     * default 60). Unparseable / unset values fall back to the default.
     */
    private static int rateLimitMax() {
        return utils.PlayConfig.intOr(CFG_RATE_LIMIT_MAX, DEFAULT_RATE_LIMIT_MAX);
    }

    /** M1: rate-limit window in seconds ({@code telegram.webhook.rate-limit.window-seconds}, default 60). */
    private static long rateLimitWindowSeconds() {
        return utils.PlayConfig.intOr(CFG_RATE_LIMIT_WINDOW_SECONDS, (int) DEFAULT_RATE_LIMIT_WINDOW_SECONDS);
    }

    /** M1: maximum accepted body size in bytes ({@code telegram.webhook.max-body-bytes}, default 1 MiB). */
    private static long maxBodyBytes() {
        return utils.PlayConfig.longOr(CFG_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * M1: true when the request's {@code Content-Length} header is present and
     * exceeds {@code maxBodyBytes}. A missing / unparseable header returns false
     * here; the read-length backstop in {@link #webhook} catches those.
     */
    private static boolean contentLengthExceeds(long maxBodyBytes) {
        var header = Http.Request.current().headers.get("content-length");
        if (header == null || header.value() == null) return false;
        try {
            return Long.parseLong(header.value().trim()) > maxBodyBytes;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /**
     * M1: resolve the real client IP for logging. When
     * {@code telegram.webhook.trusted-proxy=false} (default) the socket peer
     * ({@code remoteAddress}) is authoritative. When {@code true} — JClaw sits
     * behind a trusted reverse proxy — prefer the left-most entry of
     * {@code X-Forwarded-For}, falling back to {@code remoteAddress}. This is a
     * best-effort logging aid; it never gates the request.
     */
    private static String resolveClientIp() {
        var req = Http.Request.current();
        if (trustedProxy()) {
            var xff = req.headers.get("x-forwarded-for");
            if (xff != null && xff.value() != null && !xff.value().isBlank()) {
                return xff.value().split(",")[0].trim();
            }
        }
        return req.remoteAddress;
    }

    private static boolean trustedProxy() {
        var raw = play.Play.configuration.getProperty(CFG_TRUSTED_PROXY, "false");
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    private static BindingCtx loadBindingCtx(Long bindingId) {
        return services.Tx.run(() -> {
            TelegramBinding b = TelegramBinding.findById(bindingId);
            if (b == null) return null;
            if (b.agent != null) {
                var _ = b.agent.name; // touch inside tx to avoid detached-proxy access later
            }
            return new BindingCtx(b.id, b.botToken, b.telegramUserId, b.agent,
                    b.webhookSecret, b.enabled);
        });
    }

    /**
     * JCLAW-16: Telegram's published auth mechanism is the secret_token
     * registered with setWebhook and echoed back as
     * X-Telegram-Bot-Api-Secret-Token. We also require the path segment
     * to match — it's a local routing convention, but rejecting a wrong
     * one costs nothing and fails fast before the header compare.
     */
    private static boolean verifySecret(BindingCtx ctx, String secret, Long bindingId) {
        var agentName = ctx.agent() != null ? ctx.agent().name : null;
        if (ctx.webhookSecret() == null
                || !MessageDigest.isEqual(
                        ctx.webhookSecret().getBytes(StandardCharsets.UTF_8),
                        secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8))) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, agentName, CHANNEL_TELEGRAM,
                    "Invalid webhook secret for binding %d".formatted(bindingId));
            return false;
        }
        var secretHeader = Http.Request.current().headers.get("x-telegram-bot-api-secret-token");
        if (secretHeader == null
                || !MessageDigest.isEqual(
                        ctx.webhookSecret().getBytes(StandardCharsets.UTF_8),
                        secretHeader.value().getBytes(StandardCharsets.UTF_8))) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, agentName, CHANNEL_TELEGRAM,
                    "Missing or invalid secret-token header for binding %d".formatted(bindingId));
            return false;
        }
        return true;
    }

    /**
     * JCLAW-109: route inline-keyboard callback queries before the
     * message parse path. parseCallback returns null for non-callback
     * updates, so this falls through cleanly to the message path
     * when the update is a regular text message.
     */
    private static void dispatchUpdate(BindingCtx ctx, String rawBody, Long bindingId)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Update sdkUpdate = JACKSON.readValue(rawBody, Update.class);
        var callback = TelegramChannel.parseCallback(sdkUpdate);
        if (callback != null) {
            handleCallback(ctx, callback, bindingId);
            return;
        }
        // JCLAW-375: a message_reaction update is neither a callback nor a
        // parseable message. Surface it as a gated system event (shared gate +
        // synthesis with the polling path) before the non-message drop below.
        // NOTE: Telegram only DELIVERS message_reaction to the webhook when the
        // registrar (TelegramWebhookRegistrar — outside this story's file set)
        // names it in allowed_updates; this handler is ready for that follow-up.
        var reaction = channels.TelegramPollingRunner.parseReaction(sdkUpdate);
        if (reaction != null) {
            channels.TelegramPollingRunner.handleReaction(
                    ctx.agent(), ctx.botToken(), ctx.telegramUserId(), reaction);
            return;
        }
        // JCLAW-371: resolve the bot's own identity so parseUpdate can flag an
        // @mention / text_mention / /cmd@botname / reply-to-bot addressing THIS
        // bot — the group access gate in handleInboundMessage reads
        // InboundMessage.botMentioned.
        var identity = channels.TelegramBotIdentity.resolve(ctx.botToken());
        var message = TelegramChannel.parseUpdate(sdkUpdate, identity.username(), identity.userId());
        if (message == null) return; // non-message update (edited_message, etc.)
        // JCLAW-387 B1: detect a forward off the RAW update (the parsed
        // InboundMessage drops the forward fields) so handleInboundMessage can
        // route a forward burst through the coalesce lane.
        handleInboundMessage(ctx, message, bindingId,
                channels.TelegramPollingRunner.isForward(sdkUpdate));
    }

    private static void handleCallback(BindingCtx ctx, InboundCallback callback, Long bindingId) {
        if (!ctx.telegramUserId().equals(callback.fromId())) {
            EventLogger.warn(CATEGORY_CHANNEL,
                    ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                    "Rejected callback from user %s: binding %d is bound to user %s".formatted(
                            callback.fromId(), bindingId, ctx.telegramUserId()));
            return;
        }
        dispatchOffThread("webhook-telegram-callback", () ->
                channels.TelegramCallbackDispatcher.dispatch(
                        ctx.botToken(), ctx.agent(), callback));
    }

    /**
     * Spawn an off-request virtual thread so the webhook handler can return
     * HTTP 200 immediately while the agent turn / callback runs asynchronously.
     */
    private static void dispatchOffThread(String name, Runnable r) {
        Thread.ofVirtual().name(name).start(r);
    }

    private static void handleInboundMessage(BindingCtx ctx, InboundMessage message,
                                             Long bindingId, boolean isForward) {
        // JCLAW-371 access policy: a DM is served only for the binding owner; a
        // group/supergroup is served for any member but only when the bot was
        // directly addressed (@mention / reply-to-bot etc.). See TelegramAccessPolicy.
        boolean ownerMatches = ctx.telegramUserId().equals(message.fromId());
        if (!channels.TelegramAccessPolicy.isAllowed(ownerMatches, message.chatType(), message.botMentioned())) {
            EventLogger.warn(CATEGORY_CHANNEL,
                    ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                    "Rejected inbound from %s (id=%s) in %s chat: binding %d (owner %s, mentioned=%s)".formatted(
                            message.fromUsername() != null ? message.fromUsername() : "?",
                            message.fromId(), message.chatType(), bindingId, ctx.telegramUserId(),
                            message.botMentioned()));
            return;
        }

        EventLogger.info(CATEGORY_CHANNEL,
                ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                "Webhook received from %s: %s".formatted(
                        message.fromUsername() != null ? message.fromUsername() : message.fromId(),
                        utils.Strings.truncate(message.text(), 50)));

        // Off-request virtual thread so the HTTP 200 returns immediately.
        // JCLAW-136: multi-photo albums ride through a reassembly buffer
        // so N photos sharing a media_group_id dispatch as ONE turn, not N.
        // Plain-text and single-attachment messages skip the buffer (null
        // media_group_id → immediate dispatch).
        //
        // M2 inbound reassembly: an eligible plain-text message (no
        // attachments, no media_group_id) routes through the inbound-text
        // buffer so a long paste auto-split by the client into consecutive
        // pieces coalesces into ONE turn. Sub-threshold pieces dispatch
        // immediately there, so normal messages keep today's zero-latency path.
        // Everything else (attachments / media groups) stays on the existing
        // media-group buffer unchanged.
        //
        // JCLAW-387 B1: a FORWARD takes priority over the text/media lanes — a
        // burst of consecutive forwards coalesces into ONE turn. Checked first
        // because a forwarded plain text would otherwise fall into the
        // text-reassembly lane and a forwarded photo into the media-group lane.
        if (isForward) {
            channels.TelegramForwardCoalesceBuffer.add(message, merged ->
                    dispatchOffThread(THREAD_NAME_PROCESS, () -> processMessage(ctx, merged)));
        } else if (channels.TelegramInboundTextBuffer.isEligible(message)) {
            channels.TelegramInboundTextBuffer.add(message, merged ->
                    dispatchOffThread(THREAD_NAME_PROCESS, () -> processMessage(ctx, merged)));
        } else {
            channels.TelegramMediaGroupBuffer.add(message, merged ->
                    dispatchOffThread(THREAD_NAME_PROCESS, () -> processMessage(ctx, merged)));
        }
    }

    private static void processMessage(BindingCtx ctx, InboundMessage message) {
        final String sendToken = ctx.botToken();
        final String sendChatId = message.chatId();
        final Agent sendAgent = ctx.agent();
        try {
            // JCLAW-136: if the message carries attachments, gate them against
            // the agent's model capabilities, then download each into workspace
            // staging. Rejections (modality mismatch, size exceeded, network
            // failures) produce a user-visible reply rather than a silent drop.
            var inputs = TelegramChannel.prepareInboundAttachments(
                    sendToken, sendChatId, sendAgent, message);
            if (inputs == null) return; // prepareInboundAttachments already replied + logged

            // JCLAW-94: stream the response as it's generated. The sink owns
            // send/edit/delete of the preview message; on completion it
            // delegates to the per-binding TelegramChannel's sendTurn (the planner
            // path) for media-rich / oversize responses. JCLAW-95: the factory
            // defers sink construction until AgentRunner has resolved the
            // conversation id so the sink can persist its checkpoint.
            // JCLAW-370: a DM keys off the binding owner (unchanged); an allowed
            // group/supergroup keys off the chat id (one shared conversation per
            // chat, per forum topic) so members share one transcript owned by the
            // binding's JClaw peer. Sender attribution is prefixed onto group
            // messages so the agent can tell members apart. The outbound sink
            // still routes to the chat id (sendChatId).
            final String sendChatType = message.chatType();
            final String peerId = AgentRunner.telegramConversationPeerId(
                    ctx.telegramUserId(), sendChatType, sendChatId, message.messageThreadId());
            final String attributedText = AgentRunner.telegramSenderAttributed(
                    message.text(), sendChatType, message.fromDisplayName(), message.fromId());
            // JCLAW-377: route a forum-topic message to its per-topic override
            // agent when one is mapped; falls back to the binding default for
            // non-topic / unmapped messages. peerId + sink are unchanged — only
            // which agent runs the turn changes.
            final Agent runAgent = resolveTopicAgent(sendToken, sendChatId, message.messageThreadId(), sendAgent);
            // JCLAW-387 B4 follow-up: pass the Telegram chat.type so the new
            // conversation is stamped with it (plain DM vs group history caps).
            AgentRunner.processInboundForAgentStreaming(
                    runAgent, CHANNEL_TELEGRAM, peerId, attributedText,
                    convId -> new channels.TelegramStreamingSink(
                            sendToken, sendChatId, sendAgent, convId, sendChatType,
                            message.messageId(), message.messageThreadId()),
                    inputs, sendChatType);
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                    "Error processing message for binding %d: %s".formatted(ctx.bindingId(), e.getMessage()));
            TelegramChannel.forToken(sendToken).sendText(sendChatId,
                    "Sorry, an error occurred processing your message.");
        }
    }

    /**
     * JCLAW-377: resolve which agent should run a turn for {@code (chatId,
     * threadId)}. Reads the binding by its bot token and delegates to
     * {@link TelegramBinding#resolveAgentForTopic} — returning the per-topic
     * override agent when mapped, otherwise the binding's default. The read
     * runs in a {@link services.Tx} (this runs on an off-request virtual
     * thread with no ambient JPA transaction), and the resolved agent's name is
     * touched eagerly to avoid detached-proxy access on the streaming path.
     * Falls back to {@code defaultAgent} if the binding can't be found (e.g.
     * removed between receive and dispatch).
     */
    private static Agent resolveTopicAgent(String botToken, String chatId, Integer threadId, Agent defaultAgent) {
        return services.Tx.run(() -> {
            TelegramBinding binding = TelegramBinding.findByBotToken(botToken);
            if (binding == null) return defaultAgent;
            Agent resolved = binding.resolveAgentForTopic(chatId, threadId);
            if (resolved != null) {
                var _ = resolved.name; // touch inside tx to avoid detached-proxy access later
            }
            // Fall back to the binding default if a topic-override's agent FK was
            // orphaned (agent deleted): resolveAgentForTopic returns null then, and a
            // null agent NPEs in ConversationService downstream.
            return resolved != null ? resolved : defaultAgent;
        });
    }
}
