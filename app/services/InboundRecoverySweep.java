package services;

import agents.QueueDrainOrchestrator;
import channels.Channel;
import channels.ChannelRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.PendingInbound;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a restart owes the channel conversations the previous process left (JCLAW-1521). Slack,
 * Telegram and WhatsApp acknowledge a message before JClaw stores it and never send it again, so a
 * turn the restart cut off gets {@link #INTERRUPT_NOTE} on its channel, not a re-run that could
 * repeat what it already did, and each stored message that never started is queued again.
 */
public final class InboundRecoverySweep {

    public static final String INTERRUPT_NOTE =
            "This response was interrupted — please send your message again.";

    /** A request older than this gets no note: it is a turn that failed long ago, not one this restart cut off. */
    public static final Duration NOTE_LOOKBACK = Duration.ofHours(1);

    private static final String CATEGORY = "queue";

    public record Outcome(int requeued, int notified) {}

    private record Delivery(@Nullable Channel channel, String peerId, Agent agent) {}

    private record Stored(Long conversationId, ConversationQueue.QueuedMessage message) {}

    private InboundRecoverySweep() {}

    /**
     * @param bootCutoff        rows from before it belong to a process that no longer runs
     * @param placeholderEdited conversations whose user already saw the note on an edited Telegram placeholder
     */
    public static Outcome run(Instant bootCutoff, Set<Long> placeholderEdited) {
        // Notes first: a re-queued message's USER row would bury the request it answers.
        int notified = noteInterruptedTurns(bootCutoff, placeholderEdited);
        int requeued = requeueStored(bootCutoff);
        if (requeued > 0 || notified > 0) {
            EventLogger.info(CATEGORY, "Boot recovery: re-queued %d stored message(s), notified %d interrupted turn(s)"
                    .formatted(requeued, notified));
        }
        return new Outcome(requeued, notified);
    }

    private static int noteInterruptedTurns(Instant cutoff, Set<Long> placeholderEdited) {
        // A subagent child carries its parent's channel and peer, or no peer: the user hears once, from the parent.
        List<Long> candidates = Tx.run(() -> JPA.em().createQuery(
                        "SELECT DISTINCT m.conversation.id FROM Message m WHERE m.role = :user"
                                + " AND m.createdAt >= :since AND m.createdAt < :cutoff"
                                + " AND m.conversation.channelType IN :channels"
                                + " AND m.conversation.parentConversation IS NULL AND m.conversation.peerId IS NOT NULL"
                                + " AND NOT EXISTS (SELECT n.id FROM Message n WHERE n.conversation = m.conversation"
                                + " AND n.role = :user AND n.createdAt >= :cutoff)", Long.class)
                .setParameter("user", MessageRole.USER.value)
                .setParameter("since", cutoff.minus(NOTE_LOOKBACK))
                .setParameter("cutoff", cutoff)
                .setParameter("channels", ConversationQueue.EXTERNAL_CHANNELS)
                .getResultList());
        int notified = 0;
        for (var conversationId : candidates) {
            // A turn of this process owns it, and its own reply closes the request.
            if (ConversationQueue.isBusy(conversationId)) continue;
            try {
                var delivery = Tx.run(() -> closeInterrupted(conversationId, cutoff));
                if (delivery == null) continue;
                notified++;
                var channel = delivery.channel();
                if (channel != null && !placeholderEdited.contains(conversationId)) {
                    channel.sendText(delivery.peerId(), INTERRUPT_NOTE, delivery.agent());
                }
            } catch (RuntimeException e) {
                EventLogger.warn(CATEGORY, "Boot recovery: no interrupt note for conversation %d: %s"
                        .formatted(conversationId, e.getMessage()));
            }
        }
        return notified;
    }

    /** Append the note under the conversation's unanswered request, so no later sweep notes it again; null when answered. */
    private static @Nullable Delivery closeInterrupted(Long conversationId, Instant cutoff) {
        Conversation conversation = Conversation.findById(conversationId);
        if (conversation == null || !sentBeforeCutoff(conversation, cutoff)
                || !ConversationService.isUnanswered(conversation)) return null;
        ConversationService.appendAssistantMessage(conversation, INTERRUPT_NOTE, null);
        return new Delivery(ChannelRegistry.forConversation(conversation), conversation.peerId, conversation.agent);
    }

    /** Re-read inside the transaction: a live message may have arrived since the scan, and a delivered report is no request. */
    private static boolean sentBeforeCutoff(Conversation conversation, Instant cutoff) {
        List<Message> newest = Message.find(
                "conversation = ?1 AND role = ?2 AND subagentRunId IS NULL ORDER BY createdAt DESC, id DESC",
                conversation, MessageRole.USER.value).fetch(1);
        if (newest.isEmpty()) return false;
        var request = newest.getFirst();
        return request.messageKind == null && request.createdAt.isBefore(cutoff)
                && !request.createdAt.isBefore(cutoff.minus(NOTE_LOOKBACK));
    }

    private static int requeueStored(Instant cutoff) {
        List<Stored> stored = Tx.run(() -> PendingInbound.createdBefore(cutoff).stream()
                .map(row -> new Stored(row.conversation.id, ConversationQueue.restore(row)))
                .toList());
        var byConversation = stored.stream().collect(Collectors.groupingBy(Stored::conversationId,
                LinkedHashMap::new, Collectors.mapping(Stored::message, Collectors.toList())));
        byConversation.forEach((conversationId, messages) -> {
            // The first starts a turn unless one of this process holds the conversation.
            long generation = ConversationQueue.tryAcquireRestored(conversationId, messages);
            if (generation != ConversationQueue.NOT_ACQUIRED) {
                QueueDrainOrchestrator.runOwned(conversationId, generation, List.of(messages.getFirst()));
            }
        });
        return stored.size();
    }
}
