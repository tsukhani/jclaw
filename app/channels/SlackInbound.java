package channels;

import agents.AgentRunner;
import com.google.gson.JsonObject;
import models.SlackBinding;
import services.EventLogger;
import services.Tx;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared Slack inbound dispatch (JCLAW-351), independent of how the event arrived.
 * Both transports feed it the same Slack JSON:
 *
 * <ul>
 *   <li>The Events API webhook ({@link controllers.WebhookSlackController}) verifies
 *       the HMAC signature, then hands off here.</li>
 *   <li>Socket Mode ({@link SlackSocketModeRunner}) receives the same envelopes over a
 *       WebSocket (authenticated by the app token) and hands off here.</li>
 * </ul>
 *
 * <p>This keeps parse + access gate + agent dispatch (events) and approval resolution
 * (interactive) in one place, so the two transports can never drift.
 */
public final class SlackInbound {

    private SlackInbound() {}

    static final String CHANNEL_SLACK = "slack";
    static final String CATEGORY_CHANNEL = "channel";
    /** JCLAW-344: cap inbound files per message (matches OpenClaw's MAX_SLACK_MEDIA_FILES). */
    private static final int MAX_INBOUND_FILES = 8;

    /**
     * Handle one Events API {@code event_callback} payload: parse it, apply the
     * JCLAW-354 access gate, and dispatch the bound agent off-thread. Bot/own/subtype
     * messages and gate-rejected messages are silently dropped. {@code binding}'s simple
     * columns are read directly (works on a detached entity from the socket thread); the
     * main-agent flag is resolved in its own tx to avoid touching the lazy association.
     */
    public static void dispatchEvent(SlackBinding binding, JsonObject eventCallbackPayload) {
        // JCLAW-357: drop redelivered events (Slack retries when it doesn't get a fast
        // ack) so the agent processes each message exactly once — across either transport.
        var dedupKey = dedupKey(eventCallbackPayload);
        if (!InboundEventDedup.firstSeen(dedupKey)) {
            EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "Duplicate Slack event %s dropped".formatted(dedupKey));
            return;
        }
        var message = SlackChannel.parseEvent(eventCallbackPayload, binding.botUserId);
        if (message == null) {
            return; // non-message event, subtype, or the bot's own message
        }
        var bindingId = binding.id;
        var agentIsMain = Boolean.TRUE.equals(Tx.run(() -> {
            SlackBinding b = SlackBinding.findById(bindingId);
            return b != null && b.agent != null && b.agent.isMain();
        }));
        if (!SlackAccessPolicy.isAllowed(binding.ownerUserId, message.userId(),
                message.channelType(), message.botMentioned(), agentIsMain)) {
            EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "Message from %s in %s (%s) dropped by access policy".formatted(
                            message.userId(), message.channelId(), message.channelType()));
            return;
        }
        EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                "Message received from %s in %s".formatted(message.userId(), message.channelId()));

        // Process async. The bot token (immutable) crosses the thread boundary; the
        // lazy agent association is re-resolved inside a fresh tx on that thread.
        var botToken = binding.botToken;
        Thread.ofVirtual().name("slack-inbound").start(() -> processMessage(bindingId, botToken, message));
    }

    /**
     * Handle one {@code block_actions} interactivity payload: resolve every exec-approval
     * button tap (JCLAW-350) against the pending-approval registry. The resolution is
     * keyed by the action's approval id and gated on the issuing user, so no binding
     * context is needed here.
     */
    public static void dispatchInteractive(JsonObject blockActionsPayload) {
        if (!blockActionsPayload.has("type")
                || !"block_actions".equals(blockActionsPayload.get("type").getAsString())) {
            return; // only block_actions carries button taps
        }
        var fromUserId = blockActionsPayload.has("user")
                && blockActionsPayload.getAsJsonObject("user").has("id")
                ? blockActionsPayload.getAsJsonObject("user").get("id").getAsString() : null;
        if (!blockActionsPayload.has("actions") || !blockActionsPayload.get("actions").isJsonArray()) {
            return;
        }
        for (var el : blockActionsPayload.getAsJsonArray("actions")) {
            if (!el.isJsonObject()) continue;
            JsonObject action = el.getAsJsonObject();
            if (!action.has("action_id")) continue;
            var actionId = action.get("action_id").getAsString();
            SlackApprovalCallback.parse(actionId).ifPresent(p ->
                    Thread.ofVirtual().name("slack-approval").start(() ->
                            SlackApprovalService.resolve(p.approvalId(), p.decision(), fromUserId)));
        }
    }

    /**
     * Dedup key for an {@code event_callback}: the message's identity {@code channel:ts}
     * (its own ts, stable across redeliveries), falling back to the envelope's
     * {@code event_id} (carried verbatim on Events API retries) when the inner event has no
     * channel/ts. Null when neither is present — {@link InboundEventDedup#firstSeen} then processes.
     */
    private static String dedupKey(JsonObject eventCallbackPayload) {
        if (eventCallbackPayload.has("event") && eventCallbackPayload.get("event").isJsonObject()) {
            var event = eventCallbackPayload.getAsJsonObject("event");
            if (event.has("channel") && event.has("ts")) {
                return event.get("channel").getAsString() + ":" + event.get("ts").getAsString();
            }
        }
        return eventCallbackPayload.has("event_id") ? eventCallbackPayload.get("event_id").getAsString() : null;
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
            // JCLAW-349: Slack reserves / for native slash commands, which it refuses
            // to deliver inside a thread (the Assistant pane is a thread). So lifecycle
            // commands use a ! prefix in messages; rewrite !cmd → /cmd here so the
            // shared slash interception below handles it and the canned reply lands
            // in-thread via the sink.
            var text = slash.Commands.rewriteBangCommand(message.text());
            // JCLAW-442: route through the shared higher-level entry (as Telegram does)
            // so slash commands + the conversation lifecycle are handled centrally. The
            // factory owns the per-binding bot token + channel/thread;
            // processInboundForAgentStreaming invokes startTypingHeartbeat before the
            // LLM. The sink streams natively in assistant threads, else posts a reply.
            AgentRunner.processInboundForAgentStreaming(
                    agent, CHANNEL_SLACK, message.channelId(), text,
                    _ -> new SlackStreamingSink(message.channelId(), threadTs, message.userId(), botToken, agent.name),
                    attachments, null);
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }

    /**
     * JCLAW-344: download up to {@link #MAX_INBOUND_FILES} inbound Slack files into the
     * agent's staging dir, returning the staged {@link services.AttachmentService.Input}s
     * the runner finalizes. Each failure (too large / unreadable / non-Slack host) is
     * logged and counted; a single note is sent to the channel if any were rejected, so
     * the user isn't left wondering why an attachment was ignored.
     */
    private static List<services.AttachmentService.Input> downloadFiles(
            String botToken, SlackChannel.InboundMessage message, String agentName) {
        var files = message.files();
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        var inputs = new ArrayList<services.AttachmentService.Input>();
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
