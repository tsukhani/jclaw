package tools;

import models.Agent;
import models.Task;
import services.DeliverySpec;
import services.Tx;

/**
 * JCLAW-726: the task-listing concern lifted out of {@link TaskTool} — the two
 * agent-facing "show me what's configured" renders. Both are agent-scoped (one
 * agent must not see another's schedule) and produce the plain-text summaries
 * the LLM reads back.
 */
final class TaskListRenderer {

    private TaskListRenderer() {}

    /** Render the agent's active recurring tasks (CRON + INTERVAL). */
    static String recurring(Agent agent) {
        var tasks = Tx.run(() -> Task.findRecurring(agent));
        if (tasks.isEmpty()) return "No recurring tasks configured.";
        var sb = new StringBuilder("Recurring tasks:\n");
        for (var task : tasks) {
            // Prefer scheduleDisplay (operator's original input) so the agent
            // sees the same string the operator typed. Falls back to the
            // type-specific field for legacy rows pre-JCLAW-294.
            String typedCadence = task.type == Task.Type.CRON
                    ? "cron: " + task.cronExpression
                    : "every " + task.intervalSeconds + "s";
            String cadence = task.scheduleDisplay != null ? task.scheduleDisplay : typedCadence;
            // JCLAW-421: surface the typed delivery (channel / tool / none) so a
            // re-normalization pass can spot tasks whose delivery still lives
            // only in the prose and lift it into the field via updateTask.
            String delivery = DeliverySpec.parse(task.delivery).label();
            sb.append("- %s (%s) [delivery: %s] — %s\n".formatted(
                    task.name, cadence, delivery,
                    task.description != null && task.description.length() > 100
                            ? task.description.substring(0, 100) + "..." : task.description));
        }
        return sb.toString();
    }

    /** Render the agent's upcoming reminders (PENDING one-shots + ACTIVE recurring). */
    static String reminders(Agent agent) {
        var reminders = Tx.run(() -> Task.findReminders(agent));
        if (reminders.isEmpty()) return "No upcoming reminders.";
        var sb = new StringBuilder("Reminders:\n");
        for (var task : reminders) {
            sb.append("- %s (%s) [%s] — %s\n".formatted(
                    task.name, whenLabel(task), task.status.name(),
                    task.description != null && task.description.length() > 100
                            ? task.description.substring(0, 100) + "..." : task.description));
        }
        return sb.toString();
    }

    /**
     * The reminder's display "when". One-shot reminders: {@code nextRunAt} is the
     * real fire instant (what the user cares about when editing). Recurring
     * reminders: {@code nextRunAt} is only a create-time placeholder (the live
     * next-fire lives in the scheduler), so show the cadence string instead — the
     * same choice {@code recurring()} makes.
     */
    private static String whenLabel(Task task) {
        boolean recurring = task.type == Task.Type.CRON || task.type == Task.Type.INTERVAL;
        if (recurring) return task.scheduleDisplay != null ? task.scheduleDisplay : task.type.name();
        if (task.nextRunAt != null) return task.nextRunAt.toString();
        return task.scheduleDisplay != null ? task.scheduleDisplay : "—";
    }
}
