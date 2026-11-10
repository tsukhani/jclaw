import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiTasksController;
import controllers.RequestPrincipal;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.EventLogger;
import services.Tx;
import tools.JClawApiTool;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An agent's {@code GET /api/tasks} rows carry a bounded description preview, the operator's stay whole, and
 * {@code GET /api/tasks/{id}} returns one task whole to either (JF-144).
 */
class ApiTasksControllerAgentReadTest extends FunctionalTest {

    private static final String AT_BOUND = "0123456789".repeat(ApiTasksController.DESCRIPTION_PREVIEW_CHARS / 10);
    private static final String LONG_DESCRIPTION = AT_BOUND.repeat(25);

    private static final Set<String> TASK_VIEW_KEYS = Set.of(
            "id", "name", "description", "type", "status", "cronExpression", "intervalSeconds", "scheduleDisplay",
            "retryCount", "maxRetries", "lastError", "nextRunAt", "lastFiredAt", "createdAt", "agentId", "agentName",
            "paused", "delivery", "payloadType", "modelProvider", "modelId", "enabledToolNames", "workdir",
            "preCheck", "script", "noAgent", "autoDeleteOnComplete", "contextFromTaskIds", "repeatLimit",
            "timezone", "effectiveTimezone", "runningRunId", "originChannel", "failedOccurrences", "pauseReason");

