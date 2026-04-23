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
        AuthFixture.seedAdminPassword("changeme");
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
    public void allEndpointsReject401WithoutAuth() {
        assertEquals(401, GET("/api/agents").status.intValue());
        assertEquals(401, GET("/api/tasks").status.intValue());
        assertEquals(401, GET("/api/channels").status.intValue());
        assertEquals(401, GET("/api/logs").status.intValue());
        assertEquals(401, GET("/api/skills").status.intValue());
        assertEquals(401, GET("/api/tools").status.intValue());
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

    // JCLAW-115: agent-name slug validation rejects traversal-shaped input.

    @Test
    public void agentsCreateRejectsPathTraversalName() {
        login();
        var body = """
                {"name": "../etc", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsCreateRejectsAbsolutePathName() {
        login();
        var body = """
                {"name": "/etc/passwd", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsCreateRejectsSlashedName() {
        login();
        var body = """
                {"name": "foo/bar", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsCreateRejectsEmptyName() {
        login();
        var body = """
                {"name": "", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsCreateRejectsDotNames() {
        login();
        var dotBody = """
                {"name": ".", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        assertEquals(400, POST("/api/agents", "application/json", dotBody).status.intValue());
        var dotDotBody = """
                {"name": "..", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        assertEquals(400, POST("/api/agents", "application/json", dotDotBody).status.intValue());
    }

    @Test
    public void agentsCreateRejectsWhitespaceInName() {
        login();
        var body = """
                {"name": "has space", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsCreateRejectsOverlongName() {
        login();
        // 65 chars — one over the limit.
        var longName = "a".repeat(65);
        var body = """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(longName);
        var response = POST("/api/agents", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsCreateAcceptsValidSlugNames() {
        login();
        // Happy-path sanity: the regex allows typical names operators use.
        var body = """
                {"name": "my-agent_01", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertIsOk(response);
    }

    @Test
    public void agentsUpdateRejectsRenameToTraversalName() {
        login();
        var id = createAgent("rename-src-115");
        var body = """
                {"name": "../etc"}
                """;
        var response = PUT("/api/agents/" + id, "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void agentsUpdateAllowsUnchangedName() {
        // JCLAW-115 grandfather clause: update requests that don't modify
        // the name must pass regardless of whether the existing name meets
        // the new regex (e.g. legacy agents). Here we flip thinkingMode
        // without touching name.
        login();
        var id = createAgent("legacy-ok-115");
        var body = """
                {"thinkingMode": null}
                """;
        var response = PUT("/api/agents/" + id, "application/json", body);
        assertIsOk(response);
    }

    @Test
    public void agentsCreateRejectsReservedLoadtestName() {
        login();
        var body = """
                {"name": "__loadtest__", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """;
        var response = POST("/api/agents", "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void agentsUpdateRejectsRenameToReservedLoadtestName() {
        login();
        var id = createAgent("rename-src");
        var body = """
                {"name": "__loadtest__"}
                """;
        var response = PUT("/api/agents/" + id, "application/json", body);
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
        var response = GET("/api/agents/" + id + "/prompt-breakdown?channelType=web");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void agentsPromptBreakdownRejectsMissingChannel() {
        login();
        var id = createTestAgent();
        assertEquals(400, GET("/api/agents/" + id + "/prompt-breakdown").status.intValue());
        assertEquals(400, GET("/api/agents/" + id + "/prompt-breakdown?channelType=").status.intValue());
        assertEquals(400, GET("/api/agents/" + id + "/prompt-breakdown?channelType=bogus").status.intValue());
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
    public void toolsMetaReturnsRichShape() {
        // Publish a known tool set so this test is independent of whatever
        // the DefaultConfigJob did (or didn't) register in the test JVM.
        var originalTools = agents.ToolRegistry.listTools();
        try {
            agents.ToolRegistry.publish(java.util.List.of(
                    new tools.ShellExecTool(),
                    new tools.FileSystemTools(),
                    new tools.WebFetchTool(),
                    new tools.DateTimeTool()
            ));
            login();
            var response = GET("/api/tools/meta");
            assertIsOk(response);
            assertContentType("application/json", response);
            var content = getContent(response);
            assertTrue(content.startsWith("["), "response is a JSON array");
            // Backend taxonomy — every tool must carry category/icon/shortDescription/actions.
            assertTrue(content.contains("\"category\""),         "carries category");
            assertTrue(content.contains("\"icon\""),             "carries icon");
            assertTrue(content.contains("\"shortDescription\""), "carries shortDescription");
            assertTrue(content.contains("\"actions\""),          "carries actions");
            // Concrete category values from the published tool set.
            assertTrue(content.contains("\"System\""),    "exec is System-category");
            assertTrue(content.contains("\"Files\""),     "filesystem is Files");
            assertTrue(content.contains("\"Web\""),       "web_fetch is Web");
            assertTrue(content.contains("\"Utilities\""), "datetime is Utilities");
            // ShellExecTool declares its config gate — gate must ride in the response.
            assertTrue(content.contains("\"shell.enabled\""), "requiresConfig surfaced when set");
            // Presentational concerns must NOT leak into the backend response.
            assertFalse(content.contains("bg-neutral"), "no Tailwind classes in API");
            assertFalse(content.contains("<path"),      "no SVG markup in API");
        } finally {
            agents.ToolRegistry.publish(originalTools);
        }
    }

    @Test
    public void toolsMetaRequiresAuth() {
        assertEquals(401, GET("/api/tools/meta").status.intValue());
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
     * unauthenticated request is rejected (covered in allEndpointsReject401WithoutAuth)
     * and that the route is wired by confirming the 401 comes from AuthCheck, not a 404.
     */
    @Test
    public void eventsStreamRouteIsWired() {
        // Without auth, AuthCheck returns 401 — proving the route exists and
        // reaches the controller (a missing route would give 404).
        var response = GET("/api/events");
        assertEquals(401, response.status.intValue());
    }

    // =====================
    // ApiConfigController — loadtest-mock reserved key guards
    // =====================

    @Test
    public void configSaveOnLoadtestMockKeyReturns409() {
        login();
        var body = """
                {"key":"provider.loadtest-mock.baseUrl","value":"http://localhost:19999/v1"}
                """;
        var response = POST("/api/config", "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void configDeleteOnLoadtestMockKeyReturns409() {
        login();
        services.ConfigService.set("provider.loadtest-mock.baseUrl", "http://127.0.0.1:19999/v1");
        try {
            var response = DELETE("/api/config/provider.loadtest-mock.baseUrl");
            assertEquals(409, response.status.intValue());
        } finally {
            services.ConfigService.delete("provider.loadtest-mock.baseUrl");
        }
    }

    @Test
    public void configListHidesLoadtestMockProviderKeys() {
        login();
        services.ConfigService.set("provider.loadtest-mock.baseUrl", "http://127.0.0.1:19999/v1");
        var response = GET("/api/config");
        assertIsOk(response);
        assertFalse(getContent(response).contains("provider.loadtest-mock."),
                "Reserved provider.loadtest-mock.* keys must not appear in /api/config");
        services.ConfigService.delete("provider.loadtest-mock.baseUrl");
    }

    @Test
    public void configSaveProviderEnabledFlagRoundTrips() {
        // JCLAW-110: the Settings toggle writes provider.NAME.enabled via the
        // standard config API. The reserved-key guard is namespaced to the
        // loadtest-mock prefix, so this POST must succeed for normal providers
        // and the value must come back on GET.
        login();
        try {
            var body = """
                    {"key":"provider.openrouter.enabled","value":"false"}
                    """;
            var response = POST("/api/config", "application/json", body);
            assertIsOk(response);

            var getResp = GET("/api/config");
            assertIsOk(getResp);
            assertTrue(getContent(getResp).contains("provider.openrouter.enabled"),
                    "round-tripped enabled key should appear in /api/config");
        } finally {
            services.ConfigService.delete("provider.openrouter.enabled");
        }
    }

    // =====================
    // ApiChatController slash-argument wiring (JCLAW-111)
    // =====================

    @Test
    public void chatSendRoutesModelStatusThroughFullDetailPath() {
        // JCLAW-111: the sync REST chat handler used to call the no-args
        // Commands.execute overload, which dropped the "status" argument
        // and fell into the no-args summary branch. This test proves the
        // args-carrying overload now fires and /model status returns the
        // full model metadata.
        login();
        services.ConfigService.set("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        services.ConfigService.set("provider.openrouter.apiKey", "sk-test");
        services.ConfigService.set("provider.openrouter.models",
                "[{\"id\":\"gpt-4.1\",\"name\":\"GPT 4.1\",\"contextWindow\":128000,\"maxTokens\":8192}]");
        llm.ProviderRegistry.refresh();
        var agentId = createAgent("slash-status-agent");
        try {
            var chatBody = """
                    {"agentId": %s, "message": "/model status"}
                    """.formatted(agentId);
            var response = POST("/api/chat/send", "application/json", chatBody);
            assertIsOk(response);
            var content = getContent(response);
            // Full-detail markers — absent from the no-args summary.
            assertTrue(content.contains("Context window"),
                    "/model status should render full detail including context window: " + content);
            assertTrue(content.contains("128K"),
                    "/model status should include formatted context window: " + content);
        } finally {
            services.ConfigService.delete("provider.openrouter.baseUrl");
            services.ConfigService.delete("provider.openrouter.apiKey");
            services.ConfigService.delete("provider.openrouter.models");
            llm.ProviderRegistry.refresh();
        }
    }

    @Test
    public void chatSendRoutesModelNameWriteThroughOverride() {
        // JCLAW-111: /model openrouter/gpt-4.1 must reach the write-path
        // branch (performModelSwitch) rather than being ignored as a no-args
        // summary. We verify by inspecting the confirmation text — the
        // summary and the switch confirmation are visibly distinct.
        login();
        services.ConfigService.set("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        services.ConfigService.set("provider.openrouter.apiKey", "sk-test");
        services.ConfigService.set("provider.openrouter.models",
                "[{\"id\":\"gpt-4.1\",\"contextWindow\":128000}]");
        llm.ProviderRegistry.refresh();
        var agentId = createAgent("slash-switch-agent");
        try {
            var chatBody = """
                    {"agentId": %s, "message": "/model openrouter/gpt-4.1"}
                    """.formatted(agentId);
            var response = POST("/api/chat/send", "application/json", chatBody);
            assertIsOk(response);
            var content = getContent(response);
            assertTrue(content.contains("Switched this conversation"),
                    "/model NAME should render the switch-confirmation text: " + content);
        } finally {
            services.ConfigService.delete("provider.openrouter.baseUrl");
            services.ConfigService.delete("provider.openrouter.apiKey");
            services.ConfigService.delete("provider.openrouter.models");
            llm.ProviderRegistry.refresh();
        }
    }

    @Test
    public void agentsListHidesLoadtestAgent() {
        login();
        services.Tx.run(() -> {
            var a = new models.Agent();
            a.name = "__loadtest__";
            a.modelProvider = "loadtest-mock";
            a.modelId = "mock-model";
            a.save();
        });
        var response = GET("/api/agents");
        assertIsOk(response);
        assertFalse(getContent(response).contains("\"__loadtest__\""),
                "Reserved agent __loadtest__ must not appear in /api/agents");
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
