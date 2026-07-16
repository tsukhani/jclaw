package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Task;
import play.db.jpa.JPA;
import services.DeliveryAdvisor;
import services.DeliveryDispatcher;
import services.DeliverySpec;
import services.EventLogger;
import services.ScheduleShorthandParser;
import services.TaskSchedulingService;
import services.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-294: agent-facing task management tool. One {@code task_manager}
 * tool with multiple actions; the {@code action} parameter dispatches.
 *
 * <h2>Schedule shorthand</h2>
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
 * <h2>Agent-scoped name addressing</h2>
 * Every action that addresses an existing task does so by name + the
 * calling agent (agent isolation). Two agents can both have a
 * task called "daily summary" without colliding; one agent can't
 * pause/resume/cancel another's.
 *
 * <h2>Fan-out semantics</h2>
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
    private static final String ACTION_LIST_REMINDERS = "listReminders";

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
    private static final String KEY_AUTO_DELETE = "autoDeleteOnComplete";
    private static final String KEY_CONTEXT_FROM_TASK_IDS = "contextFromTaskIds";
    private static final String KEY_REPEAT_LIMIT = "repeatLimit";
    private static final String KEY_TIMEZONE = "timezone";

    // --- Common response strings ---
    private static final String ERR_PREFIX = "Error: ";
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
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_CREATE_TASK,           "Create a task with a unified schedule string (any of 'now', '30m', 'every 30m', or Spring 6-field cron / @daily etc.)"),
                new ToolAction(ACTION_UPDATE_TASK,           "Partial update to a task by name — fields that aren't provided stay as-is"),
                new ToolAction(ACTION_PAUSE,                 "Pause a recurring task by name; cadence is preserved, fires no-op until resume"),
                new ToolAction(ACTION_RESUME,                "Resume a previously-paused task by name"),
                new ToolAction(ACTION_RUN_NOW,               "Fire a task immediately by name; accepts any state (revives CANCELLED to PENDING)"),
                new ToolAction(ACTION_CANCEL_TASK,           "Cancel a task by name (any type) — sets status=CANCELLED, row stays so runNow can revive it later"),
                new ToolAction(ACTION_DELETE_TASK,           "Hard-delete a task and its run history by name. Irreversible — the row is gone. Use cancelTask if you might want it back"),
                new ToolAction(ACTION_LIST_RECURRING_TASKS,  "List the agent's currently active recurring tasks"),
                new ToolAction(ACTION_LIST_REMINDERS,        "List the agent's upcoming reminders (PENDING one-shots + ACTIVE recurring) — name, fire time, status — so you can update or cancel one by name")
        );
    }

    @Override
    public String description() {
        return """
                Manage background tasks — the abstraction for ANY scheduled or \
                recurring work. If the operator asks for "a subagent that runs \
                every X" or "something that fires every X minutes", they mean a \
                Task: subagents (subagent_spawn) fire ONCE and have no schedule \
                parameter. Single tool, multiple actions selected via the \
                'action' parameter. Use createTask with a 'schedule' string: \
                'now' (IMMEDIATE), '30m'/'2h'/'1d' (SCHEDULED at now+duration), \
                an absolute ISO date-time '2026-06-13T15:00' (SCHEDULED one-shot \
                at that specific moment, in the task's timezone), \
                'every 30m'/'every 2h'/'every 1d' (INTERVAL — minimum 1 minute \
                via shorthand), or Spring 6-field cron ('0 0 9 * * *' or \
                '0/30 * * * * *' for every 30 seconds) or at-shortcut ('@daily'). \
                For a ONE-TIME fire at a specific date/time use the absolute \
                date-time, NOT a cron (a cron would repeat every year). \
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
                via the agent.

                OUTPUT DELIVERY: when you create a task, set the `delivery` \
                field to where its output should go — a channel \
                ('telegram:<id>'), a tool the task calls during its run \
                ('tool:send_gmail_message' to email the result), or 'none' if \
                the output just stays in the run. This is a typed field the UI \
                reads AND the engine acts on. Put only the WORK in \
                `description` — do NOT add a 'send it to <channel>' step: when \
                the task completes JClaw automatically delivers the run's \
                output to the `delivery` target (and for a 'tool:' target it \
                injects the call directive for you), so a manual send step is \
                redundant and can make the result go out twice. Omit \
                `delivery` entirely to auto-route output back to this chat. The \
                same applies when normalizing EXISTING tasks: listRecurringTasks \
                shows each task's [delivery: …]; for any whose delivery still \
                lives only in the prose, infer it, set the field with \
                updateTask, and drop the now-redundant send step from the \
                description.

                REMINDERS: when the user says "remind me to X" / "remind me \
                in N minutes to Y" / "remind me tomorrow about Z", create a \
                task with payloadType="reminder". For a ONE-TIME reminder at a \
                specific date/time (e.g. "remind me at 3pm on June 13"), pass an \
                absolute ISO date-time as the schedule (e.g. "2026-06-13T15:00") \
                so it fires ONCE then completes — do NOT use a cron for a one-off \
                (that repeats every year). Use a cron only when the user wants it \
                to REPEAT (e.g. "every Friday"). A one-off reminder auto-deletes \
                itself after it fires (autoDeleteOnComplete defaults true for \
                reminders); set autoDeleteOnComplete=false to KEEP a fired \
                reminder. The `description` IS the \
                reminder text the user sees verbatim (e.g. "Brush your \
                teeth", "Pay salaries") — do NOT phrase it as instructions \
                to yourself. Reminders SKIP the LLM at fire time, so no \
                agent turn happens; the description goes straight to the \
                user's notification toast (web) or Telegram chat (with a \
                🔔 prefix). The reminder description should be 1-2 short \
                lines, written as if you were the one nudging the user. \
                Leave `delivery` unset and it auto-routes to the calling \
                chat — that's almost always what the user wants. To FIND or \
                MODIFY an existing reminder (e.g. "move my 3pm reminder to \
                4pm", "cancel the dentist reminder"), call listReminders \
                first — it returns the agent's upcoming reminders (name, fire \
                time, status). A one-time reminder is SCHEDULED, so it does \
                NOT appear in listRecurringTasks; use listReminders for it. \
                Then address the reminder by its name with updateTask / \
                cancelTask / deleteTask.""";
    }

    @Override
    public String summary() {
        return "Manage background tasks via the 'action' parameter: createTask, updateTask, pause, resume, runNow, cancelTask, deleteTask, listRecurringTasks, listReminders.";
    }

    @Override
    public Map<String, Object> parameters() {
        // Map.ofEntries because Map.of caps at 10 keys and we have more.
        var props = Map.ofEntries(
                Map.entry(KEY_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.ENUM, List.of(ACTION_CREATE_TASK, ACTION_UPDATE_TASK, ACTION_PAUSE, ACTION_RESUME,
                                ACTION_RUN_NOW, ACTION_CANCEL_TASK, ACTION_DELETE_TASK, ACTION_LIST_RECURRING_TASKS,
                                ACTION_LIST_REMINDERS),
                        SchemaKeys.DESCRIPTION, "The action to perform")),
                Map.entry(KEY_NAME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Task name — a short kebab-case identifier: lower-case "
                                + "words joined by hyphens, e.g. 'nose-trimmer-search', 'morning-summary', "
                                + "'weekly-invoice-digest'. NOT a title-case phrase like 'Nose Trimmer "
                                + "Price Hunt'. It's the handle cancelTask/updateTask/runNow address it by.")),
                Map.entry(SchemaKeys.DESCRIPTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Task instructions for the agent — the WORK only. For an "
                                + "agent task ALWAYS pass a JSON array of step strings in order — never a "
                                + "prose paragraph — even when there is only one action (then it's a "
                                + "one-element array), e.g. "
                                + "[\"Fetch yesterday's orders\", \"Summarise the totals\", "
                                + "\"Highlight anything unusual\"] — the steps render as a numbered "
                                + "list in the admin UI and are flattened into the agent's prompt. Do NOT "
                                + "add a 'send it to <channel>' step — where the output goes is the "
                                + "`delivery` field's job, delivered automatically after the run. The ONLY "
                                + "case that passes a single plain string is a reminder, whose description "
                                + "is the verbatim text the user sees.")),
                Map.entry(KEY_SCHEDULE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "Schedule shorthand: 'now' (IMMEDIATE); a duration like '30m'/'2h'/'1d' for a one-shot N-from-now; an absolute ISO date-time like '2026-06-13T15:00' for a one-shot at a specific moment (interpreted in the task's timezone); 'every <duration>' for INTERVAL; or a Spring 6-field cron / at-shortcut for CRON. Use an absolute date-time (not a cron) for a one-time reminder on a specific date. "
                                + "Day-of-month modifiers (the cron engine supports them): for 'the last <weekday> of the month' use the L suffix in the day-of-week field, e.g. '0 0 17 * * 5L' = last Friday at 5 PM — do NOT use a day-of-month range like '25-31', which silently skips months where the last weekday falls before the 25th. For the Nth weekday use '#', e.g. '0 0 9 * * 1#2' = 2nd Monday. For the last calendar day of the month use 'L' in the day-of-month field, e.g. '0 0 9 L * *'.")),
                Map.entry(KEY_PAUSED, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                        SchemaKeys.DESCRIPTION, "On updateTask: flip the paused flag")),
                Map.entry(KEY_DELIVERY, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION,
                        "Where the task's output goes. Three forms: "
                                + "(1) a channel — '<channel>:<target>' e.g. 'telegram:12345', "
                                + "'slack:C0123', 'whatsapp:+15551234567' (or just the channel name "
                                + "like 'web'/'telegram', which fills the target from the calling chat); "
                                + "(2) a tool the agent calls during the run — 'tool:<toolName>' e.g. "
                                + "'tool:send_gmail_message' for emailing the result (email is NOT a "
                                + "channel — use tool: for it); "
                                + "(3) 'none' for a task whose output just stays in the run. "
                                + "When the task should deliver back to the chat that's creating it (the "
                                + "common 'remind me' case), OMIT this field — it auto-fills to the "
                                + "calling conversation.")),
                Map.entry(KEY_PAYLOAD_TYPE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION,
                        "Payload kind. \"reminder\" makes this a user-visible "
                                + "reminder — fire skips the LLM, the description "
                                + "is delivered verbatim to the configured channel "
                                + "(web notification toast / Telegram chat with 🔔 "
                                + "prefix). Other values (\"text\", \"json\", "
                                + "\"markdown\") are hints for future delivery-layer "
                                + "formatting; leave null for ordinary agent-driven "
                                + "tasks.")),
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
                Map.entry(KEY_AUTO_DELETE, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                        SchemaKeys.DESCRIPTION, "Auto-delete this reminder after a successful one-off fire (a fired one-off reminder has served its purpose). Defaults TRUE for reminders, false for regular tasks; set false to KEEP a reminder. Only one-shot reminders are affected — recurring reminders and regular tasks are never auto-deleted.")),
                Map.entry(KEY_CONTEXT_FROM_TASK_IDS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION, "JSON array of upstream Task ids whose outputs feed this task's context")),
                Map.entry(KEY_REPEAT_LIMIT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                        SchemaKeys.DESCRIPTION, "Max fires for a recurring task before auto-cancel. Null = unlimited.")),
                Map.entry(KEY_TIMEZONE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.DESCRIPTION,
                        "IANA timezone (e.g. 'America/New_York', 'Asia/Tokyo') for CRON / SCHEDULED "
                                + "fire-time resolution. Null = use the operator's default "
                                + "(Settings → Tasks → Default timezone). INTERVAL / IMMEDIATE ignore this field."))
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
            case ACTION_LIST_RECURRING_TASKS -> TaskListRenderer.recurring(agent);
            case ACTION_LIST_REMINDERS -> TaskListRenderer.reminders(agent);
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
     * JCLAW-260: read the {@code description} arg, which the LLM may pass
     * either as a plain string (single-step / free text) or as a JSON array
     * of step strings. An array is stored as its canonical JSON serialization
     * in the existing description TEXT column; a string is stored verbatim;
     * missing or JSON-null yields null. {@link services.TaskSteps#parse}
     * reverses this at read/fire time. Both shapes are accepted because some
     * models honour the string schema by sending a JSON-array string while
     * others send a real array.
     */
    private static String readDescriptionArg(JsonObject args) {
        if (!hasValue(args, SchemaKeys.DESCRIPTION)) return null;
        var el = args.get(SchemaKeys.DESCRIPTION);
        if (el.isJsonArray()) return el.toString();         // ["step 1","step 2"]
        if (el.isJsonPrimitive()) return el.getAsString();  // plain string OR a JSON-array string
        return el.toString();                               // defensive: object / other
    }

    /**
     * Typed {@code Task.find(...).fetch()} (JCLAW-729). The play1 finder returns a
     * raw {@code List} whose {@code (List<Task>)} cast won't compile, so copy the
     * rows into a typed list once — the five callers then iterate {@code Task}
     * directly instead of each repeating the raw cast + per-row {@code (Task)}
     * downcast. Call inside an active Tx (the finder needs an EntityManager).
     */
    @SuppressWarnings("unchecked")
    private static List<Task> findTasks(String query, Object... params) {
        var raw = (List<Object>) (List<?>) Task.find(query, params).fetch();
        var tasks = new ArrayList<Task>(raw.size());
        for (var row : raw) tasks.add((Task) row);
        return tasks;
    }

    /**
     * Non-cancelled task ids matching (name, agent). Returns empty list
     * when nothing matches. Used by pause/resume/cancelTask — runNow uses
     * the any-state variant below because it explicitly revives CANCELLED.
     */
    private static List<Long> findTaskIds(String name, Agent agent) {
        return Tx.run(() -> {
            var tasks = findTasks("name = ?1 AND agent = ?2 AND status != ?3",
                    name, agent, Task.Status.CANCELLED);
            var ids = new ArrayList<Long>(tasks.size());
            for (var task : tasks) ids.add(task.id);
            return ids;
        });
    }

    // --- Actions ---

    private String createTask(JsonObject args, Agent agent) {
        if (!hasValue(args, KEY_NAME)) {
            return ERR_NAME_REQUIRED;
        }
        var name = args.get(KEY_NAME).getAsString();
        var description = readDescriptionArg(args);
        if (description == null) description = "";
        if (!hasValue(args, KEY_SCHEDULE)) {
            return "Error: 'schedule' is required (use 'now', a duration like '30m', an absolute date-time like '2026-06-13T15:00', 'every 30m', or a Spring 6-field cron / @daily etc.)";
        }
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            // The zone is resolved up front (inside TaskScheduleSupport.parse) so
            // an absolute date-time schedule ("2026-06-13T15:00") is interpreted
            // in the same timezone the task is saved with (validated again in
            // persistNewTask).
            spec = TaskScheduleSupport.parse(args.get(KEY_SCHEDULE).getAsString(), optStr(args, KEY_TIMEZONE));
        } catch (IllegalArgumentException e) {
            return "Error: Invalid schedule: " + e.getMessage();
        }

        // JCLAW-418: validate the explicit delivery arg against the typed
        // grammar before persisting. null/omitted is fine (it gets inferred);
        // a malformed value (e.g. email:, which is not a channel) is rejected
        // with a hint toward tool:send_gmail_message.
        var deliveryErr = DeliverySpec.validate(optStr(args, KEY_DELIVERY));
        if (deliveryErr != null) return ERR_PREFIX + deliveryErr;

        var conflict = checkRecurringDuplicate(name, agent, spec);
        if (conflict != null) return conflict;

        final String finalDescription = description;
        final Task saved;
        try {
            saved = Tx.run(() -> persistNewTask(args, agent, name, finalDescription, spec));
        } catch (IllegalArgumentException e) {
            // JCLAW-261: surface invalid-timezone (or any other validated
            // arg) as a clean tool error instead of a Tx-wrapped trace.
            return ERR_PREFIX + e.getMessage();
        }
        TaskSchedulingService.register(saved);

        EventLogger.info("TASK_MGMT_CREATE", agent.name, null,
                "Task '%s' (id=%d, type=%s) created via tool"
                        .formatted(saved.name, saved.id, saved.type));

        var base = switch (spec.type()) {
            case IMMEDIATE -> "Task '%s' created and queued for immediate execution.".formatted(name);
            case SCHEDULED -> "Task '%s' scheduled for %s.".formatted(name, TaskScheduleSupport.formatScheduledAt(saved));
            case INTERVAL -> "Interval task '%s' created (every %ds).".formatted(name, spec.intervalSeconds());
            case CRON -> "Recurring task '%s' created with schedule '%s'.".formatted(name, spec.scheduleDisplay());
        };
        // JCLAW-455: warn in chat if the declared Slack delivery target isn't reachable
        // (private/uninvited channel). Non-blocking — the task is still created.
        return withDeliveryAdvisory(base, agent, saved.delivery);
    }

    /** JCLAW-455: append a non-blocking delivery-reachability advisory to a tool result,
     *  or return {@code base} unchanged when none applies. */
    private static String withDeliveryAdvisory(String base, Agent agent, String deliverySpec) {
        var advisory = DeliveryAdvisor.advisoryFor(agent, deliverySpec);
        return advisory == null ? base : base + "\n\n⚠️ " + advisory;
    }

    /**
     * Resolve the {@code delivery} value to store on a brand-new Task.
     * Three input shapes from the LLM:
     * <ol>
     *   <li>Missing / null → infer the full {@code "<channel>:<target>"}
     *       from the calling agent's most-recently-updated Conversation.</li>
     *   <li>Bare channel name ({@code "web"}, {@code "telegram"},
     *       {@code "slack"}, {@code "whatsapp"}) with no colon → keep the
     *       agent's channel choice, fill the target from the same
     *       Conversation lookup. This handles the common pattern where the
     *       LLM picks a channel from the request ("message this chat") but
     *       doesn't know the channel-specific target format.</li>
     *   <li>Full {@code "channel:target"} spec → store verbatim, no
     *       inference (operator knows what they're doing).</li>
     * </ol>
     */
    private static String resolveDeliverySpec(String explicit, Agent agent) {
        if (explicit == null) return DeliveryResolver.inferSpec(agent).orElse(null);
        var trimmed = explicit.trim();
        if (trimmed.isEmpty()) return DeliveryResolver.inferSpec(agent).orElse(null);
        if (trimmed.indexOf(':') >= 0) return explicit;
        // Bare channel name. Fill target from the same conversation lookup
        // the no-arg path uses, then prepend the agent-supplied channel
        // hint. If the conversation lookup doesn't yield a usable target,
        // fall back to the inference shape (which will be null and produce
        // NOT_REQUESTED rather than a fire-time spec rejection).
        if (!DeliveryDispatcher.isSupported(trimmed.toLowerCase())) {
            return explicit;  // Unknown channel — let dispatchSpec surface it.
        }
        var inferred = DeliveryResolver.inferSpec(agent).orElse(null);
        if (inferred == null) return null;
        var colon = inferred.indexOf(':');
        if (colon < 0) return null;
        return "%s:%s".formatted(trimmed.toLowerCase(), inferred.substring(colon + 1));
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
        var conflictId = Tx.run(() -> {
            var existing = findTasks(
                    "name = ?1 AND agent = ?2 AND type IN (?3, ?4) AND status != ?5",
                    name, agent, Task.Type.CRON, Task.Type.INTERVAL, Task.Status.CANCELLED);
            return existing.isEmpty() ? null : existing.getFirst().id;
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
        // CRON / INTERVAL start ACTIVE (ongoing), IMMEDIATE / SCHEDULED start
        // PENDING (waiting to fire). Override the entity default.
        task.status = Task.initialStatusFor(spec.type());
        task.scheduledAt = spec.scheduledAt();
        task.cronExpression = spec.cronExpression();
        task.intervalSeconds = spec.intervalSeconds();
        task.scheduleDisplay = spec.scheduleDisplay();
        task.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();

        // Plumbing fields (consumed by JCLAW-295/296/297/298).
        // Default (no delivery arg) → infer "<channel>:<target>" from the
        // calling agent's most-recently-touched Conversation so a task
        // created from a chat auto-delivers back to that chat on completion
        // (via TaskExecutor.dispatchDelivery → DeliveryDispatcher.dispatchSpec).
        // A bare channel name like "web" or "telegram" (no colon) is also
        // common from the LLM — interpret it as "use this channel, look up
        // the target from the calling chat", because the alternative is a
        // hard rejection at fire time with a "Delivery spec must be
        // channel:target" error that the operator never sees. Headless API
        // creation (no chat context) leaves delivery null.
        task.delivery = resolveDeliverySpec(optStr(args, KEY_DELIVERY), agent);
        task.payloadType = optStr(args, KEY_PAYLOAD_TYPE);
        // Reminders default to auto-delete-after-fire; regular tasks keep their
        // audit history. An explicit arg overrides.
        if (hasValue(args, KEY_AUTO_DELETE)) {
            task.autoDeleteOnComplete = args.get(KEY_AUTO_DELETE).getAsBoolean();
        } else {
            task.autoDeleteOnComplete = "reminder".equalsIgnoreCase(task.payloadType);
        }
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
        // JCLAW-261: optional IANA timezone. Validated in TaskScheduleSupport so
        // an invalid value surfaces as a tool error to the LLM rather than
        // landing in the DB and silently falling through at fire time.
        task.timezone = TaskScheduleSupport.parseTimezone(optStr(args, KEY_TIMEZONE));

        task.save();
        return task;
    }

    /** Parse the optional {@code schedule} shorthand into a spec, or null when absent. Throws
     *  IllegalArgumentException on a malformed schedule (the caller maps it to a tool error).
     *  Extracted to keep {@link #updateTask} under the cognitive-complexity bound (Sonar S3776). */
    private ScheduleShorthandParser.ScheduleSpec resolveScheduleSpec(JsonObject args) {
        if (!hasValue(args, KEY_SCHEDULE)) return null;
        return TaskScheduleSupport.parse(args.get(KEY_SCHEDULE).getAsString(), optStr(args, KEY_TIMEZONE));
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

        // JCLAW-418: validate an explicit delivery patch before persisting.
        if (args.has(KEY_DELIVERY)) {
            var deliveryErr = DeliverySpec.validate(optStr(args, KEY_DELIVERY));
            if (deliveryErr != null) return ERR_PREFIX + deliveryErr;
        }

        // Schedule re-parse, if present, drives type + 4 derived fields.
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            spec = resolveScheduleSpec(args);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid schedule: " + e.getMessage();
        }

        final PatchResult patch;
        try {
            patch = Tx.run(() -> applyPatch(args, taskId, spec));
        } catch (IllegalArgumentException e) {
            // JCLAW-261: surface invalid-timezone (or any other validated
            // field) as a clean tool error.
            return ERR_PREFIX + e.getMessage();
        }

        if (!patch.anyChange()) {
            return "Error: No patchable fields provided in updateTask.";
        }
        if (patch.scheduleChanged() && patch.task() != null) {
            // The Task was loaded, patched, and saved inside the patch Tx; its
            // EAGER agent relation is initialized so it stays usable after the
            // Tx closes. Reuse it for the reschedule instead of re-reading in a
            // second Tx.
            TaskSchedulingService.update(patch.task());
        }

        EventLogger.info("TASK_MGMT_UPDATE", agent.name, null,
                "Task '%s' (id=%d) updated via tool".formatted(name, taskId));
        var updated = "Task '%s' updated.".formatted(name);
        // JCLAW-455: only re-probe reachability when the delivery target was actually changed.
        if (args.has(KEY_DELIVERY) && patch.task() != null) {
            return withDeliveryAdvisory(updated, agent, patch.task().delivery);
        }
        return updated;
    }

    /** Outcome of {@link #applyPatch}: whether anything changed, whether the
     *  change touched the schedule (so the caller knows to re-arm the run), and
     *  the saved {@link Task} (null when the task was gone) so the caller can
     *  reschedule without a second Tx re-read. */
    private record PatchResult(boolean anyChange, boolean scheduleChanged, Task task) {}

    /**
     * Apply the patch surface to the addressed Task inside the calling Tx.
     * Both flags false when the task is gone or no patchable field was provided.
     */
    private static PatchResult applyPatch(JsonObject args, Long taskId,
                                          ScheduleShorthandParser.ScheduleSpec spec) {
        var task = (Task) Task.findById(taskId);
        if (task == null) return new PatchResult(false, false, null);
        boolean scheduleChanged = false;
        boolean anyChange = false;

        if (spec != null) {
            TaskScheduleSupport.applyScheduleSpec(task, spec);
            scheduleChanged = true;
            anyChange = true;
        }

        if (args.has(SchemaKeys.DESCRIPTION)) {
            var v = readDescriptionArg(args);
            task.description = v != null ? v : "";
            anyChange = true;
        }
        anyChange |= applyStringPatches(args, task);
        anyChange |= applyFlagPatches(args, task);

        if (anyChange) task.save();
        return new PatchResult(anyChange, scheduleChanged, task);
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
        if (hasValue(args, KEY_AUTO_DELETE)) {
            task.autoDeleteOnComplete = args.get(KEY_AUTO_DELETE).getAsBoolean();
            anyChange = true;
        }
        if (args.has(KEY_REPEAT_LIMIT)) {
            var el = args.get(KEY_REPEAT_LIMIT);
            task.repeatLimit = el.isJsonNull() ? null : el.getAsInt();
            anyChange = true;
        }
        // JCLAW-261: explicit-null clears the per-task override (falls
        // back to the global default); a value validates as IANA. Missing
        // key is a no-op so updates that don't touch timezone don't wipe
        // an existing value.
        if (args.has(KEY_TIMEZONE)) {
            var el = args.get(KEY_TIMEZONE);
            if (el.isJsonNull()) {
                task.timezone = null;
            } else {
                // TaskScheduleSupport.parseTimezone treats blank as null and
                // validates non-blank values as IANA zone ids.
                task.timezone = TaskScheduleSupport.parseTimezone(optStr(args, KEY_TIMEZONE));
            }
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
        for (var id : ids) TaskSchedulingService.pause(id);
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
        for (var id : ids) TaskSchedulingService.resume(id);
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
        var scan = Tx.run(() -> collectRunNowTargets(name, agent));
        if (scan.ranIds().isEmpty()) return MSG_NO_TASK_FOUND.formatted(name);
        for (var id : scan.lostIds()) {
            TaskSchedulingService.forceRemoveStaleRow(id);
            var fresh = Tx.run(() -> (Task) Task.findById(id));
            if (fresh != null) TaskSchedulingService.register(fresh);
        }
        for (var id : scan.ranIds()) {
            if (scan.lostIds().contains(id)) continue;
            TaskSchedulingService.runNow(id);
        }

        var revived = scan.revived();
        EventLogger.info("TASK_MGMT_MANUAL_RUN", agent.name, null,
                ("Task '%s' (%d match%s) run-now via tool"
                        + (revived > 0 ? " (%d revived from CANCELLED)" : ""))
                        .formatted(name, scan.ranIds().size(), scan.ranIds().size() == 1 ? "" : "es",
                                revived > 0 ? revived : 0));
        String revivedSuffix = revived > 0 ? " (revived %d from CANCELLED)".formatted(revived) : "";
        return scan.ranIds().size() == 1
                ? "Task '%s' run-now triggered%s.".formatted(name, revivedSuffix)
                : "%d tasks named '%s' run-now triggered%s.".formatted(scan.ranIds().size(), name, revivedSuffix);
    }

    /**
     * Inside the calling Tx: scan tasks matching (name, agent) and prepare
     * them for a manual fire. Mutates {@code revivedRef[0]} as it flips
     * CANCELLED rows to PENDING, and appends LOST ids to {@code lostIds}
     * so the caller can force-remove their stale scheduled_tasks row.
     * Returns the full list of matched task ids (any state).
     */
    private record RunNowScan(List<Long> ranIds, int revived, List<Long> lostIds) {}

    private static RunNowScan collectRunNowTargets(String name, Agent agent) {
        var tasks = findTasks("name = ?1 AND agent = ?2", name, agent);
        var ids = new ArrayList<Long>(tasks.size());
        var lostIds = new ArrayList<Long>();
        int revived = 0;
        for (var task : tasks) {
            if (task.status == Task.Status.CANCELLED) {
                // Revive — otherwise TaskExecutionHandler skips the fire body.
                // Recurring tasks get ACTIVE; one-shot tasks get PENDING.
                // JCLAW-733: genuine CANCELLED -> ACTIVE/PENDING transition, route
                // through the guard (both edges are legal for an existing task).
                task.transitionTo(Task.initialStatusFor(task.type));
                task.save();
                revived++;
            } else if (task.status == Task.Status.LOST) {
                // JCLAW-258: operator pre-empts db-scheduler's
                // auto-recovery. Flip back to the type-appropriate "alive"
                // state and remember the id so we can force-remove the
                // picked-but-stale scheduled_tasks row outside the Tx
                // before registering a fresh fire below.
                // JCLAW-733: genuine LOST -> ACTIVE/PENDING transition, route
                // through the guard (both edges are legal for an existing task).
                task.transitionTo(Task.initialStatusFor(task.type));
                task.save();
                lostIds.add(task.id);
            }
            ids.add(task.id);
        }
        return new RunNowScan(ids, revived, lostIds);
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
        // be able to cancel each other's — agent isolation.
        var cancelledIds = Tx.run(() -> {
            var tasks = findTasks("name = ?1 AND agent = ?2 AND status != ?3",
                    name, agent, Task.Status.CANCELLED);
            var ids = new ArrayList<Long>(tasks.size());
            for (var task : tasks) {
                // JCLAW-733: intentionally NOT routed through transitionTo. The
                // finder matches every non-CANCELLED state, including LOST /
                // COMPLETED / FAILED, whose edge to CANCELLED the lifecycle guard
                // deliberately forbids — routing here would throw on a cancel
                // that is legal today. Direct assignment preserves that behavior.
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
            TaskSchedulingService.cancel(taskId);
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
        var deletedIds = Tx.run(() -> {
            var tasks = findTasks("name = ?1 AND agent = ?2", name, agent);
            var ids = new ArrayList<Long>(tasks.size());
            var em = JPA.em();
            for (var task : tasks) {
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
            TaskSchedulingService.cancel(taskId);
        }
        EventLogger.info("TASK_MGMT_HARD_DELETE", agent.name, null,
                "Task '%s' (%d match%s) hard-deleted via tool"
                        .formatted(name, deletedIds.size(),
                                deletedIds.size() == 1 ? "" : "es"));
        return deletedIds.size() == 1
                ? "Task '%s' deleted.".formatted(name)
                : "%d tasks named '%s' deleted.".formatted(deletedIds.size(), name);
    }
}
