package services;

import jakarta.persistence.Query;
import models.Task;
import models.TaskRun;
import play.db.jpa.JPA;

import java.time.Instant;

/**
 * JCLAW-676: dashboard-KPI read aggregates and the stats-reset delete for the
 * Tasks surface, extracted from {@code ApiTasksController} so the controller
 * actions ({@code stats}, {@code resetStats}) stay thin parse-delegate-render.
 *
 * <p>Every method relies on the caller's ambient JPA transaction — no
 * {@link Tx} wrapper — matching {@link TaskService}. The Result-throwing HTTP
 * guards (badRequest/error/notFound) and response rendering stay in the
 * controller; only the pure JPQL aggregation moves here.
 *
 * <p>The {@code payloadType} / {@code excludePayloadType} pair scopes every
 * aggregate the same way {@code GET /api/tasks} scopes its list: {@code
 * payloadType} pins to one kind (e.g. reminder); {@code excludePayloadType}
 * excludes it while still matching the NULL payloadType of ordinary automation
 * tasks. Both null/blank counts everything.
 */
public final class TaskStatsService {

    private TaskStatsService() {}

    /** JPQL bind-parameter names + the TaskRun→Task alias path, factored out of
     *  the run-stats / reset queries (S1192). */
    private static final String PARAM_RUNNING = "running";
    private static final String RUN_TASK_ALIAS = "r.task";
    private static final String PARAM_RSTATUS = "rstatus";

    public static long countRunsSince(Instant since, TaskRun.Status status,
                                      String payloadType, String excludePayloadType) {
        var jpql = "SELECT COUNT(r) FROM TaskRun r WHERE r.startedAt >= :since"
                + (status != null ? " AND r.status = :rstatus" : "")
                + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql, Long.class).setParameter("since", since);
        if (status != null) q.setParameter(PARAM_RSTATUS, status);
        bindPayloadType(q, payloadType, excludePayloadType);
        return q.getSingleResult();
    }

    public static Double avgCompletedDuration(Instant since, String payloadType, String excludePayloadType) {
        var jpql = "SELECT AVG(r.durationMs) FROM TaskRun r "
                + "WHERE r.startedAt >= :since AND r.status = :rstatus AND r.durationMs IS NOT NULL"
                + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql)
                .setParameter("since", since)
                .setParameter(PARAM_RSTATUS, TaskRun.Status.COMPLETED);
        bindPayloadType(q, payloadType, excludePayloadType);
        return (Double) q.getSingleResult();
    }

    public static long countTasks(Task.Status status, String payloadType, String excludePayloadType) {
        var jpql = "SELECT COUNT(t) FROM Task t WHERE t.status = :status"
                + payloadTypeWhere("t", payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql, Long.class).setParameter("status", status);
        bindPayloadType(q, payloadType, excludePayloadType);
        return q.getSingleResult();
    }

    /**
     * Count task runs currently in flight ({@link TaskRun.Status#RUNNING}).
     * Unlike {@link #countRunsSince} this is deliberately not bounded by
     * "today" — a run that started before midnight and is still executing
     * must still count. Scoped by payloadType like the other aggregates.
     */
    public static long countRunningRuns(String payloadType, String excludePayloadType) {
        var jpql = "SELECT COUNT(r) FROM TaskRun r WHERE r.status = :rstatus"
                + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql, Long.class)
                .setParameter(PARAM_RSTATUS, TaskRun.Status.RUNNING);
        bindPayloadType(q, payloadType, excludePayloadType);
        return q.getSingleResult();
    }

    /**
     * Hard-delete terminal (non-RUNNING) task runs and their transcripts,
     * scoped by the same {@code payloadType} filter the stats aggregates use.
     * In-flight RUNNING runs are preserved so a reset during an active fire
     * doesn't orphan it. Returns the number of TaskRun rows deleted. Flushes so
     * the delete is visible to a subsequent aggregate in the same transaction.
     */
    public static int resetTerminalRuns(String payloadType, String excludePayloadType) {
        var em = JPA.em();
        var msgQ = em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.status <> :running"
                        + payloadTypeWhere("m.taskRun.task", payloadType, excludePayloadType))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindPayloadType(msgQ, payloadType, excludePayloadType);
        msgQ.executeUpdate();

        var runQ = em.createQuery("DELETE FROM TaskRun r WHERE r.status <> :running"
                        + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindPayloadType(runQ, payloadType, excludePayloadType);
        int deleted = runQ.executeUpdate();
        em.flush();
        return deleted;
    }

    /**
     * JPQL fragment scoping an aggregate by payloadType, mirroring the
     * {@code GET /api/tasks} list filter: {@code payloadType} pins to one kind
     * (e.g. reminder); {@code excludePayloadType} excludes it while still
     * matching the NULL payloadType of ordinary automation tasks. {@code alias}
     * is the entity path to the column ({@code "t"} for Task, {@code "r.task"}
     * for a TaskRun join).
     */
    private static String payloadTypeWhere(String alias, String payloadType, String excludePayloadType) {
        var sb = new StringBuilder();
        if (payloadType != null && !payloadType.isBlank()) {
            sb.append(" AND ").append(alias).append(".payloadType = :pt");
        }
        if (excludePayloadType != null && !excludePayloadType.isBlank()) {
            sb.append(" AND (").append(alias).append(".payloadType <> :ept OR ")
              .append(alias).append(".payloadType IS NULL)");
        }
        return sb.toString();
    }

    private static void bindPayloadType(Query q,
                                        String payloadType, String excludePayloadType) {
        if (payloadType != null && !payloadType.isBlank()) q.setParameter("pt", payloadType);
        if (excludePayloadType != null && !excludePayloadType.isBlank()) q.setParameter("ept", excludePayloadType);
    }
}
