package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import services.EventLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * WhatsApp Cloud API (Meta) client via raw HTTP.
 */
public class WhatsAppChannel {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson gson = new Gson();
    private static final String API_BASE = "https://graph.facebook.com/v21.0/";

    public record WhatsAppConfig(String phoneNumberId, String accessToken, String appSecret, String verifyToken) {
        public static WhatsAppConfig load() {
            var cc = ChannelConfig.findByType("whatsapp");
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new WhatsAppConfig(
                    json.get("phoneNumberId").getAsString(),
                    json.get("accessToken").getAsString(),
                    json.has("appSecret") ? json.get("appSecret").getAsString() : null,
                    json.has("verifyToken") ? json.get("verifyToken").getAsString() : null
            );
        }
    }

    public static boolean sendMessage(String to, String text) {
        var config = WhatsAppConfig.load();
        if (config == null) {
            EventLogger.error("channel", null, "whatsapp", "WhatsApp not configured");
            return false;
        }
        return sendMessage(config, to, text);
    }

    public static boolean sendMessage(WhatsAppConfig config, String to, String text) {
        var url = API_BASE + config.phoneNumberId() + "/messages";
        var body = gson.toJson(Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text)
        ));

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.accessToken())
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    EventLogger.info("channel", null, "whatsapp",
                            "Message sent to %s".formatted(to));
                    return true;
                }

                EventLogger.warn("channel", null, "whatsapp",
                        "WhatsApp API error (HTTP %d): %s".formatted(response.statusCode(), response.body()));

            } catch (Exception e) {
                EventLogger.warn("channel", null, "whatsapp",
                        "Send attempt %d failed: %s".formatted(attempt + 1, e.getMessage()));
                if (attempt == 0) {
                    try { Thread.sleep(1000); } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        EventLogger.error("channel", null, "whatsapp",
                "Failed to send message to %s after retries".formatted(to));
        return false;
    }

    public static void markAsRead(WhatsAppConfig config, String messageId) {
        var url = API_BASE + config.phoneNumberId() + "/messages";
        var body = gson.toJson(Map.of(
                "messaging_product", "whatsapp",
                "status", "read",
                "message_id", messageId
        ));

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.accessToken())
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception _) {
            // Read receipt failure is non-critical
        }
    }

    // --- HMAC-SHA256 signature verification ---

    public static boolean verifySignature(String appSecret, String rawBody, String signatureHeader) {
        if (appSecret == null || signatureHeader == null) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            var computed = "sha256=" + HexFormat.of().formatHex(hash);
            return computed.equals(signatureHeader);
        } catch (Exception _) {
            return false;
        }
    }

    // --- Parse inbound webhook ---

    public record InboundMessage(String from, String text, String messageId, String phoneNumberId) {}

    public static InboundMessage parseWebhook(JsonObject payload) {
        if (!payload.has("entry")) return null;
        var entries = payload.getAsJsonArray("entry");
        if (entries.isEmpty()) return null;

        var entry = entries.get(0).getAsJsonObject();
        if (!entry.has("changes")) return null;
        var changes = entry.getAsJsonArray("changes");
        if (changes.isEmpty()) return null;

        var change = changes.get(0).getAsJsonObject();
        var value = change.getAsJsonObject("value");
        if (value == null || !value.has("messages")) return null;

        var messages = value.getAsJsonArray("messages");
        if (messages.isEmpty()) return null;

        var msg = messages.get(0).getAsJsonObject();
        if (!"text".equals(msg.get("type").getAsString())) return null;

        var from = msg.get("from").getAsString();
        var text = msg.getAsJsonObject("text").get("body").getAsString();
        var messageId = msg.get("id").getAsString();

        var phoneNumberId = value.has("metadata")
                ? value.getAsJsonObject("metadata").get("phone_number_id").getAsString()
                : null;

        return new InboundMessage(from, text, messageId, phoneNumberId);
    }
}
