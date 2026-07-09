package services;

import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import play.db.jpa.JPA;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-676: TaskRun / TaskRunMessage read queries and the {@code list()} bulk
 * aggregation passes, extracted from {@code ApiTasksController} so the run-read
 * controller actions ({@code runs}, {@code runMessages}, {@code recentRuns})
 * and {@code list} stay thin. Pure queries only — the Result-throwing HTTP
 * guards (notFound / the malformed-instant 400) and response rendering stay in
 * the controller. Relies on the caller's ambient JPA transaction, matching
 * {@link TaskService}.
 */
public final class TaskRunQueryService {

    private TaskRunQueryService() {}

    /** JPQL bind-parameter name for the RUNNING status filter (S1192). */
    private static final String PARAM_RUNNING = "running";

    /** Max characters of an in-flight run's preview clip (one-liner in the row). */
    private static final int RUN_PREVIEW_MAX = 160;

    /**
     * Paginated TaskRun history for one task, most-recent first
     * (startedAt DESC).
     */
    public static List<TaskRun> runsForTask(Task task, int offset, int limit) {
        @SuppressWarnings("unchecked")
        List<TaskRun> rows = (List<TaskRun>) (List<?>) TaskRun.find(
                "task = ?1 ORDER BY startedAt DESC", task)
                .from(offset)
                .fetch(limit);
        return rows;
    }

    /**
     * Turn-by-turn message trace for one run in turn order.
     */
    public static List<TaskRunMessage> messagesForRun(TaskRun run) {
        @SuppressWarnings("unchecked")
        List<TaskRunMessage> rows = (List<TaskRunMessage>) (List<?>) TaskRunMessage.find(
                "taskRun = ?1 ORDER BY turnIndex ASC", run).fetch();
        return rows;
    }

    /**
     * Recent TaskRuns across all tasks for the calendar/timeline, most-recent
     * first (startedAt DESC), capped at {@code limit}. A non-null {@code until}
     * bounds the window ({@code since <= startedAt < until}); a null
     * {@code until} is the rolling-window case (no upper bound).
     */
    public static List<TaskRun> recentRuns(Instant since, Instant until, int limit) {
        var query = (until != null)
                ? TaskRun.find("startedAt >= ?1 AND startedAt < ?2 ORDER BY startedAt DESC", since, until)
                : TaskRun.find("startedAt >= ?1 ORDER BY startedAt DESC", since);
        @SuppressWarnings("unchecked")
        List<TaskRun> rows = (List<TaskRun>) (List<?>) query.fetch(limit);
        return rows;
    }

    /**
     * One-line preview of the newest turn of a still-RUNNING run, so the Tasks
     * run-history row can show a live clip of an in-flight fire — which has no
     * {@code outputSummary} yet (that's stamped only at completion). Returns
     * null for terminal runs (the row renders {@code outputSummary} instead)
     * and for a run with no text turn yet. One indexed lookup, and only for the
     * at-most-one RUNNING run in a history page.
     */
    public static String latestTurnPreviewFor(TaskRun r) {
        if (r.status != TaskRun.Status.RUNNING) return null;
        var latest = (TaskRunMessage) TaskRunMessage.find(
                "taskRun = ?1 AND content IS NOT NULL ORDER BY turnIndex DESC", r).first();
        if (latest == null || latest.content == null || latest.content.isBlank()) return null;
        var text = latest.content.strip();
        return text.length() > RUN_PREVIEW_MAX ? text.substring(0, RUN_PREVIEW_MAX) + "…" : text;
    }

    /**
     * Bulk-fetch the latest {@link TaskRun#completedAt} per task for a page of
     * task ids — GROUP BY task id, MAX(completedAt) — so the Reminders "Fired"
     * column doesn't need an N+1 round-trip. Tasks with no completed runs are
     * absent from the map. Returns an empty map for an empty id list.
     */
    public static Map<Long, Instant> lastFiredAtByTask(List<Long> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        var rows = (List<Object[]>) JPA.em().createQuery(
                "SELECT r.task.id, MAX(r.completedAt) FROM TaskRun r "
                        + "WHERE r.task.id IN :ids AND r.completedAt IS NOT NULL "
                        + "GROUP BY r.task.id")
                .setParameter("ids", taskIds)
                .getResultList();
        var map = HashMap.<Long, Instant>newHashMap(rows.size());
        for (var row : rows) {
            map.put((Long) row[0], (Instant) row[1]);
        }
        return map;
    }

    /**
     * JCLAW-414: bulk-fetch each task's currently-RUNNING TaskRun id (one query,
     * no N+1) so the Actions column can show a cancel control while a run is in
     * flight. A task has at most one RUNNING run in practice; MAX(id) is a
     * stable pick if a stale row ever overlaps. Returns an empty map for an
     * empty id list.
     */
    public static Map<Long, Long> runningRunIdByTask(List<Long> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        var runningRows = (List<Object[]>) JPA.em().createQuery(
                "SELECT r.task.id, MAX(r.id) FROM TaskRun r "
                        + "WHERE r.task.id IN :ids AND r.status = :running "
                        + "GROUP BY r.task.id")
                .setParameter("ids", taskIds)
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING)
                .getResultList();
        var rmap = HashMap.<Long, Long>newHashMap(runningRows.size());
        for (var row : runningRows) {
            rmap.put((Long) row[0], (Long) row[1]);
        }
        return rmap;
    }
}
