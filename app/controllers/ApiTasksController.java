package controllers;

import com.google.gson.Gson;
import models.Task;
import play.db.jpa.JPA;

import static utils.GsonHolder.INSTANCE;
import play.mvc.Controller;
import play.mvc.With;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.List;

@With(AuthCheck.class)
public class ApiTasksController extends Controller {

    private static final Gson gson = INSTANCE;

    private record TaskView(Long id, String name, String description, String type, String status,
                            String cronExpression, int retryCount, int maxRetries, String lastError,
                            String nextRunAt, String createdAt, Long agentId, String agentName) {
        static TaskView of(Task t) {
            return new TaskView(t.id, t.name, t.description, t.type.name(), t.status.name(),
                    t.cronExpression, t.retryCount, t.maxRetries, t.lastError,
                    t.nextRunAt != null ? t.nextRunAt.toString() : null,
                    t.createdAt.toString(),
                    t.agent != null ? t.agent.id : null,
                    t.agent != null ? t.agent.name : null);
        }
    }

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

    public static void cancel(Long id) {
        Task task = Task.findById(id);
        if (task == null) notFound();
        if (task.status != Task.Status.PENDING) {
            badRequest();
        }
        task.status = Task.Status.CANCELLED;
        task.save();
        renderJSON(gson.toJson(TaskView.of(task)));
    }

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
        renderJSON(gson.toJson(TaskView.of(task)));
    }

}
