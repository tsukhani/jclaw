package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import play.db.jpa.JPA;
import services.EventLogger;
import services.ScheduleShorthandParser;
import services.TaskSchedulingService;

import static utils.GsonHolder.INSTANCE;
import play.mvc.Controller;
import play.mvc.With;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.List;

/**
 * Tasks API.
 *
 * <h3>TODO: agent-ownership enforcement</h3>
 * Every id-addressed mutation here ({@link #update}, {@link #cancel},
 * {@link #retry}, {@link #run}, {@link #pause}, {@link #resume})
 * currently authenticates via
 * {@link AuthCheck} but does not check that the resolved Task belongs
 * to the caller's agent — because user-ownership infrastructure does
 * not yet exist in JClaw (no {@code User} model, no {@code owner} FK
 * on {@code Agent}; see CLAUDE memory
 * {@code project_multi_tenancy_design}). AuthCheck today admits a
 * single system principal. When the user-ownership story lands, add
 * a {@code task.agent} ↔ {@code currentUser.agents()} guard before
 * the {@code task.save()} call in each handler. The {@link #list}
 * endpoint also needs the same scoping (currently honors an optional
 * {@code agentId} filter but returns ALL tasks when omitted).
 */
@With(AuthCheck.class)
public class ApiTasksController extends Controller {

    private static final Gson gson = INSTANCE;

    // JSON body keys reused across create/update parsers and per-field tests.
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_SCHEDULE = "schedule";
    private static final String KEY_DESCRIPTION = "description";
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
    private static final String KEY_PAUSED = "paused";

    private record TaskView(Long id, String name, String description, String type, String status,
                            String cronExpression, Long intervalSeconds, String scheduleDisplay,
                            int retryCount, int maxRetries, String lastError,
                            String nextRunAt, String createdAt, Long agentId, String agentName,
                            boolean paused,
                            String delivery, String payloadType,
                            String modelProvider, String modelId,
                            String enabledToolNames, String workdir,
                            String preCheck, String script, boolean noAgent,
                            String contextFromTaskIds, Integer repeatLimit) {
        static TaskView of(Task t) {
            return new TaskView(t.id, t.name, t.description, t.type.name(), t.status.name(),
                    t.cronExpression, t.intervalSeconds, t.scheduleDisplay,
                    t.retryCount, t.maxRetries, t.lastError,
                    nextRunAtForDisplay(t),
                    t.createdAt.toString(),
                    t.agent != null ? t.agent.id : null,
                    t.agent != null ? t.agent.name : null,
                    t.paused,
                    t.delivery, t.payloadType,
                    t.modelProvider, t.modelId,
                    t.enabledToolNames, t.workdir,
                    t.preCheck, t.script, t.noAgent,
                    t.contextFromTaskIds, t.repeatLimit);
        }

        /**
         * Authoritative next-fire instant for the UI.  Task.nextRunAt is
         * stamped at create/update time and never refreshed by the scheduler
         * — db-scheduler owns the live next-fire in its own scheduled_tasks
         * row. For CRON tasks we recompute from the expression so the Tasks
         * page shows e.g. "tomorrow 09:00" instead of the create timestamp;
         * for INTERVAL we project from now + intervalSeconds (a reasonable
         * approximation between fires, since the last-fire timestamp isn't
         * cheap to surface here). Terminal-status tasks fall back to the
         * stored value because no further fire is expected.
         */
        private static String nextRunAtForDisplay(Task t) {
            if (t.status != Task.Status.PENDING) {
                return t.nextRunAt != null ? t.nextRunAt.toString() : null;
            }
            try {
                if (t.type == Task.Type.CRON && t.cronExpression != null) {
                    var next = services.JClawCronUtils.nextExecution(t.cronExpression);
                    if (next != null) return next.toString();
                }
                if (t.type == Task.Type.INTERVAL && t.intervalSeconds != null && t.intervalSeconds > 0) {
                    return Instant.now().plusSeconds(t.intervalSeconds).toString();
                }
            } catch (RuntimeException _) {
                // Fall through to the stored value — better to show something
                // than swallow the row on a malformed expression that somehow
                // got past validation.
            }
            return t.nextRunAt != null ? t.nextRunAt.toString() : null;
        }
    }

