package services;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import models.Task;

import java.time.Instant;

/**
 * db-scheduler {@link com.github.kagkarlsson.scheduler.task.Task} definition
 * for JClaw task fires. One registered db-scheduler Task — {@link #TASK_NAME}
 * — backs every {@link models.Task} regardless of {@link Task.Type}; the
 * per-fire {@code task_instance} string carries the JClaw Task primary
 * key, and the handler decodes it back to a row before invoking
 * {@link TaskExecutor#runTask}.
 *
 * <h2>Self-rescheduling for CRON</h2>
 * IMMEDIATE / SCHEDULED Tasks fire exactly once: db-scheduler's default
 * {@code OnCompleteRemove} handler drops the {@code scheduled_tasks}
 * row after the fire returns.
 *
 * <p>CRON Tasks use a custom {@link CompletionHandler} that runs
 * <em>after</em> {@code executeOnce} completes: first
 * {@code executionOperations.stop()} removes the current row, then a
 * fresh {@link SchedulerClient#schedule schedule()} call inserts the
 * next-fire row using the same {@code task_instance} id (so each
 * JClaw Task occupies at most one row in {@code scheduled_tasks} at
 * any time). The stop-then-schedule order matters — calling
 * {@code schedule} while the original row still exists would trip the
 * unique constraint on {@code (task_name, task_instance)}.
 *
 * <h2>Why custom over OnCompleteReschedule</h2>
 * db-scheduler ships {@link CompletionHandler.OnCompleteReschedule}
 * which takes a {@link com.github.kagkarlsson.scheduler.task.schedule.Schedule},
 * and {@link com.github.kagkarlsson.scheduler.task.schedule.CronSchedule}
 * implements {@code Schedule} for cron strings — but the Schedule has
 * to be known at task-registration time, not at fire time. Each JClaw
 * Task carries its own cron expression in
 * {@link Task#cronExpression}, so we resolve the next fire instant per
 * Task inside the handler rather than baking one schedule into the
 * db-scheduler Task registration.
 *
 * <h2>Skip semantics</h2>
 * The handler short-circuits when the JClaw Task is missing
 * (deleted between scheduling and firing) or
 * {@link Task.Status#CANCELLED}. {@code TaskExecutor.runTask} owns the
 * mid-fire failure path — anything thrown out of it propagates to
 * {@link JClawFailureHandler} which classifies the error
 * (transient vs permanent), applies the JCLAW-21 backoff schedule on
 * retry, and marks the Task FAILED when retries are exhausted.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public final class TaskExecutionHandler {

    /**
     * The {@code task_name} column value db-scheduler stores for every
     * JClaw task fire. Stable identifier — operator-visible in the
     * scheduled_tasks table for diagnostics, and the lookup key both
     * scheduling (TaskSchedulingService) and dead-execution recovery
     * (BootConsistencyCheck — both subsequent JCLAW-21 commits) use to
     * find this Task definition.
     */
    public static final String TASK_NAME = "jclaw-task-fire";

    /**
     * Static {@link SchedulerClient} handoff populated by
     * {@code DbSchedulerBootstrapJob} once the Scheduler is built.
     * Read by the CRON CompletionHandler when self-rescheduling the
     * next fire. {@code volatile} because the bootstrap runs on
     * Play's startup thread while fires run on a virtual thread from
     * the Scheduler's executorService.
     */
    // S3077 targets compound mutation on volatile non-primitives. This
    // is a pure publish-once-read-many handoff — JMM happens-before via
    // the volatile read/write is sufficient.
    @SuppressWarnings("java:S3077")
    private static volatile SchedulerClient schedulerClient;

    private TaskExecutionHandler() {}

    /**
     * Wire the SchedulerClient reference. Called exactly once from
     * {@code DbSchedulerBootstrapJob} right after the Scheduler is
     * built and before {@code scheduler.start()}.
     */
    public static void setSchedulerClient(SchedulerClient client) {
        schedulerClient = client;
    }

    /**
     * Build the db-scheduler {@link CustomTask} definition. Returned to
     * {@code DbSchedulerBootstrapJob} which hands it to
     * {@code SchedulerBuilder.startTasks(...)}.
     */
    public static CustomTask<Void> buildTask() {
        return Tasks.custom(TASK_NAME, Void.class)
                .onFailure(new JClawFailureHandler())
                .execute((inst, ctx) -> {
            String instanceId = inst.getId();
            Long jclawTaskId = parseTaskId(instanceId);
            if (jclawTaskId == null) {
                EventLogger.warn("task", null, null,
                        "TaskExecutionHandler: undecodable task_instance '%s'; skipping fire"
                                .formatted(instanceId));
                return defaultCompletion();
            }

            Task jclawTask = findTaskWithRaceBackoff(jclawTaskId);
            if (jclawTask == null) {
                EventLogger.warn("task", null, null,
                        "TaskExecutionHandler: scheduled fire arrived for missing Task id %d; skipping"
                                .formatted(jclawTaskId));
                return defaultCompletion();
            }
            if (jclawTask.status == Task.Status.CANCELLED) {
                EventLogger.info("task",
                        jclawTask.agent != null ? jclawTask.agent.name : null, null,
                        "TaskExecutionHandler: Task id %d is CANCELLED; skipping fire"
                                .formatted(jclawTaskId));
                return defaultCompletion();
            }
            if (jclawTask.paused) {
                EventLogger.info("task",
                        jclawTask.agent != null ? jclawTask.agent.name : null, null,
                        "TaskExecutionHandler: Task id %d is paused; skipping fire body"
                                .formatted(jclawTaskId));
                // Recurring Tasks still self-reschedule so the cadence
                // resumes when {@link Task#paused} clears, without
                // operator intervention.
                return scheduleNextIfRecurring(jclawTask);
            }

            // Drive the fire. Any RuntimeException propagates to
            // JClawFailureHandler (wired in via .onFailure above)
            // which decides retry-with-backoff vs permanent fail
            // based on TransientErrorClassifier.
            TaskExecutor.runTask(jclawTask);

            return scheduleNextIfRecurring(jclawTask);
        });
    }

    /**
     * For INTERVAL and CRON Tasks, build the
     * {@link CompletionHandler} that drops the current row and
     * inserts the next-fire row. For one-shot Tasks (IMMEDIATE,
     * SCHEDULED), return the default {@code OnCompleteRemove}.
     */
    private static CompletionHandler<Void> scheduleNextIfRecurring(Task task) {
        if (task.type == Task.Type.CRON && task.cronExpression != null) {
            return scheduleCronNextCompletion(task);
        }
        if (task.type == Task.Type.INTERVAL && task.intervalSeconds != null && task.intervalSeconds > 0) {
            return scheduleIntervalNextCompletion(task);
        }
        return defaultCompletion();
    }

    /**
     * Default attempt budget for {@link #findTaskWithRaceBackoff}.
     * Visible for tests so a regression test can pin the budget against
     * the observed sub-millisecond race window without hard-coding it.
     */
    static final int FIND_TASK_ATTEMPTS = 5;

    /** Per-attempt sleep in ms — total budget = (attempts - 1) * sleep. */
    static final int FIND_TASK_BACKOFF_MS = 20;

    /**
     * Race-tolerant Task lookup. db-scheduler's poll thread can fire a
     * scheduled row before the controller's INSERT for the Task row has
     * committed — the schedule row goes into {@code scheduled_tasks} in
     * the same Tx as the Task INSERT, but the poll reads via a separate
     * connection / Tx so it can briefly see the schedule before the
     * Task row. Observed in production at ~1ms separation between
     * "Scheduled Task '...'" and "missing Task id N; skipping" log
     * lines; the lost fire never recovers (IMMEDIATE/SCHEDULED Tasks
     * never retry, INTERVAL/CRON Tasks lose their initial fire AND
     * their self-reschedule, going invisibly dormant).
     *
     * <p>Fix shape: bounded retry-with-backoff on {@code findById}
     * before giving up. {@link #FIND_TASK_ATTEMPTS} attempts spaced by
     * {@link #FIND_TASK_BACKOFF_MS} ms — total wall budget ~80 ms —
     * absorbs the observed window with three orders of magnitude of
     * headroom. A genuinely-deleted Task (e.g. cancelled and
     * subsequently removed) still falls through to the skip-and-warn
     * path after the budget elapses.
     *
     * <p>Returns {@code null} if the Task is still not found after all
     * attempts. Visible for tests in the {@code services} package.
     */
    static Task findTaskWithRaceBackoff(long taskId) {
        for (int i = 0; i < FIND_TASK_ATTEMPTS; i++) {
            Task t = Tx.run(() -> (Task) Task.findById(taskId));
            if (t != null) return t;
            // Skip the sleep after the last attempt; we've done all we can.
            if (i < FIND_TASK_ATTEMPTS - 1) {
                try {
                    Thread.sleep(FIND_TASK_BACKOFF_MS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Decode the {@code task_instance} string back to a JClaw Task
     * primary key. Returns {@code null} for any malformed value so
     * the caller can skip the fire and log without throwing — a stale
     * row from a prior schema or hand-tampered DB shouldn't crash the
     * scheduler thread.
     */
    private static Long parseTaskId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return null;
        try {
            return Long.parseLong(instanceId.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** OnCompleteRemove drops the current scheduled_tasks row. */
    private static CompletionHandler<Void> defaultCompletion() {
        return new CompletionHandler.OnCompleteRemove<>();
    }

    /**
     * Two-step CRON re-schedule: drop the current row via
     * {@code executionOperations.stop()}, then insert the next-fire
     * row with the same task_instance id via
     * {@link SchedulerClient#schedule}. The stop-then-schedule order
     * is load-bearing for the unique-constraint reasons documented at
     * the class level.
     *
     * <p>If next-fire computation fails (malformed cron) or
     * {@link #schedulerClient} hasn't been wired (test path, race at
     * shutdown), the current row is still removed cleanly and the
     * CRON Task effectively pauses until the next BootConsistencyCheck
     * sweep reschedules it.
     */
    /**
     * INTERVAL counterpart to {@link #scheduleCronNextCompletion} —
     * same stop-then-schedule shape, but next fire is
     * {@code now + task.intervalSeconds} rather than parsed from a
     * cron expression. "Next fire is from-now, not from-completion"
     * is the conventional INTERVAL semantic (matches Hermes,
     * matches systemd timers) — if the body took 90 seconds to
     * complete on a 60-second interval, the next fire is still
     * 60 seconds out, not "30 seconds ago".
     */
    private static CompletionHandler<Void> scheduleIntervalNextCompletion(Task task) {
        return (executionComplete, executionOperations) -> {
            executionOperations.stop();
            try {
                SchedulerClient client = schedulerClient;
                if (client == null) {
                    EventLogger.warn("task",
                            task.agent != null ? task.agent.name : null, null,
                            "SchedulerClient not wired; cannot reschedule Task '%s' next INTERVAL fire"
                                    .formatted(task.name));
                    return;
                }
                Instant next = Instant.now().plusSeconds(task.intervalSeconds);
                String instanceId = task.id.toString();
                client.schedule(new TaskInstance<>(TASK_NAME, instanceId), next);
                EventLogger.info("task",
                        task.agent != null ? task.agent.name : null, null,
                        "Rescheduled Task '%s' next INTERVAL fire for %s (every %ds)"
                                .formatted(task.name, next, task.intervalSeconds));
            } catch (Exception e) {
                EventLogger.error("task",
                        task.agent != null ? task.agent.name : null, null,
                        "Failed to reschedule next INTERVAL fire for Task '%s': %s"
                                .formatted(task.name, e.getMessage()));
            }
        };
    }

    private static CompletionHandler<Void> scheduleCronNextCompletion(Task task) {
        return (executionComplete, executionOperations) -> {
            executionOperations.stop();
            rescheduleCronFire(task);
        };
    }

    private static void rescheduleCronFire(Task task) {
        try {
            // JCLAW-261: same zone resolution as the first-fire computation
            // in TaskSchedulingService so the next fire matches the user's
            // intent ("9 am NYC") regardless of the JVM's default zone.
            var zone = TimezoneResolver.resolve(task);
            Instant next = JClawCronUtils.nextExecution(task.cronExpression, zone);
            if (next == null) {
                EventLogger.warn("task",
                        task.agent != null ? task.agent.name : null, null,
                        "Task '%s' CRON expression '%s' yielded no next fire; pausing self-reschedule"
                                .formatted(task.name, task.cronExpression));
                return;
            }
            SchedulerClient client = schedulerClient;
            if (client == null) {
                EventLogger.warn("task",
                        task.agent != null ? task.agent.name : null, null,
                        "SchedulerClient not wired; cannot reschedule Task '%s' next CRON fire"
                                .formatted(task.name));
                return;
            }
            String instanceId = task.id.toString();
            client.schedule(new TaskInstance<>(TASK_NAME, instanceId), next);
            EventLogger.info("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Rescheduled Task '%s' next CRON fire for %s"
                            .formatted(task.name, next));
        } catch (Exception e) {
            EventLogger.error("task",
                    task.agent != null ? task.agent.name : null, null,
                    "Failed to reschedule next CRON fire for Task '%s': %s"
                            .formatted(task.name, e.getMessage()));
        }
    }
}
