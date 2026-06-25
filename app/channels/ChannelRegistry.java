package channels;

import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;

/**
 * JCLAW-141: resolves a {@link Channel} instance for a conversation so dispatch
 * sites call the generic {@link Channel} send methods instead of branching on
 * the channel-type string. Replaces the old {@code ChannelType.resolve()} switch
 * that returned {@code null} for the two commonest channels (Telegram, Web) and
 * forced every caller to special-case them.
 *
 * <p>Telegram and Slack are per-binding: the bot token lives on the matching
 * {@link TelegramBinding} / {@link SlackBinding} (looked up from the
 * conversation's agent), so the resolved instance carries that token. WhatsApp
 * is a stateless adapter. Web is a no-op push channel (replies are DB-persisted).
 *
 * <p>Returns {@code null} only when no usable channel exists — an unknown
 * channel-type string, or a Telegram/Slack conversation with no enabled binding
 * — so callers can drop the dispatch (the same way the old null branch did)
 * rather than throwing.
 */
public final class ChannelRegistry {

    private ChannelRegistry() {}

    /**
     * Resolve the {@link Channel} that should deliver outbound messages for
     * {@code conversation}, or {@code null} when none applies.
     *
     * @param conversation the conversation whose channel + peer determine the
     *                     transport; its {@code agent} is used (with {@code peerId})
     *                     to find the Telegram bot token
     */
    public static Channel forConversation(Conversation conversation) {
        if (conversation == null) return null;
        return forChannel(conversation.channelType, conversation.agent, conversation.peerId);
    }

    /**
     * Resolve a {@link Channel} from the raw (channelType, agent, peerId) tuple —
     * the shape queue-dispatch holds without a materialized {@link Conversation}
     * row. {@code agent}/{@code peerId} are only consulted for Telegram (per-binding
     * token lookup); the other channels ignore them.
     */
    public static Channel forChannel(String channelType, Agent agent, String peerId) {
        var type = ChannelType.fromValue(channelType);
        if (type == null) return null;
        return switch (type) {
            case TELEGRAM -> {
                var binding = TelegramBinding.findEnabledByAgentAndUser(agent, peerId);
                yield binding == null ? null : TelegramChannel.forToken(binding.botToken);
            }
            case SLACK -> {
                // Slack is per-agent-binding like Telegram (JCLAW-441). A subagent
                // reaches the user via an ancestor's bot, so walk the parent chain.
                var binding = SlackBinding.findByAgentOrAncestor(agent);
                yield (binding == null || !binding.enabled) ? null : SlackChannel.forToken(binding.botToken);
            }
            case WHATSAPP -> {
                // JCLAW-446: prefer the per-agent binding (transport-aware via the
                // factory). A subagent reaches the user via an ancestor's binding, so
                // walk the parent chain. Fall back to the app-global config while the
                // inbound/outbound migration completes (JCLAW-447 removes the fallback).
                var binding = WhatsAppBinding.findByAgentOrAncestor(agent);
                yield (binding != null && binding.enabled)
                        ? WhatsAppChannelFactory.forBinding(binding)
                        : new WhatsAppChannel();
            }
            case WEB -> new WebChannel();
        };
    }
}
