package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.slack.api.Slack;
import com.slack.api.app_backend.SlackSignature;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.util.json.GsonFactory;
import services.EventLogger;

import java.io.IOException;

/**
 * Slack Web API + Events API client, on the official Slack SDK (com.slack.api).
 *
 * <p>JCLAW-83: inbound uses {@link SlackSignature.Verifier} + the typed
 * {@link MessageEvent} model; outbound uses the Web API {@code MethodsClient}.
 * The SDK manages its own OkHttp client, so Slack traffic stays off JClaw's
 * LLM/general connection pools.
 *
 * <p>JCLAW-441: outbound is per-agent-binding. The send/stream methods take an
 * explicit bot token (resolved from the agent's {@link models.SlackBinding} at
 * the call site); {@link #forToken(String)} binds an instance to one agent's bot
 * for the generic {@link Channel} send path ({@code DeliveryDispatcher},
 * {@code ChannelRegistry}). There is no app-global Slack config.
 */
public class SlackChannel implements Channel {

    /** Shared SDK entry point; {@code methods(token)} yields a per-token client. */
    private static final Slack slack = Slack.getInstance();
    /** Slack's snake_case Gson for deserializing inbound event POJOs. */
    private static final Gson SLACK_GSON = GsonFactory.createSnakeCase();

    private static final String CHANNEL_NAME = "slack";
    private static final String CHANNEL = "channel";
    private static final String FILES_KEY = "files";

    /** This instance's per-agent bot token (JCLAW-441), or null for instances
     *  used only for inbound/metadata ({@link #channelName}, file-send no-ops).
     *  The generic {@link Channel} send path requires it. */
    private final String botToken;

    /** Optional Slack {@code thread_ts} this instance replies into (JCLAW-83);
     *  null = post at channel level. */
    private final String outboundThreadTs;

    public SlackChannel() { this(null, null); }

    private SlackChannel(String botToken, String outboundThreadTs) {
        this.botToken = botToken;
        this.outboundThreadTs = outboundThreadTs;
    }

    /** JCLAW-441: an instance bound to one agent's bot token, for the generic
     *  {@link Channel} send path. Slack's SDK client is per-token + stateless, so
     *  no caching is needed (unlike Telegram's {@code forToken}). */
    public static SlackChannel forToken(String botToken) {
        return new SlackChannel(botToken, null);
    }

    @Override
    public String channelName() { return CHANNEL_NAME; }

    /**
     * JCLAW-141: generic cross-channel text send (the {@link Channel} contract).
     * Still resolves the legacy app-global token; JCLAW-441 unit 5 switches this
     * to the agent's binding via {@code DeliveryDispatcher}.
     */
    @Override
    public SendResult sendText(String peerId, String text) {
        return sendWithRetry(peerId, text) ? SendResult.OK : SendResult.FAILED;
    }

    /** JCLAW-141: no native Slack file-upload send today (JCLAW-345). */
    @Override
    public SendResult sendPhoto(String peerId, java.io.File file, String caption) {
        return SendResult.FAILED;
    }

    /** JCLAW-141: no native Slack file-upload send today (JCLAW-345). */
    @Override
    public SendResult sendDocument(String peerId, java.io.File file, String caption) {
        return SendResult.FAILED;
    }

    /**
     * JCLAW-441: send to {@code channelId} as the bot identified by {@code botToken},
     * optionally replying into {@code threadTs}. Agents emit CommonMark; convert to
     * Slack mrkdwn so it renders formatted.
     */
    public static boolean sendMessage(String channelId, String text, String threadTs, String botToken) {
        if (botToken == null || botToken.isBlank()) {
            EventLogger.error(CHANNEL, null, CHANNEL_NAME, "Slack send: no bot token");
            return false;
        }
        return new SlackChannel(botToken, threadTs).trySend(botToken, channelId,
                SlackMarkdownFormatter.format(text), threadTs) == SendResult.OK;
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        if (botToken == null || botToken.isBlank()) return SendResult.FAILED;
        return trySend(botToken, peerId, text, outboundThreadTs);
    }

