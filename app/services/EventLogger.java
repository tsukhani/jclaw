package services;

import models.EventLog;
import play.Logger;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class EventLogger {

    private EventLogger() {}

    /** Category for rejected webhooks that failed platform signature verification (JCLAW-16). */
    public static final String WEBHOOK_SIGNATURE_FAILURE = "WEBHOOK_SIGNATURE_FAILURE";

    // JCLAW-272: Subagent lifecycle event taxonomy. Emission lands later
    // (JCLAW-265 spawn, JCLAW-266 limit exceeded, JCLAW-270/273 completion);
    // this story only establishes the categories and typed signatures so
    // those call sites have a clean target.
    public static final String SUBAGENT_SPAWN = "SUBAGENT_SPAWN";
    public static final String SUBAGENT_COMPLETE = "SUBAGENT_COMPLETE";
    public static final String SUBAGENT_ERROR = "SUBAGENT_ERROR";
    public static final String SUBAGENT_KILL = "SUBAGENT_KILL";
    public static final String SUBAGENT_LIMIT_EXCEEDED = "SUBAGENT_LIMIT_EXCEEDED";
    public static final String SUBAGENT_TIMEOUT = "SUBAGENT_TIMEOUT";

    private static final ConcurrentLinkedQueue<EventLog> pending = new ConcurrentLinkedQueue<>();
    private static final int BATCH_SIZE = 20;

    @SuppressWarnings("java:S6213") // 'record' as method name predates the restricted identifier; rename would churn callers/tests with no real ambiguity
    public static void record(String level, String category, String message, String details) {
        record(level, category, null, null, message, details);
    }

    @SuppressWarnings("java:S6213") // 'record' as method name predates the restricted identifier; rename would churn callers/tests with no real ambiguity
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

    // ----- JCLAW-272: typed subagent lifecycle helpers ---------------------
    //
    // Each helper builds a JSON details payload with a consistent shape so
    // downstream consumers (frontend events page, future analytics) can
    // parse without category-specific branches. Fields are nullable —
    // not every category carries every key (e.g. LIMIT_EXCEEDED has no
    // child yet, TIMEOUT has no mode/context distinction worth recording).
    // The parent agent id also flows into EventLog.agentId so the existing
    // per-agent filter on /api/logs still works.

    /** SUBAGENT_SPAWN — parent dispatched a child agent. */
    public static void recordSubagentSpawn(String parentAgentId, String childAgentId,
                                           String runId, String mode, String context) {
        record("INFO", SUBAGENT_SPAWN, parentAgentId, null,
                "Subagent spawned",
                subagentDetails(parentAgentId, childAgentId, runId, mode, context, null, null));
    }

    /** SUBAGENT_COMPLETE — child finished successfully. {@code outcome} is a short tag (e.g. "ok"). */
    public static void recordSubagentComplete(String parentAgentId, String childAgentId,
                                              String runId, String mode, String context,
                                              String outcome) {
        record("INFO", SUBAGENT_COMPLETE, parentAgentId, null,
                "Subagent completed",
                subagentDetails(parentAgentId, childAgentId, runId, mode, context, outcome, null));
    }

    /** SUBAGENT_ERROR — child raised an unrecoverable error. */
    public static void recordSubagentError(String parentAgentId, String childAgentId,
                                           String runId, String mode, String context,
                                           String reason) {
        record("ERROR", SUBAGENT_ERROR, parentAgentId, null,
                "Subagent error",
                subagentDetails(parentAgentId, childAgentId, runId, mode, context, null, reason));
    }

    /** SUBAGENT_KILL — operator or supervisor terminated a running child. */
    public static void recordSubagentKill(String parentAgentId, String childAgentId,
                                          String runId, String mode, String context,
                                          String reason) {
        record("WARN", SUBAGENT_KILL, parentAgentId, null,
                "Subagent killed",
                subagentDetails(parentAgentId, childAgentId, runId, mode, context, null, reason));
    }

    /** SUBAGENT_LIMIT_EXCEEDED — spawn refused (concurrency, depth, etc.). No child id yet. */
    public static void recordSubagentLimitExceeded(String parentAgentId, String reason) {
        record("WARN", SUBAGENT_LIMIT_EXCEEDED, parentAgentId, null,
                "Subagent spawn limit exceeded",
                subagentDetails(parentAgentId, null, null, null, null, null, reason));
    }

    /** SUBAGENT_TIMEOUT — child exceeded its wall-clock budget. */
    public static void recordSubagentTimeout(String parentAgentId, String runId) {
        record("WARN", SUBAGENT_TIMEOUT, parentAgentId, null,
                "Subagent timed out",
                subagentDetails(parentAgentId, null, runId, null, null, null, null));
    }

    @SuppressWarnings("java:S107") // intentional one-arg-per-payload-key; bundling into a DTO buys nothing here
    private static String subagentDetails(String parentAgentId, String childAgentId,
                                          String runId, String mode, String context,
                                          String outcome, String reason) {
        // LinkedHashMap preserves field order in the rendered JSON, which keeps
        // tail -f tail of details readable when humans skim the events page.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("parent_agent_id", parentAgentId);
        payload.put("child_agent_id", childAgentId);
        payload.put("run_id", runId);
        payload.put("mode", mode);          // "session" | "inline"
        payload.put("context", context);    // "fresh" | "inherit"
        payload.put("outcome", outcome);
        payload.put("reason", reason);
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }
}
