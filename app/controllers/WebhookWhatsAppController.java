package controllers;

import agents.AgentRunner;
import channels.WhatsAppChannel;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import utils.WebhookUtil;

public class WebhookWhatsAppController extends Controller {

    /**
     * GET — Hub verification challenge from Meta.
     */
    public static void verify(String hub_mode, String hub_verify_token, String hub_challenge) {
        // Play maps hub.mode → hub_mode etc. via query params
        var mode = params.get("hub.mode");
        var verifyToken = params.get("hub.verify_token");
        var challenge = params.get("hub.challenge");

        if (mode == null) { mode = hub_mode; }
        if (verifyToken == null) { verifyToken = hub_verify_token; }
        if (challenge == null) { challenge = hub_challenge; }

        var config = WhatsAppChannel.WhatsAppConfig.load();
        if (!"subscribe".equals(mode) || config == null
                || config.verifyToken() == null || !config.verifyToken().equals(verifyToken)) {
            EventLogger.warn("channel", null, "whatsapp", "Webhook verification failed");
            forbidden();
            return;
        }

        EventLogger.info("channel", null, "whatsapp", "Webhook verified");
        renderText(challenge);
    }

    /**
     * POST — Inbound messages from Meta.
     */
    public static void webhook() {
        var config = WhatsAppChannel.WhatsAppConfig.load();

        // JCLAW-16: reject before any body read / agent invocation when the
        // app is unconfigured — an absent appSecret previously became a silent
        // bypass path, which is exactly what FR33/NFR12 forbids.
        if (config == null || config.appSecret() == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, "whatsapp",
                    "Webhook rejected: WhatsApp appSecret not configured");
            unauthorized("Invalid signature");
            return;
        }

        String rawBody;
        try {
            rawBody = WebhookUtil.readRawBody();
        } catch (Exception e) {
            EventLogger.error("channel", null, "whatsapp", "Failed to read request body");
            error();
            return;
        }

        var signatureHeader = Http.Request.current().headers.get("x-hub-signature-256");
        if (signatureHeader == null || !WhatsAppChannel.verifySignature(
                config.appSecret(), rawBody, signatureHeader.value())) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, "whatsapp",
                    "Invalid webhook signature");
            unauthorized("Invalid signature");
            return;
        }

        var payload = JsonParser.parseString(rawBody).getAsJsonObject();
        var message = WhatsAppChannel.parseWebhook(payload);

        if (message == null) {
            ok(); // Status update or non-text message
            return;
        }

        EventLogger.info("channel", null, "whatsapp",
                "Message received from %s".formatted(message.from()));

        // Mark as read and process async
        var finalConfig = config;
        Thread.ofVirtual().start(() -> {
            if (finalConfig != null) {
                WhatsAppChannel.markAsRead(finalConfig, message.messageId());
            }
            processMessage(finalConfig, message);
        });

        ok();
    }

    private static void processMessage(WhatsAppChannel.WhatsAppConfig config,
                                        WhatsAppChannel.InboundMessage message) {
        try {
            AgentRunner.processWebhookMessage("whatsapp", message.from(), message.text(),
                    (peerId, response) -> WhatsAppChannel.sendMessage(peerId, response),
                    peerId -> WhatsAppChannel.sendMessage(peerId, "No agent configured for this number."));
        } catch (Exception e) {
            EventLogger.error("channel", null, "whatsapp",
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }
}
