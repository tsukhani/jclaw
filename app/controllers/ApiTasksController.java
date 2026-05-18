package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Task;
import play.db.jpa.JPA;

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
 * Every id-addressed mutation here ({@link #cancel}, {@link #retry},
 * {@link #pause}, {@link #resume}) currently authenticates via
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
