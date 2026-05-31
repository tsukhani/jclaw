import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Task;
import models.TaskRun;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Functional HTTP tests for {@code GET /api/tasks/stats} (JCLAW-22 slice K):
 * the dashboard KPI aggregate — today's run count, success rate, average
 * duration, and the pending / running / failed task counts.
 */
class ApiTasksControllerStatsTest extends FunctionalTest {

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

    /**
     * Seed an agent, three tasks (one each PENDING / FAILED / RUNNING) and
     * three of-today runs (two COMPLETED with 1000ms + 3000ms durations, one
     * FAILED) in a fresh tx so the controller thread sees them.
     */
    private static void seedAll() {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var agent = new Agent();
                    agent.name = "stats-agent";
                    agent.modelProvider = "openrouter";
                    agent.modelId = "gpt-4.1";
                    agent.enabled = true;
                    agent.save();

                    mkTask(agent, "pending-task", Task.Status.PENDING);
                    mkTask(agent, "failed-task", Task.Status.FAILED);
                    var running = mkTask(agent, "running-task", Task.Status.RUNNING);

                    mkRun(running, TaskRun.Status.COMPLETED, 1000L);
                    mkRun(running, TaskRun.Status.COMPLETED, 3000L);
                    mkRun(running, TaskRun.Status.FAILED, null);
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

    private static Task mkTask(Agent agent, String name, Task.Status status) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.type = Task.Type.IMMEDIATE;
        t.status = status;
        t.nextRunAt = Instant.now();
        t.save();
        return t;
    }

    private static void mkRun(Task task, TaskRun.Status status, Long durationMs) {
        var r = new TaskRun();
        r.task = task;
        r.startedAt = Instant.now();
        r.status = status;
        if (status == TaskRun.Status.COMPLETED) {
            r.completedAt = Instant.now();
            r.durationMs = durationMs;
        }
        r.save();
    }

    @Test
    void statsReflectsSeededTasksAndRuns() {
        seedAll();

        var resp = GET("/api/tasks/stats");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"runsToday\":3"), body);
        assertTrue(body.contains("\"pendingCount\":1"), body);
        assertTrue(body.contains("\"failedCount\":1"), body);
        assertTrue(body.contains("\"runningCount\":1"), body);
        // 2 COMPLETED / 3 terminal == 0.666...
        assertTrue(body.contains("\"successRate\":0.6"), body);
        // (1000 + 3000) / 2 == 2000
        assertTrue(body.contains("\"avgDurationMs\":2000"), body);
    }

    @Test
    void statsAreZeroWithNoData() {
        var resp = GET("/api/tasks/stats");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"runsToday\":0"), body);
        assertTrue(body.contains("\"pendingCount\":0"), body);
        assertTrue(body.contains("\"failedCount\":0"), body);
    }
}
