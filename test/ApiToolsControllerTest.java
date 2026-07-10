import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
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

    /**
     * JCLAW-654 regression guard: every native tool must enumerate its callable
     * {@code actions()} so the /tools page renders a non-empty "Functions"
     * disclosure. The {@code Tool.actions()} default is an empty list — a
     * forgotten override degrades to "Functions 0" rather than breaking
     * registration, so the convention needs a test to stay honest (this is how
     * {@code diarize_audio} shipped with no functions). MCP tools carry a
     * non-null {@code group} and fold into one server card, so they're excluded.
     */
    @Test
    void everyNativeToolEnumeratesItsActions() {
        login();
        var resp = GET("/api/tools/meta");
        assertIsOk(resp);
        var arr = com.google.gson.JsonParser.parseString(getContent(resp)).getAsJsonArray();
        var offenders = new java.util.ArrayList<String>();
        for (var el : arr) {
            var obj = el.getAsJsonObject();
            var group = obj.get("group");
            boolean isNative = group == null || group.isJsonNull();
            if (!isNative) continue;
            var actions = obj.getAsJsonArray("actions");
            if (actions == null || actions.size() == 0) {
                offenders.add(obj.get("name").getAsString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "native tools missing an actions() override (render as Functions 0): " + offenders);
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

    // --- Bulk per-action cleanup delete (JCLAW-408) ---

    @Test
    void bulkDeleteByToolNameInClauseRemovesOnlyNamedRows() {
        // updateGroupForAgent's legacy-cleanup path issues one bulk
        // AgentToolConfig.delete("... toolName IN (?2)", agent, names). Verify
        // Hibernate expands the List-valued positional parameter inside IN (?2):
        // only the named rows are removed; an unrelated row survives.
        login();
        var id = createAgent("bulk-delete-agent");
        Integer deleted = fetchInFreshTx(() -> {
            Agent agent = Agent.findById(id);
            for (var n : java.util.List.of("mcp_jira_create", "mcp_jira_search", "keep_me")) {
                var c = new AgentToolConfig();
                c.agent = agent;
                c.toolName = n;
                c.enabled = true;
                c.save();
            }
            return AgentToolConfig.delete("agent = ?1 AND toolName IN (?2)",
                    agent, java.util.List.of("mcp_jira_create", "mcp_jira_search"));
        });
        assertEquals(2, deleted.intValue(), "both named rows must be deleted in one statement");
        Boolean survivorIntact = fetchInFreshTx(() -> {
            Agent agent = Agent.findById(id);
            return AgentToolConfig.findByAgentAndTool(agent, "mcp_jira_create") == null
                    && AgentToolConfig.findByAgentAndTool(agent, "mcp_jira_search") == null
                    && AgentToolConfig.findByAgentAndTool(agent, "keep_me") != null;
        });
        assertTrue(survivorIntact, "only the named rows are removed; the unrelated row survives");
    }
}
