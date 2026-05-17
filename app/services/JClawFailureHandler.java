package services;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import models.Task;
import models.TaskRun;
import utils.TransientErrorClassifier;

import java.time.Instant;

/**
 * db-scheduler {@link FailureHandler} for JClaw task fires.
 * Implements the JCLAW-21 retry policy:
 *
 * <ul>
 *   <li>Transient failures (per
 *   {@link TransientErrorClassifier}) reschedule on the
 *   {@link #BACKOFF_SECONDS backoff schedule} provided
 *   {@code retryCount &lt; min(maxRetries, backoff schedule length)}.</li>
 *   <li>Permanent failures, or transients with retries exhausted,
 *   mark the Task {@link Task.Status#FAILED} and stop the
 *   db-scheduler row.</li>
 * </ul>
 *
 * <h3>Why policy is in its own static method</h3>
 * db-scheduler's {@link ExecutionOperations} is a concrete class
 * with a non-trivial constructor ({@code TaskRepository},
 * {@code SchedulerListeners}, {@code Execution}), so the integration
 * surface is awkward to stub from a unit test. The retry-vs-fail
 * decision lives in {@link #decide(Long, Throwable)} which takes
 * primitives and returns a {@link Decision} record; the
 * {@link #onFailure} method is then a thin shell that translates
 * the decision into the right
 * {@code executionOps.reschedule}/{@code stop} call. Tests target
 * {@code decide} directly.
 *
 * <h3>Backoff schedule (spec)</h3>
 * {@code 30s, 60s, 5m, 15m, 1h} at retry positions 0–4. Position 5
 * and beyond → permanent. {@code Task.maxRetries} (default 3, per
 * the column default) caps shorter than the backoff array when
 * operators want a tighter ceiling. The min of the two governs.
 *
 * <p>Part of JCLAW-21's Tasks foundation. Wired into
 * {@link TaskExecutionHandler}'s {@code Tasks.custom(...)}
 * registration via {@code .onFailure(new JClawFailureHandler())}.
 */
public final class JClawFailureHandler implements FailureHandler<Void> {

    /**
     * Backoff schedule from the JCLAW-21 spec — seconds at retry
     * positions 0..4. Position {@code i} is the delay applied
     * <em>before</em> the {@code (i+1)}th attempt. Beyond position
     * {@code length-1} the failure is permanent.
     */
    static final long[] BACKOFF_SECONDS = {30, 60, 5 * 60, 15 * 60, 60 * 60};

    /**
     * Outcome of {@link #decide}: either reschedule at the carried
     * instant (transient + retry budget remains) or stop the
     * scheduler row and mark the Task FAILED.
     */
    public sealed interface Decision permits Decision.Reschedule, Decision.Fail {
        /** Try again at {@code nextRunAt}; Task remains PENDING with bumped retryCount. */
        record Reschedule(Instant nextRunAt, int newRetryCount) implements Decision {}
        /** Stop the row and mark Task FAILED. */
        record Fail(String reason) implements Decision {}
    }

    @Override
    public void onFailure(ExecutionComplete executionComplete,
                          ExecutionOperations<Void> executionOps) {
        var throwable = executionComplete.getCause().orElse(null);
        var instanceId = executionComplete.getExecution().taskInstance.getId();
        Long jclawTaskId = parseTaskId(instanceId);
        if (jclawTaskId == null) {
            EventLogger.warn("task", null, null,
                    "JClawFailureHandler: undecodable task_instance '%s'; stopping row"
                            .formatted(instanceId));
            executionOps.stop();
            return;
        }

        Decision decision = decide(jclawTaskId, throwable);
        switch (decision) {
            case Decision.Reschedule r ->
                    executionOps.reschedule(executionComplete, r.nextRunAt());
            case Decision.Fail f -> executionOps.stop();
        }
    }

    /**
     * Pure-ish policy step: read the Task, classify the error, mutate
     * the Task row (retryCount++ on retry, status=FAILED on permanent),
     * return the action db-scheduler should take. Lives in its own
     * method so unit tests can drive it without an
     * {@link ExecutionOperations} stub.
     *
     * <p>Side-effects: writes the Task row in its own short
     * transaction. Returns silently with a Fail decision when the
     * Task row can't be loaded (rare — implies someone deleted the
     * Task between the fire start and the failure surface).
     */
    public static Decision decide(Long jclawTaskId, Throwable throwable) {
        Task task = Tx.run(() -> (Task) Task.findById(jclawTaskId));
        if (task == null) {
            EventLogger.warn("task", null, null,
                    "JClawFailureHandler: Task id %d disappeared mid-fire; failing"
                            .formatted(jclawTaskId));
            return new Decision.Fail("Task row missing");
        }

        boolean isTransient = TransientErrorClassifier.isTransient(throwable);
        String errorMessage = throwable != null
                ? (throwable.getMessage() != null
                        ? throwable.getMessage() : throwable.getClass().getSimpleName())
                : "Unknown error";
        int currentRetry = task.retryCount;
        int budget = Math.min(task.maxRetries, BACKOFF_SECONDS.length);

        if (isTransient && currentRetry < budget) {
            long backoffSecs = BACKOFF_SECONDS[currentRetry];
            Instant nextRunAt = Instant.now().plusSeconds(backoffSecs);

            int newRetryCount = currentRetry + 1;
            Tx.run(() -> {
                var fresh = (Task) Task.findById(jclawTaskId);
                if (fresh != null) {
                    fresh.retryCount = newRetryCount;
                    fresh.lastError = errorMessage;
                    fresh.nextRunAt = nextRunAt;
                    fresh.save();
                }
                return null;
            });

            EventLogger.warn("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Task '%s' transient failure %d/%d, retry in %ds: %s"
                            .formatted(task.name, newRetryCount, budget,
                                    backoffSecs, errorMessage));
            return new Decision.Reschedule(nextRunAt, newRetryCount);
        }

        // Permanent OR transient-but-exhausted
        Tx.run(() -> {
            var fresh = (Task) Task.findById(jclawTaskId);
            if (fresh != null) {
                fresh.status = Task.Status.FAILED;
                fresh.lastError = errorMessage;
                fresh.save();
            }
            return null;
        });

        String reason = isTransient ? "retries exhausted" : "permanent error";
        EventLogger.error("task",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' failed (%s) after %d attempt(s): %s"
                        .formatted(task.name, reason, currentRetry + 1, errorMessage));

        // JCLAW-21 lifecycle audit: TASK_FAILED bookmark. Sibling
        // to TASK_STARTED / TASK_COMPLETED emitted by TaskExecutor.
        // Fired only when the failure is terminal — transient
        // retries emit the WARN under "task" category above.
        // Pass both the classification (permanent vs exhausted) and
        // the raw error message so dashboards can group by class
        // while still showing the operator what actually happened.
        var runForLifecycle = Tx.run(() ->
                (TaskRun) TaskRun.find("task.id = ?1 ORDER BY startedAt DESC", jclawTaskId).first());
        TaskLifecycleEvents.failed(task, runForLifecycle, reason, errorMessage);

        return new Decision.Fail(reason);
    }

    /**
     * Decode {@code task_instance} → JClaw Task primary key. Returns
     * null for any malformed value. Same shape
     * {@link TaskExecutionHandler#parseTaskId} uses — duplicated
     * rather than depended on because both classes are at the same
     * layer (db-scheduler integration) and a shared helper would
     * create coupling for a 4-line method.
     */
    private static Long parseTaskId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return null;
        try {
            return Long.parseLong(instanceId.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
