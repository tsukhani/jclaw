package services;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import jobs.DbSchedulerBootstrapJob;
import models.Task;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bridge between Task CRUD operations and db-scheduler's
 * {@code scheduled_tasks} table. Every JClaw {@link Task} lifecycle
 * event (create, edit, retry, cancel, run-now) routes through this
 * service so the {@code scheduled_tasks} row state stays consistent
 * with the {@link Task} row state.
 *
 * <h3>Instance-id convention</h3>
 * The {@code task_instance} string column carries the JClaw
 * {@link Task#id} as a decimal string. {@link TaskExecutionHandler}
 * decodes it back to a primary key at fire time. One JClaw Task
 * therefore occupies <em>at most one</em> row in
 * {@code scheduled_tasks} at any moment — IMMEDIATE/SCHEDULED fires
 * remove the row on completion via the default
 * {@code OnCompleteRemove} handler; CRON fires
 * {@code stop()}-then-{@code schedule()} inside their
 * CompletionHandler to keep the slot occupied for the next fire.
 *
 * <h3>First-fire instant</h3>
 * {@link #register} computes the first scheduled time from the Task's
 * shape:
 * <ul>
 *   <li>{@link Task.Type#IMMEDIATE} → {@code Instant.now()}</li>
 *   <li>{@link Task.Type#SCHEDULED} → {@link Task#scheduledAt}
 *       (caller validates non-null at form-bind time; defensive
 *       fall-through to {@code now()} keeps the system live if a stale
 *       row sneaks through)</li>
 *   <li>{@link Task.Type#CRON} → {@link JClawCronUtils#nextExecution}
 *       computed from {@link Task#cronExpression}; if the parser
 *       returns null the registration is skipped and a warn is
 *       logged so operators can spot a malformed cron string
 *       early</li>
 * </ul>
 *
 * <h3>What this service does NOT do</h3>
 * <ul>
 *   <li>Does not touch the {@link Task} row's status / nextRunAt /
 *       paused fields. Caller is responsible for those — typically
 *       the same controller / tool that invoked
 *       {@code task.save()}. Mixing the two in one place would
 *       create silent inversions where the {@code scheduled_tasks}
 *       row exists but the {@link Task} thinks it's CANCELLED, or
 *       vice versa.</li>
 *   <li>Does not handle {@code pause} / {@code resume} — those
 *       methods are deferred until the Task entity gains the
 *       {@code paused} boolean column (separate JCLAW-21 commit,
 *       part of the entity refactor section of the spec).</li>
 * </ul>
 *
 * <p>{@link SchedulerClient} is sourced from
 * {@link DbSchedulerBootstrapJob#scheduler()} at call time rather
 * than captured in a field — the Scheduler isn't built until
 * {@code @OnApplicationStart}, so any field-injected reference
 * would be null during bootstrap competition.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public final class TaskSchedulingService {

    /**
     * Indirection over {@code DbSchedulerBootstrapJob.scheduler()} so
     * tests can swap in a recording {@link SchedulerClient} stub
     * without spinning up the real Scheduler. Defaults to the
     * production lookup; tests override via
     * {@link #setSchedulerClientSupplierForTest}.
     */
    @SuppressWarnings("java:S5852") // Mutable static is the dependency-injection seam for tests
    private static Supplier<SchedulerClient> clientSupplier =
            () -> DbSchedulerBootstrapJob.scheduler();

    private TaskSchedulingService() {}

    /**
     * Test-only setter. Production callers MUST NOT invoke this —
     * the default supplier reads the live Scheduler via the
     * bootstrap job. Package-private to limit the surface; the test
     * class lives in the default package and reaches in through
     * a {@code services.TaskSchedulingServiceTestHooks} bridge.
     */
    static void setSchedulerClientSupplierForTest(Supplier<SchedulerClient> supplier) {
        clientSupplier = supplier != null
                ? supplier
                : () -> DbSchedulerBootstrapJob.scheduler();
    }

    /**
     * Schedule the first fire for a freshly-created {@link Task}.
     * No-op for tasks already in a terminal status
     * ({@link Task.Status#COMPLETED CANCELLED FAILED}) — caller
     * shouldn't pass those, but the guard keeps the service tolerant
     * of imports / retries that accidentally call register on a
     * non-fireable Task.
     */
    public static void register(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before registration");
        }
        if (isTerminal(task.status)) {
            EventLogger.info("task",
                    task.agent != null ? task.agent.name : null, null,
                    "TaskSchedulingService.register: Task '%s' is %s; not scheduling"
                            .formatted(task.name, task.status));
            return;
        }
        Instant firstFire = computeFirstFire(task);
        if (firstFire == null) return;
        scheduleFire(task, firstFire);
    }

    /**
     * Apply a Task edit to the scheduler: cancel the existing
     * {@code scheduled_tasks} row (if any) and register a fresh one
     * based on the Task's current shape. Cancelling-then-registering
     * keeps the code path identical to first-time registration —
     * cheaper to maintain than a partial-update path that has to
     * detect whether the schedule changed.
     */
    public static void update(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before update");
        }
        cancelSchedulerRow(task.id);
        register(task);
    }

    /**
     * Trigger the Task's next fire immediately. Uses
     * {@link SchedulerClient#reschedule reschedule(Instant.now())}
     * when a row exists; falls through to a fresh
     * {@link #register} if no row is currently scheduled (e.g.
     * after a one-shot SCHEDULED Task completed and the operator
     * wants to re-fire).
     */
    public static void runNow(Long taskId) {
        Objects.requireNonNull(taskId, "taskId");
        SchedulerClient client = client();
        if (client == null) return;
        boolean rescheduled = client.reschedule(
                TaskInstanceId.of(TaskExecutionHandler.TASK_NAME, taskId.toString()),
                Instant.now());
        if (rescheduled) {
            EventLogger.info("task", null, null,
                    "Task id %d run-now: rescheduled existing row to fire immediately"
                            .formatted(taskId));
            return;
        }
        // No existing row — look up the Task and register from scratch.
        Task task = Tx.run(() -> (Task) Task.findById(taskId));
        if (task == null) {
            EventLogger.warn("task", null, null,
                    "Task id %d run-now: Task row not found; nothing to schedule"
                            .formatted(taskId));
            return;
        }
        scheduleFire(task, Instant.now());
    }

    /**
     * Remove the {@code scheduled_tasks} row for this Task. Caller is
     * responsible for updating {@link Task#status} to
     * {@link Task.Status#CANCELLED} and saving — the scheduler-side
     * cancel is idempotent and harmless if the row isn't present.
     */
    public static void cancel(Long taskId) {
        Objects.requireNonNull(taskId, "taskId");
        cancelSchedulerRow(taskId);
    }

    // --- Internal ---

    private static SchedulerClient client() {
        SchedulerClient client = clientSupplier.get();
        if (client == null) {
            EventLogger.warn("task", null, null,
                    "TaskSchedulingService called before db-scheduler bootstrap; ignored");
            return null;
        }
        return client;
    }

    private static Instant computeFirstFire(Task task) {
        switch (task.type) {
            case IMMEDIATE:
                return Instant.now();
            case SCHEDULED:
                if (task.scheduledAt == null) {
                    EventLogger.warn("task",
                            task.agent != null ? task.agent.name : null, null,
                            "SCHEDULED Task '%s' has null scheduledAt; firing now as fallback"
                                    .formatted(task.name));
                    return Instant.now();
                }
                return task.scheduledAt;
            case INTERVAL:
                if (task.intervalSeconds == null || task.intervalSeconds <= 0) {
                    EventLogger.warn("task",
                            task.agent != null ? task.agent.name : null, null,
                            "INTERVAL Task '%s' has invalid intervalSeconds (%s); skipping registration"
                                    .formatted(task.name, task.intervalSeconds));
                    return null;
                }
                // First fire happens immediately; subsequent fires
                // self-reschedule from TaskExecutionHandler's
                // CompletionHandler at {@code completionTime + intervalSeconds}.
                return Instant.now();
            case CRON:
                if (task.cronExpression == null || task.cronExpression.isBlank()) {
                    EventLogger.warn("task",
                            task.agent != null ? task.agent.name : null, null,
                            "CRON Task '%s' has blank cronExpression; skipping registration"
                                    .formatted(task.name));
                    return null;
                }
                Instant next = JClawCronUtils.nextExecution(task.cronExpression);
                if (next == null) {
                    EventLogger.warn("task",
                            task.agent != null ? task.agent.name : null, null,
                            "CRON Task '%s' expression '%s' yielded no next fire; skipping"
                                    .formatted(task.name, task.cronExpression));
                }
                return next;
            default:
                return null;
        }
    }

    /**
     * Flip {@link Task#paused} to true. The
     * {@link TaskExecutionHandler} checks the flag at fire time and
     * skips invoking {@link TaskExecutor} when set, so pause is
     * effective on the next scheduled fire without removing the
     * {@code scheduled_tasks} row. For CRON/INTERVAL Tasks this
     * preserves the recurrence cadence — resume picks up where the
     * Task would have fired anyway.
     */
    public static void pause(Long taskId) {
        Objects.requireNonNull(taskId, "taskId");
        Tx.run(() -> {
            var task = (Task) Task.findById(taskId);
            if (task == null) return null;
            task.paused = true;
            task.save();
            EventLogger.info("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Task '%s' paused".formatted(task.name));
            return null;
        });
    }

    /**
     * Flip {@link Task#paused} to false. Companion to {@link #pause}.
     * Does not re-register the row — the existing scheduled_tasks
     * row continues firing on schedule; the handler just stops
     * skipping the body once the flag clears.
     */
    public static void resume(Long taskId) {
        Objects.requireNonNull(taskId, "taskId");
        Tx.run(() -> {
            var task = (Task) Task.findById(taskId);
            if (task == null) return null;
            task.paused = false;
            task.save();
            EventLogger.info("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Task '%s' resumed".formatted(task.name));
            return null;
        });
    }

    private static void scheduleFire(Task task, Instant when) {
        SchedulerClient client = client();
        if (client == null) return;
        try {
            client.schedule(
                    new TaskInstance<>(TaskExecutionHandler.TASK_NAME, task.id.toString()),
                    when);
            EventLogger.info("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Scheduled Task '%s' (type=%s) for %s"
                            .formatted(task.name, task.type, when));
        } catch (RuntimeException e) {
            EventLogger.error("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Failed to schedule Task '%s': %s".formatted(task.name, e.getMessage()));
            throw e;
        }
    }

    private static void cancelSchedulerRow(Long taskId) {
        SchedulerClient client = client();
        if (client == null) return;
        try {
            client.cancel(TaskInstanceId.of(TaskExecutionHandler.TASK_NAME, taskId.toString()));
        } catch (RuntimeException e) {
            // cancel() throws when no row exists; that's a no-op outcome for
            // us, not an error. Log at debug-level via info-with-quiet-text
            // so operators don't see noise.
            EventLogger.info("task", null, null,
                    "TaskSchedulingService.cancel: no scheduled row for Task id %d (%s)"
                            .formatted(taskId, e.getMessage()));
        }
    }

    private static boolean isTerminal(Task.Status status) {
        return status == Task.Status.COMPLETED
                || status == Task.Status.CANCELLED
                || status == Task.Status.FAILED;
    }
}
