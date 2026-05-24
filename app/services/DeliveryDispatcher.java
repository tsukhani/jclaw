package services;

import channels.Channel;
import channels.SlackChannel;
import channels.TelegramChannel;
import channels.WhatsAppChannel;
import models.Agent;
import models.ChannelType;
import models.TelegramBinding;

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

    /** Outcome of a dispatch. {@link #FAILED_NO_CONFIG} distinguishes "channel
     *  not set up yet" from generic delivery failure so the {@code message}
     *  tool can hint at setup steps. {@code reason} is human-readable. */
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
                            + "(supported: telegram, slack, whatsapp).");
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
            case "telegram" -> dispatchTelegram(agent, target, text);
            case "slack" -> dispatchSlack(target, text);
            case "whatsapp" -> dispatchWhatsApp(target, text);
            default -> DispatchResult.unsupported(channelType);
        };
    }

    /** Parse and dispatch a combined {@code channel:target} string — matches
     *  the format the {@link models.Task#delivery} column stores. Returns
     *  {@code CHANNEL_UNSUPPORTED} when the spec doesn't contain a colon. */
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
        var binding = TelegramBinding.findByAgent(agent);
        if (binding == null) {
            return DispatchResult.noConfig("telegram",
                    "Connect a Telegram bot for agent '" + agent.name
                            + "' in Settings → Channels → Telegram, or via "
                            + "POST /api/telegram-bindings.");
        }
        if (!binding.enabled) {
            return DispatchResult.noConfig("telegram",
                    "Telegram binding for agent '" + agent.name + "' is disabled.");
        }
        var channel = TelegramChannel.forToken(binding.botToken);
        return channel.sendWithRetry(chatId, text)
                ? DispatchResult.delivered()
                : DispatchResult.failedDelivery(
                        "Telegram API rejected the message (see logs for details).");
    }

    private static DispatchResult dispatchSlack(String channelId, String text) {
        if (SlackChannel.SlackConfig.load() == null) {
            return DispatchResult.noConfig("slack",
                    "Configure the workspace Slack app in Settings → Channels → Slack.");
        }
        return new SlackChannel().sendWithRetry(channelId, text)
                ? DispatchResult.delivered()
                : DispatchResult.failedDelivery(
                        "Slack API rejected the message (see logs for details).");
    }

    private static DispatchResult dispatchWhatsApp(String phoneNumber, String text) {
        if (WhatsAppChannel.WhatsAppConfig.load() == null) {
            return DispatchResult.noConfig("whatsapp",
                    "Configure the WhatsApp Cloud API credentials in Settings → Channels → WhatsApp.");
        }
        return new WhatsAppChannel().sendWithRetry(phoneNumber, text)
                ? DispatchResult.delivered()
                : DispatchResult.failedDelivery(
                        "WhatsApp Cloud API rejected the message (see logs for details).");
    }

    /** Helper for callers (UI / docs / tool schemas): is {@code channelType}
     *  a deliverable channel via this dispatcher? */
    public static boolean isSupported(String channelType) {
        if (channelType == null) return false;
        return switch (channelType.toLowerCase()) {
            case "telegram", "slack", "whatsapp" -> true;
            default -> false;
        };
    }
}
