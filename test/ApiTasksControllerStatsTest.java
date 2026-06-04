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
        return mkTask(agent, name, status, null);
    }

    private static Task mkTask(Agent agent, String name, Task.Status status, String payloadType) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.type = Task.Type.IMMEDIATE;
        t.status = status;
        t.payloadType = payloadType;
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

    /**
     * payloadType scoping: reminders are payloadType=reminder Tasks that the
     * /tasks page hides (excludePayloadType=reminder) and /reminders shows
     * (payloadType=reminder). The stats endpoint must scope its counts AND its
     * run KPIs the same way, so a pending reminder never inflates the Tasks
     * page's "Pending" while being absent from its list.
     */
    @Test
    void statsScopeByPayloadTypeSeparatesRemindersFromTasks() {
        seedMixed();

        // Default (no scope): everything — preserves the headless-caller contract.
        var all = getContent(GET("/api/tasks/stats"));
        assertTrue(all.contains("\"pendingCount\":2"), all);  // 1 task + 1 reminder pending
        assertTrue(all.contains("\"runsToday\":2"), all);     // 1 task run + 1 reminder run

        // /tasks scope: reminders excluded from counts AND run KPIs.
        var tasks = getContent(GET("/api/tasks/stats?excludePayloadType=reminder"));
        assertTrue(tasks.contains("\"pendingCount\":1"), tasks);
        assertTrue(tasks.contains("\"runsToday\":1"), tasks);

        // /reminders scope: only reminders.
        var rem = getContent(GET("/api/tasks/stats?payloadType=reminder"));
        assertTrue(rem.contains("\"pendingCount\":1"), rem);
        assertTrue(rem.contains("\"runsToday\":1"), rem);
    }

    /**
     * Seed a mix: one automation task pending + one running (with a COMPLETED
     * run today), and one reminder pending + one reminder completed (with a
     * COMPLETED run today). Lets the scoping test pin each filter independently.
     */
    private static void seedMixed() {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var agent = new Agent();
                    agent.name = "mixed-agent";
                    agent.modelProvider = "openrouter";
                    agent.modelId = "gpt-4.1";
                    agent.enabled = true;
                    agent.save();

                    // Automation tasks (payloadType null)
                    mkTask(agent, "task-pending", Task.Status.PENDING);
                    var taskRunning = mkTask(agent, "task-running", Task.Status.RUNNING);
                    mkRun(taskRunning, TaskRun.Status.COMPLETED, 1000L);

                    // Reminders (payloadType = reminder)
                    mkTask(agent, "rem-pending", Task.Status.PENDING, "reminder");
                    var remFired = mkTask(agent, "rem-fired", Task.Status.COMPLETED, "reminder");
                    mkRun(remFired, TaskRun.Status.COMPLETED, 4L);
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
