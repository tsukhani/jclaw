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
 * repeat what it already did, and each stored message that never started is queued again. The
 * conversation that asked for the restart gets {@link #RESTART_BACK_NOTE} in its place, on any channel
 * (JF-354): its turn was cut off by the restart it asked for, and resending would restart JClaw again.
 */
public final class InboundRecoverySweep {

    public static final String INTERRUPT_NOTE =
            "This response was interrupted — please send your message again.";

    /** What the conversation that asked for the restart gets in place of {@link #INTERRUPT_NOTE}. */
    public static final String RESTART_BACK_NOTE = "JClaw is back after the restart you asked for.";

    /**
     * How far before the previous run's last write a request still gets the note. Counted from the stop, not
     * the boot, so an outage of any length still notes the turn it cut off; anything older failed long ago.
     */
    public static final Duration NOTE_LOOKBACK = Duration.ofHours(1);

    private static final String CATEGORY = "queue";

    public record Outcome(int requeued, int notified) {}

    private record Delivery(@Nullable Channel channel, String peerId, Agent agent) {}

    private record Stored(Long conversationId, ConversationQueue.QueuedMessage message) {}

    private InboundRecoverySweep() {}

    /**
     * The conversation a restart's record names, when the record is from before {@code bootCutoff} and within
     * {@link #NOTE_LOOKBACK} of the previous run's last write, either side; null otherwise. A record further off
     * belongs to a restart whose helper died early, or to a database restored since.
     *
     * @param stoppedAt the previous run's last write, from {@link #previousRunStopped}; null when there is none
     */
    public static @Nullable Long restartAsker(RestartService.@Nullable RestartRequest request, Instant bootCutoff,
                                              @Nullable Instant stoppedAt) {
        if (request == null) return null;
        var at = request.requestedAt();
        if (stoppedAt == null || !at.isBefore(bootCutoff)
                || at.isBefore(stoppedAt.minus(NOTE_LOOKBACK)) || at.isAfter(stoppedAt.plus(NOTE_LOOKBACK))) {
            EventLogger.info(CATEGORY, "Boot recovery: the restart record for conversation %d falls outside the previous run, so nobody is told JClaw is back"
                    .formatted(request.conversationId()));
            return null;
        }
        return request.conversationId();
    }

    /**
     * @param bootCutoff        rows from before it belong to a process that no longer runs
     * @param stoppedAt         the previous run's last write, from {@link #previousRunStopped}; null when there is none
     * @param placeholderEdited conversations whose user already saw the note on an edited Telegram placeholder
     * @param restartAsker      the conversation that asked for the restart, from {@link #restartAsker}; null for none
     */
    public static Outcome run(Instant bootCutoff, @Nullable Instant stoppedAt, Set<Long> placeholderEdited,
                              @Nullable Long restartAsker) {
        // Notes first: a re-queued message's USER row would bury the request it answers.
        boolean toldAsker = restartAsker != null && tellRestartAsker(restartAsker, bootCutoff, placeholderEdited);
        // An asker the notice did not reach still gets the interrupt note, if its turn was cut off.
        int notified = noteInterruptedTurns(bootCutoff, stoppedAt, placeholderEdited, toldAsker ? restartAsker : null);
        int requeued = requeueStored(bootCutoff);
        if (requeued > 0 || notified > 0) {
            EventLogger.info(CATEGORY, "Boot recovery: re-queued %d stored message(s), notified %d interrupted turn(s)"
                    .formatted(requeued, notified));
        }
        return new Outcome(requeued, notified);
    }

    /**
     * Append the notice to the conversation that asked, and send it unless its edited placeholder already says it.
     * Returns whether the notice was added.
     */
    private static boolean tellRestartAsker(Long conversationId, Instant cutoff, Set<Long> placeholderEdited) {
        // A turn of this process owns it, and its own reply comes after the restart.
        if (ConversationQueue.isBusy(conversationId)) {
            EventLogger.info(CATEGORY, "Boot recovery: conversation %d, which asked for the restart, is busy, so it gets no restart notice"
                    .formatted(conversationId));
            return false;
        }
        try {
            var delivery = Tx.run(() -> {
                Conversation conversation = Conversation.findById(conversationId);
                // Re-read in the transaction: under a message sent since the boot, the notice would answer nothing.
                if (conversation == null || hasRequestSince(conversation, cutoff)) return null;
                ConversationService.appendAssistantMessage(conversation, RESTART_BACK_NOTE, null);
                var peerId = conversation.peerId;
                return new Delivery(peerId == null ? null : ChannelRegistry.forConversation(conversation),
                        peerId == null ? "" : peerId, conversation.agent);
            });
            if (delivery == null) {
                EventLogger.info(CATEGORY, "Boot recovery: conversation %d, which asked for the restart, is gone or has a newer message, so it gets no restart notice"
                        .formatted(conversationId));
                return false;
            }
            Runnable undelivered = () -> EventLogger.warn(CATEGORY,
                    "Boot recovery: the restart notice for conversation %d was not delivered".formatted(conversationId));
            var channel = delivery.channel();
            if (channel == null) {
                undelivered.run();
            } else if (!placeholderEdited.contains(conversationId)) {
                channel.sendTextWhenConnected(delivery.peerId(), RESTART_BACK_NOTE, delivery.agent(), undelivered);
            }
            EventLogger.info(CATEGORY, "Boot recovery: added the restart notice to conversation %d, which asked for the restart"
                    .formatted(conversationId));
            return true;
        } catch (RuntimeException e) {
            EventLogger.warn(CATEGORY, "Boot recovery: no restart notice for conversation %d: %s"
                    .formatted(conversationId, e.getMessage()));
            return false;
        }
    }

    private static boolean hasRequestSince(Conversation conversation, Instant cutoff) {
        return Message.count("conversation = ?1 AND role = ?2 AND createdAt >= ?3",
                conversation, MessageRole.USER.value, cutoff) > 0;
    }

    private static int noteInterruptedTurns(Instant cutoff, @Nullable Instant stoppedAt, Set<Long> placeholderEdited,
                                            @Nullable Long restartAsker) {
        if (stoppedAt == null) return 0;
        var since = stoppedAt.minus(NOTE_LOOKBACK);
        // A subagent child carries its parent's channel and peer, or no peer: the user hears once, from the parent.
        List<Long> candidates = Tx.run(() -> JPA.em().createQuery(
                        "SELECT DISTINCT m.conversation.id FROM Message m WHERE m.role = :user"
                                + " AND m.createdAt >= :since AND m.createdAt < :cutoff"
                                + " AND m.conversation.channelType IN :channels"
                                + " AND m.conversation.parentConversation IS NULL AND m.conversation.peerId IS NOT NULL"
                                + " AND NOT EXISTS (SELECT n.id FROM Message n WHERE n.conversation = m.conversation"
                                + " AND n.role = :user AND n.createdAt >= :cutoff)", Long.class)
                .setParameter("user", MessageRole.USER.value)
                .setParameter("since", since)
                .setParameter("cutoff", cutoff)
                .setParameter("channels", ConversationQueue.EXTERNAL_CHANNELS)
                .getResultList());
        int notified = 0;
        for (var conversationId : candidates) {
            // A turn of this process owns it, and its own reply closes the request; the asker has its notice.
            if (ConversationQueue.isBusy(conversationId) || conversationId.equals(restartAsker)) continue;
            try {
                var delivery = Tx.run(() -> closeInterrupted(conversationId, since, cutoff));
                if (delivery == null) continue;
                notified++;
                var channel = delivery.channel();
                if (channel != null && !placeholderEdited.contains(conversationId)) {
                    channel.sendTextWhenConnected(delivery.peerId(), INTERRUPT_NOTE, delivery.agent(),
                            () -> EventLogger.warn(CATEGORY, "Boot recovery: the interrupt note for conversation %d was not delivered"
                                    .formatted(conversationId)));
                }
            } catch (RuntimeException e) {
                EventLogger.warn(CATEGORY, "Boot recovery: no interrupt note for conversation %d: %s"
                        .formatted(conversationId, e.getMessage()));
            }
        }
        return notified;
    }

    /** Append the note under the conversation's unanswered request, so no later sweep notes it again; null when answered. */
    private static @Nullable Delivery closeInterrupted(Long conversationId, Instant since, Instant cutoff) {
        Conversation conversation = Conversation.findById(conversationId);
        if (conversation == null || !sentInWindow(conversation, since, cutoff)
                || !ConversationService.isUnanswered(conversation)) return null;
        ConversationService.appendAssistantMessage(conversation, INTERRUPT_NOTE, null);
        return new Delivery(ChannelRegistry.forConversation(conversation), conversation.peerId, conversation.agent);
    }

    /** The newest message saved before the boot: the nearest record of when the previous run stopped. */
    public static @Nullable Instant previousRunStopped(Instant cutoff) {
        return Tx.run(() -> JPA.em().createQuery(
                        "SELECT MAX(m.createdAt) FROM Message m WHERE m.createdAt < :cutoff", Instant.class)
                .setParameter("cutoff", cutoff)
                .getSingleResult());
    }

    /** Re-read inside the transaction: a live message may have arrived since the scan, and a delivered report is no request. */
    private static boolean sentInWindow(Conversation conversation, Instant since, Instant cutoff) {
        List<Message> newest = Message.find(
                "conversation = ?1 AND role = ?2 AND subagentRunId IS NULL ORDER BY createdAt DESC, id DESC",
                conversation, MessageRole.USER.value).fetch(1);
        if (newest.isEmpty()) return false;
        var request = newest.getFirst();
        return request.messageKind == null && request.createdAt.isBefore(cutoff) && !request.createdAt.isBefore(since);
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
