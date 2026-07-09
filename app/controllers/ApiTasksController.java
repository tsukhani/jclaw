package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.DeliverySpec;
import services.EventLogger;
import services.JClawCronUtils;
import services.ScheduleShorthandParser;
import services.TaskListQueryService;
import services.TaskRunQueryService;
import services.TaskSchedulingService;
import services.TaskService;
import services.TaskWriteService;
import services.TimezoneResolver;
import services.Tx;
import utils.ApiResponses;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static utils.GsonHolder.INSTANCE;

/**
 * Tasks API.
 *
 * <h2>Single-operator scope</h2>
 * Id-addressed mutations ({@link #update}, {@link #cancel}, {@link #retry},
 * {@link #run}, {@link #pause}, {@link #resume}) authenticate via {@link AuthCheck}
 * and don't add a per-caller ownership check: JClaw is single-operator Personal
 * Edition — one admin owns every agent and task, so there is no other user to scope
 * against. The {@link #list} endpoint honors an optional {@code agentId} filter and
 * otherwise returns all tasks.
 */
@With(AuthCheck.class)
public class ApiTasksController extends Controller {

    private static final Gson gson = INSTANCE;

    // JSON body keys read by the controller's write-path validation guards.
    // (The mapping keys travel with the field mapping in TaskWriteService.)
    private static final String KEY_NAME = "name";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_SCHEDULE = "schedule";
    private static final String KEY_DELIVERY = "delivery";
    private static final String KEY_TIMEZONE = "timezone";

    public record TaskRequest(String name, Long agentId, String schedule, String description,
                              String delivery, String payloadType, String modelProvider, String modelId,
                              List<String> enabledToolNames, String workdir, String preCheck,
                              String script, Boolean noAgent, Boolean autoDeleteOnComplete,
                              List<Long> contextFromTaskIds, Integer repeatLimit,
                              Boolean paused, String timezone) {}

    private record TaskView(Long id, String name, String description, String type, String status,
                            String cronExpression, Long intervalSeconds, String scheduleDisplay,
                            int retryCount, int maxRetries, String lastError,
                            String nextRunAt, String lastFiredAt, String createdAt,
                            Long agentId, String agentName,
                            boolean paused,
                            String delivery, String payloadType,
                            String modelProvider, String modelId,
                            String enabledToolNames, String workdir,
                            String preCheck, String script, boolean noAgent,
                            boolean autoDeleteOnComplete,
                            String contextFromTaskIds, Integer repeatLimit,
                            String timezone, String effectiveTimezone,
                            Long runningRunId) {
        static TaskView of(Task t) {
            return of(t, null, null);
        }

        static TaskView of(Task t, Instant lastFiredAt) {
            return of(t, lastFiredAt, null);
        }

        /**
         * Fullest variant, populated by {@link #list} via two single bulk SQL
         * passes:
         * <ul>
         *   <li>{@code lastFiredAt} — the latest {@link TaskRun#completedAt} for
         *       this task. Drives the Reminders "Fired" column without an N+1
         *       round-trip; {@code null} when the task has no completed run.</li>
         *   <li>{@code runningRunId} (JCLAW-414) — the id of this task's
         *       currently-RUNNING {@link TaskRun}, or {@code null} when none is
         *       in flight. Lets the Tasks Actions column swap the "Run now" bolt
         *       for a cancel control (POST /api/task-runs/{id}/cancel) whether
         *       the row is collapsed or expanded.</li>
         * </ul>
         */
        static TaskView of(Task t, Instant lastFiredAt, Long runningRunId) {
            return new TaskView(t.id, t.name, t.description, t.type.name(), t.status.name(),
                    t.cronExpression, t.intervalSeconds, t.scheduleDisplay,
                    t.retryCount, t.maxRetries, t.lastError,
                    nextRunAtForDisplay(t, lastFiredAt),
                    lastFiredAt != null ? lastFiredAt.toString() : null,
                    t.createdAt.toString(),
                    t.agent != null ? t.agent.id : null,
                    t.agent != null ? t.agent.name : null,
                    t.paused,
                    t.delivery, t.payloadType,
                    t.modelProvider, t.modelId,
                    t.enabledToolNames, t.workdir,
                    t.preCheck, t.script, t.noAgent,
                    t.autoDeleteOnComplete,
                    t.contextFromTaskIds, t.repeatLimit,
                    t.timezone,
                    // JCLAW-261: precomputed effective zone so the UI can
                    // render the actual fire-time zone for each task
                    // without re-running the resolver client-side. Returns
                    // a stable IANA id even when t.timezone is null
                    // (falls back through Config / conf / JVM default).
                    TimezoneResolver.resolve(t).getId(),
                    runningRunId);
        }

    }

