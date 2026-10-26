package controllers;

import channels.InboundEventDedup;
import channels.WhatsAppChannel;
import channels.WhatsAppCloudApiParser;
import channels.WhatsAppDeliveryStatus;
import channels.WhatsAppInbound;
import channels.WhatsAppInboundMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.WhatsAppAcceptedMessage;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import models.WhatsAppTransport;
import org.jspecify.annotations.Nullable;
import play.db.jpa.NoTransaction;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import services.Tx;
import utils.AppClock;
import utils.WebhookUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;


/**
 * Cloud-API inbound webhook for WhatsApp (JCLAW-446). Per-binding routed: a single
 * endpoint serves every Cloud-API {@link WhatsAppBinding} on the instance, routing
 * each message to its binding by its {@code phone_number_id} (POST) or each request
 * by the {@code verify_token} (GET hub-verification). Mirrors the per-binding shape
 * of {@link WebhookSlackController}/{@link WebhookTelegramController}.
 *
 * <p>The controller does no business logic: it verifies the request (HMAC for POST,
 * token match for GET), parses via {@link WhatsAppCloudApiParser}, records the 24h
 * customer-service window (JCLAW-447), and hands each normalized message to
 * {@link WhatsAppInbound#dispatchMessage} — the single inbound seam that owns
 * dedup, the access gate, media staging, and agent dispatch. It returns {@code ok()}
 * immediately (fast-ack) so Meta doesn't retry; dispatch runs off-thread inside the
 * seam.
 */
public class WebhookWhatsAppController extends Controller {

    private static final String CHANNEL_WHATSAPP = "whatsapp";
    private static final String CATEGORY_CHANNEL = "channel";
    /** JCLAW-783: config prefix for this channel's ingress-gate limits (mirrors telegram.webhook.*). */
    private static final String CFG_PREFIX = "whatsapp.webhook";

