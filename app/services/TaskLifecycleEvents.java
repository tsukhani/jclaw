package services;

import models.Task;
import models.TaskRun;

import java.time.Duration;
import java.time.Instant;

/**
 * The structured {@code event_log} entries that bracket a JClaw
 * task fire. Five lifecycle points are emitted across the fire's
 * STARTED → terminal → (optional) delivery sequence; a sixth
 * out-of-band point covers operator-visible heartbeat loss
 * ({@link #LOST}).
 *
 * <h2>The lifecycle points</h2>
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
 *   <li>{@link #DELIVERED} — emitted by
 *       {@link TaskExecutor#dispatchDelivery} after a successful
 *       post-completion push through {@link DeliveryDispatcher#dispatchSpec}.
 *       Independent of {@link #COMPLETED} — a fire can complete
 *       without delivery (no {@link models.Task#delivery} configured),
 *       in which case the TaskRun's {@code deliveryStatus} is
 *       {@code NOT_REQUESTED} and no DELIVERED event fires.</li>
 *   <li>{@link #DELIVERY_FAILED} — emitted by
 *       {@link TaskExecutor#dispatchDelivery} when the dispatcher
 *       rejected the push. The TaskRun itself remains COMPLETED —
 *       the body succeeded, only the channel push did not.
 *       Dashboards key on this to distinguish "task broken" from
 *       "channel broken".</li>
 *   <li>{@link #LOST} — emitted by {@link LostTaskDetector} when a
 *       RUNNING TaskRun's db-scheduler heartbeat goes stale past
 *       the threshold. Visibility-only; db-scheduler's own
 *       dead-execution recovery later re-fires the row.</li>
 * </ul>
 *
 * <h2>Why a separate helper class</h2>
 * The {@code recordXxx} emit methods centralise the format of the
 * {@code details} JSON payload (run id + duration on COMPLETED,
 * reason on FAILED, delivery spec on DELIVERED / DELIVERY_FAILED,
 * stale seconds on LOST) so consumers parsing the events for
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

    private static final String KEY_TASK_ID = "task_id";
    private static final String KEY_RUN_ID = "run_id";

    public static final String STARTED = "TASK_STARTED";
    public static final String COMPLETED = "TASK_COMPLETED";
    public static final String FAILED = "TASK_FAILED";
    /**
     * JCLAW-258: emitted when {@link services.LostTaskDetector} flips a
     * RUNNING Task to LOST after observing a stale db-scheduler heartbeat.
     * Visibility-only: db-scheduler's own dead-execution detection
     * recovers the row at a longer threshold, so a TASK_LOST entry is
     * typically followed by a fresh TASK_STARTED / TASK_COMPLETED pair
     * once the re-fire lands.
     */
    public static final String LOST = "TASK_LOST";
    /** Emitted after a successful TaskRun completion whose {@link Task#delivery}
     *  spec was honored by {@link DeliveryDispatcher#dispatchSpec}. */
    public static final String DELIVERED = "TASK_DELIVERED";
    /** Emitted when post-completion delivery dispatch returned a non-OK
     *  {@link DeliveryDispatcher.DispatchResult}. The TaskRun itself is still
     *  COMPLETED — the body succeeded, only the push to the configured channel
     *  failed. Dashboards key on this to distinguish "task broken" from
     *  "channel broken". */
    public static final String DELIVERY_FAILED = "TASK_DELIVERY_FAILED";

    private TaskLifecycleEvents() {}

    /**
     * Mark fire start. {@code run.startedAt} is already populated
     * by the caller (TaskExecutor opens the TaskRun row first).
     */
    public static void recordStarted(Task task, TaskRun run) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' fire started (run=%d)".formatted(task.name, run.id);
        var details = detailsJson(
                KEY_TASK_ID, task.id,
                KEY_RUN_ID, run.id,
                "type", task.type != null ? task.type.name() : null);
        EventLogger.record("INFO", STARTED, agentName, null, message, details);
    }

    /**
     * Mark successful completion. {@code durationMs} comes from
     * the TaskRun row (completedAt − startedAt) — caller passes it
     * in rather than recomputing so the value matches what the
     * monitoring UI reads back.
     */
    public static void recordCompleted(Task task, TaskRun run, long durationMs) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' completed in %dms (run=%d)".formatted(
                task.name, durationMs, run.id);
        var details = detailsJson(
                KEY_TASK_ID, task.id,
                KEY_RUN_ID, run.id,
                "duration_ms", durationMs);
        EventLogger.record("INFO", COMPLETED, agentName, null, message, details);
    }

    /**
     * Mark permanent failure. Only call when the failure is
     * terminal (permanent error OR retries exhausted) —
     * transient failures with retry budget remaining emit WARN
     * entries via {@link JClawFailureHandler}'s usual logging
     * rather than this lifecycle event.
     *
     * @param classification short tag for the failure category —
     *        {@code "permanent error"} or {@code "retries exhausted"}.
     *        Operators / dashboard alerts key on this to distinguish
     *        config bugs (permanent) from upstream flakiness that
     *        outran the retry budget (exhausted).
     * @param errorMessage   the underlying exception's message;
     *        carried so the dashboard can show "what actually
     *        happened" without joining to the TaskRun row.
     */
    public static void recordFailed(Task task, TaskRun run,
                                    String classification, String errorMessage) {
        var agentName = task.agent != null ? task.agent.name : null;
        long durationMs = run != null && run.startedAt != null
                ? Duration.between(run.startedAt, Instant.now()).toMillis()
                : 0L;
        var message = "Task '%s' failed (%s): %s".formatted(
                task.name, classification, errorMessage);
        var details = detailsJson(
                KEY_TASK_ID, task.id,
                KEY_RUN_ID, run != null ? run.id : null,
                "duration_ms", durationMs,
                "classification", classification,
                "error_message", errorMessage);
        EventLogger.record("ERROR", FAILED, agentName, null, message, details);
    }

    /** Mark a successful post-completion delivery via {@link DeliveryDispatcher#dispatchSpec}. */
    public static void recordDelivered(Task task, TaskRun run, String deliverySpec) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' delivered via '%s' (run=%d)".formatted(
                task.name, deliverySpec, run.id);
        var details = detailsJson(
                KEY_TASK_ID, task.id,
                KEY_RUN_ID, run.id,
                "delivery", deliverySpec);
        EventLogger.record("INFO", DELIVERED, agentName, null, message, details);
    }

    /** Mark a failed post-completion delivery. {@code reason} is the
     *  human-readable reason from {@link DeliveryDispatcher.DispatchResult#reason()}. */
    public static void recordDeliveryFailed(Task task, TaskRun run,
                                            String deliverySpec, String reason) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' delivery failed via '%s' (run=%d): %s".formatted(
                task.name, deliverySpec, run.id, reason);
        var details = detailsJson(
                KEY_TASK_ID, task.id,
                KEY_RUN_ID, run.id,
                "delivery", deliverySpec,
                "reason", reason);
        EventLogger.record("WARN", DELIVERY_FAILED, agentName, null, message, details);
    }

    /**
     * Mark a RUNNING Task as LOST. Distinct from {@link #recordFailed} because
     * the recovery surface is db-scheduler, not the operator —
     * {@code staleSeconds} is preserved in the details payload so
     * dashboards can distinguish "barely past the threshold" from
     * "JVM was offline for an hour" without joining to scheduled_tasks.
     */
    public static void recordLost(Task task, long staleSeconds) {
        var agentName = task.agent != null ? task.agent.name : null;
        var message = "Task '%s' marked LOST after %ds of stale heartbeat"
                .formatted(task.name, staleSeconds);
        var details = detailsJson(
                KEY_TASK_ID, task.id,
                "stale_seconds", staleSeconds);
        EventLogger.record("WARN", LOST, agentName, null, message, details);
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