    /**
     * Pure, testable {@code chat.postMessage} request builder — sets
     * {@code mrkdwn:true} and the optional {@code thread_ts} (null omitted, so
     * the reply posts at channel level).
     */
    public static ChatPostMessageRequest postRequest(String channelId, String text, String threadTs) {
        return ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(text)
                .mrkdwn(true)
                .threadTs(threadTs)
                .build();
    }

    private SendResult trySend(String botToken, String channelId, String text, String threadTs) {
        try {
            var resp = slack.methods(botToken)
                    .chatPostMessage(postRequest(channelId, text, threadTs));
            if (resp.isOk()) {
                EventLogger.info(CHANNEL, null, CHANNEL_NAME,
                        "Message sent to channel %s".formatted(channelId));
                return SendResult.OK;
            }
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
     * JCLAW-341/441: start a native Slack text stream in a thread as the bot
     * identified by {@code botToken}; return its {@code ts} (or null on failure).
     * {@code recipientUserId} is required for DM streaming. The {@code markdown_text}
     * fields render standard markdown natively (no mrkdwn conversion). Requires the
     * app to be a Slack AI Assistant with {@code assistant:write}.
     */
    public static String startStream(String channelId, String threadTs, String recipientUserId,
                                     String initialMarkdown, String botToken) {
        if (botToken == null || botToken.isBlank()) return null;
        try {
            var resp = slack.methods(botToken).chatStartStream(r -> r
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

    /** JCLAW-341/441: append a markdown delta to a live stream. Returns false on error. */
    public static boolean appendStream(String channelId, String ts, String markdownDelta, String botToken) {
        if (botToken == null || botToken.isBlank()) return false;
        try {
            return slack.methods(botToken)
                    .chatAppendStream(r -> r.channel(channelId).ts(ts).markdownText(markdownDelta))
                    .isOk();
        } catch (SlackApiException | IOException _) {
            return false;
        }
    }

    /** JCLAW-341/441: finalize a live stream. Returns false on error. */
    public static boolean stopStream(String channelId, String ts, String botToken) {
        if (botToken == null || botToken.isBlank()) return false;
        try {
            return slack.methods(botToken)
                    .chatStopStream(r -> r.channel(channelId).ts(ts))
                    .isOk();
        } catch (SlackApiException | IOException _) {
            return false;
        }
    }

    /**
     * JCLAW-341/441: set (or clear, with {@code ""}) the assistant-thread status
     * line as the bot identified by {@code botToken}. Requires the AI Assistant
     * feature + {@code assistant:write} + a {@code thread_ts}. Best-effort.
     */
    public static void setAssistantStatus(String channelId, String threadTs, String status, String botToken) {
        if (botToken == null || botToken.isBlank()) return;
        try {
            slack.methods(botToken).assistantThreadsSetStatus(r -> r
                    .channelId(channelId).threadTs(threadTs).status(status));
        } catch (SlackApiException | IOException e) {
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "setStatus failed: %s".formatted(e.getMessage()));
        }
    }

    /**
     * JCLAW-346: post {@code text} (mrkdwn) and return the new message's {@code ts}
     * (or null on failure). Used to seed the off-thread draft-preview message that
     * {@link #editMessage} then progressively edits. The text is posted verbatim —
     * the live preview shows raw deltas; the formatted text lands on the final edit.
     */
    public static String postText(String channelId, String text, String threadTs, String botToken) {
        if (botToken == null || botToken.isBlank()) return null;
        try {
            var resp = slack.methods(botToken).chatPostMessage(postRequest(channelId, text, threadTs));
            return resp.isOk() ? resp.getTs() : null;
        } catch (SlackApiException | IOException e) {
            EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "postText failed: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /**
     * JCLAW-346: replace a message's text via {@code chat.update} (mrkdwn). Drives
     * the off-thread draft-preview edit loop + the final formatted edit. Returns
     * false on error so the caller can stop editing / fall back. (The chat.update
     * primitive is also the basis for JCLAW-347's edit action tool.)
     */
    public static boolean editMessage(String channelId, String ts, String text, String botToken) {
        if (botToken == null || botToken.isBlank()) return false;
        try {
            return slack.methods(botToken)
                    .chatUpdate(r -> r.channel(channelId).ts(ts).text(text))
                    .isOk();
        } catch (SlackApiException | IOException _) {
            return false;
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
     * stale-timestamp / replay window. JCLAW-441 passes the per-binding signing
     * secret here.
     */
    public static boolean verifySignature(String signingSecret, String timestamp, String body, String signature) {
        if (signingSecret == null || timestamp == null || signature == null) {
            return false;
        }
        return new SlackSignature.Verifier(new SlackSignature.Generator(signingSecret))
                .isValid(timestamp, body, signature);
    }

    // --- Inbound event parsing (SDK model) ---

    public record InboundMessage(String channelId, String userId, String text, String threadTs,
                                 java.util.List<SlackPendingFile> files) {}

    /**
     * Parse an Events API envelope into an {@link InboundMessage}, or null for
     * anything that isn't a plain user message. {@code botUserId} (the binding's
     * own bot, JCLAW-357) drops the bot's own messages in addition to the
     * {@code bot_id} guard. {@code url_verification} is handled by the controller.
     * The {@code file_share} subtype is admitted (it carries inbound files,
     * JCLAW-344); other subtypes (edits/joins/etc.) stay ignored pending
     * JCLAW-352/353.
     */
    public static InboundMessage parseEvent(JsonObject payload, String botUserId) {
        if (!"event_callback".equals(str(payload, "type"))) {
            return null;
        }
        var eventObj = payload.getAsJsonObject("event");
        if (eventObj == null || !"message".equals(str(eventObj, "type"))) {
            return null;
        }
        // Drop the bot's own messages (bot_id, or user == this bot). Most message
        // subtypes (edits, joins, bot_message, ...) are still ignored, but admit
        // `file_share` — it carries inbound files (JCLAW-344). Edit/delete subtypes
        // remain pending JCLAW-352/353.
        if (eventObj.has("bot_id")) {
            return null;
        }
        var subtype = str(eventObj, "subtype");
        if (!subtype.isBlank() && !"file_share".equals(subtype)) {
            return null;
        }
        var user = str(eventObj, "user");
        if (botUserId != null && !botUserId.isBlank() && botUserId.equals(user)) {
            return null;
        }
        var event = SLACK_GSON.fromJson(eventObj, MessageEvent.class);
        return new InboundMessage(
                event.getChannel(),
                event.getUser(),
                event.getText() != null ? event.getText() : "",
                event.getThreadTs(),
                parseFiles(eventObj));
    }

    /** Extract the event's {@code files[]} array into pending downloads (JCLAW-344).
     *  Empty when absent — the text-only path. Prefers {@code url_private_download}
     *  (direct bytes) over {@code url_private} (which serves an HTML page). */
    private static java.util.List<SlackPendingFile> parseFiles(JsonObject eventObj) {
        if (!eventObj.has(FILES_KEY) || !eventObj.get(FILES_KEY).isJsonArray()) {
            return java.util.List.of();
        }
        var arr = eventObj.getAsJsonArray(FILES_KEY);
        var out = new java.util.ArrayList<SlackPendingFile>(arr.size());
        for (var el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            var f = el.getAsJsonObject();
            var url = str(f, "url_private_download");
            if (url.isBlank()) {
                url = str(f, "url_private");
            }
            if (url.isBlank()) {
                continue; // nothing downloadable on this file object
            }
            long size = f.has("size") && !f.get("size").isJsonNull() ? f.get("size").getAsLong() : 0L;
            out.add(new SlackPendingFile(
                    str(f, "id"), url, str(f, "name"), str(f, "mimetype"), size, str(f, "subtype")));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