    /**
     * Authoritative next-fire instant for the UI. {@code Task.nextRunAt} is
     * stamped at create/update time and never refreshed by the scheduler —
     * db-scheduler owns the live next-fire in its own {@code scheduled_tasks}
     * row, which JCLAW-22 deliberately does NOT read (that would couple the
     * API to the scheduler's internal schema). Instead:
     * <ul>
     *   <li><b>Paused</b> → no next fire (null); it won't fire until resumed.</li>
     *   <li><b>Terminal</b> → the stored value; no further fire is expected.</li>
     *   <li><b>CRON</b> → recomputed from the expression in the task's
     *       effective zone (JCLAW-261), so the column shows e.g. "tomorrow
     *       09:00" rather than the create timestamp.</li>
     *   <li><b>INTERVAL</b> (JCLAW-22 option C) → anchored on the previous
     *       completion: {@code lastFiredAt + intervalSeconds}, because that's
     *       where db-scheduler sets the next fire. {@code now + intervalSeconds}
     *       would jitter a full interval ahead on every page load. Before the
     *       first fire there's no completion to anchor on, so it falls back to
     *       the stored creation-time first-fire projection.</li>
     * </ul>
     *
     * @param lastFiredAt the most-recent {@link TaskRun#completedAt} for this
     *     task (from {@link #list}'s bulk pass), or null when it has never
     *     completed a run.
     */
    public static String nextRunAtForDisplay(Task t, Instant lastFiredAt) {
        // Paused recurring tasks won't fire until resumed.
        if (t.paused) return null;
        // Only live tasks (PENDING one-shot / ACTIVE recurring) have a
        // meaningful "next fire" — terminal-state tasks fall through to the
        // stored value since no further fire is expected.
        if (t.status != Task.Status.PENDING && t.status != Task.Status.ACTIVE) {
            return t.nextRunAt != null ? t.nextRunAt.toString() : null;
        }
        try {
            if (t.type == Task.Type.CRON && t.cronExpression != null) {
                // JCLAW-261: render the next fire using the same zone the
                // scheduler will fire under, so the column matches the real time.
                var zone = TimezoneResolver.resolve(t);
                var next = JClawCronUtils.nextExecution(t.cronExpression, zone);
                if (next != null) return next.toString();
            }
            if (t.type == Task.Type.INTERVAL && t.intervalSeconds != null && t.intervalSeconds > 0) {
                // Option C: db-scheduler anchors the next INTERVAL fire at the
                // previous completion, so the live next-fire is
                // lastFiredAt + intervalSeconds. Before the first fire fall
                // back to the stored creation-time first-fire projection.
                if (lastFiredAt != null) {
                    return lastFiredAt.plusSeconds(t.intervalSeconds).toString();
                }
                return t.nextRunAt != null ? t.nextRunAt.toString() : null;
            }
        } catch (RuntimeException _) {
            // Fall through to the stored value — better to show something
            // than swallow the row on a malformed expression that somehow
            // got past validation.
        }
        return t.nextRunAt != null ? t.nextRunAt.toString() : null;
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskView.class))))
    @Operation(summary = "List tasks filtered by status/type/agent/payloadType and full-text q, paginated with X-Total-Count")
    public static void list(String status, String type, Long agentId, String q,
                             String payloadType, String excludePayloadType,
                             Integer limit, Integer offset) {
        var result = TaskListQueryService.query(status, type, agentId, q,
                payloadType, excludePayloadType, limit, offset);

        // Empty-FTS short-circuit: a non-blank q that matched nothing renders
        // zero rows with an explicit total of 0.
        if (result.ftsEmpty()) {
            response.setHeader("X-Total-Count", "0");
            renderJSON("[]");
        }
        response.setHeader("X-Total-Count", String.valueOf(result.total()));

        // Bulk-fetch the latest TaskRun.completedAt for each task on the page
        // so the Reminders "Fired" column (and any future "last run" surface)
        // doesn't need an N+1 round-trip; and (JCLAW-414) each task's
        // currently-RUNNING TaskRun id so the Actions column can show a cancel
        // control while a run is in flight.
        var tasks = result.tasks();
        var taskIds = tasks.stream().map(t -> t.id).filter(Objects::nonNull).toList();
        final var fired = TaskRunQueryService.lastFiredAtByTask(taskIds);
        final var running = TaskRunQueryService.runningRunIdByTask(taskIds);
        renderJSON(gson.toJson(tasks.stream()
                .map(t -> TaskView.of(t, fired.get(t.id), running.get(t.id))).toList()));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TaskRequest.class)))
    @Operation(summary = "Create a task from a JSON body (agentId, name, schedule + optional fields) and register it with the scheduler")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var agent = requireAgentFromBody(body);
        var name = requireTaskName(body);
        var spec = requireScheduleSpec(body);
        rejectInvalidDelivery(body);

        rejectDuplicateRecurringTask(name, agent, spec);
        // Validated here (not in the service) so the invalid-timezone 400 stays
        // a controller guard, fired at the same point persistNewTask used to
        // validate it — after the duplicate-recurring 409, before the persist.
        rejectInvalidTimezone(body);

        var saved = Tx.run(() -> TaskWriteService.persistNewTask(body, agent, name, spec));

        TaskSchedulingService.register(saved);

        EventLogger.info("TASK_MGMT_CREATE", agent.name, null,
                "Task '%s' (id=%d, type=%s) created via API"
                        .formatted(saved.name, saved.id, saved.type));

        renderJSON(gson.toJson(TaskView.of(saved)));
    }

    /**
     * Resolve and require the {@code agentId} from the request body.
     *
     * <p>agentId resolution. Single-operator Personal Edition: the one admin
     * principal (admitted by AuthCheck) can address any agent. The 400 here is
     * "you typed an id that doesn't resolve"; a non-existent task addressed in
     * /api/tasks/{id} would be 404, but a non-existent agent named in the
     * request body is bad input.
     */
    @SuppressWarnings("java:S2259")
    private static Agent requireAgentFromBody(JsonObject body) {
        if (!body.has(KEY_AGENT_ID) || body.get(KEY_AGENT_ID).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId is required");
            throw new AssertionError("unreachable: error() throws");
        }
        var agent = AgentService.findById(body.get(KEY_AGENT_ID).getAsLong());
        if (agent == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId does not resolve to an existing agent");
            throw new AssertionError("unreachable: error() throws");
        }
        return agent;
    }

    @SuppressWarnings("java:S2259")
    private static String requireTaskName(JsonObject body) {
        if (!body.has(KEY_NAME) || body.get(KEY_NAME).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "name is required");
            throw new AssertionError("unreachable: error() throws");
        }
        var name = body.get(KEY_NAME).getAsString();
        if (name == null || name.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "name must be non-blank");
            throw new AssertionError("unreachable: error() throws");
        }
        return name;
    }

    /**
     * JCLAW-417: reject a malformed {@code delivery} at the write boundary via
     * {@link services.DeliverySpec#validate}. Structural only — tool
     * resolution and channel configuration are caller/runtime concerns. No-op
     * when {@code delivery} is absent or explicit-null (the latter clears it).
     */
    private static void rejectInvalidDelivery(JsonObject body) {
        var delivery = TaskWriteService.readOptionalString(body, KEY_DELIVERY);
        if (delivery == null) return;
        var err = DeliverySpec.validate(delivery);
        if (err != null) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, err);
    }

    /**
     * JCLAW-261: reject an invalid IANA {@code timezone} at the create boundary
     * with a 400. Kept a controller guard (not folded into the persist service)
     * so the halt stays at the HTTP boundary; a null/blank value is a no-op
     * (the task inherits the effective default zone at fire time).
     */
    @SuppressWarnings("java:S2259")
    private static void rejectInvalidTimezone(JsonObject body) {
        var tzRaw = TaskWriteService.readOptionalString(body, KEY_TIMEZONE);
        if (tzRaw == null) return;
        try {
            ZoneId.of(tzRaw.trim());
        } catch (DateTimeException e) {
            response.status = 400;
            renderText("Invalid IANA timezone '" + tzRaw + "': " + e.getMessage()
                    + ". Use a value from GET /api/timezones (e.g. 'America/New_York', 'Asia/Tokyo').");
        }
    }

    @SuppressWarnings("java:S2259")
    private static ScheduleShorthandParser.ScheduleSpec requireScheduleSpec(JsonObject body) {
        if (!body.has(KEY_SCHEDULE) || body.get(KEY_SCHEDULE).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "schedule is required");
            throw new AssertionError("unreachable: error() throws");
        }
        try {
            var zone = TimezoneResolver.resolve(TaskWriteService.readOptionalString(body, KEY_TIMEZONE));
            return ScheduleShorthandParser.parse(body.get(KEY_SCHEDULE).getAsString(), zone);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid schedule: " + e.getMessage());
            throw new AssertionError("unreachable: error() throws");
        }
    }

    /**
     * Recurring-type duplicate detection per AC: 409 with the
     * conflicting Task ID. Only CRON / INTERVAL — IMMEDIATE and
     * SCHEDULED are one-shot, so duplicate names are inert and not
     * worth rejecting.
     */
    private static void rejectDuplicateRecurringTask(String name, Agent agent,
                                                      ScheduleShorthandParser.ScheduleSpec spec) {
        if (spec.type() != Task.Type.CRON && spec.type() != Task.Type.INTERVAL) return;
        List<Task> conflicts = TaskService.findRecurringConflicts(name, agent);
        if (!conflicts.isEmpty()) {
            var conflictId = conflicts.getFirst().id;
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "A recurring task named '%s' already exists for this agent (id=%d)".formatted(name, conflictId),
                    "conflictingTaskId", conflictId);
        }
    }

    /**
     * Rename variant of {@link #rejectDuplicateRecurringTask}: re-checks the
     * duplicate-name rule against the task's current agent + type, excluding the
     * task itself (filtered out by id in the controller) so renaming to its own
     * name is a no-op.
     * Only CRON / INTERVAL are checked — one-shot names are inert (parity with
     * create). 409 with the conflicting Task id. Relies on {@code update}'s
     * ambient action transaction (no {@code Tx.run} wrap — it runs alongside the
     * raw {@code findById}/{@code save} that {@code update} already performs).
     */
    private static void rejectDuplicateRecurringRename(String name, Task task) {
        if (task.type != Task.Type.CRON && task.type != Task.Type.INTERVAL) return;
        List<Task> conflicts = TaskService.findRecurringConflicts(name, task.agent)
                .stream().filter(t -> !t.id.equals(task.id)).toList();
        if (!conflicts.isEmpty()) {
            var conflictId = conflicts.getFirst().id;
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "A recurring task named '%s' already exists for this agent (id=%d)".formatted(name, conflictId),
                    "conflictingTaskId", conflictId);
        }
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TaskRequest.class)))
    @Operation(summary = "Patch a task by id from a JSON body of changed fields, re-registering the scheduler when the schedule changes")
    public static void update(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        rejectInvalidDelivery(body);

        boolean scheduleChanged = applyScheduleUpdate(task, body);
        boolean nameChanged = applyNameUpdate(task, body);
        boolean fieldsChanged = TaskWriteService.applyOptionalFieldUpdates(task, body);
        boolean anyChange = scheduleChanged || nameChanged || fieldsChanged;

        if (!anyChange) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "No patchable fields in body");
        }

        task.save();
        if (scheduleChanged) {
            // cancel-then-register against db-scheduler. No-op in tests
            // where SchedulerClient isn't wired (null-soft per the
            // service's internal client() guard).
            TaskSchedulingService.update(task);
        }

        EventLogger.info("TASK_MGMT_UPDATE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) updated via API".formatted(task.name, task.id));

        renderJSON(gson.toJson(TaskView.of(task)));
    }

    /**
     * Schedule re-parse: when present, drives type + scheduledAt +
     * cronExpression + intervalSeconds + scheduleDisplay together.
     * Passing through ScheduleShorthandParser revalidates cron
     * expressions (with the prepend-0 hint for Unix 5-field) per AC.
     *
     * @return true if the schedule field was applied.
     */
    @SuppressWarnings("java:S2259")
    private static boolean applyScheduleUpdate(Task task, JsonObject body) {
        if (!body.has(KEY_SCHEDULE) || body.get(KEY_SCHEDULE).isJsonNull()) return false;
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            spec = ScheduleShorthandParser.parse(body.get(KEY_SCHEDULE).getAsString());
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid schedule: " + e.getMessage());
            return false; // unreachable — error() throws
        }
        task.type = spec.type();
        task.scheduledAt = spec.scheduledAt();
        task.cronExpression = spec.cronExpression();
        task.intervalSeconds = spec.intervalSeconds();
        task.scheduleDisplay = spec.scheduleDisplay();
        // nextRunAt mirrors the new fire time so the Tasks page render
        // stays in sync — the scheduled_tasks row is the source of
        // truth at fire time but operators read nextRunAt from the UI.
        task.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();
        return true;
    }

    /**
     * Name update: present-and-non-blank renames the task. Unlike description,
     * name cannot be cleared — present-and-null or present-and-blank is a 400
     * (mirrors create's {@link #requireTaskName}). A rename of a recurring task
     * re-checks the duplicate-name rule via {@link #rejectDuplicateRecurringRename}.
     * Must run AFTER {@link #applyScheduleUpdate} so that check sees the task's
     * effective type when the same PATCH also changes the schedule.
     *
     * @return true if the name field was present (and applied).
     */
    private static boolean applyNameUpdate(Task task, JsonObject body) {
        if (!body.has(KEY_NAME)) return false;
        var el = body.get(KEY_NAME);
        var name = el.isJsonNull() ? null : el.getAsString();
        if (name == null || name.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "name must be non-blank");
            return false; // unreachable — error() throws
        }
        rejectDuplicateRecurringRename(name, task);
        task.name = name;
        return true;
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Cancel a PENDING or ACTIVE task by id (sets status CANCELLED and unschedules)")
    public static void cancel(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Both PENDING (one-shot waiting) and ACTIVE (recurring ongoing)
        // are cancellable; other states (RUNNING, terminal) are not.
        if (task.status != Task.Status.PENDING && task.status != Task.Status.ACTIVE) {
            badRequest();
        }
        task.status = Task.Status.CANCELLED;
        task.save();
        TaskSchedulingService.cancel(task.id);
        EventLogger.info("TASK_MGMT_DELETE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) cancelled via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(task)));
    }

    /**
     * DELETE /api/tasks/{id} — hard-delete a task and its run history.
     *
     * <p>Distinct from {@link #cancel(Long)}: cancel sets the task to
     * {@link Task.Status#CANCELLED} (row stays, can be revived via
     * runNow); delete removes the Task row entirely along with every
     * {@link TaskRun} and {@link TaskRunMessage} that references it.
     * Use cancel for "stop running, I might want this back"; use
     * delete for "I'm done with this, get rid of the row".
     *
     * <p>FK chain (NOT NULL on both sides):
     * {@code TaskRunMessage → TaskRun → Task}.  JPQL bulk deletes
     * cover the descendants in two queries regardless of run-count,
     * then the scheduler row is dropped (idempotent), then the Task
     * row itself.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Hard-delete a task by id along with its runs, messages, notifications, and scheduler row")
    public static void delete(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        var agentName = task.agent != null ? task.agent.name : null;
        var taskName = task.name;
        var taskId = task.id;

        TaskWriteService.deleteWithHistory(task);

        EventLogger.info("TASK_MGMT_HARD_DELETE", agentName, null,
                "Task '%s' (id=%d) hard-deleted via API".formatted(taskName, taskId));
        renderJSON("{\"status\":\"deleted\",\"id\":" + taskId + "}");
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Pause a PENDING or ACTIVE task by id so it won't fire until resumed")
    public static void pause(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Pause only applies to live tasks (PENDING one-shot waiting / ACTIVE
        // recurring ongoing); pausing a terminal Task has no effect since the
        // scheduler row is already gone.
        if (task.status != Task.Status.PENDING && task.status != Task.Status.ACTIVE) {
            badRequest();
        }
        TaskSchedulingService.pause(task.id);
        EventLogger.info("TASK_MGMT_PAUSE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) paused via API".formatted(task.name, task.id));
        // Re-read so the response reflects the flipped flag.
        renderJSON(gson.toJson(TaskView.of(TaskService.findById(task.id))));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Resume a paused PENDING or ACTIVE task by id so it fires on schedule again")
    public static void resume(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Resume mirrors pause — accept PENDING or ACTIVE alive states.
        if (task.status != Task.Status.PENDING && task.status != Task.Status.ACTIVE) {
            badRequest();
        }
        TaskSchedulingService.resume(task.id);
        EventLogger.info("TASK_MGMT_RESUME",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) resumed via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(TaskService.findById(task.id))));
    }

    /**
     * POST /api/tasks/{id}/reenable — re-arm a CANCELLED task's schedule
     * <em>without</em> an immediate fire. Revives status to the type's
     * initial live state (recurring → ACTIVE, one-shot → PENDING), clears any
     * stale paused flag, and registers a fresh scheduler row at the next
     * natural fire ({@link services.TaskSchedulingService#register}): CRON
     * resumes at its next cron occurrence, INTERVAL at the next interval
     * boundary, a SCHEDULED one-shot at its original time (or immediately if
     * that time has already passed).
     *
     * <p>Distinct from {@link #run} (revives AND fires immediately) and
     * {@link #retry} (the FAILED/LOST "reset retryCount and rerun" path).
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Re-arm a CANCELLED task's schedule at its next natural fire without firing immediately")
    public static void reenable(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Re-enable only applies to a CANCELLED task: reviving a live Task
        // would double-schedule, and reviving a COMPLETED/FAILED one would
        // resurrect a finished fire. Anything else is a client error.
        if (task.status != Task.Status.CANCELLED) {
            badRequest();
        }
        task.status = Task.initialStatusFor(task.type);
        task.paused = false;
        task.save();
        TaskSchedulingService.register(task);
        EventLogger.info("TASK_MGMT_REENABLE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) re-enabled via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(TaskService.findById(task.id))));
    }

    /**
     * Operator-initiated immediate fire — distinct from {@link #retry} which
     * is the FAILED-only "fix and rerun" path. Accepts any current status
     * per AC; revives a CANCELLED task to PENDING first so
     * {@link services.TaskExecutionHandler}'s CANCELLED-skip doesn't
     * swallow the fire. Other terminal states (COMPLETED, FAILED) don't
     * trigger that skip so their status stays intact — re-firing a
     * COMPLETED task is a deliberate operator action and the audit-log
     * record alone is the trail.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Fire a task immediately by id, reviving a CANCELLED task first so the run isn't skipped")
    public static void run(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        boolean revivedFromCancel = false;
        if (task.status == Task.Status.CANCELLED) {
            // Recurring tasks resume as ACTIVE, one-shot as PENDING.
            task.status = Task.initialStatusFor(task.type);
            task.save();
            revivedFromCancel = true;
        }

        TaskSchedulingService.runNow(task.id);

        EventLogger.info("TASK_MGMT_MANUAL_RUN",
                task.agent != null ? task.agent.name : null, null,
                ("Task '%s' (id=%d) run-now via API" + (revivedFromCancel ? " (revived from CANCELLED)" : ""))
                        .formatted(task.name, task.id));

        renderJSON(gson.toJson(TaskView.of(task)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Retry a FAILED or LOST task by id (reset retryCount/lastError and re-register to fire now)")
    public static void retry(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // JCLAW-258 extends retry to accept LOST in addition to FAILED.
        // FAILED: no scheduled_tasks row (it was removed when the failure
        // terminated the previous fire) — register() inserts a fresh row.
        // LOST: scheduled_tasks row still exists (Design A keeps it intact
        // for db-scheduler's own recovery); operator click pre-empts that
        // auto-recovery by cancelling the stale row and registering a
        // fresh one via update().
        boolean wasLost = task.status == Task.Status.LOST;
        if (task.status != Task.Status.FAILED && !wasLost) {
            badRequest();
        }
        task.retryCount = 0;
        // Recurring tasks return to ACTIVE, one-shot to PENDING.
        task.status = Task.initialStatusFor(task.type);
        task.nextRunAt = Instant.now();
        task.lastError = null;
        task.save();
        if (wasLost) {
            // LOST rows are picked=true with a stale heartbeat — the regular
            // cancel API refuses to touch them. forceRemoveStaleRow deletes
            // the row directly so the subsequent register inserts a clean
            // new fire row without tripping the PK constraint.
            TaskSchedulingService.forceRemoveStaleRow(task.id);
        }
        TaskSchedulingService.register(task);
        // /retry is operator-initiated re-firing — same intent as /run from
        // the audit timeline's POV. The mechanical distinction (resets
        // retryCount/lastError, FAILED/LOST-only guard) is internal; the
        // operator simply "ran a previously-stuck task again", which maps
        // to the MANUAL_RUN category.
        EventLogger.info("TASK_MGMT_MANUAL_RUN",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) retried via API (was %s)"
                        .formatted(task.name, task.id, wasLost ? "LOST" : "FAILED"));
        renderJSON(gson.toJson(TaskView.of(task)));
    }

}
