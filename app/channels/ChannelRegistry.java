package channels;

import agents.TelegramMessageAddressing;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.SlackBinding;
import models.TelegramBinding;
import models.TelegramTopicBinding;
import models.WhatsAppBinding;
import org.jspecify.annotations.Nullable;

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
 * is per-binding too. Web is a no-op push channel (replies are DB-persisted).
 *
 * <p>Returns {@code null} when no usable channel exists — an unknown
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
    public static @Nullable Channel forConversation(Conversation conversation) {
        if (conversation == null) return null;
        return forChannel(conversation.channelType, conversation.agent, conversation.peerId);
    }

    /**
     * Resolve a {@link Channel} from the raw (channelType, agent, peerId) tuple —
     * the shape queue-dispatch holds without a materialized {@link Conversation}
     * row. {@code peerId} is consulted only for Telegram; Slack and WhatsApp resolve
     * their binding from {@code agent}.
     */
    public static @Nullable Channel forChannel(String channelType, Agent agent, String peerId) {
        var type = ChannelType.fromValue(channelType);
        if (type == null) return null;
        return switch (type) {
            case TELEGRAM -> {
                var binding = telegramBindingFor(agent, peerId);
                // A bot token that did not decrypt reads as null (JCLAW-1455), and forToken refuses one.
                yield binding == null || binding.botToken == null ? null : TelegramChannel.forToken(binding.botToken);
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
                // walk the parent chain. Fall back to the app-global config.
                var binding = WhatsAppBinding.findByAgentOrAncestor(agent);
                yield (binding != null && binding.enabled)
                        ? WhatsAppChannelFactory.forBinding(binding)
                        : new WhatsAppChannel();
            }
            case WEB -> new WebChannel();
            // Voice has no out-of-band transport. Replies go over the live
            // WebSocket that VoiceController owns, and when that closes the
            // session conversation is deleted outright (JCLAW-864) — so there is
            // nothing to deliver to and nowhere to deliver it. null is already
            // the established "no channel" answer here (the Telegram branch
            // yields it for a missing binding), so callers skip rather than throw.
            case VOICE -> null;
        };
    }

    /**
     * A DM's key is the owner's user id, which names its binding. A group's key is its chat id
     * (JCLAW-1605): the agent's own bot serves it, or, for a forum topic that runs on an override
     * agent, the bot whose topic mapping names that agent.
     */
    private static @Nullable TelegramBinding telegramBindingFor(Agent agent, String peerId) {
        if (!TelegramMessageAddressing.isGroupPeer(peerId)) {
            return TelegramBinding.findEnabledByAgentAndUser(agent, peerId);
        }
        var to = TelegramMessageAddressing.chatAddress(peerId);
        var threadId = to.threadId();
        if (threadId != null) {
            var mapping = TelegramTopicBinding.findByAgentAndTopic(agent, to.chatId(), threadId);
            if (mapping != null && mapping.binding.enabled) return mapping.binding;
        }
        var own = TelegramBinding.findByAgent(agent);
        return own != null && own.enabled ? own : null;
    }
}
