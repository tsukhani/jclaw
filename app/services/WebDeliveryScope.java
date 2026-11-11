package services;

import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * JF-289: the web conversations an agent may deliver into. Its own, and its parent chain's: the
 * conversation that started a run it is the child of, and every conversation above that one.
 * {@code main} delivers into any. {@link DeliveryDispatcher} asks before it appends a message, and
 * {@code task_manager} and {@code POST}/{@code PATCH /api/tasks} before they save a task's
 * {@code web:<id>} delivery.
 *
 * <p>JF-305: the operator and {@code main} save any conversation as the target of any agent's task, and
 * the task records that they did ({@link models.Task#deliverySetByOperator}), so its fire delivers there.
 * A target the agent saved is the agent's send, at the save and at the fire.
 */
public final class WebDeliveryScope {

    private WebDeliveryScope() {}

    /** The conversation a web {@code target} names, or null when it names none: blank, or a peer id. */
    static @Nullable Long conversationId(@Nullable String target) {
        if (target == null || target.isBlank()) return null;
        try {
            return Long.parseLong(target.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** One text for a conversation that does not exist and for one out of reach, so a refusal confirms no id. */
    static String notFound(long conversationId) {
        return "Web target conversation " + conversationId + " not found.";
    }

    /**
     * The conversation {@code conversationId} names when {@code agent} may deliver into it, or null when
     * it does not exist or is out of the agent's reach. Must run inside a JPA transaction.
     *
     * @param agent the sender; null, an agent nobody identified, reaches nothing
     */
    static @Nullable Conversation reachable(@Nullable Agent agent, long conversationId) {
        var conversation = (Conversation) Conversation.findById(conversationId);
        return conversation != null && agent != null && mayDeliverInto(agent, conversation) ? conversation : null;
    }

    /**
     * {@link #reachable(Agent, long)} for the saved target of a task's fire (JF-305). A target the operator
     * or {@code main} saved needs only to exist; one the task's agent saved, and a row from before the task
     * recorded who saved it, is judged as the agent's.
     *
     * @param setByOperator {@link models.Task#deliverySetByOperator} of the task that fired
     */
    static @Nullable Conversation reachable(@Nullable Agent agent, long conversationId, boolean setByOperator) {
        return setByOperator ? (Conversation) Conversation.findById(conversationId) : reachable(agent, conversationId);
    }

    private static boolean mayDeliverInto(Agent agent, Conversation target) {
        if (agent.isMain()) return true;
        if (agent.id == null || target.id == null) return false;
        if (target.agent != null && agent.id.equals(target.agent.id)) return true;
        return inParentChain(agent.id, target.id);
    }

    /**
     * Why {@code agent} may not save {@code deliverySpec} as a task's delivery, or null when it may. Only
     * a {@code web:<id>} spec is checked. {@code main}'s is not: it saves an id that names nothing as it
     * always could, and the fire reports it.
     *
     * @param agent the agent saving the task; null reaches nothing
     */
    public static @Nullable String refusalFor(@Nullable Agent agent, @Nullable String deliverySpec) {
        if (agent != null && agent.isMain()) return null;
        var spec = DeliverySpec.parse(deliverySpec);
        if (spec.kind() != DeliverySpec.Kind.CHANNEL || !"web".equals(spec.channel())) return null;
        var named = conversationId(spec.target());
        if (named == null) return null;
        long conversationId = named;
        return Tx.run(() -> reachable(agent, conversationId) == null ? notFound(conversationId) : null);
    }

    /**
     * Whether {@code targetId} started a run {@code agentId} is the child of, or is above a conversation
     * that did. The first step reads the run's record, not the agent's conversations: an inline run's
     * child agent owns none to walk up from.
     */
    private static boolean inParentChain(Long agentId, Long targetId) {
        var em = JPA.em();
        List<Long> level = em.createQuery("SELECT DISTINCT r.parentConversation.id FROM SubagentRun r "
                        + "WHERE r.childAgent.id = :agent AND r.parentConversation IS NOT NULL", Long.class)
                .setParameter("agent", agentId).getResultList();
        // Ends without a hop limit: a level never repeats a conversation already seen.
        var seen = new HashSet<Long>();
        while (!level.isEmpty()) {
            if (level.contains(targetId)) return true;
            seen.addAll(level);
            var above = new ArrayList<>(em.createQuery("SELECT DISTINCT c.parentConversation.id FROM Conversation c "
                            + "WHERE c.id IN :ids AND c.parentConversation IS NOT NULL", Long.class)
                    .setParameter("ids", level).getResultList());
            above.removeAll(seen);
            level = above;
        }
        return false;
    }
}
