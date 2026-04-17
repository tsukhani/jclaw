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
                new agents.ToolAction("deleteRecurringTask",   "Cancel a recurring task by name"),
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
                Use action="deleteRecurringTask" to remove a recurring schedule. \
                Use action="listRecurringTasks" to list all recurring schedules. \
                Tasks run asynchronously via the agent.""";
    }

    @Override
    public String summary() {
        return "Manage background tasks via the 'action' parameter: createTask, scheduleTask, scheduleRecurringTask, deleteRecurringTask, listRecurringTasks.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("createTask", "scheduleTask", "scheduleRecurringTask",
                                        "deleteRecurringTask", "listRecurringTasks"),
                                "description", "The action to perform"),
                        "name", Map.of("type", "string", "description", "Task name (short identifier)"),
                        "description", Map.of("type", "string", "description", "Task description/instructions"),
                        "executionTime", Map.of("type", "string",
                                "description", "ISO datetime for scheduled tasks: YYYY-MM-ddTHH:mm:ss"),
                        "cronExpression", Map.of("type", "string",
                                "description", "Cron expression for recurring tasks (e.g. '0 12 * * *')")
                ),
                "required", List.of("action")
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
            case "deleteRecurringTask" -> deleteRecurringTask(args);
            case "listRecurringTasks" -> listRecurringTasks();
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private String createTask(JsonObject args, Agent agent) {
        var task = new Task();
        task.agent = agent;
        task.name = args.get("name").getAsString();
        task.description = args.has("description") ? args.get("description").getAsString() : "";
        task.type = Task.Type.IMMEDIATE;
        task.nextRunAt = Instant.now();
        task.save();
        EventLogger.info("task", agent.name, null, "Task created: %s".formatted(task.name));
        return "Task '%s' created and queued for immediate execution.".formatted(task.name);
    }

    private String scheduleTask(JsonObject args, Agent agent) {
        var task = new Task();
        task.agent = agent;
        task.name = args.get("name").getAsString();
        task.description = args.has("description") ? args.get("description").getAsString() : "";
        task.type = Task.Type.SCHEDULED;
        var ldt = LocalDateTime.parse(args.get("executionTime").getAsString());
        task.scheduledAt = ldt.atZone(ZoneId.systemDefault()).toInstant();
        task.nextRunAt = task.scheduledAt;
        task.save();
        EventLogger.info("task", agent.name, null, "Task scheduled: %s at %s".formatted(task.name, ldt));
        return "Task '%s' scheduled for %s.".formatted(task.name, ldt);
    }

    private String scheduleRecurringTask(JsonObject args, Agent agent) {
        var task = new Task();
        task.agent = agent;
        task.name = args.get("name").getAsString();
        task.description = args.has("description") ? args.get("description").getAsString() : "";
        task.type = Task.Type.CRON;
        task.cronExpression = args.get("cronExpression").getAsString();
        task.nextRunAt = Instant.now(); // Will be computed properly by poller
        task.save();
        EventLogger.info("task", agent.name, null,
                "Recurring task created: %s (cron: %s)".formatted(task.name, task.cronExpression));
        return "Recurring task '%s' created with cron '%s'.".formatted(task.name, task.cronExpression);
    }

    private String deleteRecurringTask(JsonObject args) {
        var name = args.get("name").getAsString();
        java.util.List<Task> tasks = Task.find("name = ?1 AND type = ?2 AND status != ?3",
                name, Task.Type.CRON, Task.Status.CANCELLED).fetch();
        if (tasks.isEmpty()) {
            return "No recurring task found with name '%s'.".formatted(name);
        }
        for (var task : tasks) {
            task.status = Task.Status.CANCELLED;
            task.save();
        }
        return "Recurring task '%s' cancelled.".formatted(name);
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
