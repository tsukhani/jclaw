package services;

import models.Agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-conversation message queue that serializes agent processing.
 * Prevents state corruption when multiple messages arrive simultaneously.
 *
 * Three modes:
 * - "queue" (default): FIFO, process one at a time
 * - "collect": Batch pending messages into a single prompt
 * - "interrupt": Cancel in-flight via {@link #cancellationFlag(Long)}, queue new message for drain
 */
public class ConversationQueue {

    private static final ConcurrentHashMap<Long, QueueState> queues = new ConcurrentHashMap<>();
    private static final int MAX_QUEUE_SIZE = 20;

    public record QueuedMessage(String text, String channelType, String peerId, Agent agent) {}

    static class QueueState {
        final ArrayDeque<QueuedMessage> pending = new ArrayDeque<>();
        boolean processing = false; // all reads/writes guarded by synchronized(this)
        String mode = "queue";
        /** Signals in-flight processing to cancel. Set by interrupt mode, cleared on drain. */
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /**
         * Wall-clock ms of the last activity that touched this state (tryAcquire / drain /
         * releaseOwnership). Used by {@link #evictIdle} to skip recently-active entries.
         * Volatile so the read path of evictIdle's iterator doesn't have to take the lock
         * before deciding whether a state is even a candidate; the authoritative check
         * (under the per-state lock) re-reads it.
         */
        volatile long lastActivityMs = System.currentTimeMillis();

        synchronized boolean tryStartProcessing() {
            if (processing) return false;
            processing = true;
            return true;
        }

        synchronized void finishProcessing() {
            processing = false;
        }

        synchronized boolean isProcessing() {
            return processing;
        }
    }

    /**
     * Try to acquire the conversation for processing.
     * Returns true if acquired (caller should process), false if message was queued.
     */
    public static boolean tryAcquire(Long conversationId, QueuedMessage message) {
        var state = queues.computeIfAbsent(conversationId, _ -> new QueueState());

        // Read config BEFORE entering the synchronized block — ConfigService.get()
        // may hit the DB, and we don't want to hold the state lock during I/O.
        var mode = ConfigService.get("agent." + message.agent().name + ".queue.mode", "queue");

        // All mode-read + processing-flag + queue decisions must be atomic to prevent
        // TOCTOU races where two threads both believe they acquired the conversation.
        synchronized (state) {
            state.mode = mode;
            state.lastActivityMs = System.currentTimeMillis();

            if (!state.processing) {
                state.processing = true;
                return true; // Caller should process this message
            }

            // Agent is busy -- handle based on mode
            if ("interrupt".equals(mode)) {
                // Signal the in-flight processor to cancel, then queue this message
                // so drain() will pick it up after the current run finishes.
                state.cancelled.set(true);
                state.pending.clear();
                state.pending.addLast(message);
                EventLogger.info("queue", message.agent().name, message.channelType(),
                        "Interrupt mode: signalled cancellation for conversation %d, queued new message"
                                .formatted(conversationId));
                return false;
            }

            // Queue the message (queue and collect modes)
            if (state.pending.size() >= MAX_QUEUE_SIZE) {
                state.pending.pollFirst(); // Drop oldest
                EventLogger.warn("queue", message.agent().name, message.channelType(),
                        "Queue overflow for conversation %d, dropped oldest message".formatted(conversationId));
            }
            state.pending.addLast(message);
        }

        EventLogger.info("queue", message.agent().name, message.channelType(),
                "Message queued for conversation %d (position: %d)"
                        .formatted(conversationId, getQueueSize(conversationId)));

        return false; // Message was queued, caller should NOT process
    }

    /**
     * Returns an {@link AtomicBoolean} that becomes {@code true} when interrupt
     * mode signals that the in-flight processor should cancel. Callers (e.g.
     * {@link agents.AgentRunner}) should poll this in their processing loops.
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
     */
    public static List<QueuedMessage> drain(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return List.of();

        synchronized (state) {
            state.cancelled.set(false);
            state.lastActivityMs = System.currentTimeMillis();

            if (state.pending.isEmpty()) {
                // Nothing to do — release ownership and return. The next
                // inbound message will re-acquire via tryAcquire.
                state.finishProcessing();
                return List.of();
            }

            if ("collect".equals(state.mode)) {
                var all = new ArrayList<>(state.pending);
                state.pending.clear();
                // Ownership transfers to the caller — processing stays true.
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
    public static void releaseOwnership(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return;
        synchronized (state) {
            state.lastActivityMs = System.currentTimeMillis();
            state.finishProcessing();
        }
    }

    /**
     * Format collected messages into a single prompt.
     */
    public static String formatCollectedMessages(List<QueuedMessage> messages) {
        if (messages.size() == 1) return messages.getFirst().text();

        var sb = new StringBuilder("[Queued messages while agent was busy]\n");
        for (int i = 0; i < messages.size(); i++) {
            sb.append("\n---\nMessage %d:\n%s\n".formatted(i + 1, messages.get(i).text()));
        }
        return sb.toString();
    }

    /**
     * Get queue position for a conversation (0 if not queued).
     */
    public static int getQueueSize(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return 0;
        synchronized (state) {
            return state.pending.size();
        }
    }

    /**
     * Check if a conversation is currently being processed.
     */
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
