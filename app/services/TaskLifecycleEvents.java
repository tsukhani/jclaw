package services;

import models.Task;
import models.TaskRun;

import java.time.Duration;
import java.time.Instant;

/**
 * The structured {@code event_log} entries that bracket a JClaw
 * task fire. JCLAW-21 specifies five lifecycle points; three are
 * in scope for this story, two are stubbed for the delivery
 * sibling story.
 *
 * <h3>The five points</h3>
 * <ul>
 *   <li>{@link #STARTED} — emitted by {@link TaskExecutor} just
 *       before the agent body runs. Marks the start-of-fire so
 *       monitoring dashboards (JCLAW-22) can render "running" pills
 *       without depending on the heartbeat to catch up.</li>
 *   <li>{@link #COMPLETED} — emitted by {@link TaskExecutor} after
 *       the agent body returns and the TaskRun row closes to
 *       COMPLETED. Carries duration so operators can spot drift
 *       without joining to the TaskRun row.</li>
 *   <li>{@link #FAILED} — emitted by
 *       {@link JClawFailureHandler#decide} when the failure is
 *       permanent (non-transient OR retry budget exhausted). NOT
 *       emitted on transient retries — those generate WARN entries
 *       under the {@code task} category instead.</li>
 *   <li>{@link #DELIVERED} — for the delivery sibling story; not
 *       emitted yet.</li>
 *   <li>{@link #DELIVERY_FAILED} — same.</li>
 * </ul>
 *
 * <h3>Why a separate helper class</h3>
 * The three emit methods centralise the format of the
 * {@code details} JSON payload (run id + duration on COMPLETED,
 * reason on FAILED, etc.) so consumers parsing the events for
 * dashboards / alerts can rely on a stable shape. Inlining the
 * format strings into the call sites would let it drift the next
 * time someone tweaks a message.
 *
 * <p>{@link services.EventLogger} is the underlying batch sink;
 * this helper just routes to {@code EventLogger.info} /
 * {@code error} with the right category constants.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public final class TaskLifecycleEvents {

    public static final String STARTED = "TASK_STARTED";
    public static final String COMPLETED = "TASK_COMPLETED";
    public static final String FAILED = "TASK_FAILED";
    // Reserved for the JCLAW-21 sibling delivery story. Declared
    // here so the taxonomy is one-stop and so dashboard consumers
    // can group on the prefix.
    public static final String DELIVERED = "TASK_DELIVERED";
    public static final String DELIVERY_FAILED = "TASK_DELIVERY_FAILED";

    private TaskLifecycleEvents() {}

    /**
     * Mark fire start. {@code run.startedAt} is already populated
     * by the caller (TaskExecutor opens the TaskRun row first).
     */
    public static void started(Task task, TaskRun run) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' fire started (run=%d)".formatted(task.name, run.id);
        var details = detailsJson(
                "task_id", task.id,
                "run_id", run.id,
                "type", task.type != null ? task.type.name() : null);
        EventLogger.record("INFO", STARTED, agentName, null, message, details);
    }

    /**
     * Mark successful completion. {@code durationMs} comes from
     * the TaskRun row (completedAt − startedAt) — caller passes it
     * in rather than recomputing so the value matches what the
     * monitoring UI reads back.
     */
    public static void completed(Task task, TaskRun run, long durationMs) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' completed in %dms (run=%d)".formatted(
                task.name, durationMs, run.id);
        var details = detailsJson(
                "task_id", task.id,
                "run_id", run.id,
                "duration_ms", durationMs);
        EventLogger.record("INFO", COMPLETED, agentName, null, message, details);
    }

    /**
     * Mark permanent failure. Only call when the failure is
     * terminal (permanent error OR retries exhausted) —
     * transient failures with retry budget remaining emit WARN
     * entries via {@link JClawFailureHandler}'s usual logging
     * rather than this lifecycle event.
     */
    public static void failed(Task task, TaskRun run, String reason) {
        var agentName = task.agent != null ? task.agent.name : null;
        long durationMs = run != null && run.startedAt != null
                ? Duration.between(run.startedAt, Instant.now()).toMillis()
                : 0L;
        var message = "Task '%s' permanently failed: %s".formatted(task.name, reason);
        var details = detailsJson(
                "task_id", task.id,
                "run_id", run != null ? run.id : null,
                "duration_ms", durationMs,
                "reason", reason);
        EventLogger.record("ERROR", FAILED, agentName, null, message, details);
    }

    /**
     * Minimal hand-rolled JSON for the {@code details} column.
     * Used by the info-level events; the structured payload makes
     * dashboard rendering and downstream tooling parsing
     * straightforward. {@code null} values are emitted as JSON
     * null rather than skipped so the schema stays consistent.
     */
    @SuppressWarnings("java:S2629") // String concat is fine; details payloads are tiny
    private static String detailsJson(Object... kvPairs) {
        if (kvPairs == null || kvPairs.length == 0) return "{}";
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("kvPairs must have even length");
        }
        var sb = new StringBuilder("{");
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(kvPairs[i]).append("\":");
            Object value = kvPairs[i + 1];
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number) {
                sb.append(value);
            } else {
                // Strings and enums — JSON-escape the bare minimum
                // (just the double quote and backslash).
                sb.append("\"").append(value.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
