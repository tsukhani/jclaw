package controllers;

import agents.AgentRunner;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import utils.WebhookUtil;

public class WebhookTelegramController extends Controller {

    public static void webhook(String secret) {
        // Verify secret path
        var config = TelegramChannel.TelegramConfig.load();
        if (config == null) {
            EventLogger.warn("channel", null, "telegram", "Webhook received but Telegram not configured");
            ok();
            return;
        }

        if (config.webhookSecret() != null && !config.webhookSecret().equals(secret)) {
            EventLogger.warn("channel", null, "telegram", "Invalid webhook secret");
            forbidden();
            return;
        }

        // Verify X-Telegram-Bot-Api-Secret-Token header if present
        var secretHeader = Http.Request.current().headers.get("x-telegram-bot-api-secret-token");
        if (secretHeader != null && config.webhookSecret() != null
                && !config.webhookSecret().equals(secretHeader.value())) {
            EventLogger.warn("channel", null, "telegram", "Invalid secret token header");
            forbidden();
            return;
        }

        // Parse update
        try {
            var update = JsonParser.parseString(WebhookUtil.readRawBody()).getAsJsonObject();
            var message = TelegramChannel.parseUpdate(update);

            if (message == null) {
                ok(); // Non-message update (e.g., edited_message, callback_query)
                return;
            }

            EventLogger.info("channel", null, "telegram",
                    "Message received from %s: %s".formatted(
                            message.fromUsername() != null ? message.fromUsername() : message.fromId(),
                            message.text().length() > 50 ? message.text().substring(0, 50) + "..." : message.text()));

            // Process async in virtual thread
            Thread.ofVirtual().start(() -> processMessage(config, message));

        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram", "Webhook parse error: %s".formatted(e.getMessage()));
        }

        ok();
    }

    private static void processMessage(TelegramChannel.TelegramConfig config,
                                        TelegramChannel.InboundMessage message) {
        try {
            AgentRunner.processWebhookMessage("telegram", message.chatId(), message.text(),
                    (peerId, response) -> TelegramChannel.sendMessage(config, peerId, response),
                    peerId -> TelegramChannel.sendMessage(config, peerId, "No agent configured for this chat."));
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Error processing message: %s".formatted(e.getMessage()));
            TelegramChannel.sendMessage(config, message.chatId(),
                    "Sorry, an error occurred processing your message.");
        }
    }
}
