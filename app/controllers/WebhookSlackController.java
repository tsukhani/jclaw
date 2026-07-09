package controllers;

import channels.SlackChannel;
import channels.SlackInbound;
import com.google.gson.JsonParser;
import models.SlackBinding;
import play.mvc.Controller;
import play.mvc.Http;
import services.BindingService;
import services.EventLogger;
import utils.ApiResponses;
import utils.WebhookUtil;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Slack Events API webhook + interactivity endpoints. JCLAW-441: the URL carries the
 * per-agent binding id, so each bot has its own endpoint and authenticates against its
 * own signing secret. These endpoints own the HTTP-transport specifics (HMAC over the
 * raw body, the {@code url_verification} challenge, the 200 ack); the actual parse +
 * access gate + dispatch live in {@link SlackInbound}, shared with the Socket Mode
 * transport (JCLAW-351).
 */
public class WebhookSlackController extends Controller {

    private static final String CHANNEL_SLACK = "slack";
    private static final String INVALID_SIGNATURE = "Invalid signature";
    private static final String CATEGORY_CHANNEL = "channel";

    public static void webhook(Long bindingId) {
        var verified = resolveAndVerify(bindingId);
        var binding = verified.binding();
        var payload = JsonParser.parseString(verified.rawBody()).getAsJsonObject();

        // URL verification challenge (runs post-verification).
        if (payload.has("type") && "url_verification".equals(payload.get("type").getAsString())) {
            var challenge = payload.get("challenge").getAsString();
            response.contentType = "text/plain";
            renderText(challenge);
            return;
        }

        SlackInbound.dispatchEvent(binding, payload);
        ok();
    }

    /**
     * Slack interactivity endpoint (JCLAW-350): receives {@code block_actions} when the
     * bound owner taps an exec-approval button. Same per-binding HMAC gate as
     * {@link #webhook} — the signature is over the raw {@code application/x-www-form-urlencoded}
     * body ({@code payload=<urlencoded-json>}), so we verify the raw body first, then
     * extract and decode the {@code payload} field. Resolution runs off-thread so the
     * 200 ack lands inside Slack's 3 s window.
     */
    public static void interactive(Long bindingId) {
        var verified = resolveAndVerify(bindingId);
        var rawBody = verified.rawBody();

        // Interactivity bodies are form-encoded with a single `payload` field. The
        // signature (already verified) covers this raw form string, not the JSON.
        if (!rawBody.startsWith("payload=")) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Missing payload");
        }
        var payloadJson = URLDecoder.decode(rawBody.substring("payload=".length()), StandardCharsets.UTF_8);
        var payload = JsonParser.parseString(payloadJson).getAsJsonObject();

        SlackInbound.dispatchInteractive(payload);
        ok();
    }

    /**
     * Shared per-binding gate for the Slack POST endpoints: resolve the binding
     * (404 unknown / 403 disabled / 401 no-secret), read the raw body, and verify
     * the Slack request signature against this binding's secret. Returns only on
     * success; every rejection path halts via a Play {@code Result} exception.
     */
    @SuppressWarnings("java:S2259") // ApiResponses.error / unauthorized halt; binding is non-null past each guard
    private static Verified resolveAndVerify(Long bindingId) {
        SlackBinding binding = BindingService.findSlackBindingById(bindingId);
        if (binding == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: unknown Slack binding %s".formatted(bindingId));
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "Unknown Slack binding");
        }
        if (!binding.enabled) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: Slack binding %s is disabled".formatted(bindingId));
            ApiResponses.error(403, "forbidden", "Binding disabled");
        }
        if (binding.signingSecret == null || binding.signingSecret.isBlank()) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: Slack binding %s has no signing secret".formatted(bindingId));
            unauthorized(INVALID_SIGNATURE);
        }

        var rawBody = readRawBodyOrHalt();

        // Verify against THIS binding's secret before any payload parsing —
        // url_verification challenges are signed by Slack too, so they run through
        // the same gate as normal events.
        var timestamp = Http.Request.current().headers.get("x-slack-request-timestamp");
        var signature = Http.Request.current().headers.get("x-slack-signature");
        if (timestamp == null || signature == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Missing signature headers");
            unauthorized("Missing signature");
        }
        if (!SlackChannel.verifySignature(binding.signingSecret,
                timestamp.value(), rawBody, signature.value())) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    INVALID_SIGNATURE);
            unauthorized(INVALID_SIGNATURE);
        }
        return new Verified(binding, rawBody);
    }

    private static String readRawBodyOrHalt() {
        try {
            return WebhookUtil.readRawBody();
        } catch (Exception _) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK, "Failed to read request body");
            error();
            return null; // unreachable: error() halts the request; satisfies javac definite-assignment
        }
    }

    /** A binding whose request signature has passed, paired with the verified raw body. */
    private record Verified(SlackBinding binding, String rawBody) {}
}
