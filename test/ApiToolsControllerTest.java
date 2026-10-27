import models.Agent;
import models.AgentToolConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
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

    @Test
    void listForAgentReportsEffectiveStateNotADefaultPolicyGuess() {
        login();
        var id = createAgent("tools-effective-state-agent");

        var body = getContent(GET("/api/agents/" + id + "/tools"));
        // Gson emits no spaces, so a name/flag pair is contiguous per entry.
        assertTrue(body.contains("\"name\":\"datetime\",\"description\""),
                "sanity: the listing should include a default-ON native tool");

        // The bug this pins: these tools are hidden from the model unless a row
        // explicitly enables them, but the endpoint used to re-derive "native
        // tools are on" and report them enabled. The agent editor then showed a
        // ticked box for a tool the agent could not call. Found in JCLAW-911 UAT.
        for (var optIn : java.util.List.of("printer", "generate_image",
                "generate_video", "generate_audio", "memory", "skill_read")) {
            var idx = body.indexOf("\"name\":\"" + optIn + "\"");
            assertTrue(idx >= 0, optIn + " should appear in the listing: " + body);
            var entry = body.substring(idx, Math.min(body.length(), idx + 4000));
            var enabledAt = entry.indexOf("\"enabled\":");
            assertTrue(enabledAt >= 0, "no enabled flag for " + optIn);
            assertTrue(entry.startsWith("\"enabled\":false", enabledAt),
                    optIn + " is opt-in, so a fresh agent must see it as disabled here — "
                            + "otherwise the UI contradicts the agent loop");
        }
    }

    /** The enabled flag for {@code tool} in a per-agent tool listing body. */
    private static boolean enabledIn(String body, String tool) {
        var idx = body.indexOf("\"name\":\"" + tool + "\"");
        assertTrue(idx >= 0, tool + " should appear in the listing: " + body);
        var entry = body.substring(idx, Math.min(body.length(), idx + 4000));
        var at = entry.indexOf("\"enabled\":");
        assertTrue(at >= 0, "no enabled flag for " + tool);
        return entry.startsWith("\"enabled\":true", at);
    }

    @Test
    void memoryIsOnForMainByDefaultAndOffForEveryOtherAgent() {
        // JCLAW-919: forget hard-deletes, which is why a non-main agent has to be granted
        // the tool — but main is the agent the operator actually talks to, and gating it
        // there leaves "forget that" unanswerable in the conversation where it is said.
        login();
        var main = createAgent(models.Agent.MAIN_AGENT_NAME);
        var other = createAgent("tools-memory-default-agent");

        assertTrue(enabledIn(getContent(GET("/api/agents/" + main + "/tools")), "memory"),
                "main must get the memory tool without being granted it");
        assertFalse(enabledIn(getContent(GET("/api/agents/" + other + "/tools")), "memory"),
                "a non-main agent must have to opt in");
    }

    @Test
    void skillReadIsOffForMainToo() {
        // JF-146: filesystem already reads every skill, so no agent gets skill_read unasked.
        login();
        var main = createAgent(models.Agent.MAIN_AGENT_NAME);

        assertFalse(enabledIn(getContent(GET("/api/agents/" + main + "/tools")), "skill_read"));
    }

    @Test
    void anExplicitDisableStillTurnsMemoryOffForMain() {
        // Default-on must stay an operator default, not a fixture.
        login();
        var main = createAgent(models.Agent.MAIN_AGENT_NAME);

        assertIsOk(PUT("/api/agents/" + main + "/tools/memory", "application/json",
                "{\"enabled\":false}"));

        assertFalse(enabledIn(getContent(GET("/api/agents/" + main + "/tools")), "memory"),
                "an explicit disable row must win over the main default");
    }

    @Test
    void jclawApiIsOffForANonMainAgentWithNoConfigRowAtAll() {
        // JCLAW-941: this used to be guaranteed by a disable row written at agent creation,
        // which cannot cover an agent that already existed, one created outside
        // AgentService.create, or a row later deleted — the live instance had two agents
        // holding no row and therefore full admin-API access. Deleting every row for the
        // agent reproduces exactly that state.
        login();
        var id = createAgent("tools-jclaw-api-norow-agent");
        fetchInFreshTx(() -> models.AgentToolConfig.delete("agent.id = ?1", id));
        agents.ToolRegistry.clearDisabledToolsCache();

        var body = getContent(GET("/api/agents/" + id + "/tools"));
        var idx = body.indexOf("\"name\":\"jclaw_api\"");
        assertTrue(idx >= 0, "jclaw_api should still be listed: " + body);
        var entry = body.substring(idx, Math.min(body.length(), idx + 4000));
        var enabledAt = entry.indexOf("\"enabled\":");
        assertTrue(entry.startsWith("\"enabled\":false", enabledAt),
                "a non-main agent with no config row must not get jclaw_api: " + entry);
    }

    @Test
    void anExplicitGrantStillEnablesJclawApiForANonMainAgent() {
        // The default is opt-in, not a prohibition: granting it to a purpose-built agent
        // stays one click, which is how this instance's cyber-concierge agent has it.
        login();
        var id = createAgent("tools-jclaw-api-granted-agent");

        assertIsOk(PUT("/api/agents/" + id + "/tools/jclaw_api", "application/json",
                "{\"enabled\":true}"));

        var body = getContent(GET("/api/agents/" + id + "/tools"));
        var idx = body.indexOf("\"name\":\"jclaw_api\"");
        var entry = body.substring(idx, Math.min(body.length(), idx + 4000));
        assertTrue(entry.startsWith("\"enabled\":true", entry.indexOf("\"enabled\":")),
                "an explicit grant must win over the default: " + entry);
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
    void bulkDeleteOfPerActionRowsSparesTheServerHandleAndNativeGrants() {
        // updateGroupForAgent's legacy-cleanup path issues one bulk delete over the toggled
        // server's per-action rows. Since JCLAW-983 those rows are addressed by
        // (mcpServer, mcpAction) rather than by name, and the empty action that marks the
        // server-level row is what keeps it out of the delete.
        login();
        var id = createAgent("bulk-delete-agent");
        Integer deleted = fetchInFreshTx(() -> {
            Agent agent = Agent.findById(id);
            var server = new models.McpServer();
            server.name = "jira";
            server.transport = models.McpServer.Transport.STDIO;
            server.configJson = "{\"command\":\"true\",\"args\":[]}";
            server.enabled = false;
            server.save();
            for (var action : java.util.List.of("", "create", "search")) {
                var c = new AgentToolConfig();
                c.agent = agent;
                c.mcpServer = server;
                c.mcpAction = action;
                c.enabled = true;
                c.save();
            }
            var keep = new AgentToolConfig();
            keep.agent = agent;
            keep.toolName = "keep_me";
            keep.enabled = true;
            keep.save();
            return AgentToolConfig.delete("agent = ?1 AND mcpServer = ?2 AND mcpAction <> ''",
                    agent, server);
        });
        assertEquals(2, deleted.intValue(), "both per-action rows must go in one statement");
        Boolean survivorsIntact = fetchInFreshTx(() -> {
            Agent agent = Agent.findById(id);
            return AgentToolConfig.count("agent = ?1 AND mcpAction = ?2", agent, "") == 1
                    && AgentToolConfig.findByAgentAndTool(agent, "keep_me") != null;
        });
        assertTrue(survivorsIntact,
                "the server-level row and the unrelated native row both survive");
    }
}