    /**
     * Operator-facing view of one TaskRun row. Returns the audit fields
     * (status + timestamps + duration) plus the operator-relevant payload
     * (error, outputSummary, delivery state, trace). Verbose fields like
     * {@code error} and {@code traceJson} are returned raw — the UI
     * decides how to render or truncate them. Keeping the API honest
     * about what's there avoids a second round-trip for "show me the
     * full error" UX.
     */
    private record TaskRunView(Long id, String status,
                               String startedAt, String completedAt, Long durationMs,
                               String error, String outputSummary,
                               String deliveryStatus, String deliveryTarget, String deliveryError,
                               String traceJson, String createdAt) {
        static TaskRunView of(TaskRun r) {
            return new TaskRunView(
                    r.id,
                    r.status != null ? r.status.name() : null,
                    r.startedAt != null ? r.startedAt.toString() : null,
                    r.completedAt != null ? r.completedAt.toString() : null,
                    r.durationMs,
                    r.error,
                    r.outputSummary,
                    r.deliveryStatus != null ? r.deliveryStatus.name() : null,
                    r.deliveryTarget,
                    r.deliveryError,
                    r.traceJson,
                    r.createdAt != null ? r.createdAt.toString() : null);
        }
    }

    /**
     * One hit from the transcript search: the matched
     * {@link TaskRunMessage} content + role plus enough parent context
     * (task id/name, taskRun id) for the UI to link back.
     */
    private record TranscriptSearchHit(Long messageId, String role, String content,
                                        String createdAt, Long taskRunId,
                                        Long taskId, String taskName,
                                        Long agentId, String agentName) {
        static TranscriptSearchHit of(TaskRunMessage m) {
            var run = m.taskRun;
            var task = run != null ? run.task : null;
            var agent = task != null ? task.agent : null;
            return new TranscriptSearchHit(
                    m.id,
                    m.role != null ? m.role.name() : null,
                    m.content,
                    m.createdAt != null ? m.createdAt.toString() : null,
                    run != null ? run.id : null,
                    task != null ? task.id : null,
                    task != null ? task.name : null,
                    agent != null ? agent.id : null,
                    agent != null ? agent.name : null);
        }
    }

