package tools;

import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Task;
import services.EventLogger;
import services.ScheduleShorthandParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-294: agent-facing task management tool. One {@code task_manager}
 * tool with multiple actions; the {@code action} parameter dispatches.
 *
 * <h3>Schedule shorthand</h3>
 * The four typed creation actions
 * ({@code createTask}/{@code scheduleTask}/{@code scheduleRecurringTask}/{@code scheduleIntervalTask})
 * that JCLAW-21 shipped collapsed into a single {@code createTask} that
 * takes a {@code schedule} parameter routed through
 * {@link ScheduleShorthandParser}. The agent picks one of:
 * <ul>
 *   <li>{@code "now"} — IMMEDIATE</li>
 *   <li>{@code "30m"} / {@code "2h"} / {@code "1d"} — SCHEDULED at now + duration</li>
 *   <li>{@code "every 30m"} / {@code "every 2h"} / {@code "every 1d"} — INTERVAL</li>
 *   <li>Spring 6-field cron or {@code @hourly}/{@code @daily}/{@code @weekly}/{@code @monthly}/{@code @yearly} — CRON</li>
 * </ul>
 *
 * <h3>Agent-scoped name addressing</h3>
 * Every action that addresses an existing task does so by name + the
 * calling agent (per the multi-tenancy stance —
 * project_multi_tenancy_design memory). Two agents can both have a
 * task called "daily summary" without colliding; one agent can't
 * pause/resume/cancel another's.
 *
 * <h3>Fan-out semantics</h3>
 * One-shot tasks (IMMEDIATE/SCHEDULED) allow duplicate names per agent.
 * {@code cancelTask}/{@code pause}/{@code resume}/{@code runNow} fan out
 * across all non-cancelled matches and report a count.
 * {@code updateTask} requires exactly one match (the patch surface is
 * wider, and silently mass-updating multiple tasks would be surprising
 * — the response calls out the ambiguity instead).
 */
public class TaskTool implements ToolRegistry.Tool {

    // --- Action names (dispatch + schema enum + ToolAction labels) ---
    private static final String ACTION_CREATE_TASK = "createTask";
    private static final String ACTION_UPDATE_TASK = "updateTask";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_RESUME = "resume";
    private static final String ACTION_RUN_NOW = "runNow";
    private static final String ACTION_CANCEL_TASK = "cancelTask";
    private static final String ACTION_DELETE_TASK = "deleteTask";
    private static final String ACTION_LIST_RECURRING_TASKS = "listRecurringTasks";

    // --- JSON argument / schema keys ---
    private static final String KEY_ACTION = "action";
    private static final String KEY_NAME = "name";
    private static final String KEY_SCHEDULE = "schedule";
    private static final String KEY_PAUSED = "paused";
    private static final String KEY_DELIVERY = "delivery";
    private static final String KEY_PAYLOAD_TYPE = "payloadType";
    private static final String KEY_MODEL_PROVIDER = "modelProvider";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_ENABLED_TOOL_NAMES = "enabledToolNames";
    private static final String KEY_WORKDIR = "workdir";
    private static final String KEY_PRE_CHECK = "preCheck";
    private static final String KEY_SCRIPT = "script";
    private static final String KEY_NO_AGENT = "noAgent";
    private static final String KEY_CONTEXT_FROM_TASK_IDS = "contextFromTaskIds";
    private static final String KEY_REPEAT_LIMIT = "repeatLimit";

    // --- Common response strings ---
    private static final String ERR_NAME_REQUIRED = "Error: 'name' is required";
    private static final String MSG_NO_TASK_FOUND = "No task found with name '%s'.";

    @Override
    public String name() { return "task_manager"; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "tasks"; }

