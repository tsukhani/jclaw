package jobs;

import models.Task;
import play.db.jpa.JPA;
import play.jobs.Every;
import play.jobs.Job;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import services.TaskSchedulingService;

/**
 * JCLAW-259: scheduled auto-cleanup for terminal tasks past their
 * {@code tasks.retentionDays} TTL.
 *
 * <p>Runs every 24 hours off the chat hot path. Scans for tasks in a
 * terminal status ({@link Task.Status#COMPLETED}, {@link Task.Status#FAILED},
 * {@link Task.Status#CANCELLED}, {@link Task.Status#LOST}) whose
 * {@code updatedAt} predates {@code now() − retentionDays}, then hard-
 * deletes them along with their full run history
 * ({@code TaskRunMessage → TaskRun → Task}) and any leftover
 * {@code scheduled_tasks} row.
 *
 * <p>Configuration: {@code tasks.retentionDays} (integer; default
 * {@link #DEFAULT_RETENTION_DAYS}). Set to {@link #RETENTION_DISABLED}
 * — or unset — to disable cleanup entirely (the AC's "unset means
 * forever" semantic). Out-of-range or non-numeric values fall back to
 * the default, with a one-shot warn so a typo isn't silently ignored.
 *
 * <p>Why the bulk JPQL deletes match {@link controllers.ApiTasksController#delete}:
 * the FK chain is the same and the per-task delete loop would do N
 * round-trips per task, but a single batch can address all eligible
 * tasks in three JPQL statements regardless of count. Scheduler-row
 * cancel is per-task and idempotent — terminal tasks usually have
 * already-removed scheduler rows so the cost is a few DB pings.
 *
 * <p>Active (PENDING / ACTIVE / RUNNING) tasks are never touched. LOST
 * is in scope because it's a terminal-shape state (the task isn't going
 * to run again under its own steam — db-scheduler's re-fire would flip
 * it back to RUNNING first, at which point the cleanup query no longer
 * matches). If an operator wants to preserve LOST tasks for forensics
 * past the TTL, they can retry → PENDING → ACTIVE.
 */
@Every("24h")
public class TaskCleanupJob extends Job<Void> {

    private static final String EVENT_CATEGORY = "TASK_CLEANUP";
    private static final String CONFIG_KEY = "tasks.retentionDays";

    /** Default retention window when {@code tasks.retentionDays} is absent. */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /** Sentinel value (0) meaning "retention disabled, never auto-delete". */
    public static final int RETENTION_DISABLED = 0;

    /** Upper bound on the configured value — defense-in-depth against a
     *  typo like 365000 that would make the query effectively no-op but
     *  also looks alarming in logs. ~10 years is the practical ceiling. */
    private static final int MAX_RETENTION_DAYS = 3650;

    @Override
    public void doJob() {
        var retentionDays = resolveRetentionDays();
        if (retentionDays == RETENTION_DISABLED) {
            // "Retention disabled" is the deliberate operator choice; log
            // only at debug-ish-info level so the daily noise stays minimal.
            EventLogger.info(EVENT_CATEGORY, null, null,
                    "Skipped: tasks.retentionDays = 0 (cleanup disabled)");
            return;
        }

        var cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = Tx.run(() -> deleteExpired(cutoff));

        if (deleted > 0) {
            EventLogger.info(EVENT_CATEGORY, null, null,
                    "Deleted %d terminal task(s) older than %d day(s) (cutoff=%s)"
                            .formatted(deleted, retentionDays, cutoff));
        }
        // Suppress zero-delete log noise: the job runs daily and silence
        // here means "system is healthy, nothing to clean", which doesn't
        // need an audit line.
    }

    /**
     * Read {@code tasks.retentionDays} from {@link ConfigService}, with
     * defensive handling: missing → {@link #DEFAULT_RETENTION_DAYS}, 0 →
     * disabled, negative or above ceiling → default plus a warn.
     *
     * <p>Public so {@code TaskCleanupJobTest} (which sits in the default
     * package) can pin every parsing branch without going through the
     * full {@code doJob} path. Play 1.x's test compilation places test
     * classes in the default package, so package-private here would block
     * direct access.
     */
    public static int resolveRetentionDays() {
        var raw = ConfigService.get(CONFIG_KEY);
        if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
        try {
            var parsed = Integer.parseInt(raw.trim());
            if (parsed == 0) return RETENTION_DISABLED;
            if (parsed < 0 || parsed > MAX_RETENTION_DAYS) {
                EventLogger.warn(EVENT_CATEGORY,
                        ("tasks.retentionDays out of range (%d); using default %d. "
                                + "Allowed: 0 (disabled) or 1..%d.")
                                .formatted(parsed, DEFAULT_RETENTION_DAYS, MAX_RETENTION_DAYS));
                return DEFAULT_RETENTION_DAYS;
            }
            return parsed;
        } catch (NumberFormatException _) {
            EventLogger.warn(EVENT_CATEGORY,
                    "tasks.retentionDays is not numeric ('%s'); using default %d"
                            .formatted(raw, DEFAULT_RETENTION_DAYS));
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /**
     * Hard-delete every terminal task whose {@code updatedAt} predates
     * {@code cutoff}. Mirrors {@link controllers.ApiTasksController#delete}
     * but as a bulk pass. Returns the number of Task rows removed.
     *
     * <p>FK chain ({@code TaskRunMessage → TaskRun → Task}) is swept in
     * dependency order via JPQL bulk deletes — three statements regardless
     * of count. The per-row {@code scheduled_tasks} cancel iterates the
     * eligible ids before any bulk delete fires so we don't try to drop
     * scheduler rows for tasks we've already evicted from the DB.
     */
    private static int deleteExpired(Instant cutoff) {
        var em = JPA.em();

        // Collect ids first — both for the scheduler-cancel loop AND so the
        // event log can name the count accurately (JPQL bulk delete returns
        // the row count of the LAST statement, which is the Task delete).
        @SuppressWarnings("unchecked")
        List<Long> expiredIds = em.createQuery(
                        "SELECT t.id FROM Task t "
                                + "WHERE t.status IN (:terminal) "
                                + "AND t.updatedAt < :cutoff "
                                + "ORDER BY t.id")
                .setParameter("terminal", List.of(
                        Task.Status.COMPLETED,
                        Task.Status.FAILED,
                        Task.Status.CANCELLED,
                        Task.Status.LOST))
                .setParameter("cutoff", cutoff)
                .getResultList();

        if (expiredIds.isEmpty()) return 0;

        // Drop the FK descendants first, then scheduled_tasks rows, then
        // the Task rows themselves. Same dependency order the per-task
        // ApiTasksController.delete uses.
        em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.task.id IN :ids")
                .setParameter("ids", expiredIds).executeUpdate();
        em.createQuery("DELETE FROM TaskRun r WHERE r.task.id IN :ids")
                .setParameter("ids", expiredIds).executeUpdate();

        for (var taskId : expiredIds) {
            // Idempotent: terminal tasks usually have no scheduler row,
            // but a CANCELLED-then-revived flow could leave one behind.
            TaskSchedulingService.cancel(taskId);
        }

        em.createQuery("DELETE FROM Task t WHERE t.id IN :ids")
                .setParameter("ids", expiredIds).executeUpdate();
        em.flush();

        return expiredIds.size();
    }
}
