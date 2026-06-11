package controllers;

import agents.AgentRunner;
import channels.SlackChannel;
import channels.SlackFileDownloader;
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
    /** JCLAW-344: cap inbound files per message (matches OpenClaw's MAX_SLACK_MEDIA_FILES). */
    private static final int MAX_INBOUND_FILES = 8;

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
            // JCLAW-344: download inbound files (files:read) into the agent's staging
            // dir so the runner gets a vision / transcription turn. Rejected files
            // (too large / unreadable) get a user-visible note; the rest proceed.
            var attachments = downloadFiles(botToken, message, agent.name);
            // JCLAW-442: route through the shared higher-level entry (as Telegram does)
            // so slash commands + the conversation lifecycle are handled centrally. The
            // factory owns the per-binding bot token + channel/thread;
            // processInboundForAgentStreaming invokes startTypingHeartbeat before the
            // LLM. The sink streams natively in assistant threads, else posts a reply.
            AgentRunner.processInboundForAgentStreaming(
                    agent, CHANNEL_SLACK, message.channelId(), message.text(),
                    _ -> new SlackStreamingSink(message.channelId(), threadTs, message.userId(), botToken),
                    attachments, null);
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }

    /**
     * JCLAW-344: download up to {@link #MAX_INBOUND_FILES} inbound Slack files into
     * the agent's staging dir, returning the staged {@link services.AttachmentService.Input}s
     * the runner finalizes. Each failure (too large / unreadable / non-Slack host)
     * is logged and counted; a single note is sent to the channel if any were
     * rejected, so the user isn't left wondering why an attachment was ignored.
     */
    private static java.util.List<services.AttachmentService.Input> downloadFiles(
            String botToken, SlackChannel.InboundMessage message, String agentName) {
        var files = message.files();
        if (files == null || files.isEmpty()) {
            return java.util.List.of();
        }
        var inputs = new java.util.ArrayList<services.AttachmentService.Input>();
        int rejected = 0;
        for (int i = 0; i < files.size() && i < MAX_INBOUND_FILES; i++) {
            var file = files.get(i);
            var result = SlackFileDownloader.download(botToken, file, agentName);
            if (result instanceof SlackFileDownloader.Ok(var input)) {
                inputs.add(input);
            } else {
                rejected++;
                String reason = result instanceof SlackFileDownloader.SizeExceeded
                        ? "exceeds the 20 MB limit"
                        : "could not be downloaded";
                EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                        "Slack inbound file '%s' %s".formatted(file.name(), reason));
            }
        }
        if (rejected > 0) {
            SlackChannel.sendMessage(message.channelId(),
                    "⚠️ %d attachment(s) couldn't be processed (too large or unreadable).".formatted(rejected),
                    message.threadTs(), botToken);
        }
        return inputs;
    }
}
