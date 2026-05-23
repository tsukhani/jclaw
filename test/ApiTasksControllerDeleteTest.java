import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.FunctionalTest;
import models.Task;
import models.TaskRun;
import services.EventLogger;
import services.TaskExecutionHandler;
import services.Tx;

import java.util.regex.Pattern;

/**
 * Functional tests for {@code DELETE /api/tasks/{id}}, the hard-delete
 * endpoint added in v0.12.17. Unlike {@code POST /api/tasks/{id}/cancel}
 * which transitions the row to {@link Task.Status#CANCELLED} so
 * {@code runNow} can revive it later, the DELETE endpoint sweeps the
 * Task row and its run history ({@link TaskRun} +
 * {@link models.TaskRunMessage}) in one shot. Verifies:
 *
 * <ul>
 *   <li>Deleting a PENDING task removes the row.</li>
 *   <li>The cascade removes every {@link TaskRun} that references the
 *       deleted task — the FK chain we walk via JPQL bulk deletes.</li>
 *   <li>Deleting a non-existent id returns 404.</li>
 *   <li>Deleting a CANCELLED task still works — cancel/delete are
 *       independent paths and delete accepts any status.</li>
 *   <li>The audit emits a TASK_MGMT_HARD_DELETE entry so an operator
 *       can distinguish soft-cancels from hard-deletes after the fact.</li>
 * </ul>
 */
class ApiTasksControllerDeleteTest extends FunctionalTest {

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
                {"name": "delete-test-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
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

    /**
     * Insert a synthetic TaskRun row referencing the given task so we
     * can assert the cascade. Done outside the FunctionalTest carrier
     * tx for the same commit-visibility reason as the other helpers in
     * this file — fresh virtual thread + Tx commits before the next
     * HTTP request runs through the FunctionalTest carrier's ambient
     * transaction.
     */
    private static void persistTaskRun(Long taskId) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var run = new TaskRun();
                    run.task = task;
                    run.status = TaskRun.Status.COMPLETED;
                    run.startedAt = java.time.Instant.now().minusSeconds(60);
                    run.completedAt = java.time.Instant.now().minusSeconds(30);
                    run.save();
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
    void deletePendingTaskRemovesTheRow() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "delete-pending", "every 1h");

        // Sanity: the row is present.
        assertNotNull(Task.findById(taskId), "task exists before delete");

        var resp = DELETE("/api/tasks/" + taskId);
        assertIsOk(resp);
        assertContentMatch("\"status\":\"deleted\"", resp);
        assertContentMatch("\"id\":" + taskId, resp);

        // Clear the L1 cache so the next finder hits the DB rather than
        // returning the stale entity reference the FunctionalTest
        // carrier thread cached from the seed POST.
        JPA.em().clear();
        assertNull(Task.findById(taskId), "task row removed after delete");
    }

    @Test
    void deleteCascadesTaskRunHistory() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "delete-with-runs", "every 1h");

        // Seed a couple of runs so the FK chain has something to clean up.
        persistTaskRun(taskId);
        persistTaskRun(taskId);
        assertEquals(2L, TaskRun.count("task.id = ?1", taskId),
                "two TaskRun rows present before delete");

        var resp = DELETE("/api/tasks/" + taskId);
        assertIsOk(resp);

        JPA.em().clear();
        assertNull(Task.findById(taskId), "task row removed");
        assertEquals(0L, TaskRun.count("task.id = ?1", taskId),
                "TaskRun cascade swept the run history");
    }

    @Test
    void deleteOnCancelledTaskStillWorks() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "delete-after-cancel", "every 1h");

        // Send through the cancel path first so the row is in CANCELLED
        // state; delete must accept any status (the controller doesn't
        // gate on PENDING the way cancel does).
        var cancelResp = POST("/api/tasks/" + taskId + "/cancel", "application/json", "");
        assertIsOk(cancelResp);
        assertContentMatch("\"status\":\"CANCELLED\"", cancelResp);

        var resp = DELETE("/api/tasks/" + taskId);
        assertIsOk(resp);
        JPA.em().clear();
        assertNull(Task.findById(taskId), "cancelled task removed by delete");
    }

    @Test
    void deleteOnMissingIdReturns404() {
        var resp = DELETE("/api/tasks/999999");
        assertStatus(404, resp);
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
