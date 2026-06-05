import org.junit.jupiter.api.*;
import play.test.*;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import models.EventLog;
import services.EventLogger;

import java.io.ByteArrayInputStream;
import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code PATCH /api/tasks/{id}} (JCLAW-294
 * commit 5). Mirrors the create-endpoint test's shape: AuthFixture +
 * login, agent seed via POST /api/agents, task seed via POST /api/tasks,
 * then exercise PATCH with each patchable field plus the validation /
 * audit cases.
 */
class ApiTasksControllerUpdateTest extends FunctionalTest {

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
                {"name": "task-update-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
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
     * Play 1.x FunctionalTest ships GET / POST / PUT / DELETE helpers
     * but no PATCH. Mirror the PUT implementation locally — same shape,
     * different verb. {@code FunctionalTest.savedCookies} is private
     * static so we reflect to pull it in; the alternative is
     * contributing PATCH upstream to the play1 fork, which belongs in
     * a separate commit.
     */
    @SuppressWarnings("unchecked")
    private static Response PATCH(Object url, String contentType, String body) {
        Request request = newRequest();
        String turl = url.toString();
        int q = turl.indexOf('?');
        String path = (q >= 0) ? turl.substring(0, q) : turl;
        String qs = (q >= 0) ? turl.substring(q + 1) : "";
        request.method = "PATCH";
        request.contentType = contentType;
        request.url = turl;
        request.path = path;
        request.querystring = qs;
        request.body = new ByteArrayInputStream(body.getBytes());
        try {
            var f = FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            var sc = (java.util.Map<String, play.mvc.Http.Cookie>) f.get(null);
            if (sc != null) request.cookies = sc;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not read FunctionalTest.savedCookies", e);
        }
        return makeRequest(request);
    }

    // --- Happy paths ---

    @Test
    void updateDescriptionOnly() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "t", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"description": "new body"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"description\":\"new body\"", resp);
        // Schedule fields untouched.
        assertContentMatch("\"type\":\"IMMEDIATE\"", resp);
        assertContentMatch("\"scheduleDisplay\":\"now\"", resp);
    }

    @Test
    void updateScheduleSwitchesType() {
        var agent = seedAgent();
        // Seed as INTERVAL, then switch to CRON.
        var taskId = seedTask(agent, "switcher", "every 1h");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"schedule": "@daily"}
                """);
        assertIsOk(resp);
        var body = getContent(resp);
        assertContentMatch("\"type\":\"CRON\"", resp);
        assertContentMatch("\"cronExpression\":\"@daily\"", resp);
        assertContentMatch("\"scheduleDisplay\":\"@daily\"", resp);
        // Cleared intervalSeconds (was 3600 from seed).
        assertTrue(body.contains("\"intervalSeconds\":null"),
                "intervalSeconds should be cleared on type switch; got: " + body);
    }

    @Test
    void updatePausedFlipsTheField() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "p", "every 1h");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"paused": true}
                """);
        assertIsOk(resp);
        assertContentMatch("\"paused\":true", resp);
    }

    @Test
    void updatePlumbingFieldsRoundTrip() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "plumb", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"delivery": "slack:#ops", "payloadType": "markdown",
                 "modelProvider": "openrouter", "modelId": "claude-haiku-4-5",
                 "workdir": "/tmp/x", "noAgent": true, "repeatLimit": 10}
                """);
        assertIsOk(resp);
        assertContentMatch("\"delivery\":\"slack:#ops\"", resp);
        assertContentMatch("\"payloadType\":\"markdown\"", resp);
        assertContentMatch("\"modelProvider\":\"openrouter\"", resp);
        assertContentMatch("\"modelId\":\"claude-haiku-4-5\"", resp);
        assertContentMatch("\"workdir\":\"/tmp/x\"", resp);
        assertContentMatch("\"noAgent\":true", resp);
        assertContentMatch("\"repeatLimit\":10", resp);
    }

    @Test
    void explicitNullClearsField() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "cleartest", "every 1h");

        // First set a delivery + repeatLimit
        assertIsOk(PATCH("/api/tasks/" + taskId, "application/json", """
                {"delivery": "telegram:1", "repeatLimit": 5}
                """));

        // Then clear them via explicit null.
        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"delivery": null, "repeatLimit": null}
                """);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"delivery\":null"),
                "delivery should clear to null; got: " + body);
        assertTrue(body.contains("\"repeatLimit\":null"),
                "repeatLimit should clear to null; got: " + body);
    }

    // --- 400 / 404 paths ---

    @Test
    void rejectsNonexistentTask() {
        var resp = PATCH("/api/tasks/999999", "application/json", """
                {"description": "irrelevant"}
                """);
        assertStatus(404, resp);
    }

    @Test
    void rejectsInvalidSchedule() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "t", "now");

        // Unix 5-field cron should surface the prepend-0 hint
        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"schedule": "0 9 \\u002A \\u002A \\u002A"}
                """);
        assertStatus(400, resp);
        assertTrue(getContent(resp).contains("5 fields"),
                "400 body should call out 5-field count");
    }

    @Test
    void rejectsEmptyBody() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "t", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", "{}");
        assertStatus(400, resp);
    }

    @Test
    void rejectsUnrecognizedOnlyBody() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "t", "now");

        // Body has fields but none are patchable.
        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"unknownField": "value", "anotherUnknown": 5}
                """);
        assertStatus(400, resp);
    }

    // --- Audit ---

    @Test
    void emitsTaskMgmtUpdateEvent() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audited-update", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"description": "after update"}
                """);
        assertIsOk(resp);
        EventLogger.flush();
        long count = EventLog.count(
                "category = ?1 AND message LIKE ?2",
                "TASK_MGMT_UPDATE", "%audited-update%");
        assertEquals(1L, count, "expected exactly one TASK_MGMT_UPDATE event");
    }

    // --- Remaining optional-string field branches ---

    @Test
    void updatesEnabledToolNames() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "tools-task", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"enabledToolNames": "filesystem,exec"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"enabledToolNames\":\"filesystem,exec\"", resp);
    }

    @Test
    void updatesPreCheck() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "precheck-task", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"preCheck": "exit 0"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"preCheck\":\"exit 0\"", resp);
    }

    @Test
    void updatesScript() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "script-task", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"script": "echo hello"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"script\":\"echo hello\"", resp);
    }

    @Test
    void updatesContextFromTaskIds() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "ctx-task", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"contextFromTaskIds": "1,2,3"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"contextFromTaskIds\":\"1,2,3\"", resp);
    }

    @Test
    void updatesMultipleOptionalStringFieldsInOnePatch() {
        // One PATCH carries enabledToolNames + preCheck + script — exercises
        // multiple sequential body.has() branches in the same request.
        var agent = seedAgent();
        var taskId = seedTask(agent, "multi-field-task", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"enabledToolNames": "filesystem", "preCheck": "test -d /tmp", "script": "ls"}
                """);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"enabledToolNames\":\"filesystem\""));
        assertTrue(body.contains("\"preCheck\":\"test -d /tmp\""));
        assertTrue(body.contains("\"script\":\"ls\""));
    }

    // --- JCLAW-426: name rename ---

    @Test
    void updateNameRenamesTask() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "old-name", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"name": "new-name"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"name\":\"new-name\"", resp);
    }

    @Test
    void rejectsBlankName() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "keep", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"name": "   "}
                """);
        assertStatus(400, resp);
        assertTrue(getContent(resp).contains("non-blank"),
                "400 body should explain name must be non-blank");
    }

    @Test
    void rejectsExplicitNullName() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "keep", "now");

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"name": null}
                """);
        assertStatus(400, resp);
    }

    @Test
    void rejectsDuplicateRecurringRename() {
        var agent = seedAgent();
        seedTask(agent, "alpha", "every 1h");
        var beta = seedTask(agent, "beta", "every 1h");

        // Renaming beta onto alpha's name collides (both recurring, same agent).
        var resp = PATCH("/api/tasks/" + beta, "application/json", """
                {"name": "alpha"}
                """);
        assertStatus(409, resp);
        assertTrue(getContent(resp).contains("already exists"),
                "409 body should name the conflict");
    }

    @Test
    void allowsRenameToOwnCurrentName() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "samename", "every 1h");

        // Self is excluded from the duplicate check, so renaming to the current
        // name is accepted (not a 409 against itself).
        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"name": "samename"}
                """);
        assertIsOk(resp);
        assertContentMatch("\"name\":\"samename\"", resp);
    }
}
