import org.junit.jupiter.api.*;
import play.test.*;
import models.Task;
import models.TaskRun;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code GET /api/tasks/{id}/runs} (JCLAW-294
 * commit 7). Read-only endpoint; pagination, sort-order, empty-history,
 * and 404 paths.
 */
class ApiTasksControllerRunsTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "task-runs-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name) {
        // Use "1d" so the scheduler's far-future fire doesn't race the test.
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
     * Seed TaskRun rows in a fresh tx so the controller (running on
     * TestEngine.functionalTestsExecutor) sees them. Mirrors the
     * mutateAndCommit pattern documented in
     * project_functionaltest_tx_isolation memory.
     */
    private static void seedRuns(Long taskId, int count) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    for (int i = 0; i < count; i++) {
                        var r = new TaskRun();
                        r.task = task;
                        // Stagger so ORDER BY startedAt DESC is deterministic.
                        r.startedAt = Instant.now().minusSeconds(60L * (count - i));
                        r.completedAt = r.startedAt.plusSeconds(5);
                        r.durationMs = 5000L;
                        r.status = TaskRun.Status.COMPLETED;
                        r.outputSummary = "run-" + i + " output";
                        r.save();
                    }
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
    void returnsEmptyListForTaskWithNoRuns() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "empty-task");

        var resp = GET("/api/tasks/" + taskId + "/runs");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void returnsAllRunsForTaskWithoutPaginationParams() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "with-runs");
        seedRuns(taskId, 3);

        var resp = GET("/api/tasks/" + taskId + "/runs");
        assertIsOk(resp);
        var body = getContent(resp);
        // Each of the three runs shows up.
        assertTrue(body.contains("run-0 output"));
        assertTrue(body.contains("run-1 output"));
        assertTrue(body.contains("run-2 output"));
    }

    @Test
    void sortsMostRecentFirst() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "sort-task");
        seedRuns(taskId, 3);

        var resp = GET("/api/tasks/" + taskId + "/runs");
        assertIsOk(resp);
        var body = getContent(resp);
        // seedRuns stages startedAt so run-2 has the newest startedAt
        // (smallest minusSeconds offset). Most-recent-first means
        // run-2 appears before run-0 in the response body.
        int idx2 = body.indexOf("run-2 output");
        int idx0 = body.indexOf("run-0 output");
        assertTrue(idx2 < idx0, "expected run-2 (newest) to appear before run-0 (oldest)");
    }

    @Test
    void respectsLimitParam() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "limit-task");
        seedRuns(taskId, 5);

        var resp = GET("/api/tasks/" + taskId + "/runs?limit=2");
        assertIsOk(resp);
        var body = getContent(resp);
        // 2 runs returned — verify by counting "\"status\":" occurrences,
        // which appears once per TaskRunView.
        int count = body.split("\"status\":", -1).length - 1;
        assertEquals(2, count, "limit=2 should return exactly 2 runs; body=" + body);
    }

    @Test
    void respectsOffsetParam() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "offset-task");
        seedRuns(taskId, 4);

        var resp = GET("/api/tasks/" + taskId + "/runs?limit=2&offset=2");
        assertIsOk(resp);
        var body = getContent(resp);
        // After offset=2 (skipping the two newest, which are run-3 and run-2),
        // page contains run-1 and run-0.
        assertTrue(body.contains("run-1 output"));
        assertTrue(body.contains("run-0 output"));
        assertFalse(body.contains("run-3 output"),
                "run-3 should be on the first page, not this one");
    }

    @Test
    void returns404ForNonexistentTask() {
        var resp = GET("/api/tasks/999999/runs");
        assertStatus(404, resp);
    }

    @Test
    void exposesAuditAndPayloadFields() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "payload-task");
        // Seed one run with rich fields.
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var r = new TaskRun();
                    r.task = task;
                    r.startedAt = Instant.now();
                    r.completedAt = Instant.now();
                    r.durationMs = 1234L;
                    r.status = TaskRun.Status.FAILED;
                    r.error = "boom";
                    r.outputSummary = "partial output";
                    r.deliveryStatus = TaskRun.DeliveryStatus.NOT_DELIVERED;
                    r.deliveryTarget = "telegram:42";
                    r.deliveryError = "channel down";
                    r.traceJson = "{\"step\":\"x\"}";
                    r.save();
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());

        var resp = GET("/api/tasks/" + taskId + "/runs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"FAILED\""), body);
        assertTrue(body.contains("\"durationMs\":1234"), body);
        assertTrue(body.contains("\"error\":\"boom\""), body);
        assertTrue(body.contains("\"deliveryStatus\":\"NOT_DELIVERED\""), body);
        assertTrue(body.contains("\"deliveryTarget\":\"telegram:42\""), body);
        assertTrue(body.contains("\"deliveryError\":\"channel down\""), body);
        assertTrue(body.contains("\"traceJson\":"), body);
    }

    @Test
    void runningRunExposesLatestTurnPreviewTerminalRunsDoNot() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "preview-task");
        // One older terminal run (no preview — the row renders outputSummary)
        // plus one newest in-flight RUNNING run whose latest turn is the clip.
        seedRuns(taskId, 1);
        seedRunningRunWithTurns(taskId, "first turn", "latest turn in flight");

        var resp = GET("/api/tasks/" + taskId + "/runs");
        assertIsOk(resp);
        var body = getContent(resp);
        // The RUNNING run surfaces its newest turn as a live preview clip...
        assertTrue(body.contains("\"latestTurnPreview\":\"latest turn in flight\""), body);
        // ...while the terminal run carries none.
        assertTrue(body.contains("\"latestTurnPreview\":null"), body);
    }

    /** Seed one RUNNING run carrying the given turns (turnIndex ascending). */
    private static void seedRunningRunWithTurns(Long taskId, String... turns) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var run = new TaskRun();
                    run.task = task;
                    run.startedAt = Instant.now();
                    run.status = TaskRun.Status.RUNNING;
                    run.save();
                    for (int i = 0; i < turns.length; i++) {
                        var m = new models.TaskRunMessage();
                        m.taskRun = run;
                        m.role = models.MessageRole.ASSISTANT;
                        m.content = turns[i];
                        m.turnIndex = i;
                        m.save();
                    }
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
}
