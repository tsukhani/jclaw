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
 * - "interrupt": Cancel in-flight via {@link #isCancelled}, queue new message for drain
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
     * or empty list if queue is empty.
     *
     * In "collect" mode, returns all pending messages combined.
     * In "queue" mode, returns just the next message.
     *
     * Does NOT remove the QueueState from the map — avoids a race between
     * {@code remove()} and a concurrent {@code computeIfAbsent()} that would
     * permanently orphan the conversation. Idle entries are harmless (empty
     * deque + processing=false) and will be reused on the next message.
     */
    public static List<QueuedMessage> drain(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return List.of();

        synchronized (state) {
            state.finishProcessing();
            state.cancelled.set(false);

            if (state.pending.isEmpty()) {
                return List.of();
            }

            if ("collect".equals(state.mode)) {
                var all = new ArrayList<>(state.pending);
                state.pending.clear();
                return all;
            }

            // Queue mode -- return next message
            var next = state.pending.pollFirst();
            return next != null ? List.of(next) : List.of();
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
}
