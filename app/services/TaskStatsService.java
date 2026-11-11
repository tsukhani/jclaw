package services;

import jakarta.persistence.Query;
import models.Task;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.search.LuceneIndexer;

import java.time.Instant;
import java.util.List;

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
 * <p>A {@link Scope} narrows every aggregate the same way {@code GET /api/tasks}
 * narrows its list.
 */
public final class TaskStatsService {

    private TaskStatsService() {}

    /** JPQL bind-parameter names + the TaskRun→Task alias path, factored out of
     *  the run-stats / reset queries (S1192). */
    private static final String PARAM_RUNNING = "running";
    private static final String RUN_TASK_ALIAS = "r.task";
    private static final String PARAM_RSTATUS = "rstatus";
    private static final String PARAM_PAUSED = "paused";

    public static long countRunsSince(Instant since, TaskRun.@Nullable Status status,
                                      Scope scope) {
        var jpql = "SELECT COUNT(r) FROM TaskRun r WHERE r.startedAt >= :since"
                + (status != null ? " AND r.status = :rstatus" : "")
                + scopeWhere(RUN_TASK_ALIAS, scope);
        var q = JPA.em().createQuery(jpql, Long.class).setParameter("since", since);
        if (status != null) q.setParameter(PARAM_RSTATUS, status);
        bindScope(q, scope);
        return q.getSingleResult();
    }

    /** Null when no completed run matches the window — {@code AVG} over an empty set. */
    public static @Nullable Double avgCompletedDuration(Instant since, Scope scope) {
        var jpql = "SELECT AVG(r.durationMs) FROM TaskRun r "
                + "WHERE r.startedAt >= :since AND r.status = :rstatus AND r.durationMs IS NOT NULL"
                + scopeWhere(RUN_TASK_ALIAS, scope);
        var q = JPA.em().createQuery(jpql)
                .setParameter("since", since)
                .setParameter(PARAM_RSTATUS, TaskRun.Status.COMPLETED);
        bindScope(q, scope);
        return (Double) q.getSingleResult();
    }

    /**
     * Count tasks in a status. {@code paused} null counts both; false excludes
     * suspended schedules, so Pending/Active and {@link #countPausedTasks}
     * partition the live tasks instead of double-counting one — {@link
     * Task#paused} is a flag, not a status.
     */
    public static long countTasks(Task.Status status, @Nullable Boolean paused,
                                  Scope scope) {
        var jpql = "SELECT COUNT(t) FROM Task t WHERE t.status = :status"
                + (paused != null ? " AND t.paused = :paused" : "")
                + scopeWhere("t", scope);
        var q = JPA.em().createQuery(jpql, Long.class).setParameter("status", status);
        if (paused != null) q.setParameter(PARAM_PAUSED, paused);
        bindScope(q, scope);
        return q.getSingleResult();
    }

    /**
     * Count suspended schedules — paused tasks in the two states pause applies
     * to (PENDING one-shot waiting, ACTIVE recurring ongoing). A terminal
     * task's stale flag never counts: it has no scheduler row left to suspend.
     */
    public static long countPausedTasks(Scope scope) {
        var jpql = "SELECT COUNT(t) FROM Task t WHERE t.paused = :paused "
                + "AND t.status IN (:pending, :active)"
                + scopeWhere("t", scope);
        var q = JPA.em().createQuery(jpql, Long.class)
                .setParameter(PARAM_PAUSED, true)
                .setParameter("pending", Task.Status.PENDING)
                .setParameter("active", Task.Status.ACTIVE);
        bindScope(q, scope);
        return q.getSingleResult();
    }

