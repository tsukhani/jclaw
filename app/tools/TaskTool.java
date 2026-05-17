package tools;

import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Task;
import services.EventLogger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public class TaskTool implements ToolRegistry.Tool {

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;

    @Override
    public String name() { return "task_manager"; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "tasks"; }

    @Override
    public String shortDescription() {
        return "Create, schedule, and manage recurring background tasks for the agent.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("createTask",            "Create an immediate background task with a name and description"),
                new agents.ToolAction("scheduleTask",          "Schedule a task to run once at a specific ISO 8601 datetime"),
                new agents.ToolAction("scheduleRecurringTask", "Create a recurring task using a cron expression"),
                new agents.ToolAction("scheduleIntervalTask",  "Create a recurring task that fires every N seconds"),
                new agents.ToolAction("cancelTask",            "Cancel a task by name (any type — immediate, scheduled, interval, or cron)"),
                new agents.ToolAction("listRecurringTasks",    "List all currently active recurring tasks")
        );
    }

    @Override
    public String description() {
        return """
                Manage background tasks. This is a single tool with an 'action' parameter. \
                Use action="createTask" to create a one-off task. \
                Use action="scheduleTask" to schedule a task for a future time. \
                Use action="scheduleRecurringTask" to create a recurring task on a cron schedule. \
                Use action="scheduleIntervalTask" to create a recurring task on a fixed period (every N seconds). \
                Use action="cancelTask" to cancel any task by name (any type). \
                Use action="listRecurringTasks" to list all recurring schedules. \
                Tasks run asynchronously via the agent.""";
    }

    @Override
    public String summary() {
        return "Manage background tasks via the 'action' parameter: createTask, scheduleTask, scheduleRecurringTask, scheduleIntervalTask, cancelTask, listRecurringTasks.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        "action", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("createTask", "scheduleTask", "scheduleRecurringTask",
                                        "scheduleIntervalTask", "cancelTask", "listRecurringTasks"),
                                SchemaKeys.DESCRIPTION, "The action to perform"),
                        "name", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING, SchemaKeys.DESCRIPTION, "Task name (short identifier)"),
                        SchemaKeys.DESCRIPTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING, SchemaKeys.DESCRIPTION, "Task description/instructions"),
                        "executionTime", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "ISO datetime for scheduled tasks: YYYY-MM-ddTHH:mm:ss"),
                        "cronExpression", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Cron expression for recurring tasks (e.g. '0 12 * * *')"),
                        "intervalSeconds", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Recurrence period in seconds for scheduleIntervalTask (positive integer)")
                ),
                SchemaKeys.REQUIRED, List.of("action")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();

        return switch (action) {
            case "createTask" -> createTask(args, agent);
            case "scheduleTask" -> scheduleTask(args, agent);
            case "scheduleRecurringTask" -> scheduleRecurringTask(args, agent);
            case "scheduleIntervalTask" -> scheduleIntervalTask(args, agent);
            case "cancelTask" -> cancelTask(args, agent);
            case "listRecurringTasks" -> listRecurringTasks();
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private String createTask(JsonObject args, Agent agent) {
        // Tools run on a virtual thread spawned by
        // ParallelToolExecutor with no inherited JPA transaction.
        // Wrap the save in a short Tx so the entity persists; the
        // scheduler-side register() runs after commit (it needs
        // task.id) and uses its own internal Tx for any reads.
        var saved = services.Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = args.get("name").getAsString();
            task.description = args.has(SchemaKeys.DESCRIPTION) ? args.get(SchemaKeys.DESCRIPTION).getAsString() : "";
            task.type = Task.Type.IMMEDIATE;
            task.nextRunAt = Instant.now();
            task.save();
            return task;
        });
        services.TaskSchedulingService.register(saved);
        EventLogger.info("task", agent.name, null, "Task created: %s".formatted(saved.name));
        return "Task '%s' created and queued for immediate execution.".formatted(saved.name);
    }

    private String scheduleTask(JsonObject args, Agent agent) {
        var ldt = LocalDateTime.parse(args.get("executionTime").getAsString());
        var saved = services.Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = args.get("name").getAsString();
            task.description = args.has(SchemaKeys.DESCRIPTION) ? args.get(SchemaKeys.DESCRIPTION).getAsString() : "";
            task.type = Task.Type.SCHEDULED;
            task.scheduledAt = ldt.atZone(ZoneId.systemDefault()).toInstant();
            task.nextRunAt = task.scheduledAt;
            task.save();
            return task;
        });
        services.TaskSchedulingService.register(saved);
        EventLogger.info("task", agent.name, null, "Task scheduled: %s at %s".formatted(saved.name, ldt));
        return "Task '%s' scheduled for %s.".formatted(saved.name, ldt);
    }

    private String scheduleIntervalTask(JsonObject args, Agent agent) {
        var seconds = args.get("intervalSeconds").getAsLong();
        if (seconds <= 0) {
            return "Error: intervalSeconds must be a positive integer.";
        }
        var saved = services.Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = args.get("name").getAsString();
            task.description = args.has(SchemaKeys.DESCRIPTION) ? args.get(SchemaKeys.DESCRIPTION).getAsString() : "";
            task.type = Task.Type.INTERVAL;
            task.intervalSeconds = seconds;
            // First fire is now; subsequent fires self-reschedule from
            // TaskExecutionHandler's CompletionHandler at completion-time
            // + intervalSeconds. The nextRunAt column is no longer
            // authoritative under db-scheduler — the scheduled_tasks row
            // is the source of truth — but we set it for backward-compat
            // dashboard queries until the column drops.
            task.nextRunAt = Instant.now();
            task.save();
            return task;
        });
        services.TaskSchedulingService.register(saved);
        EventLogger.info("task", agent.name, null,
                "Interval task created: %s (every %ds)".formatted(saved.name, seconds));
        return "Interval task '%s' created (every %ds).".formatted(saved.name, seconds);
    }

    private String scheduleRecurringTask(JsonObject args, Agent agent) {
        var saved = services.Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = args.get("name").getAsString();
            task.description = args.has(SchemaKeys.DESCRIPTION) ? args.get(SchemaKeys.DESCRIPTION).getAsString() : "";
            task.type = Task.Type.CRON;
            task.cronExpression = args.get("cronExpression").getAsString();
            // nextRunAt is no longer authoritative — db-scheduler's scheduled_tasks
            // row carries the next fire time. Set to a sentinel so the column has
            // a value (the entity refactor will drop it entirely).
            task.nextRunAt = Instant.now();
            task.save();
            return task;
        });
        services.TaskSchedulingService.register(saved);
        EventLogger.info("task", agent.name, null,
                "Recurring task created: %s (cron: %s)".formatted(saved.name, saved.cronExpression));
        return "Recurring task '%s' created with cron '%s'.".formatted(saved.name, saved.cronExpression);
    }

    private String cancelTask(JsonObject args, Agent agent) {
        var name = args.get("name").getAsString();
        // Tx-on-tool-thread: the finder + save need an active EntityManager
        // which the VT carrier thread lacks. Collect the ids inside the Tx;
        // cancel the scheduler rows outside since SchedulerClient.cancel is
        // JDBC-driven and doesn't need JPA context.
        //
        // Agent-scoped by design — two agents naming a task "daily summary"
        // must not be able to cancel each other's. Pairs with the multi-
        // tenancy stance documented elsewhere in JClaw.
        var cancelledIds = services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = (java.util.List<Object>) (java.util.List<?>) Task.find(
                    "name = ?1 AND agent = ?2 AND status != ?3",
                    name, agent, Task.Status.CANCELLED).fetch();
            var ids = new java.util.ArrayList<Long>(raw.size());
            for (var row : raw) {
                var task = (Task) row;
                task.status = Task.Status.CANCELLED;
                task.save();
                ids.add(task.id);
            }
            return ids;
        });
        if (cancelledIds.isEmpty()) {
            return "No task found with name '%s'.".formatted(name);
        }
        for (var taskId : cancelledIds) {
            services.TaskSchedulingService.cancel(taskId);
        }
        return cancelledIds.size() == 1
                ? "Task '%s' cancelled.".formatted(name)
                : "%d tasks named '%s' cancelled.".formatted(cancelledIds.size(), name);
    }

    private String listRecurringTasks() {
        var tasks = Task.findRecurring();
        if (tasks.isEmpty()) return "No recurring tasks configured.";
        var sb = new StringBuilder("Recurring tasks:\n");
        for (var task : tasks) {
            sb.append("- %s (cron: %s) — %s\n".formatted(
                    task.name, task.cronExpression,
                    task.description != null && task.description.length() > 100
                            ? task.description.substring(0, 100) + "..." : task.description));
        }
        return sb.toString();
    }
}
