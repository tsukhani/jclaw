import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.EventLogger;

import java.util.regex.Pattern;

/**
 * Functional verification that the four "legacy" id-addressed write
 * endpoints — /cancel, /pause, /resume, /retry — emit the matching
 * TASK_MGMT_* audit category. Each was wired pre-JCLAW-294-audit-AC
 * and got its event added in commit 10 of this story.
 *
 * <p>Sibling audit assertions for /create, /update, /run live in the
 * per-endpoint tests (ApiTasksControllerCreateTest etc.) so each
 * endpoint's happy/error/audit triad stays co-located. This file
 * exists because the four endpoints above didn't have dedicated
 * test files of their own.
 */
class ApiTasksControllerAuditTest extends FunctionalTest {

    private static final String AGENT_NAME = "audit-agent";

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
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(AGENT_NAME));
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

    private static long auditCountOfPost(String url, String category, String namePattern) {
        var events = EventLogger.captureMatchingForTest(e -> AGENT_NAME.equals(e.agentId()),
                _ -> assertIsOk(POST(url, "application/json", "")));
        return events.stream()
                .filter(e -> e.category().equals(category) && e.message().contains(namePattern))
                .count();
    }

    /**
     * Apply a Task mutation in a fresh tx so it commits before the next
     * HTTP call sees it. The FunctionalTest carrier-thread's outer tx
     * doesn't commit until the test method ends, so direct mutations
     * stay invisible to the controller's HTTP thread.
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
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    @Test
    void cancelEmitsTaskMgmtDelete() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audit-cancel", "every 1h");

        assertEquals(1L, auditCountOfPost("/api/tasks/" + taskId + "/cancel", "TASK_MGMT_DELETE", "audit-cancel"));
    }

    @Test
    void pauseEmitsTaskMgmtPause() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audit-pause", "every 1h");

        assertEquals(1L, auditCountOfPost("/api/tasks/" + taskId + "/pause", "TASK_MGMT_PAUSE", "audit-pause"));
    }

    @Test
    void resumeEmitsTaskMgmtResume() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audit-resume", "every 1h");

        assertEquals(1L, auditCountOfPost("/api/tasks/" + taskId + "/resume", "TASK_MGMT_RESUME", "audit-resume"));
    }

    @Test
    void retryEmitsTaskMgmtManualRun() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audit-retry", "every 1h");

        // Flip to FAILED so /retry's guard accepts.
        mutateAndCommit(taskId, t -> {
            t.status = Task.Status.FAILED;
            t.retryCount = 3;
            t.lastError = "exhausted";
        });

        // /retry shares the MANUAL_RUN category with /run — same operator
        // intent ("re-fire this task"). The category enumeration in the AC
        // is six values, not seven; lumping retry under MANUAL_RUN keeps
        // the timeline coherent.
        assertEquals(1L, auditCountOfPost("/api/tasks/" + taskId + "/retry", "TASK_MGMT_MANUAL_RUN", "audit-retry"));
    }
}
