package channels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import services.EventLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * WhatsApp Cloud API (Meta) client via raw HTTP.
 */
public class WhatsAppChannel implements Channel {

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;
    private static final String API_BASE = "https://graph.facebook.com/v21.0/";
    private static final okhttp3.MediaType JSON_MEDIA_TYPE = okhttp3.MediaType.get("application/json");

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

    @Override
    public String channelName() { return "whatsapp"; }

    public static boolean sendMessage(String to, String text) {
        var config = WhatsAppConfig.load();
        if (config == null) {
            EventLogger.error("channel", null, "whatsapp", "WhatsApp not configured");
            return false;
        }
        return new WhatsAppChannel().sendWithRetry(to, text);
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        var config = WhatsAppConfig.load();
        if (config == null) return SendResult.FAILED;
        var url = API_BASE + config.phoneNumberId() + "/messages";
        var body = gson.toJson(Map.of(
                "messaging_product", "whatsapp",
                "to", peerId,
                "type", "text",
                "text", Map.of("body", text)
        ));
        var request = new okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.accessToken())
                .post(okhttp3.RequestBody.create(body, JSON_MEDIA_TYPE))
                .build();
        try (var response = utils.HttpFactories.general().newCall(request).execute()) {
            if (response.code() == 200) {
                EventLogger.info("channel", null, "whatsapp",
                        "Message sent to %s".formatted(peerId));
                return SendResult.OK;
            }

            var responseBody = response.body() != null ? response.body().string() : "";
            EventLogger.warn("channel", null, "whatsapp",
                    "WhatsApp API error (HTTP %d): %s".formatted(response.code(), responseBody));
            return SendResult.FAILED;
        } catch (Exception e) {
            EventLogger.warn("channel", null, "whatsapp",
                    "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    public static void markAsRead(WhatsAppConfig config, String messageId) {
        var url = API_BASE + config.phoneNumberId() + "/messages";
        var body = gson.toJson(Map.of(
                "messaging_product", "whatsapp",
                "status", "read",
                "message_id", messageId
        ));

        var request = new okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.accessToken())
                .post(okhttp3.RequestBody.create(body, JSON_MEDIA_TYPE))
                .build();
        try (var resp = utils.HttpFactories.general().newCall(request).execute()) {
            // Drain the body so the connection returns to the pool; result discarded.
            if (resp.body() != null) resp.body().bytes();
        } catch (Exception _) {
            // Read receipt failure is non-critical
        }
    }

    // --- HMAC-SHA256 signature verification ---

    public static boolean verifySignature(String appSecret, String rawBody, String signatureHeader) {
        if (appSecret == null || signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            var received = HexFormat.of().parseHex(signatureHeader.substring(7));
            return MessageDigest.isEqual(expected, received);
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
