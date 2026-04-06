package controllers;

import com.google.gson.Gson;
import models.Task;
import play.mvc.Controller;
import play.mvc.With;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@With(AuthCheck.class)
public class ApiTasksController extends Controller {

    private static final Gson gson = new Gson();

    public static void list(String status, String type, Long agentId, Integer limit, Integer offset) {
        var query = new StringBuilder();
        var params = new java.util.ArrayList<>();
        int idx = 1;

        if (status != null && !status.isBlank()) {
            query.append("status = ?%d".formatted(idx++));
            params.add(Task.Status.valueOf(status.toUpperCase()));
        }
        if (type != null && !type.isBlank()) {
            if (!query.isEmpty()) query.append(" AND ");
            query.append("type = ?%d".formatted(idx++));
            params.add(Task.Type.valueOf(type.toUpperCase()));
        }
        if (agentId != null) {
            if (!query.isEmpty()) query.append(" AND ");
            query.append("agent.id = ?%d".formatted(idx++));
            params.add(agentId);
        }

        var orderBy = query.isEmpty() ? "ORDER BY createdAt DESC" : query + " ORDER BY createdAt DESC";
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        List<Task> tasks = Task.find(orderBy.toString(), params.toArray())
                .from(effectiveOffset).fetch(effectiveLimit);

        renderJSON(gson.toJson(tasks.stream().map(ApiTasksController::taskToMap).toList()));
    }

    public static void cancel(Long id) {
        Task task = Task.findById(id);
        if (task == null) notFound();
        if (task.status != Task.Status.PENDING) {
            badRequest();
        }
        task.status = Task.Status.CANCELLED;
        task.save();
        renderJSON(gson.toJson(taskToMap(task)));
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
        renderJSON(gson.toJson(taskToMap(task)));
    }

    private static Map<String, Object> taskToMap(Task t) {
        var map = new HashMap<String, Object>();
        map.put("id", t.id);
        map.put("name", t.name);
        map.put("description", t.description);
        map.put("type", t.type.name());
        map.put("status", t.status.name());
        map.put("cronExpression", t.cronExpression);
        map.put("retryCount", t.retryCount);
        map.put("maxRetries", t.maxRetries);
        map.put("lastError", t.lastError);
        map.put("nextRunAt", t.nextRunAt != null ? t.nextRunAt.toString() : null);
        map.put("createdAt", t.createdAt.toString());
        if (t.agent != null) {
            map.put("agentId", t.agent.id);
            map.put("agentName", t.agent.name);
        }
        return map;
    }
}
