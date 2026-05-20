import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.AgentToolConfig;
import services.AgentService;

class ApiToolsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private static <T> T fetchInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
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
        return ref.get();
    }

    private Long createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1").id);
    }

    // --- Auth gate ---

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/tools").status.intValue());
    }

    @Test
    void metaRequiresAuth() {
        assertEquals(401, GET("/api/tools/meta").status.intValue());
    }

    @Test
    void listForAgentRequiresAuth() {
        assertEquals(401, GET("/api/agents/1/tools").status.intValue());
    }

    @Test
    void updateForAgentRequiresAuth() {
        var resp = PUT("/api/agents/1/tools/filesystem", "application/json",
                "{\"enabled\":false}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void updateGroupForAgentRequiresAuth() {
        var resp = PUT("/api/agents/1/tool-groups/some-group", "application/json",
                "{\"enabled\":false}");
        assertEquals(401, resp.status.intValue());
    }

    // --- GET /api/tools — global catalog ---

    @Test
    void listReturnsJsonArrayContainingNativeTools() {
        login();
        var resp = GET("/api/tools");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.startsWith("["));
        // ToolRegistry always carries the native filesystem tool.
        assertTrue(body.contains("\"name\":\"filesystem\""),
                "native filesystem tool must appear in catalog: " + body);
    }

    @Test
    void metaReturnsCategoryAndIcon() {
        login();
        var resp = GET("/api/tools/meta");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"category\""), "meta must carry category: " + body);
        assertTrue(body.contains("\"icon\""), "meta must carry icon: " + body);
    }

    // --- GET /api/agents/{id}/tools ---

    @Test
    void listForAgentReturns404ForUnknownAgent() {
        login();
        assertEquals(404, GET("/api/agents/999999/tools").status.intValue());
    }

    @Test
    void listForAgentReturnsEnabledFlagPerTool() {
        login();
        var id = createAgent("tools-list-agent");
        var resp = GET("/api/agents/" + id + "/tools");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"enabled\":true") || body.contains("\"enabled\":false"),
                "per-agent listing must surface the enabled flag: " + body);
    }

    // --- PUT /api/agents/{id}/tools/{name} ---

    @Test
    void updateForAgentReturns404ForUnknownAgent() {
        login();
        var resp = PUT("/api/agents/999999/tools/filesystem", "application/json",
                "{\"enabled\":false}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void updateForAgentReturns400OnMissingEnabledField() {
        login();
        var id = createAgent("tools-missing-enabled");
        var resp = PUT("/api/agents/" + id + "/tools/filesystem", "application/json", "{}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void updateForAgentTogglesNativeToolAndPersists() {
        login();
        var id = createAgent("tools-toggle-agent");
        var resp = PUT("/api/agents/" + id + "/tools/filesystem", "application/json",
                "{\"enabled\":false}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
        Boolean persisted = fetchInFreshTx(() -> {
            Agent agent = Agent.findById(id);
            var config = AgentToolConfig.findByAgentAndTool(agent, "filesystem");
            return config != null && !config.enabled;
        });
        assertTrue(persisted, "config row must show enabled=false after toggle");
    }

    @Test
    void updateForAgentUpdatesExistingConfigRow() {
        // Second toggle on the same tool should update the row, not insert
        // a duplicate. Verifies the find-or-create path.
        login();
        var id = createAgent("tools-update-existing");
        PUT("/api/agents/" + id + "/tools/filesystem", "application/json",
                "{\"enabled\":false}");
        var resp = PUT("/api/agents/" + id + "/tools/filesystem", "application/json",
                "{\"enabled\":true}");
        assertIsOk(resp);
        Boolean persisted = fetchInFreshTx(() -> {
            Agent agent = Agent.findById(id);
            var config = AgentToolConfig.findByAgentAndTool(agent, "filesystem");
            return config != null && config.enabled;
        });
        assertTrue(persisted, "re-toggle must update the row to enabled=true");
    }

    // --- PUT /api/agents/{id}/tool-groups/{group} ---

    @Test
    void updateGroupForAgentReturns404ForUnknownAgent() {
        login();
        var resp = PUT("/api/agents/999999/tool-groups/some-group", "application/json",
                "{\"enabled\":false}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void updateGroupForAgentReturns400OnMissingEnabledField() {
        login();
        var id = createAgent("group-missing-enabled");
        var resp = PUT("/api/agents/" + id + "/tool-groups/some-group", "application/json", "{}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void updateGroupForAgentReturns404ForUnknownGroup() {
        // No MCP server is registered with the name "definitely-not-a-group" in
        // a default test JVM — covers the serverLevel == null branch.
        login();
        var id = createAgent("group-unknown");
        var resp = PUT("/api/agents/" + id + "/tool-groups/definitely-not-a-real-group",
                "application/json", "{\"enabled\":false}");
        assertEquals(404, resp.status.intValue());
    }
}
