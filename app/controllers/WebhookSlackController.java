package controllers;

import agents.AgentRunner;
import channels.SlackChannel;
import channels.SlackStreamingSink;
import com.google.gson.JsonParser;
import models.SlackBinding;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import services.Tx;
import utils.WebhookUtil;

/**
 * Slack Events API webhook. JCLAW-441: the URL carries the per-agent binding id
 * ({@code /api/webhooks/slack/{bindingId}}), so each bot has its own endpoint and
 * authenticates against its own signing secret. Routing is binding-first — the
 * binding's agent handles the message; there is no app-global config or
 * channel-id lookup in this path.
 */
public class WebhookSlackController extends Controller {

    private static final String CHANNEL_SLACK = "slack";
    private static final String INVALID_SIGNATURE = "Invalid signature";
    private static final String CATEGORY_CHANNEL = "channel";

    @SuppressWarnings("java:S2259")
    public static void webhook(Long bindingId) {
        // Resolve the binding from the URL first. Unknown id → 404; disabled → 403.
        // The id is not a secret (the signing-secret HMAC below is the auth), so a
        // distinct 404/403 here is fine and aids operator debugging.
        SlackBinding binding = SlackBinding.findById(bindingId);
        if (binding == null) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: unknown Slack binding %s".formatted(bindingId));
            notFound("Unknown Slack binding");
        }
        if (!binding.enabled) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: Slack binding %s is disabled".formatted(bindingId));
            forbidden("Binding disabled");
        }
        if (binding.signingSecret == null || binding.signingSecret.isBlank()) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, null, CHANNEL_SLACK,
                    "Webhook rejected: Slack binding %s has no signing secret".formatted(bindingId));
            unauthorized(INVALID_SIGNATURE);
        }

        // Read raw body for signature verification.
        String rawBody;
        try {
            rawBody = WebhookUtil.readRawBody();
        } catch (Exception _) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK, "Failed to read request body");
            error();
            return;  // javac definite-assignment: rawBody is unassigned on this catch path
        }

        // Verify signature against THIS binding's secret before any payload parsing
        // — url_verification challenges are signed by Slack too, so they run through
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

        var payload = JsonParser.parseString(rawBody).getAsJsonObject();

        // URL verification challenge (runs post-verification).
        if (payload.has("type") && "url_verification".equals(payload.get("type").getAsString())) {
            var challenge = payload.get("challenge").getAsString();
            response.contentType = "text/plain";
            renderText(challenge);
            return;
        }

        // Parse event, dropping the binding's own bot messages (JCLAW-357 self-loop
        // guard via the cached bot user id).
        var message = SlackChannel.parseEvent(payload, binding.botUserId);
        if (message == null) {
            ok(); // Non-message event, subtype, or the bot's own message
            return;
        }

        EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                "Message received from %s in %s".formatted(message.userId(), message.channelId()));

        // Process async. The bot token (immutable) crosses the thread boundary; the
        // lazy agent association is re-resolved inside a fresh tx on that thread.
        var botToken = binding.botToken;
        Thread.ofVirtual().name("webhook-slack").start(() -> processMessage(bindingId, botToken, message));

        ok();
    }

    private static void processMessage(Long bindingId, String botToken, SlackChannel.InboundMessage message) {
        try {
            // JCLAW-83: capture the inbound thread_ts so the reply lands in-thread
            // (null for a non-threaded message → posts at channel level).
            var threadTs = message.threadTs();
            // Binding-first dispatch: the bound agent handles the message. Re-resolve
            // it (and re-check enabled) inside a tx on this virtual thread.
            var agent = Tx.run(() -> {
                SlackBinding b = SlackBinding.findById(bindingId);
                return (b == null || !b.enabled) ? null : b.agent;
            });
            if (agent == null) {
                return; // binding deleted/disabled between accept and dispatch
            }
            // JCLAW-442: route through the shared higher-level entry (as Telegram does)
            // so slash commands + the conversation lifecycle are handled centrally and
            // future inbound attachments (JCLAW-344) ride the same overload. The factory
            // owns the per-binding bot token + channel/thread; processInboundForAgentStreaming
            // invokes startTypingHeartbeat (the "is typing…" status) before the LLM. The
            // sink streams natively in assistant threads, else posts a formatted reply.
            AgentRunner.processInboundForAgentStreaming(
                    agent, CHANNEL_SLACK, message.channelId(), message.text(),
                    _ -> new SlackStreamingSink(message.channelId(), threadTs, message.userId(), botToken),
                    java.util.List.of(), null);
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }
}