    private Long agentId;

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
        var resp = POST("/api/agents", "application/json", """
                {"name": "task-agent-read-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        agentId = JsonParser.parseString(getContent(resp)).getAsJsonObject().get("id").getAsLong();
    }

    @AfterEach
    void tearDown() {
        clearCookies();
        EventLogger.flush();
    }

    @Test
    void anAgentsListRowKeepsADescriptionAtTheBoundWholeAndUnmarked() {
        createTask("at-bound", AT_BOUND);
        var operatorRow = onlyRow(GET("/api/tasks"));

        var resp = asAgent(() -> GET(agentRequest(), "/api/tasks"));
        var row = onlyRow(resp);

        assertEquals("1", resp.getHeader("X-Total-Count"));
        assertEquals(AT_BOUND, row.get("descriptionPreview").getAsString());
        assertFalse(row.get("descriptionTruncated").getAsBoolean());
        assertEquals(operatorRow, withoutPreview(row, operatorRow.get("description").getAsString()));
    }

    @Test
    void anAgentsListRowCutsADescriptionOneCharacterOverTheBoundAndMarksTheCut() {
        createTask("over-bound", AT_BOUND + "X");
        var operatorRow = onlyRow(GET("/api/tasks"));

        var row = onlyRow(asAgent(() -> GET(agentRequest(), "/api/tasks")));

        assertEquals(AT_BOUND + "…", row.get("descriptionPreview").getAsString());
        assertTrue(row.get("descriptionTruncated").getAsBoolean());
        assertEquals(operatorRow, withoutPreview(row, AT_BOUND + "X"));
    }

    @Test
    void anAgentsListRowForATaskCreatedWithoutADescriptionHasAnEmptyUnmarkedPreview() {
        createTask("no-description", null);
        var operatorRow = onlyRow(GET("/api/tasks"));

        var row = onlyRow(asAgent(() -> GET(agentRequest(), "/api/tasks")));

        assertEquals("", row.get("descriptionPreview").getAsString());
        assertFalse(row.get("descriptionTruncated").getAsBoolean());
        assertEquals(operatorRow, withoutPreview(row, ""));
    }

    @Test
    void anAgentsListRowForATaskWithANullDescriptionHasANullUnmarkedPreview() {
        var id = createTask("null-description", "set aside");
        commitInFreshTx(() -> {
            Task task = Task.findById(id);
            task.description = null;
            task.save();
        });

        var row = onlyRow(asAgent(() -> GET(agentRequest(), "/api/tasks")));

        assertTrue(row.get("descriptionPreview").isJsonNull(), row.toString());
        assertFalse(row.get("descriptionTruncated").getAsBoolean());
        assertFalse(row.has("description"), row.toString());
    }

    @Test
    void theOperatorsListRowIsUnchangedAndCarriesTheWholeDescription() {
        var id = createTask("whole", LONG_DESCRIPTION);

        var row = onlyRow(GET("/api/tasks"));

        assertEquals(TASK_VIEW_KEYS, row.keySet());
        assertEquals(id, row.get("id").getAsLong());
        assertEquals(LONG_DESCRIPTION, row.get("description").getAsString());
    }

    @Test
    void anAgentReadsOneTaskWhole() {
        var id = createTask("whole", LONG_DESCRIPTION);
        var operatorRow = onlyRow(GET("/api/tasks"));

        var resp = asAgent(() -> GET(agentRequest(), "/api/tasks/" + id));

        assertIsOk(resp);
        var task = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals(LONG_DESCRIPTION, task.get("description").getAsString());
        assertEquals(operatorRow, task);
    }

    @Test
    void theOperatorReadsOneTaskWhole() {
        var id = createTask("whole", LONG_DESCRIPTION);
        createTask("other", "another task");
        var listed = JsonParser.parseString(getContent(GET("/api/tasks"))).getAsJsonArray();
        JsonObject operatorRow = null;
        for (var el : listed) {
            if (el.getAsJsonObject().get("id").getAsLong() == id) operatorRow = el.getAsJsonObject();
        }

        var resp = GET("/api/tasks/" + id);

        assertIsOk(resp);
        var task = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals(TASK_VIEW_KEYS, task.keySet());
        assertEquals("whole", task.get("name").getAsString());
        assertEquals(LONG_DESCRIPTION, task.get("description").getAsString());
        assertEquals(operatorRow, task);
    }

    @Test
    void anUnknownTaskIdIsNotFoundForTheOperatorAndForAnAgent() {
        createTask("present", "a task");

        assertIsNotFound(GET("/api/tasks/999999"));
        assertIsNotFound(asAgent(() -> GET(agentRequest(), "/api/tasks/999999")));
    }

    @Test
    void theStatsRouteStillResolvesBesideTheSingleRead() {
        createTask("present", "a task");

        var resp = GET("/api/tasks/stats");

        assertIsOk(resp);
        assertEquals(Set.of("runsToday", "successRate", "avgDurationMs", "pendingCount", "runningCount",
                        "pausedCount", "activeCount", "failedCount", "retentionDays"),
                JsonParser.parseString(getContent(resp)).getAsJsonObject().keySet());
    }

    @Test
    void todaysTaskCountFitsUnderTheJClawApiCapForAnAgent() {
        // 19 tasks on the live instance on 2026-11-11.
        for (int i = 0; i < 19; i++) createTask("task-" + i, LONG_DESCRIPTION);

        var operatorBody = getContent(GET("/api/tasks"));
        var agentBody = getContent(asAgent(() -> GET(agentRequest(), "/api/tasks")));

        assertEquals(19, JsonParser.parseString(agentBody).getAsJsonArray().size());
        assertTrue(agentBody.length() < JClawApiTool.MAX_RESPONSE_CHARS, "agent list chars: " + agentBody.length());
        assertTrue(operatorBody.length() > JClawApiTool.MAX_RESPONSE_CHARS,
                "the whole rows must overflow the cap, or this measures nothing: " + operatorBody.length());
    }

    // ==================== helpers ====================

    private long createTask(String name, String description) {
        var body = new JsonObject();
        body.addProperty("agentId", agentId);
        body.addProperty("name", name);
        body.addProperty("schedule", "every 1h");
        if (description != null) body.addProperty("description", description);
        var resp = POST("/api/tasks", "application/json", body.toString());
        assertIsOk(resp);
        return JsonParser.parseString(getContent(resp)).getAsJsonObject().get("id").getAsLong();
    }

    private JsonObject onlyRow(Http.Response resp) {
        assertIsOk(resp);
        var rows = JsonParser.parseString(getContent(resp)).getAsJsonArray();
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0).getAsJsonObject();
    }

    /** The agent's row put back into the operator's shape, so every field but the preview must match. */
    private static JsonObject withoutPreview(JsonObject agentRow, String description) {
        var keys = new HashSet<>(TASK_VIEW_KEYS);
        keys.remove("description");
        keys.add("descriptionPreview");
        keys.add("descriptionTruncated");
        assertEquals(keys, agentRow.keySet());
        var row = agentRow.deepCopy();
        row.remove("descriptionPreview");
        row.remove("descriptionTruncated");
        row.addProperty("description", description);
        return row;
    }

    private static void commitInFreshTx(Runnable block) {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                Tx.run(block);
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Http.Request agentRequest() {
        var request = newRequest();
        request.headers.put("authorization",
                new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A bearer call leaves an agent-principal cookie in the JVM-global jar; clear it, then sign the operator back in. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
            login();
        }
    }
}
