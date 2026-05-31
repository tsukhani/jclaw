import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.*;
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

    /**
     * Every lifecycle action (cancel/pause/resume/run) returns 404 when the
     * task id doesn't exist. Single matrix covers all four endpoints.
     */
    @ParameterizedTest(name = "{0}Returns404ForUnknownTask")
    @ValueSource(strings = {"cancel", "pause", "resume", "run", "reenable"})
    void lifecycleActionReturns404ForUnknownTask(String action) {
        var resp = POST("/api/tasks/999999/" + action, "application/json", "");
        assertEquals(404, resp.status.intValue());
    }

    /**
     * Cancel, pause, and resume all reject with 400 when invoked on a task
     * that's already in a terminal state (CANCELLED). The setup pattern
     * (seed agent + task + initial cancel) and the assertion shape are
     * identical across the three.
     */
    @ParameterizedTest(name = "{0}RejectsNonPendingTaskWith400")
    @ValueSource(strings = {"cancel", "pause", "resume"})
    void lifecycleActionRejectsNonPendingTaskWith400(String action) {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, action + "-after-cancel", "every 1h");
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));
        var resp = POST("/api/tasks/" + taskId + "/" + action, "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    // --- cancel ---

    @Test
    void cancelTransitionsPendingToCancelled() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "cancel-me", "every 1h");

        var resp = POST("/api/tasks/" + taskId + "/cancel", "application/json", "");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"CANCELLED\""),
                "task must report CANCELLED: " + getContent(resp));
    }

    // --- pause ---

    @Test
    void pauseSucceedsForPendingTask() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "pause-me", "every 1h");
        var resp = POST("/api/tasks/" + taskId + "/pause", "application/json", "");
        assertIsOk(resp);
    }

    // --- resume ---

    @Test
    void resumeSucceedsForPendingTask() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "resume-me", "every 1h");
        // Pause first then resume — exercises both flag flips.
        assertIsOk(POST("/api/tasks/" + taskId + "/pause", "application/json", ""));
        var resp = POST("/api/tasks/" + taskId + "/resume", "application/json", "");
        assertIsOk(resp);
    }

    // --- reenable ---

    @Test
    void reenableRevivesCancelledRecurringTaskToActive() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "reenable-me", "every 1h");
        // Cancel first so the task is in the only state reenable accepts.
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));

        var resp = POST("/api/tasks/" + taskId + "/reenable", "application/json", "");
        assertIsOk(resp);
        // Recurring (INTERVAL) revives to ACTIVE; the schedule is re-armed.
        assertTrue(getContent(resp).contains("\"status\":\"ACTIVE\""),
                "re-enabled recurring task must report ACTIVE: " + getContent(resp));
    }

    @Test
    void reenableRejectsLiveTaskWith400() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "reenable-live", "every 1h");
        // Never cancelled — reenable on a live task is a client error.
        var resp = POST("/api/tasks/" + taskId + "/reenable", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }
}
