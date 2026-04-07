package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import services.EventLogger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Telegram Bot API client. Sends messages via HTTP POST and registers webhooks.
 */
public class TelegramChannel {

    private static final Gson gson = new Gson();
    private static final String API_BASE = "https://api.telegram.org/bot";

    public record TelegramConfig(String botToken, String webhookSecret, String webhookUrl) {
        public static TelegramConfig load() {
            var cc = ChannelConfig.findByType("telegram");
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new TelegramConfig(
                    json.get("botToken").getAsString(),
                    json.has("webhookSecret") ? json.get("webhookSecret").getAsString() : null,
                    json.has("webhookUrl") ? json.get("webhookUrl").getAsString() : null
            );
        }
    }

    public static boolean sendMessage(String chatId, String text) {
        var config = TelegramConfig.load();
        if (config == null) {
            EventLogger.error("channel", null, "telegram", "Telegram not configured");
            return false;
        }
        return sendMessage(config, chatId, text);
    }

    public static boolean sendMessage(TelegramConfig config, String chatId, String text) {
        var url = API_BASE + config.botToken() + "/sendMessage";
        var body = gson.toJson(Map.of("chat_id", chatId, "text", text, "parse_mode", "Markdown"));

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                var response = utils.HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());
                var result = JsonParser.parseString(response.body()).getAsJsonObject();

                if (result.get("ok").getAsBoolean()) {
                    EventLogger.info("channel", null, "telegram",
                            "Message sent to chat %s".formatted(chatId));
                    return true;
                }

                EventLogger.warn("channel", null, "telegram",
                        "Telegram API error: %s".formatted(response.body()));

            } catch (Exception e) {
                EventLogger.warn("channel", null, "telegram",
                        "Send attempt %d failed: %s".formatted(attempt + 1, e.getMessage()));
                if (attempt == 0) {
                    try { Thread.sleep(1000); } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        EventLogger.error("channel", null, "telegram",
                "Failed to send message to chat %s after retries".formatted(chatId));
        return false;
    }

    public static boolean setWebhook(TelegramConfig config) {
        if (config.webhookUrl() == null) return false;

        var url = API_BASE + config.botToken() + "/setWebhook";
        var params = new java.util.HashMap<String, String>();
        params.put("url", config.webhookUrl());
        if (config.webhookSecret() != null) {
            params.put("secret_token", config.webhookSecret());
        }
        var body = gson.toJson(params);

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = utils.HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());
            var result = JsonParser.parseString(response.body()).getAsJsonObject();

            if (result.get("ok").getAsBoolean()) {
                EventLogger.info("channel", null, "telegram", "Webhook registered: %s".formatted(config.webhookUrl()));
                return true;
            }
            EventLogger.error("channel", null, "telegram", "Webhook registration failed: %s".formatted(response.body()));
            return false;

        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram", "Webhook registration error: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * Parse an inbound Telegram Update JSON and extract chat_id, text, and from user info.
     */
    public record InboundMessage(String chatId, String text, String fromId, String fromUsername) {}

    public static InboundMessage parseUpdate(JsonObject update) {
        if (!update.has("message")) return null;
        var message = update.getAsJsonObject("message");
        if (!message.has("text")) return null;

        var chat = message.getAsJsonObject("chat");
        var chatId = chat.get("id").getAsString();
        var text = message.get("text").getAsString();

        String fromId = null;
        String fromUsername = null;
        if (message.has("from")) {
            var from = message.getAsJsonObject("from");
            fromId = from.get("id").getAsString();
            fromUsername = from.has("username") ? from.get("username").getAsString() : null;
        }

        return new InboundMessage(chatId, text, fromId, fromUsername);
    }
}