    /**
     * Full-text search across task transcripts. Routes through the
     * {@link services.search.MessageSearch} facade, which dispatches
     * to either the direct Lucene 10 backend (default) or Postgres
     * tsvector (operator opt-in via {@code -Djclaw.search.postgres-native=true}).
     * Query syntax accepts Lucene's standard QueryParser grammar:
     * phrase quoting, AND/OR/NOT, and prefix wildcards.
     *
     * <p>Empty {@code q} returns {@code []} — the search facade
     * intentionally non-exceptional on empty input so the UI can render
     * an empty results panel before the operator types.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TranscriptSearchHit.class))))
    public static void searchTranscripts(String q, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        if (q == null || q.isBlank()) {
            renderJSON("[]");
        }
        try {
            var hits = services.search.MessageSearch.search(q, effectiveLimit);
            renderJSON(gson.toJson(hits.stream().map(TranscriptSearchHit::of).toList()));
        } catch (Throwable e) {
            // Throwable (not just Exception) because Lucene API removals
            // surface as NoClassDefFoundError / LinkageError / AbstractMethodError
            // — Error subclasses that escape a narrower catch and yield a
            // generic Play 500 page with no useful diagnostic. Log the full
            // stack so version-bump incompats are debuggable.
            play.Logger.error(e, "search-transcripts failed for q='%s'", q);
            error(500, "Search failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Paginated TaskRun history for one Task. Sorted most-recent first
     * (startedAt DESC). Returns {@code []} for a task that exists but
     * has no runs yet. Returns 404 only if the Task itself is missing.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskRunView.class))))
    public static void runs(Long id, Integer limit, Integer offset) {
        Task task = Task.findById(id);
        if (task == null) notFound();

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        @SuppressWarnings("unchecked")
        List<TaskRun> rows = (List<TaskRun>) (List<?>) TaskRun.find(
                "task = ?1 ORDER BY startedAt DESC", task)
                .from(effectiveOffset)
                .fetch(effectiveLimit);

        renderJSON(gson.toJson(rows.stream().map(TaskRunView::of).toList()));
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskView.class))))
    public static void list(String status, String type, Long agentId, Integer limit, Integer offset) {
        var filter = new JpqlFilter()
                .eq("status", status != null && !status.isBlank() ? Task.Status.valueOf(status.toUpperCase()) : null)
                .eq("type", type != null && !type.isBlank() ? Task.Type.valueOf(type.toUpperCase()) : null)
                .eq("agent.id", agentId);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var where = filter.toWhereClause();
        String jpql = where.isEmpty()
                ? "SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC"
                : "SELECT t FROM Task t LEFT JOIN FETCH t.agent WHERE " + where + " ORDER BY t.createdAt DESC";
        var q = JPA.em().createQuery(jpql, Task.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
        List<Task> tasks = q.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

        renderJSON(gson.toJson(tasks.stream().map(TaskView::of).toList()));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) { badRequest(); return; }

        var agent = requireAgentFromBody(body);
        var name = requireTaskName(body);
        var spec = requireScheduleSpec(body);

        rejectDuplicateRecurringTask(name, agent, spec);

        var saved = services.Tx.run(() -> persistNewTask(body, agent, name, spec));

        TaskSchedulingService.register(saved);

        EventLogger.info("TASK_MGMT_CREATE", agent.name, null,
                "Task '%s' (id=%d, type=%s) created via API"
                        .formatted(saved.name, saved.id, saved.type));

        renderJSON(gson.toJson(TaskView.of(saved)));
    }

    /**
     * Resolve and require the {@code agentId} from the request body.
     *
     * <p>agentId resolution. Until user-ownership lands (class-level TODO)
     * the caller can address any agent — admitted by AuthCheck's single
     * principal. The 400 here is "you typed an id that doesn't resolve";
     * a non-existent task addressed in /api/tasks/{id} would be 404, but
     * a non-existent agent named in the request body is bad input.
     */
    private static Agent requireAgentFromBody(com.google.gson.JsonObject body) {
        if (!body.has(KEY_AGENT_ID) || body.get(KEY_AGENT_ID).isJsonNull()) {
            error(400, "agentId is required");
            throw new AssertionError("unreachable: error() throws");
        }
        var agent = (Agent) Agent.findById(body.get(KEY_AGENT_ID).getAsLong());
        if (agent == null) {
            error(400, "agentId does not resolve to an existing agent");
            throw new AssertionError("unreachable: error() throws");
        }
        return agent;
    }

    private static String requireTaskName(com.google.gson.JsonObject body) {
        if (!body.has("name") || body.get("name").isJsonNull()) {
            error(400, "name is required");
            return null; // unreachable
        }
        var name = body.get("name").getAsString();
        if (name == null || name.isBlank()) {
            error(400, "name must be non-blank");
            return null; // unreachable
        }
        return name;
    }

    private static ScheduleShorthandParser.ScheduleSpec requireScheduleSpec(com.google.gson.JsonObject body) {
        if (!body.has(KEY_SCHEDULE) || body.get(KEY_SCHEDULE).isJsonNull()) {
            error(400, "schedule is required");
            return null; // unreachable
        }
        try {
            return ScheduleShorthandParser.parse(body.get(KEY_SCHEDULE).getAsString());
        } catch (IllegalArgumentException e) {
            error(400, "Invalid schedule: " + e.getMessage());
            return null; // unreachable
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
        @SuppressWarnings("unchecked")
        var conflicts = (List<Object>) (List<?>) services.Tx.run(() -> Task.find(
                "name = ?1 AND agent = ?2 AND type IN (?3, ?4) AND status != ?5",
                name, agent, Task.Type.CRON, Task.Type.INTERVAL, Task.Status.CANCELLED
        ).fetch());
        if (!conflicts.isEmpty()) {
            var conflictId = ((Task) conflicts.getFirst()).id;
            error(409, "A recurring task named '%s' already exists for this agent (id=%d)"
                    .formatted(name, conflictId));
        }
    }

    private static Task persistNewTask(com.google.gson.JsonObject body, Agent agent, String name,
                                       ScheduleShorthandParser.ScheduleSpec spec) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = readOptionalString(body, KEY_DESCRIPTION);
        if (t.description == null) t.description = "";

        t.type = spec.type();
        t.scheduledAt = spec.scheduledAt();
        t.cronExpression = spec.cronExpression();
        t.intervalSeconds = spec.intervalSeconds();
        t.scheduleDisplay = spec.scheduleDisplay();
        // nextRunAt is no longer authoritative under db-scheduler (see
        // JCLAW-21), but keep it populated for the Tasks-page render
        // until the column is dropped.
        t.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();

        // Plumbing-ahead fields (consumed by JCLAW-295/296/297/298).
        t.delivery = readOptionalString(body, KEY_DELIVERY);
        t.payloadType = readOptionalString(body, KEY_PAYLOAD_TYPE);
        t.modelProvider = readOptionalString(body, KEY_MODEL_PROVIDER);
        t.modelId = readOptionalString(body, KEY_MODEL_ID);
        t.enabledToolNames = readOptionalString(body, KEY_ENABLED_TOOL_NAMES);
        t.workdir = readOptionalString(body, KEY_WORKDIR);
        t.preCheck = readOptionalString(body, KEY_PRE_CHECK);
        t.script = readOptionalString(body, KEY_SCRIPT);
        if (body.has(KEY_NO_AGENT) && !body.get(KEY_NO_AGENT).isJsonNull()) {
            t.noAgent = body.get(KEY_NO_AGENT).getAsBoolean();
        }
        t.contextFromTaskIds = readOptionalString(body, KEY_CONTEXT_FROM_TASK_IDS);
        if (body.has(KEY_REPEAT_LIMIT) && !body.get(KEY_REPEAT_LIMIT).isJsonNull()) {
            t.repeatLimit = body.get(KEY_REPEAT_LIMIT).getAsInt();
        }

        t.save();
        return t;
    }

