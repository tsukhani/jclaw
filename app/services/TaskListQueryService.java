package services;

import models.Task;
import play.db.jpa.JPA;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.JpqlFilter;

import java.io.IOException;
import java.util.List;

/**
 * JCLAW-676: {@code GET /api/tasks} query assembly — JpqlFilter construction,
 * FTS id resolution, the paged fetch and the independent COUNT — extracted from
 * {@code ApiTasksController} so {@code list()} stays a thin
 * delegate-and-render. Returns a {@link TaskListResult}; the controller keeps
 * the {@code X-Total-Count} header set, the empty-FTS short-circuit render, the
 * per-page bulk passes, and the {@code TaskView} mapping. Relies on the
 * caller's ambient JPA transaction.
 */
public final class TaskListQueryService {

    private TaskListQueryService() {}

    private static final String KEY_PAYLOAD_TYPE = "payloadType";

    /**
     * Outcome of a task-list query: the page of {@code tasks}, the {@code
     * total} matching the same WHERE clause (ignoring limit/offset), and {@code
     * ftsEmpty} — true when a non-blank {@code q} matched nothing, signalling
     * the controller to short-circuit to zero rows with {@code X-Total-Count: 0}.
     */
    public record TaskListResult(List<Task> tasks, long total, boolean ftsEmpty) {}

    public static TaskListResult query(String status, String type, Long agentId, String q,
                                       String payloadType, String excludePayloadType,
                                       Integer limit, Integer offset) {
        var filter = new JpqlFilter()
                .eq("status", status != null && !status.isBlank() ? Task.Status.valueOf(status.toUpperCase()) : null)
                .eq("type", type != null && !type.isBlank() ? Task.Type.valueOf(type.toUpperCase()) : null)
                .eq("agent.id", agentId)
                // payloadType filters: the frontend's /tasks page passes
                // excludePayloadType=reminder so reminders don't appear in
                // the automation-tasks list; /reminders passes
                // payloadType=reminder for the inverse. Both null/blank for
                // headless callers and the Dashboard tile counts.
                .eq(KEY_PAYLOAD_TYPE, payloadType)
                .notEqOrNull(KEY_PAYLOAD_TYPE, excludePayloadType);

        // JCLAW-304: q resolves against the TASK Lucene scope (virtual
        // doc of name + description). Null when q is absent/blank;
        // empty list when q matched nothing — caller short-circuits to
        // zero rows. Non-empty narrows the result set via `t.id IN (:fts)`.
        var ftsTaskIds = ftsTaskIds(q);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var where = filter.toWhereClause();
        if (ftsTaskIds != null && ftsTaskIds.isEmpty()) {
            return new TaskListResult(List.of(), 0L, true);
        }
        if (ftsTaskIds != null) {
            where = where.isEmpty() ? "t.id IN (:fts)" : where + " AND t.id IN (:fts)";
        }
        String jpql = where.isEmpty()
                ? "SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC"
                : "SELECT t FROM Task t LEFT JOIN FETCH t.agent WHERE " + where + " ORDER BY t.createdAt DESC";
        var jpaQ = JPA.em().createQuery(jpql, Task.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            jpaQ.setParameter(i + 1, params.get(i));
        }
        if (ftsTaskIds != null) jpaQ.setParameter("fts", ftsTaskIds);
        List<Task> tasks = jpaQ.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

        // X-Total-Count: mirror the convention used by /api/conversations
        // (consumed by the dashboard's per-status task counts and any
        // future paginated UI). Computed via a separate COUNT query
        // applying the same WHERE clause but ignoring limit/offset, so
        // the header reflects the true total irrespective of pagination.
        String countJpql = where.isEmpty()
                ? "SELECT COUNT(t) FROM Task t"
                : "SELECT COUNT(t) FROM Task t WHERE " + where;
        var countQ = JPA.em().createQuery(countJpql, Long.class);
        for (int i = 0; i < params.size(); i++) {
            countQ.setParameter(i + 1, params.get(i));
        }
        if (ftsTaskIds != null) countQ.setParameter("fts", ftsTaskIds);
        Long total = countQ.getSingleResult();

        return new TaskListResult(tasks, total, false);
    }

    /**
     * JCLAW-304: resolve a {@code q} keyword to the matching Task ids
     * via the TASK Lucene scope. Same null / empty / non-empty contract
     * as the conversation-list helper: null = "no q filter"; empty =
     * "matched nothing, render zero rows"; non-empty = "narrow". FTS
     * backend errors fall through as "no FTS filter" so the operator sees
     * equality-only results rather than a 500 on a stray Lucene IO hiccup.
     */
    @SuppressWarnings("java:S1168") // null vs empty-list is a deliberate tri-state (see query())
    private static List<Long> ftsTaskIds(String q) {
        if (q == null || q.isBlank()) return null;
        try {
            var ids = MessageSearch.searchIds(
                    LuceneIndexer.Scope.TASK, q, 500);
            return ids.isEmpty() ? List.of() : ids;
        } catch (IOException e) {
            EventLogger.warn("search", null, null,
                    "FTS lookup failed for tasks q='%s': %s".formatted(q, e.getMessage()));
            return null;
        }
    }
}
