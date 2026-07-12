package agents;

import channels.ChannelRegistry;
import channels.ChannelStreamingSink;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import services.AttachmentService;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * JCLAW-678: inbound-message orchestration for external channels — webhook
 * dispatch, binding-first Telegram dispatch (sync + streaming), and best-effort
 * outbound delivery back through the originating channel. Extracted from
 * {@link AgentRunner}; the {@code AgentRunner.process*} / {@code dispatchToChannel}
 * public/package delegators forward here so the channel and controller call sites
 * are unchanged.
 */
final class ChannelInboundDispatcher {

    private ChannelInboundDispatcher() {}

    /**
     * Shared webhook message handler: resolve agent route, find/create conversation,
     * run the agent synchronously, and send the response via the provided sender.
     * Used by Slack, Telegram, and WhatsApp webhook controllers.
     *
     * @param channelType  channel identifier ("slack", "telegram", "whatsapp")
     * @param peerId       channel-specific peer/chat ID
     * @param text         inbound message text
     * @param sendResponse callback to deliver the response (receives peerId and response text)
     * @param sendNoRoute  callback when no agent is routed (receives peerId); may be null to silently drop
     */
    static void processWebhookMessage(String channelType, String peerId, String text,
                                      BiConsumer<String, String> sendResponse,
                                      Consumer<String> sendNoRoute) {
        var route = Tx.run(() -> AgentRouter.resolve(channelType, peerId));
        if (route == null) {
            if (sendNoRoute != null) sendNoRoute.accept(peerId);
            return;
        }

        var conversation = Tx.run(() ->
                ConversationService.findOrCreate(route.agent(), channelType, peerId));
        var result = AgentRunner.run(route.agent(), conversation, text);
        sendResponse.accept(peerId, result.response());
    }

