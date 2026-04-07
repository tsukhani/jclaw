package services;

import models.Agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-conversation message queue that serializes agent processing.
 * Prevents state corruption when multiple messages arrive simultaneously.
 *
 * Three modes:
 * - "queue" (default): FIFO, process one at a time
 * - "collect": Batch pending messages into a single prompt
 * - "interrupt": Cancel in-flight, process new message immediately
 */
public class ConversationQueue {

    private static final ConcurrentHashMap<Long, QueueState> queues = new ConcurrentHashMap<>();
    private static final int MAX_QUEUE_SIZE = 20;

    public record QueuedMessage(String text, String channelType, String peerId, Agent agent) {}

    static class QueueState {
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<QueuedMessage> pending = new ArrayDeque<>();
        volatile boolean processing = false;
        volatile String mode = "queue";

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

        // Load queue mode from config
        state.mode = ConfigService.get("agent." + message.agent().name + ".queue.mode", "queue");

        if (state.tryStartProcessing()) {
            return true; // Caller should process this message
        }

        // Agent is busy -- handle based on mode
        if ("interrupt".equals(state.mode)) {
            // Clear pending and let caller process (caller is responsible for cancelling in-flight)
            state.pending.clear();
            return true;
        }

        // Queue the message (queue and collect modes)
        synchronized (state) {
            if (state.pending.size() >= MAX_QUEUE_SIZE) {
                state.pending.pollFirst(); // Drop oldest
                EventLogger.warn("queue", message.agent().name, message.channelType(),
                        "Queue overflow for conversation %d, dropped oldest message".formatted(conversationId));
            }
            state.pending.addLast(message);
        }

        EventLogger.info("queue", message.agent().name, message.channelType(),
                "Message queued for conversation %d (position: %d)"
                        .formatted(conversationId, state.pending.size()));

        return false; // Message was queued, caller should NOT process
    }

    /**
     * Called after processing completes. Returns the next message(s) to process,
     * or empty list if queue is empty.
     *
     * In "collect" mode, returns all pending messages combined.
     * In "queue" mode, returns just the next message.
     */
    public static List<QueuedMessage> drain(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return List.of();

        state.finishProcessing();

        synchronized (state) {
            if (state.pending.isEmpty()) {
                queues.remove(conversationId);
                return List.of();
            }

            if ("collect".equals(state.mode)) {
                // Return all pending messages
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
        return state != null ? state.pending.size() : 0;
    }

    /**
     * Check if a conversation is currently being processed.
     */
    public static boolean isBusy(Long conversationId) {
        var state = queues.get(conversationId);
        return state != null && state.isProcessing();
    }
}
