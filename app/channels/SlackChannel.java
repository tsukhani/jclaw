package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slack.api.Slack;
import com.slack.api.app_backend.SlackSignature;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
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

    private static final String CHANNEL_NAME = "slack";
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
            var cc = ChannelConfig.findByType(CHANNEL_NAME);
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new SlackConfig(
                    json.get("botToken").getAsString(),
                    json.get("signingSecret").getAsString()
            );
        }
    }

    @Override
    public String channelName() { return CHANNEL_NAME; }

    /**
     * JCLAW-141: generic cross-channel text send (the {@link Channel} contract).
     * Posts {@code text} at channel level via the shared retry policy, behaviorally
     * identical to the {@code sendWithRetry} path the queue-drain dispatcher used
     * before this refactor — so no formatting is applied here. The mrkdwn-formatting
     * + thread-aware reply path is the static {@link #sendMessage(String, String,
     * String)}, which the live webhook/streaming-fallback path uses and which is
     * outside the generic Channel contract (it carries Slack's {@code thread_ts}).
     */
    @Override
    public SendResult sendText(String peerId, String text) {
        return sendWithRetry(peerId, text) ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-141: Slack outbound here is the Web API {@code chat.postMessage} text
     * path only — JClaw has no native Slack file-upload send today, so a photo
     * delivery isn't supported. Returns {@link SendResult#FAILED} (the interface
     * default) explicitly so the no-op is unambiguous at this call site.
     */
    @Override
    public SendResult sendPhoto(String peerId, java.io.File file, String caption) {
        return SendResult.FAILED;
    }

    /** JCLAW-141: no native Slack file-upload send — see {@link #sendPhoto}. */
    @Override
    public SendResult sendDocument(String peerId, java.io.File file, String caption) {
        return SendResult.FAILED;
    }

    public static boolean sendMessage(String channelId, String text) {
        return sendMessage(channelId, text, null);
    }

    /** Send to {@code channelId}, optionally replying into {@code threadTs}
     *  (JCLAW-83). {@code threadTs} null posts at channel level. */
    public static boolean sendMessage(String channelId, String text, String threadTs) {
        var config = SlackConfig.load();
        if (config == null) {
            EventLogger.error(CHANNEL, null, CHANNEL_NAME, "Slack not configured");
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
                EventLogger.info(CHANNEL, null, CHANNEL_NAME,
                        "Message sent to channel %s".formatted(channelId));
                return SendResult.OK;
            }
            // The synchronous client surfaces a 429 as SlackApiException (below);
            // a "ratelimited" body error is rare but handled defensively.
            if ("ratelimited".equals(resp.getError())) {
                EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "Rate-limited (API error)");
                return SendResult.rateLimited(0L);
            }
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME,
                    "Slack API error: %s".formatted(resp.getError()));
            return SendResult.FAILED;
        } catch (SlackApiException e) {
            var http = e.getResponse();
            if (http != null && http.code() == 429) {
                long retryAfterMs = parseRetryAfterMs(http.header("Retry-After"));
                EventLogger.warn(CHANNEL, null, CHANNEL_NAME,
                        "Rate-limited; Retry-After=%sms".formatted(retryAfterMs));
                return SendResult.rateLimited(retryAfterMs);
            }
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME,
                    "Slack API error: %s".formatted(e.getResponseBody()));
            return SendResult.FAILED;
        } catch (IOException e) {
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME,
                    "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    /**
     * JCLAW-341: start a native Slack text stream in a thread and return its
     * {@code ts} (or null on failure). {@code recipientUserId} is required for DM
     * streaming. The {@code markdown_text} fields render standard markdown
     * natively, so no mrkdwn conversion is applied on the streaming path. Requires
     * the app to be a Slack AI Assistant (Agents & AI Apps) with {@code assistant:write}.
     */
    public static String startStream(String channelId, String threadTs, String recipientUserId,
                                     String initialMarkdown) {
        var config = SlackConfig.load();
        if (config == null) return null;
        try {
            var resp = slack.methods(config.botToken()).chatStartStream(r -> r
                    .channel(channelId).threadTs(threadTs).recipientUserId(recipientUserId)
                    .markdownText(initialMarkdown));
            if (resp.isOk()) return resp.getTs();
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "startStream not ok: %s".formatted(resp.getError()));
            return null;
        } catch (SlackApiException | IOException e) {
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "startStream failed: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /** JCLAW-341: append a markdown delta to a live stream. Returns false on error. */
    public static boolean appendStream(String channelId, String ts, String markdownDelta) {
        var config = SlackConfig.load();
        if (config == null) return false;
        try {
            return slack.methods(config.botToken())
                    .chatAppendStream(r -> r.channel(channelId).ts(ts).markdownText(markdownDelta))
                    .isOk();
        } catch (SlackApiException | IOException _) {
            return false;
        }
    }

    /** JCLAW-341: finalize a live stream. Returns false on error. */
    public static boolean stopStream(String channelId, String ts) {
        var config = SlackConfig.load();
        if (config == null) return false;
        try {
            return slack.methods(config.botToken())
                    .chatStopStream(r -> r.channel(channelId).ts(ts))
                    .isOk();
        } catch (SlackApiException | IOException _) {
            return false;
        }
    }

    /**
     * JCLAW-341: set (or clear, with {@code ""}) the assistant-thread status line —
     * the native "X is typing…" indicator. Requires the AI Assistant feature +
     * {@code assistant:write} + a {@code thread_ts}. Best-effort; failures logged.
     */
    public static void setAssistantStatus(String channelId, String threadTs, String status) {
        var config = SlackConfig.load();
        if (config == null) return;
        try {
            slack.methods(config.botToken()).assistantThreadsSetStatus(r -> r
                    .channelId(channelId).threadTs(threadTs).status(status));
        } catch (SlackApiException | IOException e) {
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "setStatus failed: %s".formatted(e.getMessage()));
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