    /**
     * Binding-first inbound dispatch used by the Telegram channel. The caller has
     * already resolved (bot token → binding → agent) and verified the sender, so
     * we skip {@link AgentRouter} entirely and hand the message straight to the
     * bound agent. Conversation persistence still keys on (agent, channelType,
     * peerId) so history per end-user is preserved.
     *
     * @param agent        the bound agent the caller already resolved
     * @param channelType  channel identifier
     * @param peerId       channel-specific peer id (per-user conversation key)
     * @param text         inbound message text
     * @param sendResponse callback to deliver the response (receives
     *                     {@code peerId} and response text)
     */
    static void processInboundForAgent(Agent agent, String channelType, String peerId,
                                       String text, BiConsumer<String, String> sendResponse) {
        // JCLAW-26: intercept slash commands before the LLM round. /new creates
        // a fresh conversation and short-circuits; /reset + /help mutate the
        // current conversation and short-circuit. Unknown /foo falls through.
        var slashCmd = slash.Commands.parse(text);
        if (slashCmd.isPresent()) {
            var cmd = slashCmd.get();
            Conversation current = cmd == slash.Commands.Command.NEW
                    ? null
                    : Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId));
            var result = slash.Commands.execute(cmd, agent, channelType, peerId, current,
                    slash.Commands.extractArgs(text));
            sendResponse.accept(peerId, result.responseText());
            return;
        }
        var conversation = Tx.run(() ->
                ConversationService.findOrCreate(agent, channelType, peerId));
        var result = AgentRunner.run(agent, conversation, text);
        sendResponse.accept(peerId, result.response());
    }

    /**
     * Streaming-aware counterpart to {@link #processInboundForAgent}: wires
     * LLM tokens into a {@link channels.TelegramStreamingSink} so the user
     * sees progressive edits rather than waiting for the full response
     * (JCLAW-94). {@link AgentRunner#runStreaming} starts its own virtual thread, so
     * this call returns immediately after queue acquisition; the sink is
     * sealed asynchronously when the LLM call completes.
     *
     * <p>The sink receives text-only tokens (reasoning and status callbacks
     * are no-ops — Telegram has no separate surface for them yet). On
     * completion the full response is handed to {@link channels.TelegramStreamingSink#seal},
     * which either performs one final HTML edit or falls back to the
     * chunked planner path for oversize / media-rich responses.
     *
     * @param agent        the bound agent
     * @param channelType  channel identifier
     * @param peerId       channel-specific peer id
     * @param text         inbound message text
     * @param sinkFactory  factory that returns a streaming sink for the
     *                     given message id; called inside the streaming
     *                     virtual thread once the persisted message id is
     *                     known
     */
    static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            Function<Long, ChannelStreamingSink> sinkFactory) {
        processInboundForAgentStreaming(agent, channelType, peerId, text, sinkFactory,
                List.of(), null);
    }

    /**
     * JCLAW-136 overload: accepts inbound file attachments (images, audio,
     * documents, video) alongside the text. The caller (webhook or polling
     * runner) has already resolved Telegram file_ids via Bot API getFile and
     * streamed the bytes into the agent's {@code attachments/staging}
     * directory, so each {@link services.AttachmentService.Input} points at
     * a real staged file the runner can finalize. Empty list is the
     * text-only path — same behavior as before.
     *
     * @param agent       the bound agent
     * @param channelType channel identifier
     * @param peerId      channel-specific peer id
     * @param text        inbound message text
     * @param sinkFactory factory that returns a streaming sink for the
     *                    given message id
     * @param attachments staged file metadata to finalize against the new
     *                    user message; empty list is the text-only path
     */
    static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            Function<Long, ChannelStreamingSink> sinkFactory,
            List<AttachmentService.Input> attachments) {
        processInboundForAgentStreaming(agent, channelType, peerId, text, sinkFactory,
                attachments, null);
    }

    /**
     * Chat-type-aware variant: stamps the Telegram {@code chat.type} onto the
     * conversation at creation so {@link ConversationService#effectiveHistoryLimit}
     * (and any other chat-type-scoped behavior) can distinguish a plain DM from a
     * plain group. Only the two Telegram ingress call sites
     * ({@link controllers.WebhookTelegramController} and
     * {@link channels.TelegramPollingRunner}) pass a real value; every other
     * overload delegates with {@code chatType=null}, leaving the column null and
     * behavior unchanged. The chat type is stamped once at creation — an existing
     * conversation row is never re-stamped.
     *
     * @param agent       the bound agent
     * @param channelType channel identifier
     * @param peerId      channel-specific peer id
     * @param text        inbound message text
     * @param sinkFactory factory that returns a streaming sink for the given
     *                    message id
     * @param attachments staged file metadata to finalize; empty list is the
     *                    text-only path
     * @param chatType    Telegram {@code chat.type} ("private"/"group"/
     *                    "supergroup"), or null
     */
    static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            Function<Long, ChannelStreamingSink> sinkFactory,
            List<AttachmentService.Input> attachments,
            String chatType) {
        // JCLAW-26: intercept slash commands before the LLM round. Reuse the
        // existing sink machinery to deliver the canned response — an unused
        // sink's seal() path falls through to the per-binding TelegramChannel's
        // sendTurn, which keeps the bot-token / chat-id routing owned by the caller.
        var slashCmd = slash.Commands.parse(text);
        if (slashCmd.isPresent()) {
            var cmd = slashCmd.get();
            Conversation current = cmd == slash.Commands.Command.NEW
                    ? null
                    : Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId, chatType));
            var result = slash.Commands.execute(cmd, agent, channelType, peerId, current,
                    slash.Commands.extractArgs(text));
            var slashSink = sinkFactory.apply(
                    result.conversation() != null ? result.conversation().id : null);
            // JCLAW-109: an empty responseText is the handler's signal that
            // it already delivered the reply itself (e.g. /model on Telegram
            // sent an inline-keyboard message via a dedicated Bot API path).
            // Fall through to seal only when there's text to emit.
            if (result.responseText() != null && !result.responseText().isEmpty()) {
                slashSink.seal(result.responseText());
            }
            return;
        }
        // JCLAW-430: /start always introduces the bot via the LLM. Telegram
        // auto-sends /start on first open, and a user can re-invoke it anytime.
        // Open a FRESH conversation (so the intro isn't muddied by prior context)
        // and replace the bare "/start" with an explicit intro instruction — the
        // agent's identity/persona comes from its md-file system prompt, and we
        // hand it the slash-command list, so it produces a proper self-
        // introduction instead of improvising from "/start" alone. Replaces the
        // first-contact-only deterministic welcome (JCLAW-97).
        final boolean isStart = slash.Commands.isStart(text);
        final String turnText = isStart ? slash.Commands.startIntroPrompt() : text;
        var conversation = Tx.run(() -> isStart
                ? ConversationService.create(agent, channelType, peerId)
                : ConversationService.findOrCreate(agent, channelType, peerId, chatType));
        // The sink needs the conversation id so it can persist / clear the
        // stream checkpoint (JCLAW-95). Callers supply a factory — they own
        // botToken / chatId, we own conversation lookup.
        var sink = sinkFactory.apply(conversation.id);
        // JCLAW-98: show Telegram's native typing indicator during the
        // prologue gap (request received → first token). Cancels itself
        // on first sink.update(), seal(), or errorFallback(). Intentionally
        // NOT started for slash commands above — their responses are
        // instant and don't benefit from the indicator.
        sink.startTypingHeartbeat();
        var isCancelled = ConversationQueue.cancellationFlag(conversation.id);
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {},                 // onInit — nothing to do for Telegram
                sink::update,            // onToken — live preview edits
                _ -> {},                 // onReasoning — not surfaced on Telegram
                _ -> {},                 // onStatus — not surfaced on Telegram
                tc -> {                  // onToolCall — Slack off-thread draft preview (JCLAW-346); Telegram out-of-band media
                    sink.toolProgress(tc.name());
                    if (!tc.generatedAttachmentUuids().isEmpty()) {
                        sink.collectGeneratedAttachments(tc.generatedAttachmentUuids());
                    }
                },
                sink::seal,              // onComplete — final edit / planner fallback
                sink::errorFallback,     // onError — delete placeholder + send error
                sink::cancel);           // onCancel — quiesce typing heartbeat on /stop
        AgentRunner.runStreaming(agent, conversation.id, channelType, peerId, turnText,
                isCancelled, cb, null, attachments);
    }

    /**
     * Best-effort delivery of a response to an external channel. Web channel
     * responses are already persisted to the DB by {@link AgentRunner#run} — the user sees
     * them on next conversation load or refresh. External channels need explicit
     * dispatch because there is no persistent connection to push through.
     *
     * <p>JCLAW-141: resolves the right {@link channels.Channel} via
     * {@link channels.ChannelRegistry#forChannel} (which carries Telegram's
     * per-binding bot token, looked up from (agent, peerId)) and calls the generic
     * {@link channels.Channel#sendText(String, String, Agent)} — no channel-type
     * switch.
     * The agent-aware overload lets Telegram's planner resolve workspace file
     * links; the other channels ignore the agent. A null channel (unknown type,
     * or a Telegram conversation with no enabled binding) drops the dispatch,
     * matching the prior null-branch behavior.
     */
    static void dispatchToChannel(Agent agent, String channelType, String peerId, String text) {
        if (peerId == null || text == null) return;
        try {
            var channel = Tx.run(() ->
                    ChannelRegistry.forChannel(channelType, agent, peerId));
            if (channel == null) {
                if (ChannelType.TELEGRAM == ChannelType.fromValue(channelType)) {
                    EventLogger.warn("channel", agent != null ? agent.name : null, "telegram",
                            "No enabled binding for (agent=%s, userId=%s); dropping queued response"
                                    .formatted(agent != null ? agent.name : "?", peerId));
                }
                return;
            }
            channel.sendText(peerId, text, agent);
        } catch (Exception e) {
            EventLogger.error("channel", null, channelType,
                    "Failed to dispatch queued response to %s/%s: %s"
                            .formatted(channelType, peerId, e.getMessage()));
        }
    }
}
