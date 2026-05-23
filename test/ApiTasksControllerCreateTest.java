import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.*;
import services.EventLogger;

import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code POST /api/tasks} (JCLAW-294 commit 4).
 * Covers: happy paths for each Task.Type via the schedule shorthand,
 * plumbing-field round-trip, 400 validation errors, 409 duplicate-name
 * conflict on recurring tasks, and audit-event emission.
 *
 * <p>Drives the controller via {@code POST(url, contentType, body)} the
 * way {@code ApiAgentsControllerTest} does; agent seed and login share
 * the existing AuthFixture pattern. Each test deletes the DB up-front
 * so they're independent.
 */
class ApiTasksControllerCreateTest extends FunctionalTest {

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
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "task-create-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        var id = extractId(getContent(resp));
        return Long.parseLong(id);
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    // --- Happy paths ---

    @Test
    void createImmediate() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "immediate-task", "description": "Do it now",
                 "schedule": "now"}
                """.formatted(agentId));
        assertIsOk(resp);
        assertContentMatch("\"type\":\"IMMEDIATE\"", resp);
        assertContentMatch("\"name\":\"immediate-task\"", resp);
        assertContentMatch("\"scheduleDisplay\":\"now\"", resp);
        assertContentMatch("\"status\":\"PENDING\"", resp);
        // Plumbing fields default to null/false.
        assertContentMatch("\"noAgent\":false", resp);
    }

    @Test
    void createScheduledBareDuration() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "later-task", "schedule": "2h"}
                """.formatted(agentId));
        assertIsOk(resp);
        assertContentMatch("\"type\":\"SCHEDULED\"", resp);
        assertContentMatch("\"scheduleDisplay\":\"2h\"", resp);
    }

    @Test
    void createIntervalEveryDuration() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "every-30m", "schedule": "every 30m"}
                """.formatted(agentId));
        assertIsOk(resp);
        assertContentMatch("\"type\":\"INTERVAL\"", resp);
        assertContentMatch("\"intervalSeconds\":1800", resp);
        assertContentMatch("\"scheduleDisplay\":\"every 30m\"", resp);
    }

    @Test
    void createCronSpring6Field() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "morning-task", "schedule": "0 0 9 * * *"}
                """.formatted(agentId));
        assertIsOk(resp);
        assertContentMatch("\"type\":\"CRON\"", resp);
        assertContentMatch("\"cronExpression\":\"0 0 9 \\* \\* \\*\"", resp);
    }

    @Test
    void createCronAtShortcut() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "daily-task", "schedule": "@daily"}
                """.formatted(agentId));
        assertIsOk(resp);
        assertContentMatch("\"type\":\"CRON\"", resp);
        assertContentMatch("\"cronExpression\":\"@daily\"", resp);
    }

    @Test
    void createWithPlumbingFieldsRoundTrips() {
        var agentId = seedAgent();
        // Plumbing-ahead fields land verbatim (JCLAW-294 doesn't read them at fire time;
        // future stories consume).
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "full-task", "schedule": "@hourly",
                 "delivery": "telegram:12345", "payloadType": "json",
                 "modelProvider": "openrouter", "modelId": "claude-sonnet-4-6",
                 "enabledToolNames": "[\\"web_search\\"]", "workdir": "/tmp/wd",
                 "preCheck": "true", "script": "echo hi", "noAgent": true,
                 "contextFromTaskIds": "[1,2]", "repeatLimit": 5}
                """.formatted(agentId));
        assertIsOk(resp);
        assertContentMatch("\"delivery\":\"telegram:12345\"", resp);
        assertContentMatch("\"payloadType\":\"json\"", resp);
        assertContentMatch("\"modelProvider\":\"openrouter\"", resp);
        assertContentMatch("\"modelId\":\"claude-sonnet-4-6\"", resp);
        assertContentMatch("\"workdir\":\"/tmp/wd\"", resp);
        assertContentMatch("\"noAgent\":true", resp);
        assertContentMatch("\"repeatLimit\":5", resp);
    }

    // --- 400 paths ---

    @Test
    void rejectsMissingAgentId() {
        var resp = POST("/api/tasks", "application/json", """
                {"name": "x", "schedule": "now"}
                """);
        assertStatus(400, resp);
    }

    @Test
    void rejectsNonexistentAgent() {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": 999999, "name": "x", "schedule": "now"}
                """);
        assertStatus(400, resp);
    }

    /**
     * Four validation rejections that share the same seedAgent + POST + 400
     * skeleton: missing name, blank name, missing schedule, garbage schedule
     * (the last is the @Test below).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "rejectsMissingName     | {\"agentId\": %d, \"schedule\": \"now\"}",
            "rejectsBlankName       | {\"agentId\": %d, \"name\": \"   \", \"schedule\": \"now\"}",
            "rejectsMissingSchedule | {\"agentId\": %d, \"name\": \"x\"}",
            "rejectsGarbageSchedule | {\"agentId\": %d, \"name\": \"x\", \"schedule\": \"tomorrow\"}"
    })
    void rejectsInvalidTaskBody(String label, String bodyTemplate) {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", bodyTemplate.formatted(agentId));
        assertStatus(400, resp);
    }

    @Test
    void rejectsUnixFiveFieldCronWithPrependHint() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "x", "schedule": "0 9 \\u002A \\u002A \\u002A"}
                """.formatted(agentId));
        assertStatus(400, resp);
        var body = getContent(resp);
        // Hint should surface the 5-field count and the prepend-0 fix.
        assertTrue(body.contains("5 fields"),
                "400 body should call out 5-field count; got: " + body);
    }

    // --- 409 conflict (recurring duplicate) ---

    @Test
    void rejectsDuplicateRecurringIntervalName() {
        var agentId = seedAgent();
        var first = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "dup-task", "schedule": "every 1h"}
                """.formatted(agentId));
        assertIsOk(first);
        var firstId = extractId(getContent(first));

        var second = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "dup-task", "schedule": "every 2h"}
                """.formatted(agentId));
        assertStatus(409, second);
        // Error body should call out the conflicting id so operators can target it.
        var body = getContent(second);
        assertTrue(body.contains("id=" + firstId),
                "409 body should include conflicting id; got: " + body);
    }

    @Test
    void rejectsDuplicateRecurringCronName() {
        var agentId = seedAgent();
        var first = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "dup-cron", "schedule": "@hourly"}
                """.formatted(agentId));
        assertIsOk(first);

        // Even with a different cron expression, same (name, agent) on a
        // recurring type collides.
        var second = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "dup-cron", "schedule": "@daily"}
                """.formatted(agentId));
        assertStatus(409, second);
    }

    @Test
    void allowsDuplicateOneShotName() {
        var agentId = seedAgent();
        // IMMEDIATE + SCHEDULED are one-shot — duplicate names are inert and accepted.
        assertIsOk(POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "same-name", "schedule": "now"}
                """.formatted(agentId)));
        assertIsOk(POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "same-name", "schedule": "now"}
                """.formatted(agentId)));
        assertIsOk(POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "same-name", "schedule": "1h"}
                """.formatted(agentId)));
    }

    @Test
    void recurringNameAcrossDifferentAgentsIsNotAConflict() {
        var agentA = seedAgent();
        // Seed a second agent.
        var resp = POST("/api/agents", "application/json", """
                {"name": "task-create-agent-2", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        var agentB = Long.parseLong(extractId(getContent(resp)));

        assertIsOk(POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "shared-name", "schedule": "every 1h"}
                """.formatted(agentA)));
        // Same name on a different agent is fine — multi-tenancy isolation.
        assertIsOk(POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "shared-name", "schedule": "every 1h"}
                """.formatted(agentB)));
    }

    // --- Audit ---

    @Test
    void emitsTaskMgmtCreateEvent() {
        var agentId = seedAgent();
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "audited-task", "schedule": "now"}
                """.formatted(agentId));
        assertIsOk(resp);
        EventLogger.flush();
        // event_log row with category TASK_MGMT_CREATE should mention the task name.
        long count = models.EventLog.count(
                "category = ?1 AND message LIKE ?2",
                "TASK_MGMT_CREATE", "%audited-task%");
        assertEquals(1L, count, "expected exactly one TASK_MGMT_CREATE event");
    }
}
