package services;

import models.Task;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import utils.AppClock;

import java.time.Instant;

/**
 * A task fire that a stop of JClaw cut off never runs again (JF-355): whatever the first run had
 * already sent, such as a message or an email, would go out a second time. A one-shot task ends
 * {@link Task.Status#FAILED} and a recurring one waits for its next occurrence; either way the chat
 * waiting on the run and the operator alert are told.
 *
 * <p>Every place that finds a cut-off fire goes through {@link #close}: the boot sweep's orphaned
 * runs and stranded tasks, and the dead-execution handler for a row a stopped JVM left picked.
 */
public final class RestartCutOff {

    /** The failure classification the lifecycle event and the waiting chat's notice carry. */
    public static final String REASON = "cut off by a restart";

    /** What a cut-off one-shot task and its run record. */
    public static final String ONE_SHOT_ERROR = "Cut off when JClaw stopped before the run finished. It does not run"
            + " again on its own, so nothing it already did is repeated. Retry it from the Tasks page to run it again.";

    /** What a cut-off run of a recurring task records. */
    public static final String RECURRING_ERROR = "Cut off when JClaw stopped before the run finished. It does not run"
            + " again; the task's next occurrence runs at its scheduled time.";

    private RestartCutOff() {}

    /** The error a run cut off by a restart records. */
    public static String runError(Task task) {
        return isRecurring(task) ? RECURRING_ERROR : ONE_SHOT_ERROR;
    }

    /**
     * End a task whose fire was cut off, inside the caller's transaction. Only a task still
     * {@link Task.Status#RUNNING} or {@link Task.Status#LOST} changes: a recurring one returns to
     * {@link Task.Status#ACTIVE} with {@link Task#nextRunAt} set to its next occurrence, a one-shot one
     * ends {@link Task.Status#FAILED}.
     *
     * @return true when it ended the fire, which the caller passes to {@link #announce} once the
     *         transaction commits
     */
    public static boolean close(Task task) {
        if (task.status != Task.Status.RUNNING && task.status != Task.Status.LOST) return false;
        if (isRecurring(task)) {
            task.status = Task.Status.ACTIVE;
            var next = TaskSchedulingService.nextOccurrence(task);
            if (next != null) task.nextRunAt = next;
        } else {
            task.status = Task.Status.FAILED;
            task.lastError = ONE_SHOT_ERROR;
        }
        task.save();
        return true;
    }

    /**
     * After the commit that {@link #close}d a task: the TASK_FAILED event, which reports the run to the
     * chat waiting on it, and the operator alert. A recurring task's alert names its next run, since a
     * monthly task would otherwise wait a month with only its run history to say a run was skipped.
     */
    public static void announce(Task task, @Nullable TaskRun run) {
        var agentName = task.agent != null ? task.agent.name : null;
        if (isRecurring(task)) {
            TaskLifecycleEvents.recordFailed(task, run, REASON, RECURRING_ERROR);
            OperatorAlerts.onOccurrenceCutOff(task, agentName, nextOccurrence(task));
            return;
        }
        TaskLifecycleEvents.recordFailed(task, run, REASON, ONE_SHOT_ERROR);
        OperatorAlerts.onTaskCutOff(task, agentName);
    }

    /**
     * When a recurring task fires next after a cut-off: the occurrence {@link #close} recorded while it
     * is still ahead, so the dead row a later sweep moves fires at the time the alert named, else the
     * next one from now.
     */
    public static @Nullable Instant nextOccurrence(Task task) {
        if (task.nextRunAt != null && task.nextRunAt.isAfter(AppClock.now())) return task.nextRunAt;
        return TaskSchedulingService.nextOccurrence(task);
    }

    public static boolean isRecurring(Task task) {
        return task.type == Task.Type.CRON || task.type == Task.Type.INTERVAL;
    }
}
