package services;

import channels.Channel;
import channels.SlackChannel;
import channels.TelegramChannel;
import channels.WhatsAppChannel;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.TelegramBinding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JCLAW-327 / JCLAW-295: dispatch a text message to an external chat channel.
 *
 * <p>Single entry point for any code path that needs to deliver a string to a
 * Telegram chat, Slack channel, or WhatsApp number. Used by:
 * <ul>
 *   <li>{@link tools.MessageTool} — the agent-facing {@code message} tool
 *       that lets a running agent push a message to its channel mid-turn
 *       (JCLAW-327, the Radarr-monitor use case).</li>
 *   <li>Future task-completion delivery wiring (JCLAW-295) that reads
 *       {@link models.Task#delivery} and surfaces task output to the
 *       configured channel when a {@link models.TaskRun} terminates.</li>
 * </ul>
 *
 * <p>Telegram routes via the per-agent {@link TelegramBinding}'s bot token
 * (each agent has at most one binding by privacy design — see the
 * {@code agent_id} unique constraint on the binding table). Slack and
 * WhatsApp use system-wide {@link models.ChannelConfig} rows because their
 * Bot API tokens are workspace-scoped, not per-agent.
 *
 * <p>Retry + rate-limit backoff are inherited from {@link Channel#sendWithRetry}
 * — a single retry with the platform's {@code Retry-After} hint, capped at
 * 60 seconds. Failures past that return a {@code FAILED} result so the caller
 * can surface a clear error envelope to the LLM (AC-6).
 */
public final class DeliveryDispatcher {

    private DeliveryDispatcher() {}

    private static final String TELEGRAM = "telegram";
    private static final String SLACK = "slack";
    private static final String WHATSAPP = "whatsapp";

    /**
     * Outcome of a dispatch.
     *
     * @param ok     true on a successful push; false for any failure or
     *               unsupported channel
     * @param status fine-grained outcome — {@link Status#FAILED_NO_CONFIG}
     *               distinguishes "channel not set up yet" from generic
     *               delivery failure so the {@code message} tool can hint
     *               at setup steps
     * @param reason human-readable explanation
     */
    public record DispatchResult(boolean ok, Status status, String reason) {

        public enum Status {
            /** Message accepted by the channel API. */
            DELIVERED,
            /** Channel type is unknown or unsupported (e.g. agent's conversation lives on a non-deliverable channel like {@code web}). */
            CHANNEL_UNSUPPORTED,
            /** Channel binding/config row missing — operator hasn't connected this channel yet. */
            FAILED_NO_CONFIG,
            /** API call attempted but failed (network, auth, invalid target). */
            FAILED_DELIVERY
        }

        public static DispatchResult delivered() {
            return new DispatchResult(true, Status.DELIVERED, "Delivered");
        }
        public static DispatchResult unsupported(String channelType) {
            return new DispatchResult(false, Status.CHANNEL_UNSUPPORTED,
                    "Channel '" + channelType + "' is not a deliverable channel "
                            + "(supported: telegram, slack, whatsapp, web).");
        }
        public static DispatchResult noConfig(String channelType, String hint) {
            return new DispatchResult(false, Status.FAILED_NO_CONFIG,
                    "Channel '" + channelType + "' is not configured. " + hint);
        }
        public static DispatchResult failedDelivery(String reason) {
            return new DispatchResult(false, Status.FAILED_DELIVERY, reason);
        }
    }

    /**
     * Send {@code text} to {@code target} via {@code channelType}, on behalf
     * of {@code agent} (used to look up the per-agent Telegram bot token).
     *
     * <p>Wraps {@link Channel#sendWithRetry} so callers get one automatic
     * retry against the platform's rate-limit hint. Must run inside a JPA
     * transaction when telegram is the channel — the per-agent binding
     * lookup goes through JPA.
     *
     * @param agent the agent owning the conversation that initiated the
     *              dispatch. Required for telegram; ignored for slack/whatsapp
     *              because their configs are workspace-scoped.
     * @param channelType one of {@code telegram}, {@code slack}, {@code whatsapp}.
     *              Case-insensitive. Other values return {@code CHANNEL_UNSUPPORTED}.
     * @param target  channel-specific peer id. Telegram: numeric chat id.
     *              Slack: channel id ({@code Cxxxx}). WhatsApp: e.164 phone.
     * @param text    UTF-8 message body. Channel-specific formatting limits apply.
     */
    public static DispatchResult dispatch(Agent agent, String channelType, String target, String text) {
        if (channelType == null || channelType.isBlank()) {
            return DispatchResult.unsupported("(null)");
        }
        if (target == null || target.isBlank()) {
            return DispatchResult.failedDelivery("Target is required.");
        }
        var canonical = channelType.toLowerCase();
        return switch (canonical) {
            case TELEGRAM -> dispatchTelegram(agent, target, text);
            case SLACK -> dispatchSlack(target, text);
            case WHATSAPP -> dispatchWhatsApp(target, text);
            case "web" -> dispatchWeb(agent, target, text);
            default -> DispatchResult.unsupported(channelType);
        };
    }

    /** Parse and dispatch a combined {@code channel:target} string — matches
     *  the format the {@link models.Task#delivery} column stores. Returns
     *  {@code CHANNEL_UNSUPPORTED} when the spec doesn't contain a colon.
     *  Web specs may carry the target {@link Conversation} id (so a task
     *  fires-back to a specific chat, not just the agent's most-recent
     *  conversation); see {@link #dispatchWeb(Agent, String, String)}. */
    public static DispatchResult dispatchSpec(Agent agent, String deliverySpec, String text) {
        if (deliverySpec == null || deliverySpec.isBlank()) {
            return DispatchResult.unsupported("(null)");
        }
        var idx = deliverySpec.indexOf(':');
        if (idx <= 0 || idx == deliverySpec.length() - 1) {
            return DispatchResult.failedDelivery(
                    "Delivery spec must be 'channel:target' (got '" + deliverySpec + "').");
        }
        return dispatch(agent,
                deliverySpec.substring(0, idx),
                deliverySpec.substring(idx + 1),
                text);
    }

    private static DispatchResult dispatchTelegram(Agent agent, String chatId, String text) {
        if (agent == null) {
            return DispatchResult.failedDelivery(
                    "Telegram dispatch requires an agent context for per-binding bot-token lookup.");
        }
        // Walk the parentAgent chain: a sub-agent spawned by `main` (or any
        // ancestor with a Telegram binding) should reach the user via that
        // binding. Sub-agents don't have their own bot — only user-facing
        // root agents do — so a strict findByAgent on the sub-agent always
        // misses. Mirror's the workspace-path parent-walk pattern.
        var binding = TelegramBinding.findByAgentOrAncestor(agent);
        if (binding == null) {
            return DispatchResult.noConfig(TELEGRAM,
                    "Connect a Telegram bot for agent '" + agent.name
                            + "' (or any of its ancestors) in Settings → Channels → Telegram, "
                            + "or via POST /api/telegram-bindings.");
        }
        if (!binding.enabled) {
            return DispatchResult.noConfig(TELEGRAM,
                    "Telegram binding for agent '" + binding.agent.name + "' is disabled.");
        }
        // Use the static sendMessage path (not channel.sendWithRetry directly)
        // so agent-emitted markdown like **bold** and # heading is rendered
        // through TelegramMarkdownFormatter.toHtml before reaching the wire.
        // The instance trySend uses parseMode="HTML" and would otherwise leave
        // literal asterisks in the user's chat — Telegram's HTML parser finds
        // no tags and renders the markup as plain text. The static path also
        // chunks safely at 4000 chars with tag-aware splitting, matching the
        // agent-reply flow's behavior.
        return TelegramChannel.sendMessage(binding.botToken, chatId, text, agent)
                ? DispatchResult.delivered()
                : DispatchResult.failedDelivery(
                        "Telegram API rejected the message (see logs for details).");
    }

    private static DispatchResult dispatchSlack(String channelId, String text) {
        if (SlackChannel.SlackConfig.load() == null) {
            return DispatchResult.noConfig(SLACK,
                    "Configure the workspace Slack app in Settings → Channels → Slack.");
        }
        return new SlackChannel().sendWithRetry(channelId, text)
                ? DispatchResult.delivered()
                : DispatchResult.failedDelivery(
                        "Slack API rejected the message (see logs for details).");
    }

    private static DispatchResult dispatchWhatsApp(String phoneNumber, String text) {
        if (WhatsAppChannel.WhatsAppConfig.load() == null) {
            return DispatchResult.noConfig(WHATSAPP,
                    "Configure the WhatsApp Cloud API credentials in Settings → Channels → WhatsApp.");
        }
        return new WhatsAppChannel().sendWithRetry(phoneNumber, text)
                ? DispatchResult.delivered()
                : DispatchResult.failedDelivery(
                        "WhatsApp Cloud API rejected the message (see logs for details).");
    }

    /**
     * Route a web-channel send to a specific {@link Conversation} (when
     * {@code target} parses as a numeric conversation id) or — for the
     * Radarr-monitor pattern where no explicit target was set — by walking
     * the calling agent's most-recent conversation up the
     * {@link Conversation#parentConversation} chain to the root (the
     * user-facing row the chat UI is polling). Either path appends a
     * USER-role message stamped with {@code messageKind="subagent_send"}.
     *
     * <p>Same shape JCLAW-326's {@link tools.ConversationSendTool} writes,
     * so the chat UI renders it through the existing "agent-initiated
     * message" path with no special-case in the frontend. Source is
     * distinguished from conversation_send via metadata
     * ({@code source="message_tool"}) for analytics/debug, but the UI
     * treatment is the same.
     *
     * <p>Task auto-delivery (the
     * {@link services.TaskExecutor} → {@link #dispatchSpec} wire) stamps
     * the calling conversation id as the target so a fire that completes
     * on a virtual-thread carrier with no Conversation context still
     * lands in the right chat — the agent's "most-recent conversation"
     * heuristic isn't reliable when the agent has parallel chats open.
     */
    private static DispatchResult dispatchWeb(Agent agent, String target, String text) {
        if (agent == null) {
            return DispatchResult.failedDelivery(
                    "Web dispatch requires an agent context to resolve the active conversation.");
        }
        // Numeric target = explicit conversation id (the auto-delivery wire
        // from TaskExecutor stamps it this way so a fire pushes to the same
        // chat that created the task, not the agent's "most-recent" one).
        // Any other target value (legacy "ignored" sentinel from MessageTool's
        // pre-explicit-routing era, a peerId from an external-channel
        // conversation that doesn't apply to web) silently falls through to
        // the walk-up — that path predates this overload and the existing
        // callers depend on it.
        Conversation conv = null;
        if (target != null && !target.isBlank()) {
            try {
                var convId = Long.parseLong(target.trim());
                conv = (Conversation) Conversation.findById(convId);
                if (conv == null) {
                    return DispatchResult.failedDelivery(
                            "Web target conversation " + convId + " not found.");
                }
            } catch (NumberFormatException _) {
                // Non-numeric → not an explicit routing signal; fall through.
            }
        }
        if (conv == null) {
            conv = (Conversation) Conversation.find(
                    "agent = ?1 ORDER BY updatedAt DESC", agent).first();
        }
        if (conv == null) {
            return DispatchResult.failedDelivery(
                    "No active conversation for agent '" + agent.name + "' to deliver to. "
                            + "Web dispatch routes to the calling agent's most-recent conversation; "
                            + "spawn the agent inside a chat first.");
        }
        // Walk up to the user-facing root. A subagent's child Conversation
        // has parentConversation set; the chat UI is watching the row at
        // the top of the chain. Safety cap prevents an unbounded walk if
        // the parent FK is ever corrupted (single-parent in current schema,
        // but defensive).
        int hops = 0;
        while (conv.parentConversation != null && hops++ < 64) {
            conv = conv.parentConversation;
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "message_tool");
        metadata.put("agentId", agent.id);
        metadata.put("agentName", agent.name);
        var msg = ConversationService.appendMessage(conv, MessageRole.USER, text,
                null, null, null);
        msg.messageKind = "subagent_send";
        msg.metadata = utils.GsonHolder.INSTANCE.toJson(metadata, Map.class);
        msg.save();
        return DispatchResult.delivered();
    }

    /** Helper for callers (UI / docs / tool schemas): is {@code channelType}
     *  a deliverable channel via this dispatcher? Includes {@code web} —
     *  routes to the calling agent's parent-chain root conversation so the
     *  chat UI's poller picks the message up live, even for agents that
     *  don't talk to an external chat platform. */
    public static boolean isSupported(String channelType) {
        if (channelType == null) return false;
        return switch (channelType.toLowerCase()) {
            case TELEGRAM, SLACK, WHATSAPP, "web" -> true;
            default -> false;
        };
    }
}
