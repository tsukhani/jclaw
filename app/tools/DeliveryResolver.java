package tools;

import models.Agent;
import models.Conversation;
import services.DeliveryDispatcher;
import services.Tx;

import java.util.Optional;

/**
 * JCLAW-726: Pure Fabrication owning "which conversation is the agent
 * operating on, and how do I address it" inference. The single source shared
 * by {@link TaskTool} (a created task's default output {@code delivery} target)
 * and {@link MessageTool} (a mid-turn send's channel + peer).
 *
 * <p>Both tools previously carried their own copy of the
 * most-recently-updated-conversation lookup and had to be "kept agreeing" by
 * hand. Routing both through {@link #mostRecentConversation} makes that
 * agreement structural: change "most-recently-updated wins" here and both
 * surfaces move together.
 */
public final class DeliveryResolver {

    private DeliveryResolver() {}

    /**
     * The agent's most-recently-updated {@link Conversation} — "the channel
     * I'm operating on". Empty when the agent has no conversations (headless
     * task creation via API, a freshly-spawned agent with no chat history).
     * Runs in the caller's JPA transaction when there is one (via
     * {@link Tx#run}), else opens its own.
     */
    public static Optional<Conversation> mostRecentConversation(Agent agent) {
        return Tx.run(() -> Optional.ofNullable((Conversation) Conversation.find(
                "agent = ?1 ORDER BY updatedAt DESC", agent).first()));
    }

    /**
     * Infer a {@code "<channelType>:<target>"} delivery spec from the agent's
     * most-recently-updated conversation, or empty when none is usable. The
     * {@code target} is:
     * <ul>
     *   <li>{@link Conversation#id} for the {@code web} channel, because web
     *       conversations don't always carry a peerId and the chat UI is keyed
     *       by conversation id ({@link services.DeliveryDispatcher#dispatchSpec}
     *       routes this through the conv-id-aware web path);</li>
     *   <li>{@link Conversation#peerId} for external channels (telegram chat
     *       id, slack channel id, whatsapp e.164) — the same shape
     *       {@link services.DeliveryDispatcher} parses.</li>
     * </ul>
     *
     * <p>Empty when the agent has no conversation, the most-recent conversation
     * is on a non-deliverable channel, or the required target field is absent
     * (no peerId on a non-web channel).
     */
    public static Optional<String> inferSpec(Agent agent) {
        return mostRecentConversation(agent).flatMap(DeliveryResolver::specFor);
    }

    private static Optional<String> specFor(Conversation conv) {
        if (conv.channelType == null || !DeliveryDispatcher.isSupported(conv.channelType)) {
            return Optional.empty();
        }
        var target = "web".equalsIgnoreCase(conv.channelType)
                ? (conv.id != null ? conv.id.toString() : null)
                : conv.peerId;
        if (target == null || target.isBlank()) return Optional.empty();
        return Optional.of("%s:%s".formatted(conv.channelType, target));
    }
}
