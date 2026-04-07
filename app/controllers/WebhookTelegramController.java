package controllers;

import agents.AgentRouter;
import agents.AgentRunner;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConversationService;
import services.EventLogger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            var update = JsonParser.parseReader(reader).getAsJsonObject();
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
            var route = services.Tx.run(() -> AgentRouter.resolve("telegram", message.chatId()));
            if (route == null) {
                TelegramChannel.sendMessage(config, message.chatId(),
                        "No agent configured for this chat.");
                return;
            }

            var conversation = services.Tx.run(() ->
                    ConversationService.findOrCreate(route.agent(), "telegram", message.chatId()));
            var result = AgentRunner.run(route.agent(), conversation, message.text());

            TelegramChannel.sendMessage(config, message.chatId(), result.response());

        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Error processing message: %s".formatted(e.getMessage()));
            TelegramChannel.sendMessage(config, message.chatId(),
                    "Sorry, an error occurred processing your message.");
        }
    }
}
