package services;

import models.EventLog;
import play.Logger;

public class EventLogger {

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

        // Persist to DB — use Tx.run for virtual thread safety
        try {
            Tx.run(() -> {
                var event = new EventLog();
                event.level = level;
                event.category = category;
                event.agentId = agentId;
                event.channel = channel;
                event.message = message != null && message.length() > 500
                        ? message.substring(0, 497) + "..." : message;
                event.details = details;
                event.save();
            });
        } catch (Exception e) {
            Logger.warn("Failed to persist event log: %s", e.getMessage());
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
