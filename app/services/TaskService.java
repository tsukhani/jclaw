package services;

import models.Agent;
import models.Task;
import models.TaskRun;

import java.util.List;

/**
 * JCLAW-153: entity-lookup accessors for the Task domain so controllers route
 * their finder calls through the service layer instead of reaching into raw
 * {@code Entity.findById(...)}. Thin passthroughs that rely on the caller's
 * ambient JPA transaction — no {@link Tx} wrapper — matching
 * {@link AgentService#findById}.
 */
public final class TaskService {

    private TaskService() {}

    public static Task findById(Long id) {
        return Task.findById(id);
    }

    public static TaskRun findRunById(Long id) {
        return TaskRun.findById(id);
    }

    /**
     * JCLAW-153: recurring-task name conflicts for the create path. Returns the
     * tasks sharing {@code name} + {@code agent} whose type is CRON or INTERVAL
     * and status is not CANCELLED — the set {@code ApiTasksController.create}
     * inspects to 409 a duplicate recurring task. Exposes the inline
     * {@code Task.find(...)} at that create site so the controller no longer
     * calls a raw JPA finder. Relies on the caller's ambient transaction.
     */
    public static List<Task> findRecurringConflicts(String name, Agent agent) {
        return Task.<Task>find(
                "name = ?1 AND agent = ?2 AND type IN (?3, ?4) AND status != ?5",
                name, agent, Task.Type.CRON, Task.Type.INTERVAL, Task.Status.CANCELLED
        ).fetch();
    }
}
