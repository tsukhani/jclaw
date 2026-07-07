import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import play.test.TestEngine;
import models.Task;
import models.TaskRun;
import services.EventLogger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * JCLAW-414: functional HTTP tests for {@code POST /api/task-runs/{runId}/cancel}
 * and the {@code runningRunId} field the Tasks list now carries.
 *
 * <p>The cooperative-cancel flag flip is a no-op here (no live fire is
 * executing on a VT for the seeded run), so what these verify is the
 * controller's status-guard + terminal stamp + the list's runningRunId
 * projection. The flag/checkpoint contract itself lives in
 * {@code TaskRunRegistryTest}.
 */
class ApiTasksControllerCancelRunTest extends FunctionalTest {

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
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "cancel-run-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name) {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "%s", "schedule": "1d"}
                """.formatted(agentId, name));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    /**
     * Seed one TaskRun with the given status in a fresh tx (fresh VT) so the
     * controller — running on TestEngine.functionalTestsExecutor — sees it.
     * Mirrors the seed pattern documented in project_functionaltest_tx_isolation.
     * Returns the new run's id.
     */
    private static Long seedRun(Long taskId, TaskRun.Status status) {
        var idRef = new AtomicReference<Long>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var r = new TaskRun();
                    r.task = task;
                    r.startedAt = Instant.now();
                    if (status != TaskRun.Status.RUNNING) {
                        r.completedAt = Instant.now();
                        r.durationMs = 0L;
                    }
                    r.status = status;
                    r.save();
                    idRef.set(r.id);
                    return null;
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
        return idRef.get();
    }

    @Test
    void cancelMissingRunIs404() {
        assertStatus(404, POST("/api/task-runs/999999/cancel", "application/json", ""));
    }

    @Test
    void cancelTerminalRunIs400() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "terminal-run-task");
        var runId = seedRun(taskId, TaskRun.Status.COMPLETED);
        // A run that isn't RUNNING has nothing to stop.
        assertStatus(400, POST("/api/task-runs/" + runId + "/cancel", "application/json", ""));
    }

    @Test
    void cancelRunningRunStampsCancelled() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "running-run-task");
        var runId = seedRun(taskId, TaskRun.Status.RUNNING);

        var resp = POST("/api/task-runs/" + runId + "/cancel", "application/json", "");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"CANCELLED\""),
                "cancel returns the run as CANCELLED: " + getContent(resp));
    }

    @Test
    void listExposesRunningRunIdAndClearsAfterCancel() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "running-id-task");
        var runId = seedRun(taskId, TaskRun.Status.RUNNING);

        // While the run is RUNNING the task carries its id so the UI can show
        // the cancel control.
        var before = getContent(GET("/api/tasks"));
        assertTrue(before.contains("\"runningRunId\":" + runId),
                "list exposes the running run id: " + before);

        assertIsOk(POST("/api/task-runs/" + runId + "/cancel", "application/json", ""));

        // After cancel the run is terminal, so the task no longer advertises it.
        var after = getContent(GET("/api/tasks"));
        assertFalse(after.contains("\"runningRunId\":" + runId),
                "list drops the running run id once cancelled: " + after);
    }
}