    private static String readOptionalString(com.google.gson.JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void update(Long id) {
        Task task = Task.findById(id);
        if (task == null) { notFound(); return; }

        var body = JsonBodyReader.readJsonBody();
        if (body == null) { badRequest(); return; }

        boolean scheduleChanged = applyScheduleUpdate(task, body);
        boolean fieldsChanged = applyOptionalFieldUpdates(task, body);
        boolean anyChange = scheduleChanged || fieldsChanged;

        if (!anyChange) {
            error(400, "No patchable fields in body");
            return;
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
    private static boolean applyScheduleUpdate(Task task, com.google.gson.JsonObject body) {
        if (!body.has(KEY_SCHEDULE) || body.get(KEY_SCHEDULE).isJsonNull()) return false;
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            spec = ScheduleShorthandParser.parse(body.get(KEY_SCHEDULE).getAsString());
        } catch (IllegalArgumentException e) {
            error(400, "Invalid schedule: " + e.getMessage());
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
     * Apply every non-schedule patchable field present in the body.
     *
     * @return true if any field was touched.
     */
    private static boolean applyOptionalFieldUpdates(Task task, com.google.gson.JsonObject body) {
        boolean changed = false;

        // Optional-string fields: present-and-null clears, present-and-blank
        // also clears (readOptionalString collapses both to null).
        if (body.has(KEY_DESCRIPTION)) {
            var v = readOptionalString(body, KEY_DESCRIPTION);
            task.description = v != null ? v : "";
            changed = true;
        }
        if (body.has(KEY_DELIVERY))            { task.delivery          = readOptionalString(body, KEY_DELIVERY);            changed = true; }
        if (body.has(KEY_PAYLOAD_TYPE))        { task.payloadType       = readOptionalString(body, KEY_PAYLOAD_TYPE);        changed = true; }
        if (body.has(KEY_MODEL_PROVIDER))      { task.modelProvider     = readOptionalString(body, KEY_MODEL_PROVIDER);      changed = true; }
        if (body.has(KEY_MODEL_ID))            { task.modelId           = readOptionalString(body, KEY_MODEL_ID);            changed = true; }
        if (body.has(KEY_ENABLED_TOOL_NAMES))  { task.enabledToolNames  = readOptionalString(body, KEY_ENABLED_TOOL_NAMES);  changed = true; }
        if (body.has(KEY_WORKDIR))             { task.workdir           = readOptionalString(body, KEY_WORKDIR);             changed = true; }
        if (body.has(KEY_PRE_CHECK))           { task.preCheck          = readOptionalString(body, KEY_PRE_CHECK);           changed = true; }
        if (body.has(KEY_SCRIPT))              { task.script            = readOptionalString(body, KEY_SCRIPT);              changed = true; }
        if (body.has(KEY_CONTEXT_FROM_TASK_IDS)){ task.contextFromTaskIds= readOptionalString(body, KEY_CONTEXT_FROM_TASK_IDS);changed = true; }

        // Boolean fields. Explicit null is rejected (no meaningful semantic)
        // — callers should omit instead.
        if (body.has(KEY_PAUSED) && !body.get(KEY_PAUSED).isJsonNull()) {
            task.paused = body.get(KEY_PAUSED).getAsBoolean();
            changed = true;
        }
        if (body.has(KEY_NO_AGENT) && !body.get(KEY_NO_AGENT).isJsonNull()) {
            task.noAgent = body.get(KEY_NO_AGENT).getAsBoolean();
            changed = true;
        }

        // Integer fields. Explicit null clears (sets back to unlimited).
        if (body.has(KEY_REPEAT_LIMIT)) {
            var el = body.get(KEY_REPEAT_LIMIT);
            task.repeatLimit = el.isJsonNull() ? null : el.getAsInt();
            changed = true;
        }

        return changed;
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void cancel(Long id) {
        Task task = Task.findById(id);
        if (task == null) notFound();
        if (task.status != Task.Status.PENDING) {
            badRequest();
        }
        task.status = Task.Status.CANCELLED;
        task.save();
        services.TaskSchedulingService.cancel(task.id);
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
    @ApiResponse(responseCode = "200")
    public static void delete(Long id) {
        Task task = Task.findById(id);
        if (task == null) { notFound(); return; }

        var agentName = task.agent != null ? task.agent.name : null;
        var taskName = task.name;
        var taskId = task.id;

        var em = play.db.jpa.JPA.em();
        em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.task.id = :taskId")
                .setParameter("taskId", taskId).executeUpdate();
        em.createQuery("DELETE FROM TaskRun r WHERE r.task.id = :taskId")
                .setParameter("taskId", taskId).executeUpdate();
        em.flush();

        // Idempotent — harmless if the task is already in a terminal
        // state and its scheduler row was already removed by cancel.
        services.TaskSchedulingService.cancel(taskId);

        task.delete();
        EventLogger.info("TASK_MGMT_HARD_DELETE", agentName, null,
                "Task '%s' (id=%d) hard-deleted via API".formatted(taskName, taskId));
        renderJSON("{\"status\":\"deleted\",\"id\":" + taskId + "}");
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void pause(Long id) {
        Task task = Task.findById(id);
        if (task == null) { notFound(); return; }
        if (task.status != Task.Status.PENDING) {
            // Pause only applies to live (PENDING/recurring) tasks — pausing a
            // terminal Task would have no effect since the scheduler row is
            // already gone.
            badRequest();
        }
        services.TaskSchedulingService.pause(task.id);
        EventLogger.info("TASK_MGMT_PAUSE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) paused via API".formatted(task.name, task.id));
        // Re-read so the response reflects the flipped flag.
        renderJSON(gson.toJson(TaskView.of(Task.findById(task.id))));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void resume(Long id) {
        Task task = Task.findById(id);
        if (task == null) { notFound(); return; }
        if (task.status != Task.Status.PENDING) {
            badRequest();
        }
        services.TaskSchedulingService.resume(task.id);
        EventLogger.info("TASK_MGMT_RESUME",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) resumed via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(Task.findById(task.id))));
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
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void run(Long id) {
        Task task = Task.findById(id);
        if (task == null) { notFound(); return; }

        boolean revivedFromCancel = false;
        if (task.status == Task.Status.CANCELLED) {
            task.status = Task.Status.PENDING;
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

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void retry(Long id) {
        Task task = Task.findById(id);
        // Explicit return after notFound() so Sonar's flow analysis
        // narrows `task` to non-null on the following line (Play's
        // notFound() throws a Result internally, but Sonar can't see
        // that). Matches the pattern used by update/run/pause/resume
        // in this file.
        if (task == null) { notFound(); return; }
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
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.lastError = null;
        task.save();
        if (wasLost) {
            // LOST rows are picked=true with a stale heartbeat — the regular
            // cancel API refuses to touch them. forceRemoveStaleRow deletes
            // the row directly so the subsequent register inserts a clean
            // new fire row without tripping the PK constraint.
            services.TaskSchedulingService.forceRemoveStaleRow(task.id);
        }
        services.TaskSchedulingService.register(task);
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