    @Override
    public String shortDescription() {
        return "Create, schedule, and manage background tasks for the agent.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction(ACTION_CREATE_TASK,           "Create a task with a unified schedule string (any of 'now', '30m', 'every 30m', or Spring 6-field cron / @daily etc.)"),
                new agents.ToolAction(ACTION_UPDATE_TASK,           "Partial update to a task by name — fields that aren't provided stay as-is"),
                new agents.ToolAction(ACTION_PAUSE,                 "Pause a recurring task by name; cadence is preserved, fires no-op until resume"),
                new agents.ToolAction(ACTION_RESUME,                "Resume a previously-paused task by name"),
                new agents.ToolAction(ACTION_RUN_NOW,               "Fire a task immediately by name; accepts any state (revives CANCELLED to PENDING)"),
                new agents.ToolAction(ACTION_CANCEL_TASK,           "Cancel a task by name (any type) — sets status=CANCELLED, row stays so runNow can revive it later"),
                new agents.ToolAction(ACTION_DELETE_TASK,           "Hard-delete a task and its run history by name. Irreversible — the row is gone. Use cancelTask if you might want it back"),
                new agents.ToolAction(ACTION_LIST_RECURRING_TASKS,  "List the agent's currently active recurring tasks")
        );
    }

    @Override
    public String description() {
        return """
                Manage background tasks — the abstraction for ANY scheduled or \
                recurring work. If the operator asks for "a subagent that runs \
                every X" or "something that fires every X minutes", they mean a \
                Task: subagents (spawn_subagent) fire ONCE and have no schedule \
                parameter. Single tool, multiple actions selected via the \
                'action' parameter. Use createTask with a 'schedule' string: \
                'now' (IMMEDIATE), '30m'/'2h'/'1d' (SCHEDULED at now+duration), \
                'every 30m'/'every 2h'/'every 1d' (INTERVAL — minimum 1 minute \
                via shorthand), or Spring 6-field cron ('0 0 9 * * *' or \
                '0/30 * * * * *' for every 30 seconds) or at-shortcut ('@daily'). \
                Use updateTask to change fields on an existing task by name. \
                Use pause/resume to toggle a recurring task without losing its \
                cadence. Use runNow to fire immediately. Use cancelTask to \
                stop fires while keeping the row (so runNow can revive it \
                later); use deleteTask to permanently remove the task and \
                its run history when you're done with it for good. Use \
                listRecurringTasks to see what's configured. Before creating \
                a new recurring task, call listRecurringTasks and \
                cancelTask/deleteTask any prior attempts with similar names \
                to avoid accumulating duplicates. Tasks run asynchronously \
                via the agent.""";
    }

    @Override
    public String summary() {
        return "Manage background tasks via the 'action' parameter: createTask, updateTask, pause, resume, runNow, cancelTask, deleteTask, listRecurringTasks.";
    }

    @Override
    public Map<String, Object> parameters() {
        // Map.ofEntries because Map.of caps at 10 keys and we have more.
        var props = Map.ofEntries(
                Map.entry(KEY_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.ENUM, List.of(ACTION_CREATE_TASK, ACTION_UPDATE_TASK, ACTION_PAUSE, ACTION_RESUME,
                                ACTION_RUN_NOW, ACTION_CANCEL_TASK, ACTION_DELETE_TASK, ACTION_LIST_RECURRING_TASKS),
                        SchemaKeys.DESCRIPTION, "The action to perform")),
                Map.entry(KEY_NAME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Task name (short identifier)")),
                Map.entry(SchemaKeys.DESCRIPTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Task description / instructions for the agent")),
                Map.entry(KEY_SCHEDULE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Schedule shorthand: 'now', duration like '30m'/'2h'/'1d' for one-shot, 'every <duration>' for INTERVAL, or Spring 6-field cron / at-shortcut for CRON")),
                Map.entry(KEY_PAUSED, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                        SchemaKeys.DESCRIPTION, "On updateTask: flip the paused flag")),
                Map.entry(KEY_DELIVERY, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Output delivery target — e.g. 'telegram:12345' or 'email:foo@bar'. Consumed by the delivery layer.")),
                Map.entry(KEY_PAYLOAD_TYPE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Output payload format hint: 'text', 'json', or 'markdown'")),
                Map.entry(KEY_MODEL_PROVIDER, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Override the agent's LLM provider for this task")),
                Map.entry(KEY_MODEL_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Override the agent's model id for this task")),
                Map.entry(KEY_ENABLED_TOOL_NAMES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "JSON array of tool names this task may use. Null = full toolset.")),
                Map.entry(KEY_WORKDIR, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Filesystem cwd for the task fire")),
                Map.entry(KEY_PRE_CHECK, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Pre-fire condition expression. Falsy skips the fire without consuming retry budget.")),
                Map.entry(KEY_SCRIPT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Shell script body — exec instead of the LLM when noAgent=true")),
                Map.entry(KEY_NO_AGENT, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                        SchemaKeys.DESCRIPTION, "Skip the LLM round-trip; runs script if set, otherwise delivers description verbatim")),
                Map.entry(KEY_CONTEXT_FROM_TASK_IDS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "JSON array of upstream Task ids whose outputs feed this task's context")),
                Map.entry(KEY_REPEAT_LIMIT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                        SchemaKeys.DESCRIPTION, "Max fires for a recurring task before auto-cancel. Null = unlimited."))
        );
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of(KEY_ACTION)
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get(KEY_ACTION).getAsString();

        return switch (action) {
            case ACTION_CREATE_TASK -> createTask(args, agent);
            case ACTION_UPDATE_TASK -> updateTask(args, agent);
            case ACTION_PAUSE -> pause(args, agent);
            case ACTION_RESUME -> resume(args, agent);
            case ACTION_RUN_NOW -> runNow(args, agent);
            case ACTION_CANCEL_TASK -> cancelTask(args, agent);
            case ACTION_DELETE_TASK -> deleteTask(args, agent);
            case ACTION_LIST_RECURRING_TASKS -> listRecurringTasks(agent);
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    // --- Helpers ---

    /** True iff {@code args} has {@code key} and its value is not JSON null. */
    private static boolean hasValue(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }

    /** Optional-string read: missing, null, or blank → null. */
    private static String optStr(JsonObject args, String key) {
        if (!args.has(key)) return null;
        var el = args.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Non-cancelled task ids matching (name, agent). Returns empty list
     * when nothing matches. Used by pause/resume/cancelTask — runNow uses
     * the any-state variant below because it explicitly revives CANCELLED.
     */
    private static List<Long> findTaskIds(String name, Agent agent) {
        return services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = (java.util.List<Object>) (java.util.List<?>) Task.find(
                    "name = ?1 AND agent = ?2 AND status != ?3",
                    name, agent, Task.Status.CANCELLED).fetch();
            var ids = new ArrayList<Long>(raw.size());
            for (var row : raw) ids.add(((Task) row).id);
            return ids;
        });
    }

    // --- Actions ---

    private String createTask(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();
        var description = optStr(args, SchemaKeys.DESCRIPTION);
        if (description == null) description = "";
        if (!hasValue(args, KEY_SCHEDULE)) {
            return "Error: 'schedule' is required (use 'now', a duration like '30m', 'every 30m', or a Spring 6-field cron / @daily etc.)";
        }
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            spec = ScheduleShorthandParser.parse(args.get(KEY_SCHEDULE).getAsString());
        } catch (IllegalArgumentException e) {
            return "Error: Invalid schedule: " + e.getMessage();
        }

        var conflict = checkRecurringDuplicate(name, agent, spec);
        if (conflict != null) return conflict;

        final String finalDescription = description;
        var saved = services.Tx.run(() -> persistNewTask(args, agent, name, finalDescription, spec));
        services.TaskSchedulingService.register(saved);

        EventLogger.info("TASK_MGMT_CREATE", agent.name, null,
                "Task '%s' (id=%d, type=%s) created via tool"
                        .formatted(saved.name, saved.id, saved.type));

        return switch (spec.type()) {
            case IMMEDIATE -> "Task '%s' created and queued for immediate execution.".formatted(name);
            case SCHEDULED -> "Task '%s' scheduled for %s.".formatted(name, spec.scheduledAt());
            case INTERVAL -> "Interval task '%s' created (every %ds).".formatted(name, spec.intervalSeconds());
            case CRON -> "Recurring task '%s' created with schedule '%s'.".formatted(name, spec.scheduleDisplay());
        };
    }

    /**
     * Recurring duplicate-name check — mirrors POST /api/tasks 409.
     * Returns the conflict error string, or null when there is no conflict.
     */
    private static String checkRecurringDuplicate(String name, Agent agent,
                                                  ScheduleShorthandParser.ScheduleSpec spec) {
        if (spec.type() != Task.Type.CRON && spec.type() != Task.Type.INTERVAL) {
            return null;
        }
        var conflictId = services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var existing = (java.util.List<Object>) (java.util.List<?>) Task.find(
                    "name = ?1 AND agent = ?2 AND type IN (?3, ?4) AND status != ?5",
                    name, agent, Task.Type.CRON, Task.Type.INTERVAL, Task.Status.CANCELLED
            ).fetch();
            return existing.isEmpty() ? null : ((Task) existing.getFirst()).id;
        });
        if (conflictId == null) return null;
        return "Error: A recurring task named '%s' already exists for this agent (id=%d). Use updateTask to modify it or cancelTask first."
                .formatted(name, conflictId);
    }

    /** Build + save a brand-new Task row inside the calling Tx. */
    private static Task persistNewTask(JsonObject args, Agent agent, String name,
                                       String description, ScheduleShorthandParser.ScheduleSpec spec) {
        var task = new Task();
        task.agent = agent;
        task.name = name;
        task.description = description;
        task.type = spec.type();
        task.scheduledAt = spec.scheduledAt();
        task.cronExpression = spec.cronExpression();
        task.intervalSeconds = spec.intervalSeconds();
        task.scheduleDisplay = spec.scheduleDisplay();
        task.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();

        // Plumbing fields (consumed by JCLAW-295/296/297/298).
        task.delivery = optStr(args, KEY_DELIVERY);
        task.payloadType = optStr(args, KEY_PAYLOAD_TYPE);
        task.modelProvider = optStr(args, KEY_MODEL_PROVIDER);
        task.modelId = optStr(args, KEY_MODEL_ID);
        task.enabledToolNames = optStr(args, KEY_ENABLED_TOOL_NAMES);
        task.workdir = optStr(args, KEY_WORKDIR);
        task.preCheck = optStr(args, KEY_PRE_CHECK);
        task.script = optStr(args, KEY_SCRIPT);
        if (hasValue(args, KEY_NO_AGENT)) {
            task.noAgent = args.get(KEY_NO_AGENT).getAsBoolean();
        }
        task.contextFromTaskIds = optStr(args, KEY_CONTEXT_FROM_TASK_IDS);
        if (hasValue(args, KEY_REPEAT_LIMIT)) {
            task.repeatLimit = args.get(KEY_REPEAT_LIMIT).getAsInt();
        }

        task.save();
        return task;
    }

    private String updateTask(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return "Error: 'name' is required to identify the task";
        }
        var name = args.get(KEY_NAME).getAsString();
        var ids = findTaskIds(name, agent);
        if (ids.isEmpty()) {
            return MSG_NO_TASK_FOUND.formatted(name);
        }
        if (ids.size() > 1) {
            // updateTask's patch surface is wide enough that fanning out
            // mass-mutations across multiple tasks is more surprising
            // than helpful. cancelTask/pause/resume fan out cleanly
            // because their effect is one toggle per row.
            return ("Ambiguous: %d tasks named '%s' for this agent. "
                    + "Cancel the duplicates and recreate, or use the HTTP API "
                    + "with the specific Task id.").formatted(ids.size(), name);
        }
        var taskId = ids.getFirst();

        // Schedule re-parse, if present, drives type + 4 derived fields.
        final ScheduleShorthandParser.ScheduleSpec spec;
        if (hasValue(args, KEY_SCHEDULE)) {
            try {
                spec = ScheduleShorthandParser.parse(args.get(KEY_SCHEDULE).getAsString());
            } catch (IllegalArgumentException e) {
                return "Error: Invalid schedule: " + e.getMessage();
            }
        } else {
            spec = null;
        }

        var changeFlags = services.Tx.run(() -> applyPatch(args, taskId, spec));

        if (!changeFlags[0]) {
            return "Error: No patchable fields provided in updateTask.";
        }
        if (changeFlags[1]) {
            // Re-read the saved task for the reschedule. Need a fresh Tx
            // because the previous one committed when its lambda returned.
            var refreshed = services.Tx.run(() -> (Task) Task.findById(taskId));
            if (refreshed != null) services.TaskSchedulingService.update(refreshed);
        }

        EventLogger.info("TASK_MGMT_UPDATE", agent.name, null,
                "Task '%s' (id=%d) updated via tool".formatted(name, taskId));
        return "Task '%s' updated.".formatted(name);
    }

    /**
     * Apply the patch surface to the addressed Task inside the calling Tx.
     * Returns {@code [anyChange, scheduleChanged]}; both false when the
     * task is gone or no patchable field was provided.
     */
    private static boolean[] applyPatch(JsonObject args, Long taskId,
                                        ScheduleShorthandParser.ScheduleSpec spec) {
        var task = (Task) Task.findById(taskId);
        if (task == null) return new boolean[] { false, false };
        boolean scheduleChanged = false;
        boolean anyChange = false;

        if (spec != null) {
            applyScheduleSpec(task, spec);
            scheduleChanged = true;
            anyChange = true;
        }

        if (args.has(SchemaKeys.DESCRIPTION)) {
            var v = optStr(args, SchemaKeys.DESCRIPTION);
            task.description = v != null ? v : "";
            anyChange = true;
        }
        anyChange |= applyStringPatches(args, task);
        anyChange |= applyFlagPatches(args, task);

        if (anyChange) task.save();
        return new boolean[] { anyChange, scheduleChanged };
    }

    /** Copy a parsed ScheduleSpec onto a Task (5 derived fields + nextRunAt). */
    private static void applyScheduleSpec(Task task, ScheduleShorthandParser.ScheduleSpec spec) {
        task.type = spec.type();
        task.scheduledAt = spec.scheduledAt();
        task.cronExpression = spec.cronExpression();
        task.intervalSeconds = spec.intervalSeconds();
        task.scheduleDisplay = spec.scheduleDisplay();
        task.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();
    }

    /** Patch the optional-string fields. Returns true iff any were touched. */
    private static boolean applyStringPatches(JsonObject args, Task task) {
        boolean anyChange = false;
        if (args.has(KEY_DELIVERY))           { task.delivery          = optStr(args, KEY_DELIVERY);           anyChange = true; }
        if (args.has(KEY_PAYLOAD_TYPE))       { task.payloadType       = optStr(args, KEY_PAYLOAD_TYPE);       anyChange = true; }
        if (args.has(KEY_MODEL_PROVIDER))     { task.modelProvider     = optStr(args, KEY_MODEL_PROVIDER);     anyChange = true; }
        if (args.has(KEY_MODEL_ID))           { task.modelId           = optStr(args, KEY_MODEL_ID);           anyChange = true; }
        if (args.has(KEY_ENABLED_TOOL_NAMES)) { task.enabledToolNames  = optStr(args, KEY_ENABLED_TOOL_NAMES); anyChange = true; }
        if (args.has(KEY_WORKDIR))            { task.workdir           = optStr(args, KEY_WORKDIR);            anyChange = true; }
        if (args.has(KEY_PRE_CHECK))          { task.preCheck          = optStr(args, KEY_PRE_CHECK);          anyChange = true; }
        if (args.has(KEY_SCRIPT))             { task.script            = optStr(args, KEY_SCRIPT);             anyChange = true; }
        if (args.has(KEY_CONTEXT_FROM_TASK_IDS)) {
            task.contextFromTaskIds = optStr(args, KEY_CONTEXT_FROM_TASK_IDS);
            anyChange = true;
        }
        return anyChange;
    }

    /** Patch the boolean / int fields (paused, noAgent, repeatLimit). */
    private static boolean applyFlagPatches(JsonObject args, Task task) {
        boolean anyChange = false;
        if (hasValue(args, KEY_PAUSED)) {
            task.paused = args.get(KEY_PAUSED).getAsBoolean();
            anyChange = true;
        }
        if (hasValue(args, KEY_NO_AGENT)) {
            task.noAgent = args.get(KEY_NO_AGENT).getAsBoolean();
            anyChange = true;
        }
        if (args.has(KEY_REPEAT_LIMIT)) {
            var el = args.get(KEY_REPEAT_LIMIT);
            task.repeatLimit = el.isJsonNull() ? null : el.getAsInt();
            anyChange = true;
        }
        return anyChange;
    }

    private String pause(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();
        var ids = findTaskIds(name, agent);
        if (ids.isEmpty()) return MSG_NO_TASK_FOUND.formatted(name);
        for (var id : ids) services.TaskSchedulingService.pause(id);
        EventLogger.info("TASK_MGMT_PAUSE", agent.name, null,
                "Task '%s' (%d match%s) paused via tool"
                        .formatted(name, ids.size(), ids.size() == 1 ? "" : "es"));
        return ids.size() == 1
                ? "Task '%s' paused.".formatted(name)
                : "%d tasks named '%s' paused.".formatted(ids.size(), name);
    }

    private String resume(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();
        var ids = findTaskIds(name, agent);
        if (ids.isEmpty()) return MSG_NO_TASK_FOUND.formatted(name);
        for (var id : ids) services.TaskSchedulingService.resume(id);
        EventLogger.info("TASK_MGMT_RESUME", agent.name, null,
                "Task '%s' (%d match%s) resumed via tool"
                        .formatted(name, ids.size(), ids.size() == 1 ? "" : "es"));
        return ids.size() == 1
                ? "Task '%s' resumed.".formatted(name)
                : "%d tasks named '%s' resumed.".formatted(ids.size(), name);
    }

    private String runNow(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();

        // Any-state lookup — runNow can target COMPLETED/FAILED/CANCELLED too.
        var revivedRef = new int[] { 0 };
        var lostIds = new ArrayList<Long>();
        var ranIds = services.Tx.run(() -> collectRunNowTargets(name, agent, revivedRef, lostIds));
        if (ranIds.isEmpty()) return MSG_NO_TASK_FOUND.formatted(name);
        for (var id : lostIds) {
            services.TaskSchedulingService.forceRemoveStaleRow(id);
            var fresh = services.Tx.run(() -> (Task) Task.findById(id));
            if (fresh != null) services.TaskSchedulingService.register(fresh);
        }
        for (var id : ranIds) {
            if (lostIds.contains(id)) continue;
            services.TaskSchedulingService.runNow(id);
        }

        var revived = revivedRef[0];
        EventLogger.info("TASK_MGMT_MANUAL_RUN", agent.name, null,
                ("Task '%s' (%d match%s) run-now via tool"
                        + (revived > 0 ? " (%d revived from CANCELLED)" : ""))
                        .formatted(name, ranIds.size(), ranIds.size() == 1 ? "" : "es",
                                revived > 0 ? revived : 0));
        String revivedSuffix = revived > 0 ? " (revived %d from CANCELLED)".formatted(revived) : "";
        return ranIds.size() == 1
                ? "Task '%s' run-now triggered%s.".formatted(name, revivedSuffix)
                : "%d tasks named '%s' run-now triggered%s.".formatted(ranIds.size(), name, revivedSuffix);
    }

    /**
     * Inside the calling Tx: scan tasks matching (name, agent) and prepare
     * them for a manual fire. Mutates {@code revivedRef[0]} as it flips
     * CANCELLED rows to PENDING, and appends LOST ids to {@code lostIds}
     * so the caller can force-remove their stale scheduled_tasks row.
     * Returns the full list of matched task ids (any state).
     */
    private static List<Long> collectRunNowTargets(String name, Agent agent,
                                                   int[] revivedRef, List<Long> lostIds) {
        @SuppressWarnings("unchecked")
        var raw = (java.util.List<Object>) (java.util.List<?>) Task.find(
                "name = ?1 AND agent = ?2", name, agent).fetch();
        var ids = new ArrayList<Long>(raw.size());
        for (var row : raw) {
            var task = (Task) row;
            if (task.status == Task.Status.CANCELLED) {
                // Revive — otherwise TaskExecutionHandler skips the fire body.
                task.status = Task.Status.PENDING;
                task.save();
                revivedRef[0]++;
            } else if (task.status == Task.Status.LOST) {
                // JCLAW-258: operator pre-empts db-scheduler's
                // auto-recovery. Flip to PENDING and remember the id
                // so we can force-remove the picked-but-stale
                // scheduled_tasks row outside the Tx before registering
                // a fresh fire below.
                task.status = Task.Status.PENDING;
                task.save();
                lostIds.add(task.id);
            }
            ids.add(task.id);
        }
        return ids;
    }

    private String cancelTask(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();
        // Tx-on-tool-thread: the finder + save need an active EntityManager
        // which the VT carrier thread lacks. Collect the ids inside the Tx;
        // cancel the scheduler rows outside since SchedulerClient.cancel is
        // JDBC-driven and doesn't need JPA context.
        //
        // Agent-scoped: two agents naming a task "daily summary" must not
        // be able to cancel each other's — multi-tenancy stance.
        var cancelledIds = services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = (java.util.List<Object>) (java.util.List<?>) Task.find(
                    "name = ?1 AND agent = ?2 AND status != ?3",
                    name, agent, Task.Status.CANCELLED).fetch();
            var ids = new ArrayList<Long>(raw.size());
            for (var row : raw) {
                var task = (Task) row;
                task.status = Task.Status.CANCELLED;
                task.save();
                ids.add(task.id);
            }
            return ids;
        });
        if (cancelledIds.isEmpty()) {
            return MSG_NO_TASK_FOUND.formatted(name);
        }
        for (var taskId : cancelledIds) {
            services.TaskSchedulingService.cancel(taskId);
        }
        EventLogger.info("TASK_MGMT_DELETE", agent.name, null,
                "Task '%s' (%d match%s) cancelled via tool"
                        .formatted(name, cancelledIds.size(),
                                cancelledIds.size() == 1 ? "" : "es"));
        return cancelledIds.size() == 1
                ? "Task '%s' cancelled.".formatted(name)
                : "%d tasks named '%s' cancelled.".formatted(cancelledIds.size(), name);
    }

    /**
     * Hard-delete a task and its run history by name. Unlike cancelTask,
     * which preserves the Task row so runNow can revive it, deleteTask
     * removes the row, every TaskRun referencing it, and every
     * TaskRunMessage under those runs. Agent-scoped — one agent cannot
     * delete another agent's tasks of the same name.
     */
    private String deleteTask(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();
        var deletedIds = services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = (java.util.List<Object>) (java.util.List<?>) Task.find(
                    "name = ?1 AND agent = ?2", name, agent).fetch();
            var ids = new ArrayList<Long>(raw.size());
            var em = play.db.jpa.JPA.em();
            for (var row : raw) {
                var task = (Task) row;
                var taskId = task.id;
                em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.task.id = :taskId")
                        .setParameter("taskId", taskId).executeUpdate();
                em.createQuery("DELETE FROM TaskRun r WHERE r.task.id = :taskId")
                        .setParameter("taskId", taskId).executeUpdate();
                task.delete();
                ids.add(taskId);
            }
            return ids;
        });
        if (deletedIds.isEmpty()) {
            return MSG_NO_TASK_FOUND.formatted(name);
        }
        // Drop scheduler rows outside the Tx — idempotent and JDBC-only.
        for (var taskId : deletedIds) {
            services.TaskSchedulingService.cancel(taskId);
        }
        EventLogger.info("TASK_MGMT_HARD_DELETE", agent.name, null,
                "Task '%s' (%d match%s) hard-deleted via tool"
                        .formatted(name, deletedIds.size(),
                                deletedIds.size() == 1 ? "" : "es"));
        return deletedIds.size() == 1
                ? "Task '%s' deleted.".formatted(name)
                : "%d tasks named '%s' deleted.".formatted(deletedIds.size(), name);
    }

    private String listRecurringTasks(Agent agent) {
        // Agent-scoped: per the multi-tenancy stance one agent must not
        // see another agent's recurring schedule. The finder also now
        // includes INTERVAL alongside CRON since both are recurring.
        var tasks = services.Tx.run(() -> Task.findRecurring(agent));
        if (tasks.isEmpty()) return "No recurring tasks configured.";
        var sb = new StringBuilder("Recurring tasks:\n");
        for (var task : tasks) {
            // Prefer scheduleDisplay (operator's original input) so the agent
            // sees the same string the operator typed. Falls back to the
            // type-specific field for legacy rows pre-JCLAW-294.
            String cadence = task.scheduleDisplay != null
                    ? task.scheduleDisplay
                    : (task.type == Task.Type.CRON
                            ? "cron: " + task.cronExpression
                            : "every " + task.intervalSeconds + "s");
            sb.append("- %s (%s) — %s\n".formatted(
                    task.name, cadence,
                    task.description != null && task.description.length() > 100
                            ? task.description.substring(0, 100) + "..." : task.description));
        }
        return sb.toString();
    }
}