    /**
     * GET — Meta hub-verification challenge. Resolves the binding whose
     * {@code verifyToken} matches {@code hub.verify_token} and echoes
     * {@code hub.challenge}; 403 when no enabled binding owns the token (or the
     * mode isn't {@code subscribe}).
     */
    @AgentAccess(OPERATOR_ONLY)
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
     * POST — inbound message notifications. Routes to the binding by the first
     * {@code phone_number_id}, verifies the {@code X-Hub-Signature-256} HMAC against
     * that binding's {@code appSecret} (fail-closed per JCLAW-16 when the secret is
     * absent), and parses. Each message is windowed and dispatched under the binding that
     * owns its number, provided that binding's secret also verifies the signature; then the
     * request is fast-acked. Runs outside a request transaction, so each message's id claim
     * commits on its own (JCLAW-1520).
     */
    @SuppressWarnings("java:S2259")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "inbound provider callback, authenticated by Meta's X-Hub-Signature-256")
    @NoTransaction
    public static void webhook() {
        // JCLAW-783: pre-auth ingress gate, keyed on the source IP (the routing id
        // is only in the parsed body, so it can't key the limiter pre-parse).
        // Rate-limit + Content-Length cap run BEFORE readRawBody /
        // JsonParser.parseString / findByPhoneNumberId, so a flood or oversized POST
        // is rejected (429 / 413) before spending any of that work.
        var clientIp = WebhookIngressGate.resolveClientIp(CFG_PREFIX);
        long maxBodyBytes = WebhookIngressGate.enforcePreAuth(CHANNEL_WHATSAPP, clientIp, CFG_PREFIX, clientIp);

        String rawBody;
        try {
            rawBody = WebhookUtil.readRawBody();
        } catch (Exception _) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP, "Failed to read request body");
            error();
            return; // javac definite-assignment: rawBody is unassigned on this catch path
        }
        // JCLAW-783: read-length backstop for a chunked / lying Content-Length.
        WebhookIngressGate.enforceReadLength(CHANNEL_WHATSAPP, clientIp, CFG_PREFIX, clientIp, rawBody, maxBodyBytes);

        JsonObject payload;
        try {
            payload = JsonParser.parseString(rawBody).getAsJsonObject();
        } catch (Exception _) {
            // Malformed JSON — ack so Meta doesn't retry a body we can't parse.
            ok();
            return;
        }

        var phoneNumberId = WhatsAppCloudApiParser.extractPhoneNumberId(payload);
        var binding = Tx.run(() -> WhatsAppBinding.findByPhoneNumberId(phoneNumberId));
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
            return;
        }

        for (var status : WhatsAppCloudApiParser.parseStatuses(payload)) {
            if (status.failed()
                    && InboundEventDedup.firstSeen("wa-status:" + status.messageId() + ":failed")) {
                recordFailedStatus(binding, status);
            }
        }

        var now = AppClock.now();
        var signature = signatureHeader.value();
        var owners = new HashMap<String, Optional<WhatsAppBinding>>();
        owners.put(binding.phoneNumberId, Optional.of(binding));
        var accepted = new ArrayList<Accepted>();
        for (var received : WhatsAppCloudApiParser.parse(payload)) {
            var msg = received.message();
            var number = msg.phoneNumberId();
            var owner = number == null ? Optional.<WhatsAppBinding>empty()
                    : owners.computeIfAbsent(number, n -> Optional.ofNullable(signedOwner(n, rawBody, signature)));
            if (owner.isEmpty()) {
                // Meta posts all of an app's numbers here; only a binding whose secret signed the body may take one.
                EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                        "WhatsApp message %s for phone number %s skipped in a webhook for binding %d"
                                .formatted(msg.messageId(), number, binding.id));
                continue;
            }
            var target = owner.get();
            if (Boolean.TRUE.equals(Tx.run(() -> WhatsAppAcceptedMessage.isRecorded(msg.messageId())))) {
                EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                        "Duplicate WhatsApp event %s dropped".formatted(msg.messageId()));
                continue;
            }
            // JCLAW-784 (VULN-012): the HMAC covers only the body, so an unseen id is accepted only within
            // Meta's redelivery window. A legitimate message always carries a timestamp; a missing one fails closed.
            var sentAt = received.sentAt();
            if (sentAt == null || !WhatsAppChannel.isFreshTimestamp(sentAt, now)) {
                EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_WHATSAPP,
                        "Webhook rejected: stale/replayed timestamp for binding " + target.id);
                unauthorized("Stale request");
                return;
            }
            var ageSeconds = now.getEpochSecond() - sentAt.getEpochSecond();
            if (ageSeconds > WhatsAppChannel.DELAYED_AFTER_SECONDS) {
                EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                        "Delayed WhatsApp message %s accepted %d s after it was sent"
                                .formatted(msg.messageId(), ageSeconds));
            }
            // Meta's 24h window runs from the customer's send, so a late redelivery must not reopen it at receipt.
            accepted.add(new Accepted(target, msg, sentAt.isAfter(now) ? now : sentAt));
        }

        for (var a : accepted) {
            // JCLAW-447: reactions still open the 24h window — the user touched the conversation.
            Tx.run(() -> WhatsAppConversationWindow.recordInbound(a.binding().id, a.message().from(), a.sentAt()));
            WhatsAppInbound.dispatchMessage(a.binding(), a.message());
        }
        ok();
    }

    private record Accepted(WhatsAppBinding binding, WhatsAppInboundMessage message, Instant sentAt) {}

    /** The enabled Cloud-API binding for {@code phoneNumberId} whose app secret signed {@code rawBody}, else null. */
    private static @Nullable WhatsAppBinding signedOwner(String phoneNumberId, String rawBody, String signature) {
        var b = Tx.run(() -> WhatsAppBinding.findByPhoneNumberId(phoneNumberId));
        if (b == null || !b.enabled || b.transport != WhatsAppTransport.CLOUD_API
                || b.appSecret == null || b.appSecret.isBlank()) {
            return null;
        }
        return WhatsAppChannel.verifySignature(b.appSecret, rawBody, signature) ? b : null;
    }

    /** JCLAW-1411: Meta could not deliver a reply; statuses touch no conversation state. */
    private static void recordFailedStatus(WhatsAppBinding binding, WhatsAppDeliveryStatus status) {
        var detail = "no error detail";
        if (status.errorCode() != null || status.errorTitle() != null || status.errorDetails() != null) {
            detail = "Meta error %s: %s%s".formatted(
                    status.errorCode() != null ? status.errorCode() : "(no code)",
                    status.errorTitle() != null ? status.errorTitle() : "(no title)",
                    status.errorDetails() != null ? " — " + status.errorDetails() : "");
        }
        EventLogger.warn(CATEGORY_CHANNEL, String.valueOf(binding.agent.id), CHANNEL_WHATSAPP,
                "WhatsApp reply to %s was not delivered (%s)".formatted(
                        status.recipientId() != null ? status.recipientId() : "unknown recipient", detail));
        Tx.run(() -> WhatsAppBinding.recordDeliveryFailure(binding.id,
                status.timestamp() != null ? status.timestamp() : AppClock.now(),
                status.errorCode(), status.errorTitle()));
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
