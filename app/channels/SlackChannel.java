package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slack.api.Slack;
import com.slack.api.app_backend.SlackSignature;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.util.json.GsonFactory;
import models.ChannelConfig;
import services.EventLogger;

import java.io.IOException;

/**
 * Slack Web API + Events API client, on the official Slack SDK (com.slack.api).
 *
 * <p>JCLAW-83: inbound uses {@link SlackSignature.Verifier} + the typed
 * {@link MessageEvent} model; outbound uses the Web API {@code MethodsClient}
 * ({@code chat.postMessage} with {@code mrkdwn} and {@code thread_ts}). The SDK
 * manages its own OkHttp client, so Slack traffic stays off JClaw's LLM/general
 * connection pools.
 */
public class SlackChannel implements Channel {

    /** Shared SDK entry point; {@code methods(token)} yields a per-token client. */
    private static final Slack slack = Slack.getInstance();
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
        // JCLAW-341: agents emit CommonMark; convert to Slack mrkdwn so it renders
        // formatted instead of showing literal **bold** / # headings / [t](u).
        return new SlackChannel(threadTs).sendWithRetry(channelId, SlackMarkdownFormatter.format(text));
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        var config = SlackConfig.load();
        if (config == null) return SendResult.FAILED;
        return trySend(config, peerId, text, outboundThreadTs);
    }

    /**
     * Pure, testable {@code chat.postMessage} request builder — sets
     * {@code mrkdwn:true} (JCLAW-14 §4.4) and the optional {@code thread_ts}
     * (JCLAW-83). A null {@code threadTs} is omitted, so the reply posts at
     * channel level.
     */
    public static ChatPostMessageRequest postRequest(String channelId, String text, String threadTs) {
        return ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(text)
                .mrkdwn(true)
                .threadTs(threadTs)
                .build();
    }

    private SendResult trySend(SlackConfig config, String channelId, String text, String threadTs) {
        try {
            var resp = slack.methods(config.botToken())
                    .chatPostMessage(postRequest(channelId, text, threadTs));
            if (resp.isOk()) {
                EventLogger.info(CHANNEL, null, SLACK,
                        "Message sent to channel %s".formatted(channelId));
                return SendResult.OK;
            }
            // The synchronous client surfaces a 429 as SlackApiException (below);
            // a "ratelimited" body error is rare but handled defensively.
            if ("ratelimited".equals(resp.getError())) {
                EventLogger.warn(CHANNEL, null, SLACK, "Rate-limited (API error)");
                return SendResult.rateLimited(0L);
            }
            EventLogger.warn(CHANNEL, null, SLACK,
                    "Slack API error: %s".formatted(resp.getError()));
            return SendResult.FAILED;
        } catch (SlackApiException e) {
            var http = e.getResponse();
            if (http != null && http.code() == 429) {
                long retryAfterMs = parseRetryAfterMs(http.header("Retry-After"));
                EventLogger.warn(CHANNEL, null, SLACK,
                        "Rate-limited; Retry-After=%sms".formatted(retryAfterMs));
                return SendResult.rateLimited(retryAfterMs);
            }
            EventLogger.warn(CHANNEL, null, SLACK,
                    "Slack API error: %s".formatted(e.getResponseBody()));
            return SendResult.FAILED;
        } catch (IOException e) {
            EventLogger.warn(CHANNEL, null, SLACK,
                    "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    /**
     * JCLAW-341: post a message and return its {@code ts} — the streaming sink's
     * handle for later {@code chat.update} — or null on failure. Text is sent
     * verbatim; the caller owns formatting.
     */
    public static String postReturningTs(String channelId, String text, String threadTs) {
        var config = SlackConfig.load();
        if (config == null) return null;
        try {
            var resp = slack.methods(config.botToken()).chatPostMessage(postRequest(channelId, text, threadTs));
            return resp.isOk() ? resp.getTs() : null;
        } catch (SlackApiException | IOException e) {
            EventLogger.warn(CHANNEL, null, SLACK, "Streaming placeholder post failed: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /**
     * JCLAW-341: edit a previously-posted message ({@code chat.update}). Returns a
     * {@link SendResult} so a streaming caller can back off on a 429.
     */
    public static SendResult updateMessage(String channelId, String ts, String text) {
        var config = SlackConfig.load();
        if (config == null) return SendResult.FAILED;
        try {
            var resp = slack.methods(config.botToken()).chatUpdate(
                    ChatUpdateRequest.builder().channel(channelId).ts(ts).text(text).build());
            if (resp.isOk()) return SendResult.OK;
            if ("ratelimited".equals(resp.getError())) return SendResult.rateLimited(0L);
            return SendResult.FAILED;
        } catch (SlackApiException e) {
            var http = e.getResponse();
            if (http != null && http.code() == 429) {
                return SendResult.rateLimited(parseRetryAfterMs(http.header("Retry-After")));
            }
            return SendResult.FAILED;
        } catch (IOException e) {
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
