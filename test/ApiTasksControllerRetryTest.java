import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.test.Fixtures;
import play.test.FunctionalTest;
import models.EventLog;
import models.Task;
import services.EventLogger;
import services.TaskExecutionHandler;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * JCLAW-258 functional tests for {@code POST /api/tasks/{id}/retry} —
 * the AC change that extends acceptance from FAILED-only to FAILED-or-LOST.
 *
 * <p>The scheduler side is null in tests (no live SchedulerClient
 * bootstrap), so {@code TaskSchedulingService.register} no-ops on
 * the scheduling-row insert path. We verify:
 * <ul>
 *   <li>Retry on a LOST Task transitions to PENDING (the recovery
 *       intent) and resets retryCount/lastError just like FAILED.</li>
 *   <li>Retry on a LOST Task removes the stale {@code scheduled_tasks}
 *       row (operator pre-emption of db-scheduler's auto-recovery).</li>
 *   <li>Retry continues to work on FAILED (no regression).</li>
 *   <li>Retry on a non-retryable status returns 400.</li>
 * </ul>
 */
class ApiTasksControllerRetryTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    @AfterEach
    void teardown() throws Exception {
        EventLogger.flush();
        truncateScheduledTasks();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(resp);
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "retry-test-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name, String schedule) {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "%s", "schedule": "%s"}
                """.formatted(agentId, name, schedule));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    /** See ApiTasksControllerRunTest for the rationale. */
    private static void mutateAndCommit(Long taskId, java.util.function.Consumer<Task> mutator) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    mutator.accept(task);
                    task.save();
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    @Test
    void retryAcceptsLostStatus() throws Exception {
        var agent = seedAgent();
        var taskId = seedTask(agent, "lost-retry", "every 1h");

        // Set up the LOST shape: status=LOST plus a picked-but-stale
        // scheduled_tasks row (the production invariant for LOST).
        mutateAndCommit(taskId, t -> {
            t.status = Task.Status.LOST;
            t.lastError = "heartbeat stale";
        });
        insertStaleSchedulerRow(taskId);

        var resp = POST("/api/tasks/" + taskId + "/retry", "application/json", "");
        assertIsOk(resp);
        // LOST flips to PENDING, retryCount resets, lastError cleared.
        assertContentMatch("\"status\":\"PENDING\"", resp);
        assertContentMatch("\"retryCount\":0", resp);
        // lastError serialized as null (not present as a non-null string).
        assertFalse(getContent(resp).contains("\"lastError\":\"heartbeat stale\""),
                "lastError must be cleared on LOST retry");

        // Operator click pre-empts db-scheduler's auto-recovery —
        // forceRemoveStaleRow must have wiped the stale row.
        assertFalse(scheduledTaskRowExists(taskId),
                "retry on LOST must remove the stale scheduled_tasks row");
    }

    @Test
    void retryStillAcceptsFailedStatus() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "failed-retry", "every 1h");

        mutateAndCommit(taskId, t -> {
            t.status = Task.Status.FAILED;
            t.lastError = "permanent: bad config";
        });

        var resp = POST("/api/tasks/" + taskId + "/retry", "application/json", "");
        assertIsOk(resp);
        assertContentMatch("\"status\":\"PENDING\"", resp);
        assertContentMatch("\"retryCount\":0", resp);
    }

    @Test
    void retryRejectsPendingTask() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "pending-noretry", "every 1h");

        var resp = POST("/api/tasks/" + taskId + "/retry", "application/json", "");
        // Retry is FAILED-or-LOST only; PENDING is not a valid source state.
        assertStatus(400, resp);
    }

    @Test
    void retryEmitsManualRunEventMentioningLost() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "lost-audit", "every 1h");

        mutateAndCommit(taskId, t -> t.status = Task.Status.LOST);

        assertIsOk(POST("/api/tasks/" + taskId + "/retry", "application/json", ""));

        EventLogger.flush();
        long count = EventLog.count(
                "category = ?1 AND message LIKE ?2",
                "TASK_MGMT_MANUAL_RUN", "%was LOST%");
        assertEquals(1L, count,
                "expected one audit row noting the LOST source state");
    }

    // === Helpers ===

    private void insertStaleSchedulerRow(Long taskId) throws Exception {
        // DB.getDataSource() returns Hikari-pooled connections with autoCommit=false
        // (Hibernate-managed). Force autocommit on so the row lands before
        // the controller's separate-connection SELECT.
        try (var conn = DB.datasource.getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO scheduled_tasks "
                    + "(task_name, task_instance, execution_time, picked, "
                    + " picked_by, last_heartbeat, version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.setString(2, taskId.toString());
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setBoolean(4, true);
                ps.setString(5, "test-scheduler");
                ps.setTimestamp(6, Timestamp.from(Instant.now().minusSeconds(120)));
                ps.setLong(7, 1L);
                ps.executeUpdate();
            }
        }
    }

    private boolean scheduledTaskRowExists(Long taskId) throws Exception {
        try (var conn = DB.getDataSource().getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM scheduled_tasks "
                    + "WHERE task_name = ? AND task_instance = ?")) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.setString(2, taskId.toString());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            }
        }
    }

    private void truncateScheduledTasks() throws Exception {
        try (var conn = DB.getDataSource().getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "DELETE FROM scheduled_tasks WHERE task_name = ?")) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.executeUpdate();
            }
        }
    }
}
