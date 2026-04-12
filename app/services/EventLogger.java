package services;

import models.EventLog;
import play.Logger;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class EventLogger {

    private static final ConcurrentLinkedQueue<EventLog> pending = new ConcurrentLinkedQueue<>();
    private static final int BATCH_SIZE = 20;

    public static void record(String level, String category, String message, String details) {
        record(level, category, null, null, message, details);
    }

    public static void record(String level, String category, String agentId, String channel,
                              String message, String details) {
        // Log to SLF4J first (always safe)
        var logMessage = "[%s/%s] %s".formatted(category, level, message);
        switch (level) {
            case "ERROR" -> Logger.error(logMessage);
            case "WARN" -> Logger.warn(logMessage);
            default -> Logger.info(logMessage);
        }

        // Queue for batch persistence — avoids opening a new transaction per log entry
        // when called from virtual threads (e.g., during tool-execution loops).
        var event = new EventLog();
        event.level = level;
        event.category = category;
        event.agentId = agentId;
        event.channel = channel;
        event.message = message != null && message.length() > 500
                ? message.substring(0, 497) + "..." : message;
        event.details = details;
        pending.add(event);

        if (pending.size() >= BATCH_SIZE) {
            flush();
        }
    }

    /**
     * Discard all queued events without persisting. For test isolation only.
     */
    public static void clear() {
        pending.clear();
    }

    /**
     * Persist all queued events in a single transaction. Call at natural
     * boundaries (end of agent turn, end of request) or when the batch
     * threshold is reached.
     */
    public static void flush() {
        var batch = new ArrayList<EventLog>();
        EventLog e;
        while ((e = pending.poll()) != null) batch.add(e);
        if (batch.isEmpty()) return;

        try {
            Tx.run(() -> { for (var ev : batch) ev.save(); });
        } catch (Exception ex) {
            Logger.warn("Failed to flush %d event logs: %s", batch.size(), ex.getMessage());
        }
    }

    public static void info(String category, String message) {
        record("INFO", category, message, null);
    }

    public static void info(String category, String message, String details) {
        record("INFO", category, message, details);
    }

    public static void info(String category, String agentId, String channel, String message) {
        record("INFO", category, agentId, channel, message, null);
    }

    public static void warn(String category, String message) {
        record("WARN", category, message, null);
    }

    public static void warn(String category, String message, String details) {
        record("WARN", category, message, details);
    }

    public static void warn(String category, String agentId, String channel, String message) {
        record("WARN", category, agentId, channel, message, null);
    }

    public static void error(String category, String message) {
        record("ERROR", category, message, null);
    }

    public static void error(String category, String message, String details) {
        record("ERROR", category, message, details);
    }

    public static void error(String category, String agentId, String channel, String message) {
        record("ERROR", category, agentId, channel, message, null);
    }

    public static void error(String category, String message, Throwable t) {
        record("ERROR", category, message, t.toString());
    }
}
