package services;

import models.Task;
import models.TaskRun;
import org.jspecify.annotations.Nullable;

/**
 * A task fire that a stop of JClaw cut off never runs again (JF-355): whatever the first run had
 * already sent, such as a message or an email, would go out a second time. A one-shot task ends
 * {@link Task.Status#FAILED} and says so to the chat waiting on the run and to the operator alert;
 * a recurring task waits for its next occurrence.
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
     * {@link Task.Status#ACTIVE}, a one-shot one ends {@link Task.Status#FAILED}.
     *
     * @return true when it ended a one-shot task, which the caller passes to {@link #announce} once
     *         the transaction commits
     */
    public static boolean close(Task task) {
        if (task.status != Task.Status.RUNNING && task.status != Task.Status.LOST) return false;
        if (isRecurring(task)) {
            task.status = Task.Status.ACTIVE;
            task.save();
            return false;
        }
        task.status = Task.Status.FAILED;
        task.lastError = ONE_SHOT_ERROR;
        task.save();
        return true;
    }

    /**
     * After the commit that {@link #close}d a one-shot task: the TASK_FAILED event, which reports the
     * run to the chat waiting on it, and the operator alert. A recurring task's next run reports instead.
     */
    public static void announce(Task task, @Nullable TaskRun run) {
        TaskLifecycleEvents.recordFailed(task, run, REASON, ONE_SHOT_ERROR);
        OperatorAlerts.onTaskCutOff(task, task.agent != null ? task.agent.name : null);
    }

    public static boolean isRecurring(Task task) {
        return task.type == Task.Type.CRON || task.type == Task.Type.INTERVAL;
    }
}
