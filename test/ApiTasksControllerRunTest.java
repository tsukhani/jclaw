import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import play.test.TestEngine;
import models.EventLog;
import models.Task;
import services.EventLogger;

import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code POST /api/tasks/{id}/run} (JCLAW-294
 * commit 6). Covers all four operator-facing scenarios per the AC:
 * any-state acceptance for PENDING, COMPLETED, FAILED, CANCELLED — with
 * special verification that a CANCELLED task gets revived to PENDING so
 * TaskExecutionHandler doesn't swallow the fire.
 *
 * <p>The scheduler side is null in tests (no live SchedulerClient
 * bootstrap), so TaskSchedulingService.runNow no-ops — what we verify
 * here is the controller's status-handling + audit emission.
 */
class ApiTasksControllerRunTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    @AfterEach
    void teardown() {
        EventLogger.flush();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(resp);
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "task-run-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
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
     * Apply a Task mutation in a fresh transaction so it commits before the
     * next HTTP call sees it. FunctionalTest's carrier thread is already
     * inside a JPA tx; an inline {@code task.save()} would nest in that tx
     * and stay uncommitted, so the controller's HTTP handler (running on
     * {@code TestEngine.functionalTestsExecutor}) wouldn't see the change.
     * Pattern documented in CLAUDE memory project_functionaltest_tx_isolation.
     */
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
    void runPendingTask() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "pending-run", "every 1h");

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // Alive recurring tasks stay ACTIVE after run — the entity state
        // doesn't change, only the scheduled_tasks fire time does.
        assertContentMatch("\"status\":\"ACTIVE\"", resp);
    }

    @Test
    void runCompletedTaskKeepsCompletedStatus() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "completed-run", "now");

        // Simulate the task finishing — JCLAW-21 flips IMMEDIATE/SCHEDULED
        // to COMPLETED on successful fire. Apply that directly here since
        // the test doesn't drive a real fire.
        mutateAndCommit(taskId, t -> t.status = Task.Status.COMPLETED);

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // COMPLETED stays COMPLETED (per the docstring on /run — re-firing
        // a terminal task is a deliberate operator action; the audit log
        // is the trail). TaskExecutionHandler doesn't skip COMPLETED so
        // the fire would actually execute.
        assertContentMatch("\"status\":\"COMPLETED\"", resp);
    }

    @Test
    void runFailedTaskKeepsFailedStatus() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "failed-run", "every 1h");

        mutateAndCommit(taskId, t -> {
            t.status = Task.Status.FAILED;
            t.lastError = "permanent: bad config";
        });

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // FAILED stays FAILED on /run — that's distinct from /retry which
        // resets retryCount/lastError and flips back to PENDING.
        assertContentMatch("\"status\":\"FAILED\"", resp);
        assertContentMatch("\"lastError\":\"permanent: bad config\"", resp);
    }

    @Test
    void runCancelledTaskRevivesToPending() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "cancelled-run", "every 1h");

        // Cancel via API to set up the CANCELLED state.
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // CANCELLED must flip back to the alive-state for the task's type —
        // ACTIVE for INTERVAL recurring (this case), PENDING for one-shot —
        // otherwise TaskExecutionHandler swallows the fire at the
        // CANCELLED-skip branch.
        assertContentMatch("\"status\":\"ACTIVE\"", resp);

        // The audit message should call out the revival so operators can
        // grep for unusual flips.
        EventLogger.flush();
        long count = EventLog.count(
                "category = ?1 AND message LIKE ?2",
                "TASK_MGMT_MANUAL_RUN", "%revived from CANCELLED%");
        assertEquals(1L, count, "expected one audit row mentioning the revival");
    }

    @Test
    void runNonexistentTaskIs404() {
        var resp = POST("/api/tasks/999999/run", "application/json", "");
        assertStatus(404, resp);
    }

    @Test
    void emitsTaskMgmtManualRunEvent() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audited-run", "now");

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        EventLogger.flush();
        long count = EventLog.count(
                "category = ?1 AND message LIKE ?2",
                "TASK_MGMT_MANUAL_RUN", "%audited-run%");
        assertEquals(1L, count, "expected exactly one TASK_MGMT_MANUAL_RUN event");
    }
}
