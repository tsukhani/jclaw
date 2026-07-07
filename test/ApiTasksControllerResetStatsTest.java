import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import models.Agent;
import models.Task;
import models.TaskRun;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Functional HTTP tests for {@code POST /api/task-runs/reset}: hard-delete
 * terminal (non-RUNNING) task runs to reset the run-derived KPIs, keeping
 * in-flight runs and honouring the same {@code payloadType} scope the stats
 * endpoint uses.
 */
class ApiTasksControllerResetStatsTest extends FunctionalTest {

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

    @Test
    void resetDeletesTerminalRunsButKeepsRunning() {
        seed(() -> {
            var agent = mkAgent("reset-agent");
            var task = mkTask(agent, "reset-task", Task.Status.RUNNING, null);
            mkRun(task, TaskRun.Status.COMPLETED, 1000L);
            mkRun(task, TaskRun.Status.COMPLETED, 3000L);
            mkRun(task, TaskRun.Status.FAILED, null);
            mkRun(task, TaskRun.Status.RUNNING, null);
        });

        // Before: 4 runs today (2 completed, 1 failed, 1 running).
        assertTrue(getContent(GET("/api/tasks/stats")).contains("\"runsToday\":4"));

        var resp = POST("/api/task-runs/reset");
        assertIsOk(resp);
        // The three terminal runs are deleted; the RUNNING one is left alone.
        assertTrue(getContent(resp).contains("\"deletedRuns\":3"), getContent(resp));

        var after = getContent(GET("/api/tasks/stats"));
        assertTrue(after.contains("\"runsToday\":1"), after);
    }

    @Test
    void resetHonoursExcludePayloadTypeScope() {
        seed(() -> {
            var agent = mkAgent("scope-agent");
            var automation = mkTask(agent, "automation", Task.Status.RUNNING, null);
            mkRun(automation, TaskRun.Status.COMPLETED, 1000L);
            var reminder = mkTask(agent, "reminder", Task.Status.COMPLETED, "reminder");
            mkRun(reminder, TaskRun.Status.COMPLETED, 2000L);
        });

        // /tasks-scoped reset must delete the automation run but not the reminder.
        var resp = POST("/api/task-runs/reset?excludePayloadType=reminder");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deletedRuns\":1"), getContent(resp));

        var tasks = getContent(GET("/api/tasks/stats?excludePayloadType=reminder"));
        assertTrue(tasks.contains("\"runsToday\":0"), tasks);   // automation run deleted
        var rem = getContent(GET("/api/tasks/stats?payloadType=reminder"));
        assertTrue(rem.contains("\"runsToday\":1"), rem);       // reminder run survived
    }

    // === Seeding (commit in a fresh tx so the controller thread sees it) ===

    private static void seed(Runnable body) {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    body.run();
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
    }

    private static Agent mkAgent(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
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
}
