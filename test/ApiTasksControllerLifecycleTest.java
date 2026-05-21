import org.junit.jupiter.api.*;
import play.test.*;
import models.Task;
import services.EventLogger;

import java.util.regex.Pattern;

/**
 * Functional HTTP tests for the lifecycle endpoints:
 *   POST /api/tasks/{id}/cancel
 *   POST /api/tasks/{id}/pause
 *   POST /api/tasks/{id}/resume
 *   POST /api/tasks/{id}/run
 * Mirrors the {@code ApiTasksControllerUpdateTest} setup pattern.
 */
class ApiTasksControllerLifecycleTest extends FunctionalTest {

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
                {"name": "task-lifecycle-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
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

    // --- cancel ---

    @Test
    void cancelReturns404ForUnknownTask() {
        var resp = POST("/api/tasks/999999/cancel", "application/json", "");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "cancel-me", "every 1h");

        var resp = POST("/api/tasks/" + taskId + "/cancel", "application/json", "");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"CANCELLED\""),
                "task must report CANCELLED: " + getContent(resp));
    }

    @Test
    void cancelRejectsNonPendingTaskWith400() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "double-cancel", "every 1h");
        // First cancel moves to CANCELLED.
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));
        // Second cancel must reject since task is no longer PENDING.
        var resp = POST("/api/tasks/" + taskId + "/cancel", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    // --- pause ---

    @Test
    void pauseReturns404ForUnknownTask() {
        var resp = POST("/api/tasks/999999/pause", "application/json", "");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void pauseSucceedsForPendingTask() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "pause-me", "every 1h");
        var resp = POST("/api/tasks/" + taskId + "/pause", "application/json", "");
        assertIsOk(resp);
    }

    @Test
    void pauseRejectsNonPendingTaskWith400() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "pause-after-cancel", "every 1h");
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));
        var resp = POST("/api/tasks/" + taskId + "/pause", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    // --- resume ---

    @Test
    void resumeReturns404ForUnknownTask() {
        var resp = POST("/api/tasks/999999/resume", "application/json", "");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void resumeSucceedsForPendingTask() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "resume-me", "every 1h");
        // Pause first then resume — exercises both flag flips.
        assertIsOk(POST("/api/tasks/" + taskId + "/pause", "application/json", ""));
        var resp = POST("/api/tasks/" + taskId + "/resume", "application/json", "");
        assertIsOk(resp);
    }

    @Test
    void resumeRejectsNonPendingTaskWith400() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "resume-after-cancel", "every 1h");
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));
        var resp = POST("/api/tasks/" + taskId + "/resume", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    // --- run (operator-initiated immediate fire) ---

    @Test
    void runReturns404ForUnknownTask() {
        var resp = POST("/api/tasks/999999/run", "application/json", "");
        assertEquals(404, resp.status.intValue());
    }
}
