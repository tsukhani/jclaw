package controllers;

import agents.AgentRouter;
import agents.AgentRunner;
import channels.SlackChannel;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConversationService;
import services.EventLogger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class WebhookSlackController extends Controller {

    public static void webhook() {
        var config = SlackChannel.SlackConfig.load();

        // Read raw body for signature verification
        String rawBody;
        try {
            rawBody = new String(Http.Request.current().body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EventLogger.error("channel", null, "slack", "Failed to read request body");
            error();
            return;
        }

        var payload = JsonParser.parseString(rawBody).getAsJsonObject();

        // URL verification challenge
        if (payload.has("type") && "url_verification".equals(payload.get("type").getAsString())) {
            var challenge = payload.get("challenge").getAsString();
            response.contentType = "text/plain";
            renderText(challenge);
            return;
        }

        // Verify signature
        if (config != null) {
            var timestamp = Http.Request.current().headers.get("x-slack-request-timestamp");
            var signature = Http.Request.current().headers.get("x-slack-signature");

            if (timestamp == null || signature == null) {
                EventLogger.warn("channel", null, "slack", "Missing signature headers");
                unauthorized("Missing signature");
                return;
            }

            if (!SlackChannel.verifySignature(config.signingSecret(),
                    timestamp.value(), rawBody, signature.value())) {
                EventLogger.warn("channel", null, "slack", "Invalid signature");
                unauthorized("Invalid signature");
                return;
            }
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
        Thread.ofVirtual().start(() -> processMessage(config, message));

        ok();
    }

    private static void processMessage(SlackChannel.SlackConfig config,
                                        SlackChannel.InboundMessage message) {
        try {
            var route = services.Tx.run(() -> AgentRouter.resolve("slack", message.channelId()));
            if (route == null) {
                SlackChannel.sendMessage(config, message.channelId(),
                        "No agent configured for this channel.");
                return;
            }

            var result = services.Tx.run(() -> {
                var conversation = ConversationService.findOrCreate(
                        route.agent(), "slack", message.channelId());
                return AgentRunner.run(route.agent(), conversation, message.text());
            });

            SlackChannel.sendMessage(config, message.channelId(), result.response());

        } catch (Exception e) {
            EventLogger.error("channel", null, "slack",
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }
}
