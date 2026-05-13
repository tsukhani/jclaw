package controllers;

import agents.AgentRunner;
import channels.SlackChannel;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import utils.WebhookUtil;

public class WebhookSlackController extends Controller {

    public static void webhook() {
        // JCLAW-16: signature verification happens BEFORE body parsing /
        // challenge handling so an unconfigured Slack app cannot be used as a
        // bypass path. Previously `config == null` returned 200 silently.
        var config = SlackChannel.SlackConfig.load();
        if (config == null || config.signingSecret() == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, "slack",
                    "Webhook rejected: Slack signingSecret not configured");
            unauthorized("Invalid signature");
            return;
        }

        // Read raw body for signature verification
        String rawBody;
        try {
            rawBody = WebhookUtil.readRawBody();
        } catch (Exception e) {
            EventLogger.error("channel", null, "slack", "Failed to read request body");
            error();
            return;
        }

        // Verify signature before any payload parsing — url_verification
        // challenges are also signed by Slack, so they run through the same
        // gate as normal events.
        var timestamp = Http.Request.current().headers.get("x-slack-request-timestamp");
        var signature = Http.Request.current().headers.get("x-slack-signature");

        if (timestamp == null || signature == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, "slack",
                    "Missing signature headers");
            unauthorized("Missing signature");
            return;
        }

        if (!SlackChannel.verifySignature(config.signingSecret(),
                timestamp.value(), rawBody, signature.value())) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, "slack",
                    "Invalid signature");
            unauthorized("Invalid signature");
            return;
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

        EventLogger.info("channel", null, "slack",
                "Message received from %s in %s".formatted(message.userId(), message.channelId()));

        // Process async
        Thread.ofVirtual().name("webhook-slack").start(() -> processMessage(message));

        ok();
    }

    private static void processMessage(SlackChannel.InboundMessage message) {
        try {
            AgentRunner.processWebhookMessage("slack", message.channelId(), message.text(),
                    (peerId, response) -> SlackChannel.sendMessage(peerId, response),
                    peerId -> SlackChannel.sendMessage(peerId, "No agent configured for this channel."));
        } catch (Exception e) {
            EventLogger.error("channel", null, "slack",
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }
}
