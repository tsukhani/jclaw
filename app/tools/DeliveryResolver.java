package tools;

import agents.ToolContext;
import models.Agent;
import models.Conversation;
import services.DeliveryDispatcher;
import services.Tx;

import java.util.Objects;
import java.util.Optional;

/**
 * JCLAW-726: Pure Fabrication owning "which conversation is the agent
 * operating on, and how do I address it" inference. The single source shared
 * by {@link TaskTool} (a created task's default output {@code delivery} target),
 * {@link MessageTool} (a mid-turn send's channel + peer) and
 * {@link SubagentChildBootstrap} (a spawned run's parent conversation).
 *
 * <p>These previously carried their own copy of the
 * most-recently-updated-conversation lookup and had to be "kept agreeing" by
 * hand. Routing them through {@link #operatingConversation} makes that
 * agreement structural: change the rule here and every surface moves together.
 */
public final class DeliveryResolver {

    private DeliveryResolver() {}

    /**
     * The agent's most-recently-updated {@link Conversation}. Empty when the
     * agent has no conversations (headless task creation via API, a
     * freshly-spawned agent with no chat history). Runs in the caller's JPA
     * transaction when there is one (via {@link Tx#run}), else opens its own.
     */
    public static Optional<Conversation> mostRecentConversation(Agent agent) {
        return Tx.run(() -> Optional.ofNullable((Conversation) Conversation.find(
                "agent = ?1 ORDER BY updatedAt DESC", agent).first()));
    }

    /**
     * "The conversation I'm operating on": the one the calling chat turn runs in
     * ({@link #callingConversation}), else {@link #mostRecentConversation}. Recency
     * alone picks whichever of the agent's chats was touched last, not the one it is
     * answering (JCLAW-1211).
     */
    public static Optional<Conversation> operatingConversation(Agent agent) {
        return callingConversation(agent).or(() -> mostRecentConversation(agent));
    }

    /**
     * The conversation bound to the current tool dispatch
     * ({@link ToolContext#conversationId}) when it belongs to {@code agent}; empty in
     * a task fire, which binds none, and outside a tool dispatch.
     */
    public static Optional<Conversation> callingConversation(Agent agent) {
        var id = ToolContext.conversationId();
        if (id == null) return Optional.empty();
        return Tx.run(() -> Optional.ofNullable((Conversation) Conversation.findById(id))
                .filter(c -> c.agent != null && Objects.equals(c.agent.id, agent.id)));
    }

    /**
     * Infer a {@code "<channelType>:<target>"} delivery spec from
     * {@link #operatingConversation}, or empty when none is usable. The
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
     * <p>Empty when the agent has no conversation, that conversation is on a
     * non-deliverable channel, or the required target field is absent (no peerId
     * on a non-web channel).
     */
    public static Optional<String> inferSpec(Agent agent) {
        return operatingConversation(agent).flatMap(DeliveryResolver::specFor);
    }

    private static Optional<String> specFor(Conversation conv) {
        if (conv.channelType == null || !DeliveryDispatcher.isSupported(conv.channelType)) {
            return Optional.empty();
        }
        String target;
        if ("web".equalsIgnoreCase(conv.channelType)) {
            target = conv.id != null ? conv.id.toString() : null;
        } else {
            target = conv.peerId;
        }
        if (target == null || target.isBlank()) return Optional.empty();
        return Optional.of("%s:%s".formatted(conv.channelType, target));
    }
}
