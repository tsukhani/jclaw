package controllers;

import channels.WhatsAppChannel;
import channels.WhatsAppCloudApiParser;
import channels.WhatsAppInbound;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import models.WhatsAppTransport;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import utils.WebhookUtil;

import java.time.Instant;

/**
 * Cloud-API inbound webhook for WhatsApp (JCLAW-446). Per-binding routed: a single
 * endpoint serves every Cloud-API {@link WhatsAppBinding} on the instance, routing
 * each request to its binding by the {@code phone_number_id} in the payload (POST)
 * or the {@code verify_token} (GET hub-verification). Mirrors the per-binding shape
 * of {@link WebhookSlackController}/{@link WebhookTelegramController}.
 *
 * <p>The controller does no business logic: it verifies the request (HMAC for POST,
 * token match for GET), parses via {@link WhatsAppCloudApiParser}, records the 24h
 * customer-service window (JCLAW-447), and hands the normalized message to
 * {@link WhatsAppInbound#dispatchMessage} — the single inbound seam that owns
 * dedup, the access gate, media staging, and agent dispatch. It returns {@code ok()}
 * immediately (fast-ack) so Meta doesn't retry; dispatch runs off-thread inside the
 * seam.
 */
public class WebhookWhatsAppController extends Controller {

    private static final String CHANNEL_WHATSAPP = "whatsapp";
    private static final String CATEGORY_CHANNEL = "channel";

    /**
     * GET — Meta hub-verification challenge. Resolves the binding whose
     * {@code verifyToken} matches {@code hub.verify_token} and echoes
     * {@code hub.challenge}; 403 when no enabled binding owns the token (or the
     * mode isn't {@code subscribe}).
     */
    public static void verify(String hubMode, String hubVerifyToken, String hubChallenge) {
        // Play maps hub.mode → hubMode etc. via query params; fall back to the
        // dotted names that Play can't bind to a Java identifier.
        var mode = firstNonNull(params.get("hub.mode"), hubMode);
        var verifyToken = firstNonNull(params.get("hub.verify_token"), hubVerifyToken);
        var challenge = firstNonNull(params.get("hub.challenge"), hubChallenge);

        var binding = WhatsAppBinding.findByVerifyToken(verifyToken);
        if (!"subscribe".equals(mode) || binding == null) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP, "Webhook verification failed");
            forbidden();
        }

        EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP, "Webhook verified");
        renderText(challenge);
    }

    /**
     * POST — inbound message notifications. Routes to the binding by
     * {@code phone_number_id}, verifies the {@code X-Hub-Signature-256} HMAC against
     * that binding's {@code appSecret} (fail-closed per JCLAW-16 when the secret is
     * absent), parses, records the 24h window, dispatches, and fast-acks.
     */
    @SuppressWarnings("java:S2259")
    public static void webhook() {
        String rawBody;
        try {
            rawBody = WebhookUtil.readRawBody();
        } catch (Exception _) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP, "Failed to read request body");
            error();
            return; // javac definite-assignment: rawBody is unassigned on this catch path
        }

        JsonObject payload;
        try {
            payload = JsonParser.parseString(rawBody).getAsJsonObject();
        } catch (Exception _) {
            // Malformed JSON — ack so Meta doesn't retry a body we can't parse.
            ok();
            return;
        }

        // Route to the binding by the payload's phone_number_id.
        var phoneNumberId = WhatsAppCloudApiParser.extractPhoneNumberId(payload);
        var binding = WhatsAppBinding.findByPhoneNumberId(phoneNumberId);
        if (binding == null || !binding.enabled
                || binding.transport != WhatsAppTransport.CLOUD_API) {
            // Unknown / disabled / non-Cloud-API target: ack (no retry) but do nothing.
            ok();
            return;
        }

        // JCLAW-16: fail closed when this binding has no appSecret — an absent
        // secret must never become a signature-verification bypass.
        if (binding.appSecret == null || binding.appSecret.isBlank()) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_WHATSAPP,
                    "Webhook rejected: appSecret not configured for binding " + binding.id);
            unauthorized("Invalid signature");
        }

        var signatureHeader = Http.Request.current().headers.get("x-hub-signature-256");
        if (signatureHeader == null || !WhatsAppChannel.verifySignature(
                binding.appSecret, rawBody, signatureHeader.value())) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_WHATSAPP,
                    "Invalid webhook signature for binding " + binding.id);
            unauthorized("Invalid signature");
        }

        var msg = WhatsAppCloudApiParser.parse(payload);
        if (msg == null) {
            // Status update or unsupported type — nothing to dispatch.
            ok();
            return;
        }

        // JCLAW-447: record the 24h customer-service window on the request thread's
        // transaction (a tiny upsert; commits with the request). Reactions still
        // open the window — the user touched the conversation.
        WhatsAppConversationWindow.recordInbound(binding.id, msg.from(), Instant.now());

        WhatsAppInbound.dispatchMessage(binding, msg);
        ok();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
