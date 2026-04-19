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

        // Secret verification. Path segment is the primary check; the optional
        // x-telegram-bot-api-secret-token header is verified when present.
        if (ctx.webhookSecret() == null || !ctx.webhookSecret().equals(secret)) {
            EventLogger.warn("channel", ctx.agent() != null ? ctx.agent().name : null, "telegram",
                    "Invalid webhook secret for binding %d".formatted(bindingId));
            forbidden();
            return;
        }
        var secretHeader = Http.Request.current().headers.get("x-telegram-bot-api-secret-token");
        if (secretHeader != null && !ctx.webhookSecret().equals(secretHeader.value())) {
            EventLogger.warn("channel", ctx.agent() != null ? ctx.agent().name : null, "telegram",
                    "Invalid secret-token header for binding %d".formatted(bindingId));
            forbidden();
            return;
        }

        try {
            var update = JsonParser.parseString(WebhookUtil.readRawBody()).getAsJsonObject();
            var message = TelegramChannel.parseUpdate(update);
            if (message == null) {
                ok(); // non-message update (edited_message, callback_query, etc.)
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
                            truncate(message.text())));

            // Off-request virtual thread so the HTTP 200 returns immediately.
            Thread.ofVirtual().start(() -> processMessage(ctx, message));
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
            AgentRunner.processInboundForAgent(sendAgent, "telegram", ctx.telegramUserId(),
                    message.text(),
                    (peerId, response) -> TelegramChannel.sendMessage(sendToken, sendChatId, response, sendAgent));
        } catch (Exception e) {
            EventLogger.error("channel", ctx.agent() != null ? ctx.agent().name : null, "telegram",
                    "Error processing message for binding %d: %s".formatted(ctx.bindingId(), e.getMessage()));
            TelegramChannel.sendMessage(sendToken, sendChatId,
                    "Sorry, an error occurred processing your message.");
        }
    }

    private static String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
