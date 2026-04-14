import org.junit.jupiter.api.*;
import play.test.*;
import play.mvc.Http.*;

/**
 * Functional HTTP tests for the 8 API controllers that previously had zero coverage:
 * ApiAgentsController, ApiProvidersController, ApiTasksController, ApiChannelsController,
 * ApiLogsController, ApiSkillsController, ApiToolsController, ApiEventsController.
 *
 * Every test method authenticates first (login), then exercises the endpoint.
 * The H2 in-memory DB is wiped between tests for isolation — the DefaultConfigJob
 * seed data does NOT survive Fixtures.deleteDatabase(), so tests that need an agent
 * create one via the API.
 */
public class ControllerApiTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    // --- Auth helper ---

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    /**
     * Create an agent via the API and return its id as a String.
     * The "main" name is reserved by the controller, so callers that need the
     * main agent must use {@link #createMainAgent()}.
     */
    private String createAgent(String name) {
        var body = """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name);
        var resp = POST("/api/agents", "application/json", body);
        assertIsOk(resp);
        return extractId(getContent(resp));
    }

    /**
     * Seed the built-in "main" agent the same way DefaultConfigJob does.
     * Since the controller rejects POST with name="main" (409), we use a
     * different name then look it up — but actually the controller checks
     * case-insensitively, so we resort to creating it through the config
     * endpoint trigger. Simplest: just call GET /api/status which triggers
     * DefaultConfigJob startup seeding if not already run, then list agents.
     *
     * In practice, within FunctionalTest the Play app is already started and
     * DefaultConfigJob has already run before the first request. But
     * Fixtures.deleteDatabase() wipes the DB. The job only runs once at
     * startup, so we must re-create the main agent manually.
     *
     * We work around the "main" name reservation by creating it with a
     * non-reserved name and using the returned id. Tests that specifically
     * need the "main" agent (e.g. delete-reject) will need to create an
     * agent and attempt operations on it.
     */
    private String createTestAgent() {
        return createAgent("test-agent");
    }

    // --- Unauthenticated access is rejected ---

    @Test
    public void allEndpointsReject403WithoutAuth() {
        assertEquals(403, GET("/api/agents").status.intValue());
        assertEquals(403, GET("/api/tasks").status.intValue());
        assertEquals(403, GET("/api/channels").status.intValue());
        assertEquals(403, GET("/api/logs").status.intValue());
        assertEquals(403, GET("/api/skills").status.intValue());
        assertEquals(403, GET("/api/tools").status.intValue());
    }

    // =====================
    // ApiAgentsController
    // =====================

    @Test
    public void agentsListReturnsJsonArray() {
        login();
        var response = GET("/api/agents");
        assertIsOk(response);
        assertContentType("application/json", response);
        // After deleteDatabase the list may be empty; verify it's a valid JSON array
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void agentsListContainsCreatedAgent() {
        login();
        createAgent("listed-agent");
        var response = GET("/api/agents");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"name\":\"listed-agent\""));
    }

    @Test
    public void agentsCrud() {
        login();

        // CREATE
        var createBody = """
                {"name": "crud-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var createResp = POST("/api/agents", "application/json", createBody);
        assertIsOk(createResp);
        assertContentType("application/json", createResp);
        var content = getContent(createResp);
        assertTrue(content.contains("\"name\":\"crud-agent\""));
        assertTrue(content.contains("\"modelProvider\":\"openrouter\""));

        var id = extractId(content);
        assertNotNull(id, "Expected an id in the create response");

        // GET by id
        var getResp = GET("/api/agents/" + id);
        assertIsOk(getResp);
        assertTrue(getContent(getResp).contains("\"name\":\"crud-agent\""));

        // UPDATE
        var updateBody = """
                {"name": "crud-agent-v2", "modelId": "gpt-4.1-nano"}
                """;
        var updateResp = PUT("/api/agents/" + id, "application/json", updateBody);
        assertIsOk(updateResp);
        assertTrue(getContent(updateResp).contains("\"name\":\"crud-agent-v2\""));
        assertTrue(getContent(updateResp).contains("\"modelId\":\"gpt-4.1-nano\""));

        // DELETE
        var deleteResp = DELETE("/api/agents/" + id);
        assertIsOk(deleteResp);
        assertTrue(getContent(deleteResp).contains("\"status\":\"ok\""));

        // GET after delete returns 404
        var afterDelete = GET("/api/agents/" + id);
        assertEquals(404, afterDelete.status.intValue());
    }

    @Test
    public void agentsCreateRejectsReservedMainName() {
        login();
        var body = """
                {"name": "main", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void agentsGetNonExistentReturns404() {
        login();
        var response = GET("/api/agents/999999");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void agentsPromptBreakdown() {
        login();
        var id = createTestAgent();
        var response = GET("/api/agents/" + id + "/prompt-breakdown");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    // =====================
    // ApiProvidersController
    // =====================

    @Test
    public void providersDiscoverModelsRequiresConfig() {
        login();
        // Provider "nonexistent" has no base URL configured, so should return 400
        var response = POST("/api/providers/nonexistent/discover-models", "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    // =====================
    // ApiTasksController
    // =====================

    @Test
    public void tasksList() {
        login();
        var response = GET("/api/tasks");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void tasksListWithFilters() {
        login();
        var response = GET("/api/tasks?status=PENDING&limit=10&offset=0");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void tasksCancelNonExistentReturns404() {
        login();
        var response = POST("/api/tasks/999999/cancel", "application/json", "{}");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void tasksRetryNonExistentReturns404() {
        login();
        var response = POST("/api/tasks/999999/retry", "application/json", "{}");
        assertEquals(404, response.status.intValue());
    }

    // =====================
    // ApiChannelsController
    // =====================

    @Test
    public void channelsList() {
        login();
        var response = GET("/api/channels");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void channelsCrud() {
        login();

        // SAVE (create) a channel config
        var saveBody = """
                {"config": {"botToken": "test-token-123"}, "enabled": true}
                """;
        var saveResp = PUT("/api/channels/telegram", "application/json", saveBody);
        assertIsOk(saveResp);
        var content = getContent(saveResp);
        assertTrue(content.contains("\"channelType\":\"telegram\""));
        assertTrue(content.contains("\"enabled\":true"));

        // GET by type
        var getResp = GET("/api/channels/telegram");
        assertIsOk(getResp);
        assertTrue(getContent(getResp).contains("\"channelType\":\"telegram\""));
        assertTrue(getContent(getResp).contains("test-token-123"));

        // UPDATE (save again with different values)
        var updateBody = """
                {"config": {"botToken": "updated-token"}, "enabled": false}
                """;
        var updateResp = PUT("/api/channels/telegram", "application/json", updateBody);
        assertIsOk(updateResp);
        assertTrue(getContent(updateResp).contains("\"enabled\":false"));
        assertTrue(getContent(updateResp).contains("updated-token"));

        // LIST should now contain the channel
        var listResp = GET("/api/channels");
        assertIsOk(listResp);
        assertTrue(getContent(listResp).contains("\"channelType\":\"telegram\""));
    }

    @Test
    public void channelsGetNonExistentReturns404() {
        login();
        var response = GET("/api/channels/nonexistent");
        assertEquals(404, response.status.intValue());
    }

    // =====================
    // ApiLogsController
    // =====================

    @Test
    public void logsList() {
        login();
        var response = GET("/api/logs");
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertTrue(content.contains("\"events\""));
        assertTrue(content.contains("\"limit\""));
        assertTrue(content.contains("\"offset\""));
    }

    @Test
    public void logsListWithFilters() {
        login();
        var response = GET("/api/logs?category=system&level=INFO&limit=10&offset=0");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void logsListWithSearchFilter() {
        login();
        var response = GET("/api/logs?search=test&limit=5");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    // =====================
    // ApiSkillsController
    // =====================

    @Test
    public void skillsList() {
        login();
        var response = GET("/api/skills");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void skillsGetNonExistentReturns404() {
        login();
        var response = GET("/api/skills/nonexistent-skill-xyz");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void skillsListForAgent() {
        login();
        var id = createTestAgent();
        var response = GET("/api/agents/" + id + "/skills");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void skillsListForNonExistentAgentReturns404() {
        login();
        var response = GET("/api/agents/999999/skills");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void skillsDeleteNonExistentReturns404() {
        login();
        var response = DELETE("/api/skills/nonexistent-skill-xyz");
        assertEquals(404, response.status.intValue());
    }

    // =====================
    // ApiToolsController
    // =====================

    @Test
    public void toolsList() {
        login();
        var response = GET("/api/tools");
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertTrue(content.startsWith("["));
    }

    @Test
    public void toolsListForAgent() {
        login();
        var id = createTestAgent();
        var response = GET("/api/agents/" + id + "/tools");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).contains("\"enabled\""));
    }

    @Test
    public void toolsListForNonExistentAgentReturns404() {
        login();
        var response = GET("/api/agents/999999/tools");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void toolsUpdateForAgent() {
        login();
        var id = createTestAgent();

        // Disable a tool
        var body = """
                {"enabled": false}
                """;
        var response = PUT("/api/agents/" + id + "/tools/exec", "application/json", body);
        assertIsOk(response);
        var content = getContent(response);
        assertTrue(content.contains("\"name\":\"exec\""));
        assertTrue(content.contains("\"enabled\":false"));
        assertTrue(content.contains("\"status\":\"ok\""));

        // Re-enable it
        var reEnableBody = """
                {"enabled": true}
                """;
        var reEnableResp = PUT("/api/agents/" + id + "/tools/exec", "application/json", reEnableBody);
        assertIsOk(reEnableResp);
        assertTrue(getContent(reEnableResp).contains("\"enabled\":true"));
    }

    // =====================
    // ApiEventsController
    // =====================

    /**
     * The SSE endpoint uses Play's async continuation (await) which blocks the
     * FunctionalTest GET() call until the stream ends (24h timeout). We cannot
     * exercise it via the standard GET() helper. Instead, verify that an
     * unauthenticated request is rejected (covered in allEndpointsReject403WithoutAuth)
     * and that the route is wired by confirming the 403 comes from AuthCheck, not a 404.
     */
    @Test
    public void eventsStreamRouteIsWired() {
        // Without auth, AuthCheck returns 403 — proving the route exists and
        // reaches the controller (a missing route would give 404).
        var response = GET("/api/events");
        assertEquals(403, response.status.intValue());
    }

    // =====================
    // Helpers
    // =====================

    /** Extract the first "id":N value from a JSON string. */
    private String extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
