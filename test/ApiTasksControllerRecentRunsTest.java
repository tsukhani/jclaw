import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Task;
import models.TaskRun;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Functional HTTP tests for {@code GET /api/task-runs/recent} (JCLAW-22 slice
 * TL): recent TaskRuns across all tasks for the Timeline view, windowed by
 * the {@code hours} param (default 24) and carrying the parent task name.
 */
class ApiTasksControllerRecentRunsTest extends FunctionalTest {

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
     * Seed one task with two runs: a COMPLETED run started now (inside the
     * default 24h window) and a FAILED run started 48h ago (outside it).
     */
    private static void seedRuns() {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var agent = new Agent();
                    agent.name = "tl-agent";
                    agent.modelProvider = "openrouter";
                    agent.modelId = "gpt-4.1";
                    agent.enabled = true;
                    agent.save();

                    var task = new Task();
                    task.agent = agent;
                    task.name = "tl-task";
                    task.type = Task.Type.IMMEDIATE;
                    task.status = Task.Status.COMPLETED;
                    task.nextRunAt = Instant.now();
                    task.save();

                    var recent = new TaskRun();
                    recent.task = task;
                    recent.startedAt = Instant.now();
                    recent.completedAt = Instant.now();
                    recent.durationMs = 1000L;
                    recent.status = TaskRun.Status.COMPLETED;
                    recent.save();

                    var old = new TaskRun();
                    old.task = task;
                    old.startedAt = Instant.now().minusSeconds(48L * 3600);
                    old.completedAt = old.startedAt.plusSeconds(2);
                    old.durationMs = 2000L;
                    old.status = TaskRun.Status.FAILED;
                    old.save();
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
    void returnsRecentRunsWithinDefaultWindow() {
        seedRuns();

        var resp = GET("/api/task-runs/recent");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"taskName\":\"tl-task\""), body);
        assertTrue(body.contains("\"status\":\"COMPLETED\""), body); // recent run present
        assertFalse(body.contains("\"status\":\"FAILED\""), body);   // 48h-old run excluded
    }

    @Test
    void widerWindowIncludesOlderRuns() {
        seedRuns();

        var resp = GET("/api/task-runs/recent?hours=72");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"FAILED\""), body); // 48h-old run now included
    }

    @Test
    void emptyWhenNoRuns() {
        var resp = GET("/api/task-runs/recent");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }
}
