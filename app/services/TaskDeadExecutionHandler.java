package services;

import com.github.kagkarlsson.scheduler.task.DeadExecutionHandler;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import models.Task;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import utils.AppClock;

import java.time.Instant;

/**
 * What happens to a task fire whose {@code scheduled_tasks} row went 120 s without a heartbeat
 * (30 s × 4 misses, per {@code DbSchedulerBootstrapJob}). db-scheduler's default revives the row,
 * which re-ran a fire a restart had cut off from the start (JF-355). Here a row is revived only when
 * that repeats nothing: a fire still live on this node (a stalled heartbeat, whose duplicate
 * {@link TaskExecutionHandler}'s claim guard drops), or a one-shot task whose fire died before it
 * started. A fire a stopped JVM left is ended as {@link RestartCutOff} says: a recurring task moves on
 * to its next occurrence, and a one-shot task's row is removed.
 *
 * <p>The decision is in {@link #decide}, which takes plain values, for the reason
 * {@link JClawFailureHandler#decide} is: db-scheduler's {@link ExecutionOperations} is awkward to stub.
 */
public final class TaskDeadExecutionHandler implements DeadExecutionHandler<Void> {

    /** What db-scheduler does with the dead row. */
    public sealed interface Decision permits Decision.Revive, Decision.NextOccurrence, Decision.Stop {
        /** Run it now: the fire is live on this node, so its duplicate is dropped. */
        record Revive() implements Decision {}

        /**
         * Move the row on to the task's next occurrence.
         *
         * @param at when the next occurrence fires
         */
        record NextOccurrence(Instant at) implements Decision {}

        /** Remove the row. */
        record Stop() implements Decision {}
    }

    @Override
    public void deadExecution(ExecutionComplete executionComplete, ExecutionOperations<Void> executionOperations) {
        var instanceId = executionComplete.getExecution().taskInstance.getId();
        Long taskId = parseTaskId(instanceId);
        if (taskId == null) {
            EventLogger.warn("task", null, null,
                    "TaskDeadExecutionHandler: undecodable task_instance '%s'; removing its row".formatted(instanceId));
            executionOperations.stop();
            return;
        }
        switch (decide(taskId, TaskRunRegistry.isTaskActive(taskId))) {
            case Decision.Revive _ -> executionOperations.reschedule(executionComplete, AppClock.now());
            case Decision.NextOccurrence(Instant at) -> executionOperations.reschedule(executionComplete, at);
            case Decision.Stop _ -> executionOperations.stop();
        }
    }

    /**
     * Decide what to do with a task's dead row, closing the task as {@link RestartCutOff} says when a
     * stopped JVM left it, and announcing a one-shot task that ends.
     *
     * @param taskId    the JClaw task the row fires
     * @param liveHere  whether a fire of the task is running on this node
     */
    public static Decision decide(Long taskId, boolean liveHere) {
        if (liveHere) return new Decision.Revive();
        record Outcome(Decision decision, @Nullable String agentName, @Nullable Task endedOneShot,
                       @Nullable TaskRun latestRun) {}
        var outcome = Tx.run(() -> {
            var task = (Task) Task.findById(taskId);
            if (task == null) return new Outcome(new Decision.Stop(), null, null, null);
            var agentName = task.agent != null ? task.agent.name : null;
            // A fire moves its task to RUNNING as it opens its run, so a one-shot still PENDING had started
            // nothing, and running it repeats nothing.
            if (!RestartCutOff.isRecurring(task) && task.status == Task.Status.PENDING) {
                return new Outcome(new Decision.Revive(), agentName, null, null);
            }
            boolean ended = RestartCutOff.close(task);
            var latest = ended ? (TaskRun) TaskRun.find("task.id = ?1 ORDER BY startedAt DESC", taskId).first() : null;
            Instant next = RestartCutOff.isRecurring(task) && task.status == Task.Status.ACTIVE
                    ? TaskSchedulingService.nextOccurrence(task) : null;
            return new Outcome(next != null ? new Decision.NextOccurrence(next) : new Decision.Stop(),
                    agentName, ended ? task : null, latest);
        });
        if (outcome.endedOneShot() != null) RestartCutOff.announce(outcome.endedOneShot(), outcome.latestRun());
        EventLogger.info("task", outcome.agentName(), null, describe(taskId, outcome.decision()));
        return outcome.decision();
    }

    private static String describe(Long taskId, Decision decision) {
        return switch (decision) {
            case Decision.Revive _ -> "TaskDeadExecutionHandler: Task id %d's fire died before it started; running it now"
                    .formatted(taskId);
            case Decision.NextOccurrence(Instant at) -> ("TaskDeadExecutionHandler: Task id %d's fire was cut off by a"
                    + " stop of JClaw; not run again, next occurrence at %s").formatted(taskId, at);
            case Decision.Stop _ -> ("TaskDeadExecutionHandler: Task id %d's fire was cut off by a stop of JClaw;"
                    + " not run again, its row removed").formatted(taskId);
        };
    }

    private static @Nullable Long parseTaskId(@Nullable String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return null;
        try {
            return Long.parseLong(instanceId.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