    /**
     * Count task runs currently in flight ({@link TaskRun.Status#RUNNING}).
     * Unlike {@link #countRunsSince} this is deliberately not bounded by
     * "today" — a run that started before midnight and is still executing
     * must still count. Narrowed by the scope like the other aggregates.
     */
    public static long countRunningRuns(Scope scope) {
        var jpql = "SELECT COUNT(r) FROM TaskRun r WHERE r.status = :rstatus"
                + scopeWhere(RUN_TASK_ALIAS, scope);
        var q = JPA.em().createQuery(jpql, Long.class)
                .setParameter(PARAM_RSTATUS, TaskRun.Status.RUNNING);
        bindScope(q, scope);
        return q.getSingleResult();
    }

    /**
     * Hard-delete terminal (non-RUNNING) task runs and their transcripts,
     * narrowed by the same scope the stats aggregates use.
     * In-flight RUNNING runs are preserved so a reset during an active fire
     * doesn't orphan it. Returns the number of TaskRun rows deleted. Flushes so
     * the delete is visible to a subsequent aggregate in the same transaction.
     */
    public static int resetTerminalRuns(Scope scope) {
        var em = JPA.em();
        // JCLAW-994: collect before the bulk DELETE — it never fires @PostRemove.
        var idQ = em.createQuery("SELECT m.id FROM TaskRunMessage m WHERE m.taskRun.status <> :running"
                        + scopeWhere("m.taskRun.task", scope))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindScope(idQ, scope);
        @SuppressWarnings("unchecked")
        List<Long> transcriptIds = idQ.getResultList();

        SubagentRegistry.deleteRunsStartedBy(
                "r.status <> :running" + scopeWhere(RUN_TASK_ALIAS, scope),
                q -> {
                    q.setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
                    bindScope(q, scope);
                });

        var msgQ = em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.status <> :running"
                        + scopeWhere("m.taskRun.task", scope))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindScope(msgQ, scope);
        msgQ.executeUpdate();

        var runQ = em.createQuery("DELETE FROM TaskRun r WHERE r.status <> :running"
                        + scopeWhere(RUN_TASK_ALIAS, scope))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindScope(runQ, scope);
        int deleted = runQ.executeUpdate();
        em.flush();
        LuceneIndexer.removeAll(LuceneIndexer.Scope.TASK_RUN_MESSAGE, transcriptIds);
        return deleted;
    }

    /**
     * What an aggregate counts: {@code payloadType} pins to one kind (e.g. reminder), and
     * {@code excludePayloadType} excludes it while still matching the NULL payloadType of ordinary
     * automation tasks, mirroring the {@code GET /api/tasks} list filter. {@code agentId} pins to one
     * agent's tasks, as an agent's own stats are (JF-284). Null or blank counts everything.
     */
    public record Scope(@Nullable String payloadType, @Nullable String excludePayloadType, @Nullable Long agentId) {

        /** Every agent's tasks, by payload type alone. */
        public static Scope ofPayload(@Nullable String payloadType, @Nullable String excludePayloadType) {
            return new Scope(payloadType, excludePayloadType, null);
        }
    }

    /** The JPQL fragment for {@code scope}; {@code alias} is the path to the Task ({@code "t"}, or {@code "r.task"} for a run). */
    private static String scopeWhere(String alias, Scope scope) {
        var sb = new StringBuilder();
        if (hasText(scope.payloadType())) {
            sb.append(" AND ").append(alias).append(".payloadType = :pt");
        }
        if (hasText(scope.excludePayloadType())) {
            sb.append(" AND (").append(alias).append(".payloadType <> :ept OR ")
              .append(alias).append(".payloadType IS NULL)");
        }
        if (scope.agentId() != null) {
            sb.append(" AND ").append(alias).append(".agent.id = :agentId");
        }
        return sb.toString();
    }

    private static void bindScope(Query q, Scope scope) {
        var payloadType = scope.payloadType();
        if (payloadType != null && !payloadType.isBlank()) q.setParameter("pt", payloadType);
        var excludePayloadType = scope.excludePayloadType();
        if (excludePayloadType != null && !excludePayloadType.isBlank()) q.setParameter("ept", excludePayloadType);
        var agentId = scope.agentId();
        if (agentId != null) q.setParameter("agentId", agentId);
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
