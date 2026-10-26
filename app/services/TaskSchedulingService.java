package services;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import jobs.DbSchedulerBootstrapJob;
import models.Task;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.db.DB;
import play.db.jpa.JPA;
import utils.AppClock;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bridge between Task CRUD operations and db-scheduler's
 * {@code scheduled_tasks} table. Every JClaw {@link Task} lifecycle
 * event (create, edit, retry, cancel, run-now) routes through this
 * service so the {@code scheduled_tasks} row state stays consistent
 * with the {@link Task} row state.
 *
 * <h2>Instance-id convention</h2>
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
 * <h2>First-fire instant</h2>
 * {@link #register} computes the first scheduled time from the Task's
 * shape:
 * <ul>
 *   <li>{@link Task.Type#IMMEDIATE} → {@code AppClock.now()}</li>
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
 * <h2>What this service does NOT do</h2>
 * <ul>
 *   <li>Does not touch the {@link Task} row's status / nextRunAt /
 *       paused fields. Caller is responsible for those — typically
 *       the same controller / tool that invoked
 *       {@code task.save()}. Mixing the two in one place would
 *       create silent inversions where the {@code scheduled_tasks}
 *       row exists but the {@link Task} thinks it's CANCELLED, or
 *       vice versa.</li>
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

    private static final String PARAM_TASK_ID = "taskId";
    private static final Duration RUN_NOW_RETRY_DELAY = Duration.ofSeconds(1);
    private static final int RUN_NOW_RETRY_ATTEMPTS = 120;
    // A platform thread, so a burst of retries parks no virtual thread on a timed wait (JDK-8373224).
    private static final ScheduledExecutorService RUN_NOW_RETRY = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "task-run-now-retry");
        t.setDaemon(true);
        return t;
    });

    /**
     * Indirection over {@code DbSchedulerBootstrapJob.scheduler()} so
     * tests can swap in a recording {@link SchedulerClient} stub
     * without spinning up the real Scheduler. Defaults to the
     * production lookup; tests override via
     * {@link #setSchedulerClientSupplierForTest}.
     */
    @SuppressWarnings("java:S5852") // Mutable static is the dependency-injection seam for tests
    private static Supplier<SchedulerClient> clientSupplier =
            DbSchedulerBootstrapJob::scheduler;

    private TaskSchedulingService() {}

    /**
     * Test-only setter. Production callers MUST NOT invoke this —
     * the default supplier reads the live Scheduler via the
     * bootstrap job. Package-private to limit the surface; the test
     * class lives in the default package and reaches in through
     * a {@code services.TaskSchedulingServiceTestHooks} bridge.
     */
    static void setSchedulerClientSupplierForTest(@Nullable Supplier<SchedulerClient> supplier) {
        clientSupplier = supplier != null
                ? supplier
                : DbSchedulerBootstrapJob::scheduler;
    }

    /**
     * Schedule the first fire for a freshly-created {@link Task}.
     * No-op for tasks already in a terminal status
     * ({@link Task.Status#COMPLETED CANCELLED FAILED}) — caller
     * shouldn't pass those, but the guard keeps the service tolerant
     * of imports / retries that accidentally call register on a
     * non-fireable Task.
     *
     * <p>Arms after the ambient transaction commits, or at once outside one: an
     * immediate fire reads the committed row on db-scheduler's own connection.
     */
    public static void register(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before registration");
        }
        afterCommit(task.id, () -> arm(task));
    }

    private static void arm(Task task) {
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
     * based on the Task's current shape. Canceling-then-registering
     * keeps the code path identical to first-time registration —
     * cheaper to maintain than a partial-update path that has to
     * detect whether the schedule changed. Deferred like {@link #register}.
     */
    public static void update(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before update");
        }
        afterCommit(task.id, () -> {
            cancelSchedulerRow(task.id);
            arm(task);
        });
    }

    /**
     * Trigger the Task's next fire immediately. Three live cases the
     * implementation handles defensively:
     *
     * <ol>
     *   <li>The {@code scheduled_tasks} row exists and isn't being
     *       executed — {@link SchedulerClient#reschedule} flips its
     *       next-fire time to now.</li>
     *   <li>The row exists but is currently being executed (db-scheduler
     *       holds a row lock for the duration of the fire). Reschedule
     *       throws {@code TaskInstanceCurrentlyExecutingException}. While a
     *       run is in flight, or one has started since the request, we log
     *       and no-op: that is the fire the operator wanted. When the last
     *       run has already closed and its execution is only delivering, we
     *       retry each second, for up to two minutes, until it ends.</li>
     *   <li>The row is gone — common after a one-shot Task COMPLETED
     *       (OnCompleteRemove dropped it) or a Task was CANCELLED.
     *       Reschedule throws {@code TaskInstanceNotFoundException};
     *       we fall through to {@link #scheduleFire} which registers
     *       a fresh row at {@code AppClock.now()}.</li>
     * </ol>
     *
     * <p>Note: case (3) for CANCELLED Tasks requires the caller to
     * have already flipped status back to PENDING — otherwise
     * {@link services.TaskExecutionHandler}'s CANCELLED-skip swallows
     * the fire body at pick-up time. The API's {@code /run} endpoint
     * is the canonical caller and does that revive itself. Deferred like
     * {@link #register}, or the fire reads the status from before that revive.
     */
    public static void runNow(Long taskId) {
        Objects.requireNonNull(taskId, PARAM_TASK_ID);
        // Read before deferring, while the ambient session is still inside its transaction.
        Task task = Tx.run(() -> (Task) Task.findById(taskId));
        afterCommit(taskId, () -> fireNow(taskId, task));
    }

    private static void fireNow(Long taskId, @Nullable Task task) {
        SchedulerClient client = client();
        if (client == null) return;
        fireNow(client, taskId, task, AppClock.now(), 0);
    }

    private static void fireNow(SchedulerClient client, Long taskId, @Nullable Task task, Instant requestedAt, int attempt) {
        var instanceId = TaskInstanceId.of(TaskExecutionHandler.TASK_NAME, taskId.toString());
        boolean rescheduled = false;
        try {
            rescheduled = client.reschedule(instanceId, AppClock.now());
        } catch (TaskInstanceCurrentlyExecutingException _) {
            if (aRunAnswers(taskId, requestedAt)) {
                // A run in flight, or one that started since, is the fire the operator wanted.
                EventLogger.info("task", null, null,
                        "Task id %d run-now: fire already in progress; not rescheduling"
                                .formatted(taskId));
                return;
            }
            // The last fire's run has closed but its execution is still delivering: fire when it ends.
            if (attempt >= RUN_NOW_RETRY_ATTEMPTS) {
                EventLogger.warn("task", null, null,
                        "Task id %d run-now: its last fire was still finishing after %d s; not rescheduling"
                                .formatted(taskId, RUN_NOW_RETRY_ATTEMPTS * RUN_NOW_RETRY_DELAY.toSeconds()));
                return;
            }
            RUN_NOW_RETRY.schedule(() -> retryFireNow(client, taskId, task, requestedAt, attempt + 1),
                    RUN_NOW_RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
            return;
        } catch (TaskInstanceNotFoundException _) {
            // Row was removed (terminal one-shot completion, cancel, etc.)
            // — fall through to registration. Common after operators
            // re-run a COMPLETED or CANCELLED Task.
        }
        if (rescheduled) {
            EventLogger.info("task", null, null,
                    "Task id %d run-now: rescheduled existing row to fire immediately"
                            .formatted(taskId));
            return;
        }
        if (task == null) {
            EventLogger.warn("task", null, null,
                    "Task id %d run-now: Task row not found; nothing to schedule"
                            .formatted(taskId));
            return;
        }
        scheduleFire(task, AppClock.now());
    }

    private static void retryFireNow(SchedulerClient client, Long taskId, @Nullable Task task, Instant requestedAt,
                                     int attempt) {
        try {
            // Checked first: a run the finishing execution opened answers the request, and firing again would double it.
            if (aRunAnswers(taskId, requestedAt)) return;
            fireNow(client, taskId, task, requestedAt, attempt);
        } catch (RuntimeException e) {
            EventLogger.warn("task", null, null,
                    "Task id %d run-now: retry failed: %s".formatted(taskId, e.getMessage()));
        }
    }

    private static boolean aRunAnswers(Long taskId, Instant requestedAt) {
        return Tx.run(() -> TaskRun.count("task.id = ?1 AND (status = ?2 OR startedAt >= ?3)",
                taskId, TaskRun.Status.RUNNING, requestedAt)) > 0;
    }

    /**
     * Remove the {@code scheduled_tasks} row for this Task. Caller is
     * responsible for updating {@link Task#status} to
     * {@link Task.Status#CANCELLED} and saving — the scheduler-side
     * cancel is idempotent and harmless if the row isn't present.
     */
    public static void cancel(Long taskId) {
        Objects.requireNonNull(taskId, PARAM_TASK_ID);
        cancelSchedulerRow(taskId);
    }

    // --- Internal ---

    /**
     * {@link Tx#afterCommit}, logging rather than throwing a deferred failure: Hibernate stops at
     * the first synchronization that throws, so one task's failed arm would skip every later one.
     */
    private static void afterCommit(Long taskId, Runnable action) {
        if (!JPA.isInsideTransaction()) {
            action.run();
            return;
        }
        Tx.afterCommit(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                EventLogger.warn("task", null, null,
                        "Task id %d: deferred scheduler call failed after commit: %s"
                                .formatted(taskId, e.getMessage()));
            }
        });
    }

    private static @Nullable SchedulerClient client() {
        SchedulerClient client = clientSupplier.get();
        if (client == null) {
            EventLogger.warn("task", null, null,
                    "TaskSchedulingService called before db-scheduler bootstrap; ignored");
            return null;
        }
        return client;
    }

    private static @Nullable Instant computeFirstFire(Task task) {
        return switch (task.type) {
            case IMMEDIATE -> AppClock.now();
            case SCHEDULED -> computeScheduledFire(task);
            case INTERVAL -> computeIntervalFirstFire(task);
            case CRON -> computeCronFirstFire(task);
        };
    }

    private static Instant computeScheduledFire(Task task) {
        if (task.scheduledAt == null) {
            EventLogger.warn("task",
                    task.agent != null ? task.agent.name : null, null,
                    "SCHEDULED Task '%s' has null scheduledAt; firing now as fallback"
                            .formatted(task.name));
            return AppClock.now();
        }
        return task.scheduledAt;
    }

    private static @Nullable Instant computeIntervalFirstFire(Task task) {
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
        return AppClock.now();
    }

    /**
     * When a recurring task fires next, counted from now: null for a one-shot, or when its schedule
     * yields no next fire. The one rule for a completed occurrence and a failed one alike.
     */
    public static @Nullable Instant nextOccurrence(Task task) {
        return switch (task.type) {
            // JCLAW-261: the task's own zone, so "0 0 9 * * *" means 09:00 where the operator is.
            case CRON -> JClawCronUtils.nextExecution(task.cronExpression, TimezoneResolver.resolve(task));
            case INTERVAL -> task.intervalSeconds != null && task.intervalSeconds > 0
                    ? AppClock.now().plusSeconds(task.intervalSeconds) : null;
            case IMMEDIATE, SCHEDULED -> null;
        };
    }

    private static @Nullable Instant computeCronFirstFire(Task task) {
        if (task.cronExpression == null || task.cronExpression.isBlank()) {
            EventLogger.warn("task",
                    task.agent != null ? task.agent.name : null, null,
                    "CRON Task '%s' has blank cronExpression; skipping registration"
                            .formatted(task.name));
            return null;
        }
        // JCLAW-261: resolve the per-task / config / conf / operator-zone
        // timezone so "0 0 9 * * *" fires at 09:00 in the user's zone,
        // not the server's. null task.timezone falls through to the global
        // default via TimezoneResolver.
        var zone = TimezoneResolver.resolve(task);
        Instant next = JClawCronUtils.nextExecution(task.cronExpression, zone);
        if (next == null) {
            EventLogger.warn("task",
                    task.agent != null ? task.agent.name : null, null,
                    "CRON Task '%s' expression '%s' yielded no next fire; skipping"
                            .formatted(task.name, task.cronExpression));
        }
        return next;
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
        Objects.requireNonNull(taskId, PARAM_TASK_ID);
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
     * A recurring Task's {@code scheduled_tasks} row survived the pause
     * (the handler self-rescheduled through it), so clearing the flag is
     * all it needs. A one-shot's may not have — see
     * {@link #reArmOneShotIfDropped}.
     */
    public static void resume(Long taskId) {
        Objects.requireNonNull(taskId, PARAM_TASK_ID);
        var task = Tx.run(() -> {
            var t = (Task) Task.findById(taskId);
            if (t == null) return null;
            t.paused = false;
            t.save();
            EventLogger.info("task",
                    t.agent != null ? t.agent.name : null, null,
                    "Task '%s' resumed".formatted(t.name));
            return t;
        });
        // After commit: through the API this joins the request's transaction, and a past-due re-arm
        // fires at once, so armed earlier the handler reads the old paused=true and drops the row.
        if (task != null) afterCommit(task.id, () -> reArmOneShotIfDropped(task));
    }

    /**
     * Re-arm a one-shot whose {@code scheduled_tasks} row was dropped while it
     * was paused: {@link TaskExecutionHandler} drops a paused one-shot's row
     * when its fire arrives, so clearing the flag alone would leave the Task
     * PENDING forever with nothing left to fire it. A past-due time re-arms at
     * that time, which db-scheduler picks up on its next poll — the same "or
     * immediately if that time has already passed" behavior {@code reenable} has.
     *
     * <p>Recurring Tasks self-reschedule through a pause, so re-arming one
     * would double-schedule it. Also called by the handler, for a resume that
     * committed while that fire was still skipping.
     */
    static void reArmOneShotIfDropped(Task task) {
        if (task.type == Task.Type.CRON || task.type == Task.Type.INTERVAL) return;
        if (isTerminal(task.status)) return;
        SchedulerClient client = client();
        if (client == null) return;
        Instant fire = computeFirstFire(task);
        if (fire == null) return;
        // scheduleIfNotExists, not schedule: a row that outlived the pause (the
        // fire time had not arrived yet) keeps the time it already holds, and
        // the check is atomic against a poll landing between look and leap.
        boolean armed = client.scheduleIfNotExists(
                new TaskInstance<>(TaskExecutionHandler.TASK_NAME, task.id.toString()), fire);
        if (armed) {
            EventLogger.info("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Task '%s' resumed: re-armed a one-shot fire dropped during the pause, for %s"
                            .formatted(task.name, fire));
        }
    }

    private static void scheduleFire(Task task, Instant when) {
        SchedulerClient client = client();
        if (client == null) return;
        try {
            boolean scheduled = client.scheduleIfNotExists(
                    new TaskInstance<>(TaskExecutionHandler.TASK_NAME, task.id.toString()),
                    when);
            // A Task holds at most one scheduled_tasks row and an existing one is left in place,
            // so "Scheduled" is only true when this call created it.
            if (scheduled) {
                EventLogger.info("task",
                        task.agent != null ? task.agent.name : null, null,
                        "Scheduled Task '%s' (type=%s) for %s"
                                .formatted(task.name, task.type, when));
            } else {
                EventLogger.info("task",
                        task.agent != null ? task.agent.name : null, null,
                        "Task '%s' already has a pending fire; left it in place, not rescheduled for %s"
                                .formatted(task.name, when));
            }
        } catch (RuntimeException e) {
            EventLogger.error("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Failed to schedule Task '%s': %s".formatted(task.name, e.getMessage()));
            throw e;
        }
    }

    /**
     * JCLAW-258 LOST-recovery helper. Force-remove the
     * {@code scheduled_tasks} row for a Task whose scheduler-side state
     * is {@code picked=true} with a stale heartbeat —
     * {@link SchedulerClient#cancel} refuses to touch picked rows
     * (raises {@code TaskInstanceCurrentlyExecutingException}), but for
     * a LOST Task we know by construction that the picking JVM is dead.
     * Bypassing the API guard is the only way for the operator's retry
     * click to pre-empt db-scheduler's own dead-execution detection.
     *
     * <p>Direct JDBC delete is intentional: it avoids re-implementing
     * the version-aware optimistic-lock branch of db-scheduler's
     * repository, which we cannot reach through the public surface.
     *
     * <p>Deferred like {@link #register}; a {@link #register} the same
     * transaction calls afterwards runs after it, in registration order.
     */
    public static void forceRemoveStaleRow(Long taskId) {
        Objects.requireNonNull(taskId, PARAM_TASK_ID);
        afterCommit(taskId, () -> removeStaleRowNow(taskId));
    }

    /** {@link #forceRemoveStaleRow} at once, for a fire's completion that re-inserts the row next. */
    static void removeStaleRowNow(Long taskId) {
        var ds = DB.getDataSource();
        if (ds == null) return;
        var sql = "DELETE FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?";
        try (var conn = ds.getConnection()) {
            // Hikari's pool returns connections with autoCommit=false
            // (Hibernate-managed); without flipping it on the DELETE
            // would roll back when the connection returns to the pool.
            // Direct setAutoCommit is safe here because this method is
            // intentionally outside any JPA transaction.
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.setString(2, taskId.toString());
                int removed = ps.executeUpdate();
                if (removed > 0) {
                    EventLogger.info("task", null, null,
                            "TaskSchedulingService.forceRemoveStaleRow: removed stale "
                                    + "scheduled_tasks row for Task id %d".formatted(taskId));
                }
            }
        } catch (SQLException e) {
            EventLogger.warn("task", null, null,
                    "TaskSchedulingService.forceRemoveStaleRow: failed for Task id %d: %s"
                            .formatted(taskId, e.getMessage()));
        }
    }

    private static void cancelSchedulerRow(Long taskId) {
        SchedulerClient client = client();
        if (client == null) return;
        try {
            client.cancel(TaskInstanceId.of(TaskExecutionHandler.TASK_NAME, taskId.toString()));
        } catch (TaskInstanceNotFoundException _) {
            // No row exists — a no-op outcome for us, not an error. Log at
            // debug-level via info-with-quiet-text so operators don't see noise.
            EventLogger.info("task", null, null,
                    "TaskSchedulingService.cancel: no scheduled row for Task id %d"
                            .formatted(taskId));
        } catch (TaskInstanceCurrentlyExecutingException _) {
            // The row is locked mid-fire (db-scheduler holds it for the fire's
            // duration); cancel() can't remove it while the executor owns it.
            // Mirror runNow's typed handling and surface this distinctly rather
            // than relabeling it "no scheduled row" — the schedule survives
            // because a fire is in flight, not because it was missing.
            EventLogger.warn("task", null, null,
                    "TaskSchedulingService.cancel: Task id %d is currently executing; scheduled row not removed"
                            .formatted(taskId));
        }
    }

    private static boolean isTerminal(Task.Status status) {
        return status == Task.Status.COMPLETED
                || status == Task.Status.CANCELLED
                || status == Task.Status.FAILED;
    }
}
