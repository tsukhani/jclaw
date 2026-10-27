package services;

import agents.DangerousActionGate;
import agents.TurnCancellation;
import com.google.gson.reflect.TypeToken;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.PendingInbound;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import utils.GsonHolder;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-conversation message queue that serializes agent processing.
 * Prevents state corruption when multiple messages arrive simultaneously.
 *
 * Three modes:
 * - "queue" (default): FIFO, process one at a time
 * - "collect": Batch pending messages into a single prompt
 * - "interrupt": Cancel in-flight via {@link #cancellationFlag(Long)}, queue new message for drain
 *
 * A message queued from an external channel is also kept in a {@link PendingInbound} row until its
 * USER row is appended, so a restart re-queues it (JCLAW-1521).
 */
public class ConversationQueue {

    /** Default queue mode and the event-log category tag for queue events. */
    private static final String QUEUE = "queue";

    private static final ConcurrentHashMap<Long, QueueState> queues = new ConcurrentHashMap<>();
    private static final int MAX_QUEUE_SIZE = 20;
    // Process-wide so a state evicted and recreated under a still-running stopped turn never reissues its generation.
    private static final AtomicLong GENERATIONS = new AtomicLong();

    /**
     * @param skipUserAppend JCLAW-273: this message's content is already a persisted
     *                       USER row (a yield-resume announce), so the drain must run
     *                       it without re-appending {@code text}. Carried on the record
     *                       because the enqueue/drain boundary drops call arguments.
     * @param attachments    JCLAW-1514: the staged files the message arrived with, finalized
     *                       onto the drained turn's USER row
     * @param pendingId      JCLAW-1521: the {@link PendingInbound} row that keeps the message across
     *                       a restart, consumed with its USER row; null when it has none
     * @param ownerInitiated JF-148: {@link DangerousActionGate#ownerInitiated} where the message was
     *                       enqueued, which its drained turn runs under. A row a restart restores has
     *                       none recorded, so it drains as a guest's.
     */
    public record QueuedMessage(String text, String channelType, String peerId, Agent agent,
                                boolean skipUserAppend, List<AttachmentService.Input> attachments,
                                @Nullable Long pendingId, boolean ownerInitiated) {
        public QueuedMessage {
            attachments = List.copyOf(attachments);
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent,
                             boolean skipUserAppend, List<AttachmentService.Input> attachments) {
            this(text, channelType, peerId, agent, skipUserAppend, attachments, null,
                    DangerousActionGate.ownerInitiated());
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent) {
            this(text, channelType, peerId, agent, false);
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent, boolean skipUserAppend) {
            this(text, channelType, peerId, agent, skipUserAppend, List.of());
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent,
                             @Nullable List<AttachmentService.Input> attachments) {
            this(text, channelType, peerId, agent, false, attachments == null ? List.of() : attachments);
        }

        private QueuedMessage withPendingId(@Nullable Long id) {
            return new QueuedMessage(text, channelType, peerId, agent, skipUserAppend, attachments, id, ownerInitiated);
        }
    }

    /** Channels that acknowledge a message to the platform before JClaw stores it, so a queued one is kept in a row. */
    public static final Set<String> EXTERNAL_CHANNELS =
            Set.of(ChannelType.TELEGRAM.value, ChannelType.SLACK.value, ChannelType.WHATSAPP.value);

    private static final Type ATTACHMENT_LIST = new TypeToken<List<AttachmentService.Input>>() {}.getType();

    static class QueueState {
        final ArrayDeque<QueuedMessage> pending = new ArrayDeque<>();
        boolean processing = false; // all reads/writes guarded by synchronized(this)
        @Nullable String mode = QUEUE;
        /** Signals in-flight processing to cancel. Set by interrupt mode, cleared on drain, replaced by {@link #stop}. */
        volatile AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Renewed on every acquire and every {@link #stop}; a release carrying another value is a no-op. */
        long generation = 0;
        /** The current owner's own cancel flag, flipped by {@link #stop}; null when the owner registered none. */
        @Nullable AtomicBoolean ownerCancel;
        /**
         * Wall-clock ms of the last activity that touched this state (tryAcquire / drain /
         * releaseOwnership). Used by {@link #evictIdle} to skip recently-active entries.
         * Volatile so the read path of evictIdle's iterator doesn't have to take the lock
         * before deciding whether a state is even a candidate; the authoritative check
         * (under the per-state lock) re-reads it.
         */
        volatile long lastActivityMs = System.currentTimeMillis();

        synchronized void finishProcessing() {
            processing = false;
            ownerCancel = null;
        }

        synchronized boolean isProcessing() {
            return processing;
        }
    }

    /** Returned by {@link #tryAcquireOwnership} when the message was queued instead of acquiring. */
    public static final long NOT_ACQUIRED = -1L;

    /**
     * Try to acquire the conversation for processing.
     *
     * @param conversationId the conversation to acquire
     * @param message        the inbound message asking for processing
     * @return {@code true} when acquired (caller should process the
     *         message), {@code false} when the conversation is already
     *         being processed and the message was queued / handled
     *         per the agent's queue mode
     */
    public static boolean tryAcquire(Long conversationId, QueuedMessage message) {
        return tryAcquireOwnership(conversationId, message, null) != NOT_ACQUIRED;
    }

    /**
     * {@link #tryAcquire} that returns the ownership generation the caller must pass to
     * {@link #drain(Long, long)} / {@link #releaseOwnership(Long, long)}.
     *
     * @param ownerCancel the turn's own cancel flag, which {@link #stop} flips; may be null
     * @return the acquired generation (non-negative), or {@link #NOT_ACQUIRED} when queued
     */
    public static long tryAcquireOwnership(Long conversationId, QueuedMessage message,
                                           @Nullable AtomicBoolean ownerCancel) {
        var state = queues.computeIfAbsent(conversationId, _ -> new QueueState());

        // Read config BEFORE entering the synchronized block — ConfigService.get()
        // may hit the DB, and we don't want to hold the state lock during I/O.
        var mode = ConfigService.get("agent." + message.agent().name + ".queue.mode", QUEUE);

        // All mode-read + processing-flag + queue decisions must be atomic to prevent
        // TOCTOU races where two threads both believe they acquired the conversation.
        // We only DECIDE under the lock; the EventLogger emits (synchronous file-append
        // + a batched DB flush) happen AFTER releasing it, mirroring the "Message queued"
        // log below — never hold the ingress lock across that I/O.
        boolean interrupted;
        boolean droppedOverflow = false;
        @Nullable RuntimeException storeFailure = null;
        AtomicBoolean interruptedFlag = state.cancelled;
        synchronized (state) {
            state.mode = mode;
            state.lastActivityMs = System.currentTimeMillis();

            if (!state.processing) {
                state.processing = true;
                state.ownerCancel = ownerCancel;
                state.generation = GENERATIONS.incrementAndGet();
                return state.generation;
            }

            interrupted = "interrupt".equals(mode);
            var dropped = new ArrayList<QueuedMessage>();
            if (interrupted) {
                // Signal the in-flight processor to cancel, then queue this message
                // so drain() will pick it up after the current run finishes.
                interruptedFlag = state.cancelled;
                interruptedFlag.set(true);
                dropped.addAll(state.pending);
                state.pending.clear();
            } else if (state.pending.size() >= MAX_QUEUE_SIZE) {
                // Queue and collect modes: drop the oldest
                var oldest = state.pending.pollFirst();
                if (oldest != null) dropped.add(oldest);
                droppedOverflow = true;
            }
            // Written under the lock, so no drain can take the message before its row commits.
            var queued = message;
            discardPending(pendingIdsOf(dropped));
            if (isStored(message)) {
                try {
                    queued = message.withPendingId(insertPending(conversationId, message));
                } catch (RuntimeException e) {
                    storeFailure = e;
                }
            }
            state.pending.addLast(queued);
        }

        if (storeFailure != null) {
            EventLogger.warn(QUEUE, message.agent().name, message.channelType(),
                    "Queued message for conversation %d not stored; a restart drops it: %s"
                            .formatted(conversationId, storeFailure.getMessage()));
        }

        if (interrupted) {
            TurnCancellation.cancel(interruptedFlag);
            EventLogger.info(QUEUE, message.agent().name, message.channelType(),
                    "Interrupt mode: signalled cancellation for conversation %d, queued new message"
                            .formatted(conversationId));
            return NOT_ACQUIRED;
        }

        if (droppedOverflow) {
            EventLogger.warn(QUEUE, message.agent().name, message.channelType(),
                    "Queue overflow for conversation %d, dropped oldest message".formatted(conversationId));
        }

        EventLogger.info(QUEUE, message.agent().name, message.channelType(),
                "Message queued for conversation %d (position: %d)"
                        .formatted(conversationId, getQueueSize(conversationId)));

        return NOT_ACQUIRED;
    }

    /**
     * {@link #tryAcquireOwnership} for the stored messages a restart left on one conversation (JCLAW-1521).
     * They predate everything this process received, so in every mode none interrupts: the first acquires
     * when no turn holds the conversation, and the rest queue ahead of the messages that arrived since the
     * restart, in their own order. Overflow drops the oldest, as for any arrival; none is stored again.
     *
     * @param restored the conversation's {@link #restore restored} messages, oldest first; not empty
     * @return the generation when the first acquired and the caller must run it, or {@link #NOT_ACQUIRED}
     *         when every message was queued
     */
    public static long tryAcquireRestored(Long conversationId, List<QueuedMessage> restored) {
        var state = queues.computeIfAbsent(conversationId, _ -> new QueueState());
        var first = restored.getFirst();
        var mode = ConfigService.get("agent." + first.agent().name + ".queue.mode", QUEUE);
        long acquired = NOT_ACQUIRED;
        var dropped = new ArrayList<QueuedMessage>();
        synchronized (state) {
            state.mode = mode;
            var queued = restored;
            if (!state.processing) {
                state.processing = true;
                state.ownerCancel = null;
                state.generation = GENERATIONS.incrementAndGet();
                acquired = state.generation;
                queued = restored.subList(1, restored.size());
            }
            for (var message : queued.reversed()) state.pending.addFirst(message);
            while (state.pending.size() > MAX_QUEUE_SIZE) dropped.add(state.pending.removeFirst());
        }
        if (!dropped.isEmpty()) {
            discardPending(pendingIdsOf(dropped));
            EventLogger.warn(QUEUE, first.agent().name, first.channelType(),
                    "Queue overflow for conversation %d, dropped %d oldest message(s)"
                            .formatted(conversationId, dropped.size()));
        }
        return acquired;
    }

    /**
     * The current ownership generation, for an owned-queue caller that was handed ownership
     * without its generation (the public 3-arg {@code AgentRunner.runWithOwnedQueue}).
     */
    public static long currentGeneration(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return 0;
        synchronized (state) {
            return state.generation;
        }
    }

    /**
     * Operator stop (GH-12): cancel the in-flight turn through its own cancel flag, drop pending
     * messages, and release ownership now, so the next send acquires instead of queueing. The
     * stopped turn's later release carries a stale generation and is a no-op.
     *
     * @return {@code true} when a turn that registered a cancel flag owned the conversation and was
     *         stopped; {@code false}, changing nothing, otherwise
     */
    public static boolean stop(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return false;
        AtomicBoolean stopped;
        @Nullable RuntimeException markerFailure = null;
        synchronized (state) {
            // An owner with no flag of its own (a drained or sync run) could not be told to stop.
            stopped = state.ownerCancel;
            if (!state.processing || stopped == null) return false;
            stopped.set(true);
            // Committed before the release, so the next turn's request cannot land ahead of it.
            try {
                ConversationService.appendStopMarkerIfUnanswered(conversationId);
            } catch (RuntimeException e) {
                markerFailure = e;
            }
            // A channel turn's flag is the conversation's, and the stale drain never clears it: renew it.
            if (stopped == state.cancelled) state.cancelled = new AtomicBoolean(false);
            discardPending(pendingIdsOf(List.copyOf(state.pending)));
            state.pending.clear();
            state.generation = GENERATIONS.incrementAndGet();
            state.finishProcessing();
        }
        if (markerFailure != null) {
            EventLogger.warn(QUEUE, "Stop marker not written for conversation %d: %s"
                    .formatted(conversationId, markerFailure.getMessage()));
        }
        TurnCancellation.cancel(stopped);
        return true;
    }

    /**
     * Write the stop marker while the turn that acquired {@code generation} still owns the
     * conversation, ignoring its cancel flag: a stopped turn's own cancel path writes it, and an
     * operator {@link #stop} that already wrote one has made the generation stale.
     *
     * @return {@code true} when the conversation was still owned (whether or not a marker was due)
     */
    public static boolean writeStopMarkerIfOwner(Long conversationId, long generation) {
        return commitIfOwner(conversationId, generation, null,
                () -> ConversationService.appendStopMarkerIfUnanswered(conversationId));
    }

    /**
     * Run {@code commit} only while the turn that acquired {@code generation} still owns the
     * conversation and its {@code turnCancel} flag (when it has one) is unset. The check and the
     * commit share {@link #stop}'s lock, so a racing write either lands before the stop returns
     * or not at all.
     *
     * @return {@code true} when {@code commit} ran; {@code false}, running nothing, otherwise
     */
    public static boolean commitIfOwner(Long conversationId, long generation, @Nullable AtomicBoolean turnCancel,
                                        Runnable commit) {
        var state = queues.get(conversationId);
        if (state == null) return false;
        synchronized (state) {
            if (!state.processing || state.generation != generation) return false;
            if (turnCancel != null && turnCancel.get()) return false;
            commit.run();
            return true;
        }
    }

    /** {@code /stop}: signal the in-flight turn through the conversation's cancel flag and run its cancel actions. */
    public static void cancelTurn(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return;
        AtomicBoolean flag;
        synchronized (state) {
            // The turn may have released since the caller saw it busy; a set flag would cancel the next one.
            if (!state.processing) return;
            flag = state.cancelled;
            flag.set(true);
        }
        TurnCancellation.cancel(flag);
    }

    /**
     * Run {@code commit} unless {@code turnCancel} is already set, under {@link #stop}'s lock, so a
     * stop either lands first and nothing runs, or after {@code commit} has finished.
     *
     * @return {@code true} when {@code commit} ran
     */
    public static boolean commitUnlessCancelled(Long conversationId, @Nullable AtomicBoolean turnCancel,
                                                Runnable commit) {
        var state = queues.get(conversationId);
        if (state == null || turnCancel == null) {
            commit.run();
            return true;
        }
        synchronized (state) {
            if (turnCancel.get()) return false;
            commit.run();
            return true;
        }
    }

    /**
     * Returns an {@link AtomicBoolean} that becomes {@code true} when interrupt
     * mode signals that the in-flight processor should cancel. Callers (e.g.
     * {@link agents.AgentRunner}) should poll this in their processing loops.
     *
     * @param conversationId the conversation whose cancel flag is needed
     * @return the conversation's cancellation flag, or a never-canceled
     *         flag when the conversation has no state yet
     */
    public static AtomicBoolean cancellationFlag(Long conversationId) {
        var state = queues.get(conversationId);
        return state != null ? state.cancelled : new AtomicBoolean(false);
    }

    /**
     * Called after processing completes. Returns the next message(s) to process,
     * or empty list if the queue is empty.
     *
     * <p><b>Ownership semantics (JCLAW-117)</b>
     * When this method returns non-empty, the {@code processing} flag STAYS
     * {@code true} — the caller inherits ownership of the conversation queue
     * and is responsible for releasing it (via {@link #releaseOwnership},
     * or by re-entering {@code drain} after their run completes; the re-entry
     * will see an empty pending deque and clear the flag itself).
     *
     * <p>Keeping ownership atomic across the pop closes a prior race where a
     * new inbound message could {@code tryAcquire} in the gap between drain
     * popping B and the caller actually starting to run B — letting C
     * overtake an older queued message.
     *
     * <p>Does NOT remove the QueueState from the map — avoids a race between
     * {@code remove()} and a concurrent {@code computeIfAbsent()} that would
     * permanently orphan the conversation. Idle entries are harmless (empty
     * deque + processing=false) and will be reused on the next message.
     *
     * <p>In "collect" mode, returns all pending messages combined. In "queue"
     * mode, returns just the next message.
     *
     * @param conversationId the conversation being drained
     * @param generation     the generation the caller acquired with; a stale one
     *                       (the conversation was {@link #stop}ped since) changes nothing
     * @return the next message(s) to process, or an empty list when the
     *         queue is empty (in which case ownership is released)
     */
    public static List<QueuedMessage> drain(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return List.of();

        synchronized (state) {
            if (state.generation != generation) return List.of();
            // A fresh flag, not a reset: a stopped channel turn still holds the old one and must stay canceled.
            if (state.cancelled.get()) state.cancelled = new AtomicBoolean(false);
            state.lastActivityMs = System.currentTimeMillis();

            if (state.pending.isEmpty()) {
                // Nothing to do — release ownership and return. The next
                // inbound message will re-acquire via tryAcquire.
                state.finishProcessing();
                return List.of();
            }

            // Ownership transfers to the caller — processing stays true — and a drained
            // run registers no cancel flag of its own.
            state.ownerCancel = null;
            if ("collect".equals(state.mode)) {
                var all = new ArrayList<>(state.pending);
                state.pending.clear();
                return all;
            }

            // Queue mode
            var next = state.pending.pollFirst();
            if (next == null) {
                // Shouldn't happen given the isEmpty check, but be defensive.
                state.finishProcessing();
                return List.of();
            }
            return List.of(next);
        }
    }

    /**
     * Release queue ownership explicitly. Callers that received a non-empty
     * result from {@link #drain} must either call this on completion or
     * re-invoke {@code drain} (which releases ownership automatically when
     * the pending deque is empty).
     *
     * <p>This is the fail-safe exit path for drained-message processors —
     * their {@code finally} block calls this to guarantee {@code processing}
     * flips back to false even when the run throws an exception that
     * short-circuits the normal re-drain.
     */
    public static void releaseOwnership(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return;
        synchronized (state) {
            if (state.generation != generation) return;
            state.lastActivityMs = System.currentTimeMillis();
            state.finishProcessing();
        }
    }

    /** Unfenced {@link #drain(Long, long)} against the current generation; tests only. */
    public static List<QueuedMessage> drain(Long conversationId) {
        return drain(conversationId, currentGeneration(conversationId));
    }

    /** Unfenced {@link #releaseOwnership(Long, long)} against the current generation; tests only. */
    public static void releaseOwnership(Long conversationId) {
        releaseOwnership(conversationId, currentGeneration(conversationId));
    }

    /** Drops the conversation's in-memory state as a restart does, its owner and pending messages included; tests only. */
    public static void forgetForTest(Long conversationId) {
        queues.remove(conversationId);
    }

    /** The rows of {@code messages} that carry one. */
    public static List<Long> pendingIdsOf(List<QueuedMessage> messages) {
        return messages.stream().map(QueuedMessage::pendingId).filter(Objects::nonNull).toList();
    }

    /**
     * Delete the rows of messages that will never run, so a restart cannot resurrect them. A failure is
     * logged, not thrown: the message is already gone from the queue.
     */
    public static void discardPending(Collection<Long> pendingIds) {
        if (pendingIds.isEmpty()) return;
        try {
            Tx.run(() -> PendingInbound.deleteByIds(pendingIds));
        } catch (RuntimeException e) {
            EventLogger.warn(QUEUE, "Stored message(s) %s not deleted; a restart re-queues them: %s"
                    .formatted(pendingIds, e.getMessage()));
        }
    }

    /** The queued message a stored row holds, carrying the row's id so the drain consumes it. */
    public static QueuedMessage restore(PendingInbound row) {
        List<AttachmentService.Input> attachments = row.attachments == null
                ? List.of() : GsonHolder.GSON.fromJson(row.attachments, ATTACHMENT_LIST);
        return new QueuedMessage(row.text, row.channelType, row.peerId, row.agent, false, attachments, row.id, false);
    }

    private static boolean isStored(QueuedMessage message) {
        return message.pendingId() == null && !message.skipUserAppend() && message.agent().id != null
                && EXTERNAL_CHANNELS.contains(message.channelType());
    }

    private static Long insertPending(Long conversationId, QueuedMessage message) {
        Supplier<Long> insert = () -> Tx.run(() -> {
            var row = new PendingInbound();
            row.conversation = JPA.em().getReference(Conversation.class, conversationId);
            row.agent = JPA.em().getReference(Agent.class, message.agent().id);
            row.channelType = message.channelType();
            row.peerId = message.peerId();
            row.text = message.text();
            row.attachments = message.attachments().isEmpty() ? null : GsonHolder.GSON.toJson(message.attachments());
            row.save();
            return row.id;
        });
        if (!JPA.isInsideTransaction()) return insert.get();
        // A caller's transaction commits after the lock is released, when a drain may already have run the message.
        try {
            return CompletableFuture.supplyAsync(insert, r -> Thread.ofVirtual().start(r)).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /**
     * Format collected messages into a single prompt.
     *
     * @param messages the queued messages to combine
     * @return the single message verbatim when only one was queued; a
     *         labeled multi-message block when multiple are present
     */
    public static String formatCollectedMessages(List<QueuedMessage> messages) {
        if (messages.size() == 1) return messages.getFirst().text();

        var sb = new StringBuilder("[Queued messages while agent was busy]\n");
        for (int i = 0; i < messages.size(); i++) {
            sb.append("\n---\nMessage %d:\n%s\n".formatted(i + 1, messages.get(i).text()));
        }
        return sb.toString();
    }

    /** Number of pending messages waiting in a conversation's queue (0 when it has no state). */
    public static int getQueueSize(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return 0;
        synchronized (state) {
            return state.pending.size();
        }
    }

    /** True when the conversation has an owner currently running a turn against it. */
    public static boolean isBusy(Long conversationId) {
        var state = queues.get(conversationId);
        return state != null && state.isProcessing();
    }

    /**
     * Periodic sweep that removes idle {@link QueueState} entries whose
     * {@code lastActivityMs} is older than {@code olderThanMs} ago AND which
     * are quiescent (no pending messages, not processing, no cancellation
     * pending). Called by {@code jobs.ConversationQueueEvictionJob} (JCLAW-286).
     *
     * <p>The per-state check runs under the state's monitor so a concurrent
     * {@link #tryAcquire} that just did {@code computeIfAbsent} cannot lose
     * its state to a racing eviction — the {@code synchronized (state)} block
     * in {@code tryAcquire} stamps {@code lastActivityMs} and flips
     * {@code processing}, both of which this sweep observes before removing.
     *
     * <p>{@link ConcurrentHashMap}'s entry iterator is safe for concurrent
     * removal via {@link java.util.Iterator#remove()}.
     *
     * @param olderThanMs idle threshold in ms — entries whose
     *                    {@code lastActivityMs} is older than
     *                    {@code now - olderThanMs} are eligible for
     *                    eviction
     * @return number of entries evicted (visible to tests)
     */
    public static int evictIdle(long olderThanMs) {
        var threshold = System.currentTimeMillis() - olderThanMs;
        var evicted = 0;
        var it = queues.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var state = entry.getValue();
            synchronized (state) {
                if (state.lastActivityMs <= threshold
                        && state.pending.isEmpty()
                        && !state.processing
                        && !state.cancelled.get()) {
                    it.remove();
                    evicted++;
                }
            }
        }
        return evicted;
    }
}
