import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpServerTool;
import mcp.McpToolAdapter;
import mcp.McpToolDef;
import models.Agent;
import models.AgentToolConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import tools.JClawApiTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            if (obj.get("actionCount").getAsInt() == 0) {
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
        assertTrue(body.contains("\"name\":\"datetime\",\"group\""),
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
    void aNonMainAgentsToolListShowsJclawApiSwitchedOffEvenWithNoConfigRow() {
        // JCLAW-941 found agents holding no row, and so full admin-API access, when the default was a
        // disable row written at creation. The tool is listed, so the operator has a switch for it (JF-293).
        login();
        var id = createAgent("tools-jclaw-api-norow-agent");
        fetchInFreshTx(() -> models.AgentToolConfig.delete("agent.id = ?1", id));
        agents.ToolRegistry.clearDisabledToolsCache();

        assertEquals(false, enabledInList(id, "jclaw_api"), "listed, and off until the operator switches it on");
        assertTrue(disabledFor(id).contains("jclaw_api"));
    }

    @Test
    void theOperatorsGrantGivesACustomAgentJclawApiAndSwitchingItOffTakesItBack() {
        login();
        var id = createAgent("tools-jclaw-api-granted-agent");
        assertTrue(disabledFor(id).contains("jclaw_api"), "off without a grant");

        var granted = PUT("/api/agents/" + id + "/tools/jclaw_api", "application/json", "{\"enabled\":true}");
        assertEquals(200, granted.status.intValue());
        assertEquals(json("{\"name\":\"jclaw_api\",\"enabled\":true,\"status\":\"ok\"}"), json(getContent(granted)));
        assertEquals(true, enabledInList(id, "jclaw_api"));
        assertFalse(disabledFor(id).contains("jclaw_api"), "on with the grant");

        var revoked = PUT("/api/agents/" + id + "/tools/jclaw_api", "application/json", "{\"enabled\":false}");
        assertEquals(200, revoked.status.intValue());
        assertEquals(false, enabledInList(id, "jclaw_api"));
        assertTrue(disabledFor(id).contains("jclaw_api"), "off again once the operator switches it off");
    }

    @Test
    void aSubagentNeverGetsJclawApiWhateverItOrItsParentWasGranted() {
        var parentId = createAgent("tools-jclaw-api-parent");
        var childId = fetchInFreshTx(() -> {
            Agent parent = Agent.findById(parentId);
            grant(parent, "jclaw_api");
            var child = AgentService.create("tools-jclaw-api-child", "openrouter", "gpt-4.1", null, null, false, parent);
            grant(child, "jclaw_api");
            return child.id;
        });
        agents.ToolRegistry.clearDisabledToolsCache();

        assertFalse(disabledFor(parentId).contains("jclaw_api"), "premise: the parent holds the operator's grant");
        assertTrue(disabledFor(childId).contains("jclaw_api"));
    }

    @Test
    void theEvalAgentGetsJclawApiOnlyWithAGrantOfItsOwn() {
        // Every tool is opt-in for the eval agent (JCLAW-883), so calibration grants exactly what a suite names.
        var id = createAgent(Agent.EVALTEST_AGENT_NAME);
        try {
            agents.ToolRegistry.clearDisabledToolsCache();
            assertTrue(disabledFor(id).contains("jclaw_api"), "off without a grant");

            fetchInFreshTx(() -> grant(Agent.findById(id), "jclaw_api"));
            agents.ToolRegistry.clearDisabledToolsCache();
            assertFalse(disabledFor(id).contains("jclaw_api"), "on with its own grant");
        } finally {
            fetchInFreshTx(() -> {
                Agent agent = Agent.findById(id);
                AgentToolConfig.delete("agent = ?1", agent);
                return agent.delete();
            });
        }
    }

    private static AgentToolConfig grant(Agent agent, String tool) {
        var row = mcp.McpGrants.newRow(agent, tool);
        row.enabled = true;
        return row.save();
    }

    private static java.util.Set<String> disabledFor(Long agentId) {
        return fetchInFreshTx(() -> agents.ToolRegistry.loadDisabledTools(Agent.findById(agentId)));
    }

    @Test
    void mainKeepsJclawApiAgentSpawnAndTheShellAndAnUnsavedOtherAgentGetsNone() {
        var main = new models.Agent();
        main.name = models.Agent.MAIN_AGENT_NAME;
        var other = new models.Agent();
        other.name = "tools-jclaw-api-unsaved";

        assertEquals(java.util.Set.of(), agents.ToolRegistry.loadDisabledTools(main));
        assertEquals(java.util.Set.of("jclaw_api", "agent_spawn", "exec"), agents.ToolRegistry.loadDisabledTools(other));
    }

    @Test
    void aCustomAgentGetsAgentSpawnAndTheShellOnlyWhenTheOperatorSwitchesThemOn() {
        // JF-288: agent_spawn once joined every agent that had not switched it off, a restricted one
        // included, and a new agent started with the shell beside its web-reading tools.
        login();
        var id = createAgent("tools-agent-spawn-optin");

        for (var tool : List.of("agent_spawn", "exec")) {
            assertEquals(false, enabledInList(id, tool), tool + " is off by default, and still listed so it can be switched on");

            assertIsOk(PUT("/api/agents/" + id + "/tools/" + tool, "application/json", "{\"enabled\":true}"));
            assertEquals(true, enabledInList(id, tool), "the operator's grant turns " + tool + " on");
        }
        assertEquals(true, enabledInList(id, "web_fetch"), "control: an ordinary tool is still on by default");
    }

    @Test
    void aSubagentNeverGetsAgentSpawnEvenWithAGrant() {
        var parentId = createAgent("tools-agent-spawn-parent");
        var childId = fetchInFreshTx(() -> {
            var child = AgentService.create("tools-agent-spawn-child", "openrouter", "gpt-4.1");
            child.parentAgent = models.Agent.findById(parentId);
            child.save();
            var row = mcp.McpGrants.newRow(child, "agent_spawn");
            row.enabled = true;
            row.save();
            return child.id;
        });
        agents.ToolRegistry.clearDisabledToolsCache();

        assertTrue(fetchInFreshTx(() -> agents.ToolRegistry.loadDisabledTools(models.Agent.findById(childId))).contains("agent_spawn"));
    }

    /** Whether {@code tool} is listed as enabled in the agent's own tool list; null when it is not listed at all. */
    private Boolean enabledInList(Long agentId, String tool) {
        for (var row : JsonParser.parseString(getContent(GET("/api/agents/" + agentId + "/tools"))).getAsJsonArray()) {
            var o = row.getAsJsonObject();
            if (tool.equals(o.get("name").getAsString())) return o.get("enabled").getAsBoolean();
        }
        return null;
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

    // --- JF-142: catalog rows, and one tool's own read ---

    /** A registry entry carrying only what the catalogs and the single read report; nothing executes it. */
    private record CatalogTool(String name, @Nullable String group, boolean isServerLevel, String category, String icon,
                               String shortDescription, String description, @Nullable String requiresConfig,
                               List<ToolAction> actions) implements ToolRegistry.Tool {
        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(String argsJson, Agent agent) {
            throw new AssertionError("a catalog test runs no tool");
        }
    }

    private static final List<ToolAction> FAKE_ACTIONS = List.of(
            new ToolAction("create_issue", "Create an issue."),
            new ToolAction("search_issues", "Search issues."),
            new ToolAction("add_comment", "Comment on an issue."));

    /** Every group published here, unpublished by {@link #withExternalGroups}. */
    private final List<String> published = new ArrayList<>();

    /** Runs {@code body} on the canonical natives plus each external group {@code publish} adds, holding the registry lock. */
    private void withExternalGroups(ToolRegistrySync.ThrowingBody publish, ToolRegistrySync.ThrowingBody body) throws Exception {
        ToolRegistrySync.canonicalForTest();
        try {
            publish.run();
            body.run();
        } finally {
            published.forEach(ToolRegistry::unpublishExternal);
            published.clear();
            ToolRegistrySync.release();
        }
    }

    private void publishGroup(String group, List<ToolRegistry.Tool> tools) {
        published.add(group);
        ToolRegistry.publishExternal(group, tools);
    }

    /** An MCP-shaped group: its server-level handle {@code mcp_<server>} listing {@link #FAKE_ACTIONS}, and one adapter per action. */
    private void publishFakeServer(String server) {
        var tools = new ArrayList<ToolRegistry.Tool>();
        tools.add(new CatalogTool("mcp_" + server, server, true, "MCP", "plug", "Handle for " + server + ".",
                "MCP server " + server + ", in full.", null, FAKE_ACTIONS));
        for (var action : FAKE_ACTIONS) {
            tools.add(new CatalogTool("mcp_" + server + "_" + action.name(), server, false, "MCP", "plug",
                    action.description(), action.description(), null, List.of(action)));
        }
        publishGroup(server, tools);
    }

    private static JsonArray readArray(String url) {
        return JsonParser.parseString(getContent(GET(url))).getAsJsonArray();
    }

    private static @Nullable JsonObject rowNamed(JsonArray rows, String name) {
        for (var e : rows) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject();
        }
        return null;
    }

    private static List<String> names(JsonArray rows) {
        var out = new ArrayList<String>();
        for (var e : rows) out.add(e.getAsJsonObject().get("name").getAsString());
        return out;
    }

    private static JsonObject json(String literal) {
        return JsonParser.parseString(literal).getAsJsonObject();
    }

    @Test
    void aCatalogRowIsTheNameGroupEnabledStateAndOneLineSummary() throws Exception {
        login();
        ToolRegistrySync.withCanonicalTools(() -> {
            var datetime = ToolRegistry.lookupTool("datetime");
            assertNotNull(datetime);
            var expected = new JsonObject();
            expected.addProperty("name", "datetime");
            expected.add("group", JsonNull.INSTANCE);
            expected.addProperty("enabled", true);
            expected.addProperty("shortDescription", datetime.shortDescription());

            assertEquals(expected, rowNamed(readArray("/api/tools"), "datetime"));
        });
    }

    @Test
    void aMetaRowKeepsTheCardFieldsAndCountsTheActionsInPlaceOfListingThem() throws Exception {
        login();
        ToolRegistrySync.withCanonicalTools(() -> {
            var filesystem = ToolRegistry.lookupTool("filesystem");
            assertNotNull(filesystem);
            var expected = new JsonObject();
            expected.addProperty("name", "filesystem");
            expected.add("group", JsonNull.INSTANCE);
            expected.addProperty("enabled", true);
            expected.addProperty("shortDescription", filesystem.shortDescription());
            expected.addProperty("category", "Files");
            expected.addProperty("icon", filesystem.icon());
            expected.add("requiresConfig", JsonNull.INSTANCE);
            expected.addProperty("actionCount", filesystem.actions().size());

            assertEquals(expected, rowNamed(readArray("/api/tools/meta"), "filesystem"));
            assertTrue(filesystem.actions().size() > 1, "sanity: filesystem has several actions to count");
        });
    }

    @Test
    void anAgentRowIsTheNameGroupItsOwnEnabledStateAndOneLineSummary() throws Exception {
        login();
        var id = createAgent("tools-row-shape-agent");
        ToolRegistrySync.withCanonicalTools(() -> {
            var rows = readArray("/api/agents/" + id + "/tools");
            for (var pair : List.of(List.of("datetime", "true"), List.of("printer", "false"))) {
                var tool = ToolRegistry.lookupTool(pair.get(0));
                assertNotNull(tool);
                var expected = new JsonObject();
                expected.addProperty("name", pair.get(0));
                expected.add("group", JsonNull.INSTANCE);
                expected.addProperty("enabled", Boolean.parseBoolean(pair.get(1)));
                expected.addProperty("shortDescription", tool.shortDescription());
                assertEquals(expected, rowNamed(rows, pair.get(0)));
            }
        });
    }

    @Test
    void theCatalogsListAnMcpServerOnceByItsHandleWithItsActionCount() throws Exception {
        login();
        var id = createAgent("tools-mcp-fold-agent");
        var server = "jf142-" + UUID.randomUUID().toString().substring(0, 8);
        withExternalGroups(() -> publishFakeServer(server), () -> {
            var handle = "mcp_" + server;
            for (var url : List.of("/api/tools", "/api/tools/meta", "/api/agents/" + id + "/tools")) {
                var listed = names(readArray(url)).stream().filter(n -> n.startsWith(handle)).toList();
                assertEquals(List.of(handle), listed, url + " lists the server by its handle alone");
            }
            var meta = rowNamed(readArray("/api/tools/meta"), handle);
            assertNotNull(meta);
            assertEquals(server, meta.get("group").getAsString());
            assertEquals("MCP", meta.get("category").getAsString());
            assertEquals(3, meta.get("actionCount").getAsInt());
            var agentRow = rowNamed(readArray("/api/agents/" + id + "/tools"), handle);
            assertNotNull(agentRow);
            assertFalse(agentRow.get("enabled").getAsBoolean(), "an MCP server is off for a non-main agent until granted");
        });
    }

    @Test
    void theSingleReadReturnsOneToolsFullDescriptionAndActions() throws Exception {
        login();
        ToolRegistrySync.withCanonicalTools(() -> {
            var filesystem = ToolRegistry.lookupTool("filesystem");
            assertNotNull(filesystem);
            var resp = GET("/api/tools/filesystem");
            assertIsOk(resp);
            assertContentType("application/json", resp);

            var expected = new JsonObject();
            expected.addProperty("name", "filesystem");
            expected.add("group", JsonNull.INSTANCE);
            expected.addProperty("enabled", true);
            expected.addProperty("shortDescription", filesystem.shortDescription());
            expected.addProperty("category", "Files");
            expected.addProperty("icon", filesystem.icon());
            expected.add("requiresConfig", JsonNull.INSTANCE);
            expected.addProperty("description", filesystem.description());
            expected.add("actions", utils.GsonHolder.GSON.toJsonTree(filesystem.actions()));
            assertEquals(expected, json(getContent(resp)));
        });
    }

    @Test
    void theSingleReadOfAnMcpHandleListsTheServersActions() throws Exception {
        login();
        var server = "jf142-" + UUID.randomUUID().toString().substring(0, 8);
        withExternalGroups(() -> publishFakeServer(server), () -> {
            var body = json(getContent(GET("/api/tools/mcp_" + server)));
            assertEquals("MCP server " + server + ", in full.", body.get("description").getAsString());
            assertEquals(json("{\"a\":[{\"name\":\"create_issue\",\"description\":\"Create an issue.\"},"
                            + "{\"name\":\"search_issues\",\"description\":\"Search issues.\"},"
                            + "{\"name\":\"add_comment\",\"description\":\"Comment on an issue.\"}]}").get("a"),
                    body.get("actions"));
        });
    }

    @Test
    void theSingleReadOfAnUnknownNameIsA404() {
        login();
        var resp = GET("/api/tools/no_such_tool_jf142");
        assertEquals(404, resp.status.intValue());
        var body = json(getContent(resp));
        assertEquals("not_found", body.get("code").getAsString());
        assertEquals("No tool named 'no_such_tool_jf142' is registered.", body.get("message").getAsString());
    }

    @Test
    void theSingleReadRequiresAuth() {
        assertEquals(401, GET("/api/tools/filesystem").status.intValue());
    }

    @Test
    void aToolIsEnabledInTheCatalogsOnlyWhileTheConfigKeyItRequiresIsTrue() throws Exception {
        login();
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var name = "jf142_gated_" + suffix;
        var key = "jf142.test." + suffix + ".enabled";
        withExternalGroups(() -> publishGroup("jf142-gated-" + suffix, List.of(new CatalogTool(name, null, false,
                "Utilities", "wrench", "Gated.", "Gated, in full.", key, List.of(new ToolAction("run", "Run."))))), () -> {
            try {
                for (var value : java.util.Arrays.asList(null, "false", "true")) {
                    if (value != null) ConfigService.set(key, value);
                    var expected = "true".equals(value);
                    for (var url : List.of("/api/tools", "/api/tools/meta")) {
                        var row = rowNamed(readArray(url), name);
                        assertNotNull(row, url);
                        assertEquals(expected, row.get("enabled").getAsBoolean(), url + " with " + key + "=" + value);
                    }
                    assertEquals(expected, json(getContent(GET("/api/tools/" + name))).get("enabled").getAsBoolean());
                    assertEquals(key, rowNamed(readArray("/api/tools/meta"), name).get("requiresConfig").getAsString());
                }
            } finally {
                ConfigService.delete(key);
            }
        });
    }

    /**
     * The live instance had 680 tools registered on 2026-10-27, 26 of them MCP server handles and most of the rest
     * those servers' per-action adapters, when every catalog read came out over jclaw_api's cap.
     */
    @Test
    void withSixHundredEightyToolsRegisteredEveryCatalogReadFitsUnderTheCap() throws Exception {
        login();
        var id = createAgent("tools-size-agent");
        var servers = 26;
        var description = "Searches the connected service for records matching a query and returns each match with "
                + "its identifier, title, owner, status, timestamps and a link, paged by a cursor the caller passes "
                + "back. Filters narrow by owner, status, label and date range; an empty query returns the most recent "
                + "records first. Rate limits apply per token, and a refused call reports the wait.";
        var adapterChars = new long[1];
        var natives = new int[1];
        withExternalGroups(() -> {
            natives[0] = ToolRegistry.listTools().size();
            var adapters = 680 - natives[0] - servers;
            for (int i = 0; i < servers; i++) {
                var server = "jf142-size-" + i + "-" + UUID.randomUUID().toString().substring(0, 4);
                var tools = new ArrayList<ToolRegistry.Tool>();
                tools.add(new McpServerTool(server));
                for (int j = i; j < adapters; j += servers) {
                    var def = new McpToolDef("search_records_number_" + j, description, new JsonObject());
                    tools.add(new McpToolAdapter(server, def, (s, t, a) -> {
                        throw new AssertionError("a catalog test runs no tool");
                    }));
                    adapterChars[0] += description.length();
                }
                publishGroup(server, tools);
            }
        }, () -> {
            assertEquals(680, ToolRegistry.listTools().size(), "the registry holds the live instance's tool count");
            assertTrue(adapterChars[0] > JClawApiTool.MAX_RESPONSE_CHARS,
                    "sanity: the adapters' descriptions alone would not fit, so the check below measures the fold");
            for (var url : List.of("/api/tools", "/api/tools/meta", "/api/agents/" + id + "/tools")) {
                var body = getContent(GET(url));
                assertEquals(natives[0] + servers, JsonParser.parseString(body).getAsJsonArray().size(), url);
                assertTrue(body.length() < JClawApiTool.MAX_RESPONSE_CHARS,
                        url + " is " + body.length() + " characters");
            }
        });
    }
}
