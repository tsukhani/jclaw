package channels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import services.EventLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Slack Web API + Events API client via raw HTTP.
 */
public class SlackChannel implements Channel {

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;
    private static final long MAX_TIMESTAMP_AGE_SECONDS = 300; // 5 minutes

    public record SlackConfig(String botToken, String signingSecret) {
        public static SlackConfig load() {
            var cc = ChannelConfig.findByType("slack");
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new SlackConfig(
                    json.get("botToken").getAsString(),
                    json.get("signingSecret").getAsString()
            );
        }
    }

    @Override
    public String channelName() { return "slack"; }

    public static boolean sendMessage(String channelId, String text) {
        var config = SlackConfig.load();
        if (config == null) {
            EventLogger.error("channel", null, "slack", "Slack not configured");
            return false;
        }
        return new SlackChannel().sendWithRetry(channelId, text);
    }

    @Override
    public boolean trySend(String peerId, String text) {
        var config = SlackConfig.load();
        if (config == null) return false;
        return trySend(config, peerId, text);
    }

    private boolean trySend(SlackConfig config, String channelId, String text) {
        var body = gson.toJson(Map.of("channel", channelId, "text", text));
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://slack.com/api/chat.postMessage"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.botToken())
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = utils.HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());
            var result = JsonParser.parseString(response.body()).getAsJsonObject();

            if (result.get("ok").getAsBoolean()) {
                EventLogger.info("channel", null, "slack",
                        "Message sent to channel %s".formatted(channelId));
                return true;
            }

            EventLogger.warn("channel", null, "slack",
                    "Slack API error: %s".formatted(result.has("error") ? result.get("error").getAsString() : response.body()));
            return false;
        } catch (Exception e) {
            EventLogger.warn("channel", null, "slack",
                    "Send failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    // --- HMAC-SHA256 signature verification ---

    public static boolean verifySignature(String signingSecret, String timestamp, String body, String signature) {
        if (signingSecret == null || timestamp == null || signature == null
                || !signature.startsWith("v0=")) {
            return false;
        }
        // Replay attack prevention
        try {
            var ts = Long.parseLong(timestamp);
            var age = Instant.now().getEpochSecond() - ts;
            if (Math.abs(age) > MAX_TIMESTAMP_AGE_SECONDS) {
                return false;
            }
        } catch (NumberFormatException _) {
            return false;
        }

        try {
            var baseString = "v0:%s:%s".formatted(timestamp, body);
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            var received = HexFormat.of().parseHex(signature.substring(3));
            return MessageDigest.isEqual(expected, received);
        } catch (Exception _) {
            return false;
        }
    }

    // --- Parse inbound event ---

    public record InboundMessage(String channelId, String userId, String text) {}

    public static InboundMessage parseEvent(JsonObject payload) {
        if (!"event_callback".equals(payload.has("type") ? payload.get("type").getAsString() : "")) {
            return null;
        }
        var event = payload.getAsJsonObject("event");
        if (event == null || !"message".equals(event.get("type").getAsString())) {
            return null;
        }
        // Ignore bot messages
        if (event.has("bot_id") || event.has("subtype")) {
            return null;
        }

        var channelId = event.get("channel").getAsString();
        var userId = event.has("user") ? event.get("user").getAsString() : null;
        var text = event.has("text") ? event.get("text").getAsString() : "";

        return new InboundMessage(channelId, userId, text);
    }
}
