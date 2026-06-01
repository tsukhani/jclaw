package controllers;

import agents.AgentRouter;
import agents.AgentRunner;
import channels.SlackChannel;
import channels.SlackStreamingSink;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import utils.WebhookUtil;

import java.util.concurrent.atomic.AtomicBoolean;

public class WebhookSlackController extends Controller {

    private static final String CHANNEL_SLACK = "slack";
    private static final String INVALID_SIGNATURE = "Invalid signature";
    private static final String CATEGORY_CHANNEL = "channel";

    @SuppressWarnings("java:S2259")
    public static void webhook() {
        // JCLAW-16: signature verification happens BEFORE body parsing /
        // challenge handling so an unconfigured Slack app cannot be used as a
        // bypass path. Previously `config == null` returned 200 silently.
        var config = SlackChannel.SlackConfig.load();
        if (config == null || config.signingSecret() == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: Slack signingSecret not configured");
            unauthorized(INVALID_SIGNATURE);
        }

        // Read raw body for signature verification
        String rawBody;
        try {
            rawBody = WebhookUtil.readRawBody();
        } catch (Exception _) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK, "Failed to read request body");
            error();
            return;  // javac definite-assignment: rawBody is unassigned on this catch path
        }

        // Verify signature before any payload parsing — url_verification
        // challenges are also signed by Slack, so they run through the same
        // gate as normal events.
        var timestamp = Http.Request.current().headers.get("x-slack-request-timestamp");
        var signature = Http.Request.current().headers.get("x-slack-signature");

        if (timestamp == null || signature == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Missing signature headers");
            unauthorized("Missing signature");
        }

        if (!SlackChannel.verifySignature(config.signingSecret(),
                timestamp.value(), rawBody, signature.value())) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    INVALID_SIGNATURE);
            unauthorized(INVALID_SIGNATURE);
        }

        var payload = JsonParser.parseString(rawBody).getAsJsonObject();

        // URL verification challenge (runs post-verification)
        if (payload.has("type") && "url_verification".equals(payload.get("type").getAsString())) {
            var challenge = payload.get("challenge").getAsString();
            response.contentType = "text/plain";
            renderText(challenge);
            return;
        }

        // Parse event
        var message = SlackChannel.parseEvent(payload);
        if (message == null) {
            ok(); // Non-message event or bot message
            return;
        }

        EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                "Message received from %s in %s".formatted(message.userId(), message.channelId()));

        // Process async
        Thread.ofVirtual().name("webhook-slack").start(() -> processMessage(message));

        ok();
    }

    private static void processMessage(SlackChannel.InboundMessage message) {
        long acceptedAtNs = System.nanoTime();
        try {
            // JCLAW-83: capture the inbound thread_ts so the reply lands in-thread
            // (null for a non-threaded message → posts at channel level).
            var threadTs = message.threadTs();
            var route = Tx.run(() -> AgentRouter.resolve(CHANNEL_SLACK, message.channelId()));
            if (route == null) {
                SlackChannel.sendMessage(message.channelId(), "No agent configured for this channel.", null);
                return;
            }
            var conversation = Tx.run(() ->
                    ConversationService.findOrCreate(route.agent(), CHANNEL_SLACK, message.channelId()));
            // JCLAW-341: native Slack streaming (chat.startStream/appendStream/
            // stopStream) → live "is typing…" + progressive text, no (edited) tag.
            // Falls back to a single formatted post off-thread. recipientUserId is
            // required by the streaming API for DMs.
            var sink = new SlackStreamingSink(message.channelId(), threadTs, message.userId());
            sink.begin();
            AgentRunner.runStreaming(route.agent(), conversation.id, CHANNEL_SLACK, message.channelId(),
                    message.text(), new AtomicBoolean(false),
                    new AgentRunner.StreamingCallbacks(
                            conv -> { }, sink::update, reasoning -> { }, status -> { },
                            tool -> { }, sink::seal, sink::errorFallback, () -> { }),
                    acceptedAtNs);
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }
}
