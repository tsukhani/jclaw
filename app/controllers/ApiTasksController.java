package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.Task;
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
                    t.nextRunAt != null ? t.nextRunAt.toString() : null,
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
        if (body == null) badRequest();

        // agentId resolution. Until user-ownership lands (class-level TODO)
        // the caller can address any agent — admitted by AuthCheck's single
        // principal. The 400 here is "you typed an id that doesn't resolve";
        // a non-existent task addressed in /api/tasks/{id} would be 404, but
        // a non-existent agent named in the request body is bad input.
        if (!body.has("agentId") || body.get("agentId").isJsonNull()) {
            error(400, "agentId is required");
            return;
        }
        var agent = (Agent) Agent.findById(body.get("agentId").getAsLong());
        if (agent == null) {
            error(400, "agentId does not resolve to an existing agent");
            return;
        }

        if (!body.has("name") || body.get("name").isJsonNull()) {
            error(400, "name is required");
            return;
        }
        var name = body.get("name").getAsString();
        if (name == null || name.isBlank()) {
            error(400, "name must be non-blank");
            return;
        }

        if (!body.has("schedule") || body.get("schedule").isJsonNull()) {
            error(400, "schedule is required");
            return;
        }
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            spec = ScheduleShorthandParser.parse(body.get("schedule").getAsString());
        } catch (IllegalArgumentException e) {
            error(400, "Invalid schedule: " + e.getMessage());
            return;
        }

        // Recurring-type duplicate detection per AC: 409 with the
        // conflicting Task ID. Only CRON / INTERVAL — IMMEDIATE and
        // SCHEDULED are one-shot, so duplicate names are inert and not
        // worth rejecting.
        if (spec.type() == Task.Type.CRON || spec.type() == Task.Type.INTERVAL) {
            @SuppressWarnings("unchecked")
            var conflicts = (List<Object>) (List<?>) services.Tx.run(() -> Task.find(
                    "name = ?1 AND agent = ?2 AND type IN (?3, ?4) AND status != ?5",
                    name, agent, Task.Type.CRON, Task.Type.INTERVAL, Task.Status.CANCELLED
            ).fetch());
            if (!conflicts.isEmpty()) {
                var conflictId = ((Task) conflicts.getFirst()).id;
                error(409, "A recurring task named '%s' already exists for this agent (id=%d)"
                        .formatted(name, conflictId));
                return;
            }
        }

        var saved = services.Tx.run(() -> {
            var t = new Task();
            t.agent = agent;
            t.name = name;
            t.description = readOptionalString(body, "description");
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
            t.delivery = readOptionalString(body, "delivery");
            t.payloadType = readOptionalString(body, "payloadType");
            t.modelProvider = readOptionalString(body, "modelProvider");
            t.modelId = readOptionalString(body, "modelId");
            t.enabledToolNames = readOptionalString(body, "enabledToolNames");
            t.workdir = readOptionalString(body, "workdir");
            t.preCheck = readOptionalString(body, "preCheck");
            t.script = readOptionalString(body, "script");
            if (body.has("noAgent") && !body.get("noAgent").isJsonNull()) {
                t.noAgent = body.get("noAgent").getAsBoolean();
            }
            t.contextFromTaskIds = readOptionalString(body, "contextFromTaskIds");
            if (body.has("repeatLimit") && !body.get("repeatLimit").isJsonNull()) {
                t.repeatLimit = body.get("repeatLimit").getAsInt();
            }

            t.save();
            return t;
        });

        TaskSchedulingService.register(saved);

        EventLogger.info("TASK_MGMT_CREATE", agent.name, null,
                "Task '%s' (id=%d, type=%s) created via API"
                        .formatted(saved.name, saved.id, saved.type));

        renderJSON(gson.toJson(TaskView.of(saved)));
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
        if (task == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        boolean anyChange = false;
        boolean scheduleChanged = false;

        // schedule re-parse: when present, drives type + scheduledAt +
        // cronExpression + intervalSeconds + scheduleDisplay together.
        // Passing through ScheduleShorthandParser revalidates cron
        // expressions (with the prepend-0 hint for Unix 5-field) per AC.
        if (body.has("schedule") && !body.get("schedule").isJsonNull()) {
            final ScheduleShorthandParser.ScheduleSpec spec;
            try {
                spec = ScheduleShorthandParser.parse(body.get("schedule").getAsString());
            } catch (IllegalArgumentException e) {
                error(400, "Invalid schedule: " + e.getMessage());
                return;
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
            scheduleChanged = true;
            anyChange = true;
        }

        // Optional-string fields: present-and-null clears, present-and-blank
        // also clears (readOptionalString collapses both to null).
        if (body.has("description")) {
            var v = readOptionalString(body, "description");
            task.description = v != null ? v : "";
            anyChange = true;
        }
        if (body.has("delivery")) {
            task.delivery = readOptionalString(body, "delivery");
            anyChange = true;
        }
        if (body.has("payloadType")) {
            task.payloadType = readOptionalString(body, "payloadType");
            anyChange = true;
        }
        if (body.has("modelProvider")) {
            task.modelProvider = readOptionalString(body, "modelProvider");
            anyChange = true;
        }
        if (body.has("modelId")) {
            task.modelId = readOptionalString(body, "modelId");
            anyChange = true;
        }
        if (body.has("enabledToolNames")) {
            task.enabledToolNames = readOptionalString(body, "enabledToolNames");
            anyChange = true;
        }
        if (body.has("workdir")) {
            task.workdir = readOptionalString(body, "workdir");
            anyChange = true;
        }
        if (body.has("preCheck")) {
            task.preCheck = readOptionalString(body, "preCheck");
            anyChange = true;
        }
        if (body.has("script")) {
            task.script = readOptionalString(body, "script");
            anyChange = true;
        }
        if (body.has("contextFromTaskIds")) {
            task.contextFromTaskIds = readOptionalString(body, "contextFromTaskIds");
            anyChange = true;
        }

        // Boolean fields. Explicit null is rejected (no meaningful semantic)
        // — callers should omit instead.
        if (body.has("paused") && !body.get("paused").isJsonNull()) {
            task.paused = body.get("paused").getAsBoolean();
            anyChange = true;
        }
        if (body.has("noAgent") && !body.get("noAgent").isJsonNull()) {
            task.noAgent = body.get("noAgent").getAsBoolean();
            anyChange = true;
        }

        // Integer fields. Explicit null clears (sets back to unlimited).
        if (body.has("repeatLimit")) {
            var el = body.get("repeatLimit");
            task.repeatLimit = el.isJsonNull() ? null : el.getAsInt();
            anyChange = true;
        }

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
        renderJSON(gson.toJson(TaskView.of(task)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void pause(Long id) {
        Task task = Task.findById(id);
        if (task == null) notFound();
        if (task.status != Task.Status.PENDING) {
            // Pause only applies to live (PENDING/recurring) tasks — pausing a
            // terminal Task would have no effect since the scheduler row is
            // already gone.
            badRequest();
        }
        services.TaskSchedulingService.pause(task.id);
        // Re-read so the response reflects the flipped flag.
        renderJSON(gson.toJson(TaskView.of(Task.findById(task.id))));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    public static void resume(Long id) {
        Task task = Task.findById(id);
        if (task == null) notFound();
        if (task.status != Task.Status.PENDING) {
            badRequest();
        }
        services.TaskSchedulingService.resume(task.id);
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
        if (task == null) notFound();

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
        if (task == null) notFound();
        if (task.status != Task.Status.FAILED) {
            badRequest();
        }
        task.retryCount = 0;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.lastError = null;
        task.save();
        // FAILED Tasks have no scheduled_tasks row (it was removed when the
        // failure terminated the previous fire), so register() rather than
        // update() — the latter would try to cancel a non-existent row,
        // which is harmless but wasteful.
        services.TaskSchedulingService.register(task);
        renderJSON(gson.toJson(TaskView.of(task)));
    }

}
