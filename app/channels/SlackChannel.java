package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slack.api.app_backend.SlackSignature;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.util.json.GsonFactory;
import models.ChannelConfig;
import services.EventLogger;
import utils.HttpKeys;

import java.util.LinkedHashMap;

/**
 * Slack Web API + Events API client.
 *
 * <p>JCLAW-83: inbound signature verification and event parsing use the official
 * Slack SDK (com.slack.api) — {@link SlackSignature.Verifier} and the typed
 * {@link MessageEvent} model — instead of hand-rolled HMAC + Gson. Outbound is
 * still raw OkHttp here; the Web API client ({@code MethodsClient}) swap and
 * {@code mrkdwn} land in Phase 2.
 */
public class SlackChannel implements Channel {

    private static final Gson gson = utils.GsonHolder.INSTANCE;
    /** Slack's snake_case Gson for deserializing inbound event POJOs. */
    private static final Gson SLACK_GSON = GsonFactory.createSnakeCase();

    private static final String SLACK = "slack";
    private static final String CHANNEL = "channel";

    /** Optional Slack {@code thread_ts} this instance replies into (JCLAW-83);
     *  null = post at channel level. Set per-send via
     *  {@link #sendMessage(String, String, String)} so the shared
     *  {@link Channel#trySend} contract stays thread-unaware. Each outbound send
     *  uses its own instance, so this final field is safe to read off the
     *  retry path. */
    private final String outboundThreadTs;

    public SlackChannel() { this(null); }

    private SlackChannel(String outboundThreadTs) { this.outboundThreadTs = outboundThreadTs; }

    public record SlackConfig(String botToken, String signingSecret) {
        public static SlackConfig load() {
            var cc = ChannelConfig.findByType(SLACK);
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new SlackConfig(
                    json.get("botToken").getAsString(),
                    json.get("signingSecret").getAsString()
            );
        }
    }

    @Override
    public String channelName() { return SLACK; }

    public static boolean sendMessage(String channelId, String text) {
        return sendMessage(channelId, text, null);
    }

    /** Send to {@code channelId}, optionally replying into {@code threadTs}
     *  (JCLAW-83). {@code threadTs} null posts at channel level. */
    public static boolean sendMessage(String channelId, String text, String threadTs) {
        var config = SlackConfig.load();
        if (config == null) {
            EventLogger.error(CHANNEL, null, SLACK, "Slack not configured");
            return false;
        }
        return new SlackChannel(threadTs).sendWithRetry(channelId, text);
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        var config = SlackConfig.load();
        if (config == null) return SendResult.FAILED;
        return trySend(config, peerId, text, outboundThreadTs);
    }

    private SendResult trySend(SlackConfig config, String channelId, String text, String threadTs) {
        var body = new LinkedHashMap<String, String>();
        body.put(CHANNEL, channelId);
        body.put("text", text);
        if (threadTs != null) body.put("thread_ts", threadTs);
        var jsonBody = gson.toJson(body);
        var jsonMediaType = okhttp3.MediaType.get(HttpKeys.APPLICATION_JSON);
        var request = new okhttp3.Request.Builder()
                .url("https://slack.com/api/chat.postMessage")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + config.botToken())
                .post(okhttp3.RequestBody.create(jsonBody, jsonMediaType))
                .build();
        try (var response = utils.HttpFactories.general().newCall(request).execute()) {
            if (response.code() == 429) {
                var retryAfterHeader = response.header("Retry-After");
                long retryAfterMs = parseRetryAfterMs(retryAfterHeader);
                EventLogger.warn(CHANNEL, null, SLACK,
                        "Rate-limited; Retry-After=%sms".formatted(retryAfterMs));
                return SendResult.rateLimited(retryAfterMs);
            }
            var responseBody = response.body().string();
            var result = JsonParser.parseString(responseBody).getAsJsonObject();

            if (result.get("ok").getAsBoolean()) {
                EventLogger.info(CHANNEL, null, SLACK,
                        "Message sent to channel %s".formatted(channelId));
                return SendResult.OK;
            }

            EventLogger.warn(CHANNEL, null, SLACK,
                    "Slack API error: %s".formatted(result.has("error") ? result.get("error").getAsString() : responseBody));
            return SendResult.FAILED;
        } catch (Exception e) {
            EventLogger.warn(CHANNEL, null, SLACK,
                    "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    private static long parseRetryAfterMs(String header) {
        if (header == null || header.isBlank()) return 0L;
        try {
            return Long.parseLong(header.trim()) * 1000L;
        } catch (NumberFormatException _) {
            return 0L;
        }
    }

    // --- Inbound signature verification (SDK) ---

    /**
     * Verify a Slack request signature with the SDK's {@link SlackSignature.Verifier}:
     * HMAC-SHA256 over {@code v0:timestamp:body} plus a built-in 5-minute
     * stale-timestamp / replay window. Same method shape as the prior hand-rolled
     * version, so {@link controllers.WebhookSlackController} is unchanged.
     */
    public static boolean verifySignature(String signingSecret, String timestamp, String body, String signature) {
        if (signingSecret == null || timestamp == null || signature == null) {
            return false;
        }
        return new SlackSignature.Verifier(new SlackSignature.Generator(signingSecret))
                .isValid(timestamp, body, signature);
    }

    // --- Inbound event parsing (SDK model) ---

    public record InboundMessage(String channelId, String userId, String text, String threadTs) {}

    /**
     * Parse an Events API envelope into an {@link InboundMessage}, or null for
     * anything that isn't a plain user message (url_verification is handled by
     * the controller; bot messages and message subtypes are ignored). Message
     * fields come from the SDK's typed {@link MessageEvent}; {@code thread_ts} is
     * carried so replies land in-thread (JCLAW-83).
     */
    public static InboundMessage parseEvent(JsonObject payload) {
        if (!"event_callback".equals(str(payload, "type"))) {
            return null;
        }
        var eventObj = payload.getAsJsonObject("event");
        if (eventObj == null || !"message".equals(str(eventObj, "type"))) {
            return null;
        }
        // Ignore bot messages and message subtypes (edits, joins, bot_message, ...).
        if (eventObj.has("bot_id") || eventObj.has("subtype")) {
            return null;
        }
        var event = SLACK_GSON.fromJson(eventObj, MessageEvent.class);
        return new InboundMessage(
                event.getChannel(),
                event.getUser(),
                event.getText() != null ? event.getText() : "",
                event.getThreadTs());
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
