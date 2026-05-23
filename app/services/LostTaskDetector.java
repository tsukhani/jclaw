package services;

import models.Task;
import play.db.DB;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-258 LOST detector. Reconciles db-scheduler's
 * {@code scheduled_tasks.last_heartbeat} signal into JClaw's
 * {@link Task.Status#LOST} visibility state.
 *
 * <h3>Design A: visibility-only</h3>
 * Per the ticket, this detector deliberately does <em>not</em> touch
 * db-scheduler's {@code scheduled_tasks} row. db-scheduler owns the
 * recovery path; we only mirror its "this fire is dead" signal into
 * the operator-facing Task status pill at a shorter threshold so the
 * UI distinguishes "running" from "stranded by an unclean shutdown"
 * roughly 60 s before db-scheduler's own re-fire kicks in.
 *
 * <h3>Why direct JDBC instead of {@code SchedulerClient.getCurrentlyExecuting}</h3>
 * {@code getCurrentlyExecuting()} returns this JVM's in-memory set of
 * currently-running executions — empty for the crash-recovery case
 * where the row was picked by a now-dead JVM and the current process
 * has not (yet) re-picked it. The single source of truth that covers
 * both live-stalled AND crash-orphan rows is the persisted
 * {@code last_heartbeat} column itself, so the detector reads it
 * directly via JDBC. The query is bounded to JClaw's own task name
 * and indexed on {@code last_heartbeat}, so the cost is constant in
 * the number of currently-picked rows (typically 0–N for small N).
 *
 * <h3>Wiring</h3>
 * <ul>
 *   <li>{@link jobs.LostTaskScanJob} fires the detector on a fixed
 *       cadence so a long-lived JVM observing another node's crash
 *       still surfaces LOST within ~30 s of the threshold.</li>
 *   <li>{@link jobs.BootConsistencyCheck#sweep} calls the detector
 *       once at startup so a crashed-then-rebooted JVM surfaces LOST
 *       tasks within the first polling cycle rather than waiting for
 *       the next periodic tick.</li>
 * </ul>
 *
 * <h3>Recovery flow</h3>
 * Operators see LOST roughly 60 s after the crash. db-scheduler's
 * own dead-execution detection runs at {@code heartbeatInterval ×
 * missedHeartbeatsLimit = 30 s × 4 = 120 s} and re-fires the row;
 * the next fire transitions {@code LOST → RUNNING → COMPLETED/FAILED}
 * without operator intervention. Operators wanting to pre-empt the
 * auto-recovery use {@code /api/tasks/{id}/retry} which flips
 * {@code LOST → PENDING} and registers a fresh row immediately.
 */
public final class LostTaskDetector {

    /**
     * How stale a heartbeat must be before the corresponding Task is
     * flipped to LOST. 60 s is half of db-scheduler's re-fire window
     * (30 s heartbeat × 4 misses = 120 s), so the visibility transition
     * consistently lands roughly 60 s before the auto-recovery fires.
     */
    public static final Duration STALE_THRESHOLD = Duration.ofSeconds(60);

    private LostTaskDetector() {}

    /**
     * Production entry. Walk currently-picked rows in
     * {@code scheduled_tasks} for the JClaw task, find those whose
     * {@code last_heartbeat} is older than {@link #STALE_THRESHOLD},
     * and transition the corresponding RUNNING Tasks to LOST.
     *
     * @return number of Tasks newly transitioned to LOST (operator-visible
     *         in the periodic-job log line; useful as a yes/no signal in
     *         tests).
     */
    public static int detect() {
        return detect(Instant.now().minus(STALE_THRESHOLD));
    }

    /**
     * Time-injected variant. Production callers should use {@link #detect()};
     * tests pass an explicit cutoff so the assertion is independent of
     * wall-clock drift between row insertion and the detector run.
     *
     * @param staleBefore Cutoff instant. Rows whose {@code last_heartbeat}
     *                    is strictly older than this are considered stale.
     */
    public static int detect(Instant staleBefore) {
        var stale = readStale(staleBefore);
        if (stale.isEmpty()) return 0;
        return markLost(stale);
    }

    /**
     * One stale row's payload: the JClaw Task id plus the
     * {@code last_heartbeat} timestamp read from {@code scheduled_tasks}.
     * Carried so the TASK_LOST event log can record actual staleness
     * (lower bound: {@code STALE_THRESHOLD}; upper bound: however long
     * the JVM was offline).
     */
    public record StaleRow(Long taskId, Instant lastHeartbeat) {}

    /**
     * Pure-logic test seam. Flip the given Tasks from RUNNING to LOST,
     * emit one TASK_LOST event-log entry per row that actually flipped
     * (Tasks not in RUNNING are skipped silently — defensive against
     * a heartbeat going stale concurrently with a normal completion).
     */
    public static int markLost(List<StaleRow> rows) {
        int flipped = 0;
        var now = Instant.now();
        for (var row : rows) {
            boolean changed = Boolean.TRUE.equals(Tx.run(() -> {
                var task = (Task) Task.findById(row.taskId());
                if (task == null || task.status != Task.Status.RUNNING) return false;
                task.status = Task.Status.LOST;
                task.save();
                return true;
            }));
            if (changed) {
                flipped++;
                var task = Tx.run(() -> (Task) Task.findById(row.taskId()));
                if (task != null) {
                    long staleSeconds = row.lastHeartbeat() != null
                            ? Math.max(0L, Duration.between(row.lastHeartbeat(), now).getSeconds())
                            : STALE_THRESHOLD.getSeconds();
                    TaskLifecycleEvents.recordLost(task, staleSeconds);
                }
            }
        }
        return flipped;
    }

    /**
     * Decode one {@code scheduled_tasks.task_instance} value to a JClaw
     * Task id. Hand-tampered rows carrying a non-numeric value are logged
     * and skipped (return null) so one bad row doesn't blow up the whole
     * detector pass.
     */
    private static Long parseTaskInstance(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            EventLogger.warn("task", null, null,
                    "LostTaskDetector: undecodable task_instance '%s'; skipping"
                            .formatted(raw));
            return null;
        }
    }

    private static List<StaleRow> readStale(Instant staleBefore) {
        var ds = DB.getDataSource();
        if (ds == null) return List.of();
        var sql = "SELECT task_instance, last_heartbeat FROM scheduled_tasks "
                + "WHERE task_name = ? AND picked = TRUE AND last_heartbeat < ?";
        var rows = new ArrayList<StaleRow>();
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, TaskExecutionHandler.TASK_NAME);
            ps.setTimestamp(2, Timestamp.from(staleBefore));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var raw = rs.getString(1);
                    if (raw == null) continue;
                    Long taskId = parseTaskInstance(raw);
                    if (taskId == null) continue;
                    var ts = rs.getTimestamp(2);
                    rows.add(new StaleRow(taskId, ts != null ? ts.toInstant() : null));
                }
            }
        } catch (SQLException e) {
            // Fall closed: a transient DB blip should not crash the
            // detector. Next periodic tick retries.
            EventLogger.warn("task", null, null,
                    "LostTaskDetector: scheduled_tasks read failed: " + e.getMessage());
            return List.of();
        }
        return rows;
    }
}
