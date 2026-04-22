package controllers;

import agents.AgentRunner;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import models.Agent;
import models.TelegramBinding;
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

    /** Snapshot of the fields {@link #webhook} needs off the request thread. */
    private record BindingCtx(Long bindingId, String botToken, String telegramUserId,
                              Agent agent, String webhookSecret, boolean enabled) {}

    public static void webhook(Long bindingId, String secret) {
        BindingCtx ctx = services.Tx.run(() -> {
            TelegramBinding b = TelegramBinding.findById(bindingId);
            if (b == null) return null;
            if (b.agent != null) {
                var _ = b.agent.name; // touch inside tx to avoid detached-proxy access later
            }
            return new BindingCtx(b.id, b.botToken, b.telegramUserId, b.agent,
                    b.webhookSecret, b.enabled);
        });

        if (ctx == null) {
            EventLogger.warn("channel", null, "telegram",
                    "Webhook for unknown binding id=%s".formatted(bindingId));
            notFound();
            return;
        }
        if (!ctx.enabled()) {
            EventLogger.warn("channel", null, "telegram",
                    "Webhook for disabled binding %d".formatted(bindingId));
            ok();
            return;
        }

        // JCLAW-16: Telegram's published auth mechanism is the secret_token
        // registered with setWebhook and echoed back as
        // X-Telegram-Bot-Api-Secret-Token. We also require the path segment
        // to match — it's a local routing convention, but rejecting a wrong
        // one costs nothing and fails fast before the header compare.
        var agentName = ctx.agent() != null ? ctx.agent().name : null;
        if (ctx.webhookSecret() == null
                || !MessageDigest.isEqual(
                        ctx.webhookSecret().getBytes(StandardCharsets.UTF_8),
                        secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8))) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, agentName, "telegram",
                    "Invalid webhook secret for binding %d".formatted(bindingId));
            unauthorized("Invalid signature");
            return;
        }
        var secretHeader = Http.Request.current().headers.get("x-telegram-bot-api-secret-token");
        if (secretHeader == null
                || !MessageDigest.isEqual(
                        ctx.webhookSecret().getBytes(StandardCharsets.UTF_8),
                        secretHeader.value().getBytes(StandardCharsets.UTF_8))) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, agentName, "telegram",
                    "Missing or invalid secret-token header for binding %d".formatted(bindingId));
            unauthorized("Invalid signature");
            return;
        }

        try {
            var update = JsonParser.parseString(WebhookUtil.readRawBody()).getAsJsonObject();

            // JCLAW-109: route inline-keyboard callback queries before the
            // message parse path. parseCallback returns null for non-callback
            // updates, so this falls through cleanly to the message path
            // when the update is a regular text message.
            var callback = TelegramChannel.parseCallback(update);
            if (callback != null) {
                if (!ctx.telegramUserId().equals(callback.fromId())) {
                    EventLogger.warn("channel",
                            ctx.agent() != null ? ctx.agent().name : null, "telegram",
                            "Rejected callback from user %s: binding %d is bound to user %s".formatted(
                                    callback.fromId(), bindingId, ctx.telegramUserId()));
                    ok();
                    return;
                }
                Thread.ofVirtual().start(() ->
                        channels.TelegramCallbackDispatcher.dispatch(
                                ctx.botToken(), ctx.agent(), callback));
                ok();
                return;
            }

            var message = TelegramChannel.parseUpdate(update);
            if (message == null) {
                ok(); // non-message update (edited_message, etc.)
                return;
            }

            // Peer-level authorization: only the bound user may reach this bot.
            if (!ctx.telegramUserId().equals(message.fromId())) {
                EventLogger.warn("channel",
                        ctx.agent() != null ? ctx.agent().name : null, "telegram",
                        "Rejected inbound from %s (id=%s): binding %d is bound to user %s".formatted(
                                message.fromUsername() != null ? message.fromUsername() : "?",
                                message.fromId(), bindingId, ctx.telegramUserId()));
                ok();
                return;
            }

            EventLogger.info("channel",
                    ctx.agent() != null ? ctx.agent().name : null, "telegram",
                    "Webhook received from %s: %s".formatted(
                            message.fromUsername() != null ? message.fromUsername() : message.fromId(),
                            utils.Strings.truncate(message.text(), 50)));

            // Off-request virtual thread so the HTTP 200 returns immediately.
            // JCLAW-136: multi-photo albums ride through a reassembly buffer
            // so N photos sharing a media_group_id dispatch as ONE turn, not N.
            // Plain-text and single-attachment messages skip the buffer (null
            // media_group_id → immediate dispatch).
            final BindingCtx captured = ctx;
            channels.TelegramMediaGroupBuffer.add(message, merged ->
                    Thread.ofVirtual().start(() -> processMessage(captured, merged)));
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Webhook parse error for binding %d: %s".formatted(bindingId, e.getMessage()));
        }

        ok();
    }

    private static void processMessage(BindingCtx ctx, TelegramChannel.InboundMessage message) {
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
            // delegates to TelegramChannel.sendMessage (via the planner path)
            // for media-rich / oversize responses. JCLAW-95: the factory
            // defers sink construction until AgentRunner has resolved the
            // conversation id so the sink can persist its checkpoint.
            final String sendChatType = message.chatType();
            AgentRunner.processInboundForAgentStreaming(
                    sendAgent, "telegram", ctx.telegramUserId(), message.text(),
                    convId -> new channels.TelegramStreamingSink(
                            sendToken, sendChatId, sendAgent, convId, sendChatType),
                    inputs);
        } catch (Exception e) {
            EventLogger.error("channel", ctx.agent() != null ? ctx.agent().name : null, "telegram",
                    "Error processing message for binding %d: %s".formatted(ctx.bindingId(), e.getMessage()));
            TelegramChannel.sendMessage(sendToken, sendChatId,
                    "Sorry, an error occurred processing your message.");
        }
    }
}
