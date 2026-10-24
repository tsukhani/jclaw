import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import mcp.McpConnectionManager;
import mcp.jsonrpc.JsonRpc;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.AgentSkillAllowedTool;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import utils.CircuitBreakers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Functional HTTP tests for {@code ApiMcpServersController} (JCLAW-33).
 * Covers auth gating, CRUD round-trips, the test endpoint, and the
 * delete path's downstream cleanup (allowlist + tool registry).
 */
class ApiMcpServersControllerTest extends FunctionalTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        McpConnectionManager.shutdown();
    }

    @AfterEach
    void tearDown() {
        McpConnectionManager.shutdown();
    }

    // ==================== auth ====================

    @Test
    void listRequiresAuth() {
        var response = GET("/api/mcp-servers");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void createRequiresAuth() {
        var response = POST("/api/mcp-servers", "application/json",
                "{\"name\":\"x\",\"transport\":\"HTTP\",\"url\":\"http://x\"}");
        assertEquals(401, response.status.intValue());
    }

    // ==================== list ====================

    @Test
    void listReturnsEmptyArrayWithNoServers() {
        login();
        var response = GET("/api/mcp-servers");
        assertIsOk(response);
        assertEquals("[]", getContent(response).trim());
    }

    @Test
    void listReturnsCreatedServerWithRuntimeStatus() {
        login();
        createHttpServer("remote", "http://127.0.0.1:1/mcp", false);
        var response = GET("/api/mcp-servers");
        var content = getContent(response);
        assertTrue(content.contains("\"name\":\"remote\""));
        assertTrue(content.contains("\"transport\":\"HTTP\""));
        assertTrue(content.contains("\"status\""), "status field must be present: " + content);
    }

    // ==================== create ====================

    @Test
    void createPersistsHttpServer() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"remote","enabled":false,"transport":"HTTP",
                 "url":"http://127.0.0.1:1/mcp",
                 "headers":{"Authorization":"Bearer t"}}
                """);
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"name\":\"remote\""));
        assertTrue(content.contains("\"url\":\"http://127.0.0.1:1/mcp\""));
        assertTrue(content.contains("\"Authorization\""), "headers must round-trip");
    }

    @Test
    void createPersistsStdioServer() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"local","enabled":false,"transport":"STDIO",
                 "command":"node","args":["script.js"],
                 "env":{"TOKEN":"abc"}}
                """);
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"command\":\"node\""));
        assertTrue(content.contains("\"args\":[\"script.js\"]"), content);
        assertTrue(content.contains("\"TOKEN\""));
    }

    @Test
    void createRejectsDuplicateName() {
        login();
        createHttpServer("dup", "http://127.0.0.1:1/mcp", false);
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"dup","enabled":false,"transport":"HTTP","url":"http://x/mcp"}
                """);
        assertEquals(409, resp.status.intValue());
    }

    /**
     * Three create-rejection shapes that share the login + POST + 400 skeleton:
     * bad slug, HTTP transport with no url, STDIO transport with no command.
     */
    @ParameterizedTest(name = "createRejects[{0}]")
    @CsvSource(delimiter = '|', value = {
            "BadName              | {\"name\":\"bad name\",\"enabled\":false,\"transport\":\"HTTP\",\"url\":\"http://x/mcp\"}",
            "HttpWithoutUrl       | {\"name\":\"noUrl\",\"enabled\":false,\"transport\":\"HTTP\"}",
            "StdioWithoutCommand  | {\"name\":\"noCmd\",\"enabled\":false,\"transport\":\"STDIO\"}"
    })
    void createRejectsInvalidPayload(String label, String body) {
        login();
        var resp = POST("/api/mcp-servers", "application/json", body);
        assertEquals(400, resp.status.intValue());
    }

    // ==================== update ====================

    @Test
    void updateTogglesEnabled() {
        login();
        var id = createHttpServer("toggle", "http://127.0.0.1:1/mcp", false);
        var resp = PUT("/api/mcp-servers/" + id, "application/json", "{\"enabled\":true}");
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"enabled\":true"));
    }

    @Test
    void updateChangesTransportRebuildsConfig() {
        login();
        var id = createHttpServer("morph", "http://127.0.0.1:1/mcp", false);
        var resp = PUT("/api/mcp-servers/" + id, "application/json", """
                {"transport":"STDIO","command":"node","args":["x.js"]}
                """);
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"transport\":\"STDIO\""));
        assertTrue(content.contains("\"command\":\"node\""));
        assertFalse(content.contains("\"url\":\"http://127.0.0.1:1/mcp\""),
                "old transport's fields must be dropped: " + content);
    }

    @Test
    void updateRejectsRenameToExistingName() {
        login();
        createHttpServer("a", "http://x/mcp", false);
        var idB = createHttpServer("b", "http://y/mcp", false);
        var resp = PUT("/api/mcp-servers/" + idB, "application/json", "{\"name\":\"a\"}");
        assertEquals(409, resp.status.intValue());
    }

    @Test
    void updateWithExplicitNullNameKeepsCurrentName() {
        login();
        var id = createHttpServer("keepme", "http://127.0.0.1:1/mcp", false);
        var resp = PUT("/api/mcp-servers/" + id, "application/json", "{\"name\":null}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"name\":\"keepme\""), getContent(resp));
    }

    @Test
    void updateUnknownIdReturns404() {
        login();
        var resp = PUT("/api/mcp-servers/999999", "application/json", "{\"enabled\":true}");
        assertEquals(404, resp.status.intValue());
    }

    // ==================== delete ====================

    @Test
    void deleteRemovesRowAndCallsStop() {
        login();
        var id = createHttpServer("doomed", "http://127.0.0.1:1/mcp", false);
        var resp = DELETE("/api/mcp-servers/" + id);
        assertIsOk(resp);
        var deleteBody = getContent(resp);
        assertTrue(deleteBody.contains("\"status\":\"ok\""), "canonical ack: " + deleteBody);
        assertTrue(deleteBody.contains("\"deleted\":true"), "delete flag: " + deleteBody);
        // Verify the row really is gone via the list endpoint.
        var listing = getContent(GET("/api/mcp-servers"));
        assertFalse(listing.contains("\"name\":\"doomed\""),
                "deleted row must not appear in list: " + listing);
    }

    @Test
    void deleteAlsoClearsAllowlistRows() {
        login();
        var id = createHttpServer("clean", "http://127.0.0.1:1/mcp", false);
        // Seed a stray allowlist row in a fresh VT so it actually commits
        // (FunctionalTest carrier thread holds an uncommitted Tx; an inline
        // Tx.run would join it and the HTTP DELETE wouldn't see the row).
        commitInFreshTx(() -> {
            var agent = new models.Agent();
            agent.name = "clean-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var row = new AgentSkillAllowedTool();
            row.agent = agent;
            row.skillName = "mcp:clean";
            row.toolName = "echo";
            row.save();
            return null;
        });
        // Confirms the seed committed before the DELETE runs; normally one no-spin read.
        awaitCommitted(() ->
                AgentSkillAllowedTool.count("skillName = ?1", "mcp:clean") == 1L,
                "seeded allowlist row never became visible to a fresh Tx");
        var resp = DELETE("/api/mcp-servers/" + id);
        assertIsOk(resp);
        // Spin until the controller's commit is visible to a fresh Tx; normally one no-spin read.
        awaitCommitted(() -> AgentSkillAllowedTool.count("skillName = ?1", "mcp:clean") == 0L,
                () -> "delete must cascade through stop() and clear allowlist rows; " + cleanupDiagnostics());
    }

    // ==================== JCLAW-1331: masked secrets written back ====================

    @Test
    void savingAStdioServerAsItWasReadKeepsItsSecretEnvValues() {
        login();
        var view = created("""
                {"name":"keeps","enabled":false,"transport":"STDIO","command":"node","args":["s.js"],
                 "env":{"GITHUB_TOKEN":"ghp_real_secret","NODE_ENV":"production"}}
                """);
        var env = view.getAsJsonObject("env");
        assertEquals("ghp_****", env.get("GITHUB_TOKEN").getAsString(), view.toString());

        var body = new JsonObject();
        body.addProperty("name", "kept");
        body.addProperty("command", "node");
        body.add("args", view.get("args"));
        body.add("env", env);
        assertIsOk(PUT("/api/mcp-servers/" + view.get("id").getAsLong(), "application/json", body.toString()));

        var stored = storedConfig(view.get("id").getAsLong(), "kept");
        assertTrue(stored.contains("\"GITHUB_TOKEN\":\"ghp_real_secret\""), stored);
        assertTrue(stored.contains("\"NODE_ENV\":\"production\""), stored);
    }

    @Test
    void savingAnHttpServerAsItWasReadKeepsItsHeaderValues() {
        login();
        var view = created("""
                {"name":"remote-keeps","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp",
                 "headers":{"Authorization":"Bearer real-token","X-Tier":"gold"}}
                """);
        var headers = view.getAsJsonObject("headers");
        assertEquals("Bear****", headers.get("Authorization").getAsString(), view.toString());

        var body = new JsonObject();
        body.addProperty("name", "remote-kept");
        body.addProperty("url", "http://127.0.0.1:1/mcp");
        body.add("headers", headers);
        assertIsOk(PUT("/api/mcp-servers/" + view.get("id").getAsLong(), "application/json", body.toString()));

        var stored = storedConfig(view.get("id").getAsLong(), "remote-kept");
        assertTrue(stored.contains("\"Authorization\":\"Bearer real-token\""), stored);
        assertTrue(stored.contains("\"X-Tier\":\"gold\""), stored);
    }

    @Test
    void aMaskThatMatchesNothingStoredUnderItsNameIsRefused() {
        login();
        var view = created("""
                {"name":"renamed","enabled":false,"transport":"STDIO","command":"node",
                 "env":{"GITHUB_TOKEN":"ghp_real_secret"}}
                """);
        var id = view.get("id").getAsLong();

        var renamed = PUT("/api/mcp-servers/" + id, "application/json",
                "{\"command\":\"node\",\"env\":{\"GH_TOKEN\":\"ghp_****\"}}");
        assertStatus(400, renamed);
        assertTrue(getContent(renamed).contains("GH_TOKEN"), getContent(renamed));
        assertTrue(storedConfig(id, "renamed").contains("ghp_real_secret"));

        var copied = POST("/api/mcp-servers", "application/json", """
                {"name":"copy","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp",
                 "headers":{"Authorization":"Bear****"}}
                """);
        assertStatus(400, copied);
    }

    // ==================== JCLAW-388: requiresApproval flag round-trip ====================

    @Test
    void createDefaultsRequiresApprovalToFalseWhenOmitted() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"noflag","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"requiresApproval\":false"),
                "absent flag must default to false in the response: " + getContent(resp));
    }

    @Test
    void createPersistsRequiresApprovalTrue() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"gated","enabled":false,"requiresApproval":true,
                 "transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"requiresApproval\":true"),
                "supplied flag must round-trip true: " + getContent(resp));
        // Confirm it really persisted by re-reading via GET.
        var listing = getContent(GET("/api/mcp-servers"));
        assertTrue(listing.contains("\"name\":\"gated\"") && listing.contains("\"requiresApproval\":true"),
                "persisted flag must surface on a fresh read: " + listing);
    }

    @Test
    void updateTogglesRequiresApproval() {
        login();
        var id = createHttpServer("toggle-approval", "http://127.0.0.1:1/mcp", false);
        var on = PUT("/api/mcp-servers/" + id, "application/json", "{\"requiresApproval\":true}");
        assertIsOk(on);
        assertTrue(getContent(on).contains("\"requiresApproval\":true"));
        var off = PUT("/api/mcp-servers/" + id, "application/json", "{\"requiresApproval\":false}");
        assertIsOk(off);
        assertTrue(getContent(off).contains("\"requiresApproval\":false"));
    }

    @Test
    void updateOmittingRequiresApprovalLeavesItIntact() {
        login();
        // Create with the flag on, then PUT an unrelated field; the flag must
        // survive (partial update must not silently reset it to default).
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"keepflag","enabled":false,"requiresApproval":true,
                 "transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """);
        assertIsOk(resp);
        var id = java.util.regex.Pattern.compile("\"id\":(\\d+)")
                .matcher(getContent(resp)).results().findFirst().orElseThrow().group(1);
        var put = PUT("/api/mcp-servers/" + id, "application/json", "{\"enabled\":true}");
        assertIsOk(put);
        assertTrue(getContent(put).contains("\"requiresApproval\":true"),
                "a PUT that omits requiresApproval must not reset it: " + getContent(put));
    }

    // ==================== Offer view tools on every channel ====================

    private static boolean viewToolsEverywhere(com.google.gson.JsonElement view) {
        return view.getAsJsonObject().get("offerViewToolsEverywhere").getAsBoolean();
    }

    @Test
    void theViewToolsSettingDefaultsOffAndRoundTripsThroughTheApiAndTheColumn() {
        login();
        var off = created("""
                {"name":"plainviews","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """);
        assertFalse(viewToolsEverywhere(off), off.toString());
        var on = created("""
                {"name":"n8n","enabled":false,"offerViewToolsEverywhere":true,"transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """);
        assertTrue(viewToolsEverywhere(on), on.toString());
        var id = on.get("id").getAsLong();
        assertTrue(viewToolsEverywhere(JsonParser.parseString(getContent(GET("/api/mcp-servers/" + id)))));
        assertTrue(commitInFreshTx(() -> McpServer.<McpServer>findById(id).offerViewToolsEverywhere), "the column holds it");

        var listed = new java.util.HashMap<String, Boolean>();
        JsonParser.parseString(getContent(GET("/api/mcp-servers"))).getAsJsonArray().forEach(row ->
                listed.put(row.getAsJsonObject().get("name").getAsString(), viewToolsEverywhere(row)));
        assertEquals(Boolean.TRUE, listed.get("n8n"));
        assertEquals(Boolean.FALSE, listed.get("plainviews"));

        var renamed = PUT("/api/mcp-servers/" + id, "application/json", "{\"name\":\"n8n-mcp\"}");
        assertIsOk(renamed);
        assertTrue(viewToolsEverywhere(JsonParser.parseString(getContent(renamed))), "a PUT that omits it keeps it");
        var cleared = PUT("/api/mcp-servers/" + id, "application/json", "{\"offerViewToolsEverywhere\":false}");
        assertIsOk(cleared);
        assertFalse(viewToolsEverywhere(JsonParser.parseString(getContent(cleared))));
        awaitCommitted(() -> !McpServer.<McpServer>findById(id).offerViewToolsEverywhere, "the column never cleared");
    }

    @Test
    void savingTheSettingReachesTheLiveConnection() throws Exception {
        login();
        withViewServer(server -> {
            assertFalse(McpConnectionManager.offersViewToolsEverywhere(server));
            var id = commitInFreshTx(() -> McpServer.findByName(server).id);
            assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", "{\"offerViewToolsEverywhere\":true}"));
            assertTrue(McpConnectionManager.offersViewToolsEverywhere(server));
        });
    }

    @Test
    void onlyTheOperatorChangesWhichChannelsSeeAServersViewTools() {
        login();
        var id = createHttpServer("agentflag", "http://127.0.0.1:1/mcp", false);
        clearCookies();
        var bearer = AuthFixture.seedBearerToken();
        java.util.function.Supplier<Http.Request> agent = () -> {
            var request = newRequest();
            request.headers.put("authorization", new Http.Header("authorization", "Bearer " + bearer));
            return request;
        };
        var refused = "offerViewToolsEverywhere is operator-only. A server's view tools are already offered in the operator's "
                + "web chat; this setting only extends them to other channels, tasks and subagents.";
        var update = PUT(agent.get(), "/api/mcp-servers/" + id, "application/json", "{\"offerViewToolsEverywhere\":true}");
        assertEquals(403, update.status.intValue());
        assertEquals(refused, JsonParser.parseString(getContent(update)).getAsJsonObject().get("message").getAsString());
        var create = POST(agent.get(), "/api/mcp-servers", "application/json", "{\"name\":\"agentmade\",\"enabled\":false,"
                + "\"offerViewToolsEverywhere\":true,\"transport\":\"HTTP\",\"url\":\"http://127.0.0.1:1/mcp\"}");
        assertEquals(403, create.status.intValue());
        assertIsOk(PUT(agent.get(), "/api/mcp-servers/" + id, "application/json", "{\"offerViewToolsEverywhere\":false}"));
        clearCookies();
        assertFalse(commitInFreshTx(() -> McpServer.<McpServer>findById(Long.valueOf(id)).offerViewToolsEverywhere));
        assertNull(commitInFreshTx(() -> McpServer.findByName("agentmade")));

        login();
        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", "{\"offerViewToolsEverywhere\":true}"));
        awaitCommitted(() -> McpServer.<McpServer>findById(Long.valueOf(id)).offerViewToolsEverywhere, "the operator's change never saved");
    }

    @Test
    void aViewToolsSettingThatIsNotABooleanIsRefused() {
        login();
        var refused = "offerViewToolsEverywhere must be true or false";
        for (var value : List.of("\"yes\"", "1", "{}", "[]")) {
            var create = POST("/api/mcp-servers", "application/json", "{\"name\":\"bad\",\"enabled\":false,"
                    + "\"offerViewToolsEverywhere\":" + value + ",\"transport\":\"HTTP\",\"url\":\"http://127.0.0.1:1/mcp\"}");
            assertEquals(400, create.status.intValue(), value);
            assertEquals(refused, JsonParser.parseString(getContent(create)).getAsJsonObject().get("message").getAsString());
        }
        var id = createHttpServer("viewflag", "http://127.0.0.1:1/mcp", false);
        var update = PUT("/api/mcp-servers/" + id, "application/json", "{\"offerViewToolsEverywhere\":\"true\"}");
        assertEquals(400, update.status.intValue());
        assertEquals(refused, JsonParser.parseString(getContent(update)).getAsJsonObject().get("message").getAsString());
    }

    @Test
    void aServerThatExistedAtTheUpgradeReadsOnAndANewOneOff() {
        // The column's DDL default is what the ALTER backfills onto every row already in mcp_server.
        var columnDefault = commitInFreshTx(() -> String.valueOf(play.db.jpa.JPA.em().createNativeQuery(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'MCP_SERVER'"
                        + " AND COLUMN_NAME = 'OFFER_VIEW_TOOLS_EVERYWHERE'").getSingleResult()));
        assertEquals("TRUE", columnDefault);
        login();
        assertFalse(viewToolsEverywhere(created("""
                {"name":"added-later","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """)));
    }

    // ==================== JCLAW-1443: protocol setting ====================

    @Test
    void aServerCreatedWithoutAProtocolReadsAutomatic() {
        login();
        var created = created("""
                {"name":"proto-default","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """);
        assertEquals("AUTOMATIC", created.get("protocol").getAsString());
        assertTrue(created.get("protocolVersion") == null || created.get("protocolVersion").isJsonNull());
        assertTrue(created.get("protocolGeneration") == null || created.get("protocolGeneration").isJsonNull());
    }

    @Test
    void theProtocolSettingRoundTripsAndRejectsAnUnknownValue() {
        login();
        var created = created("""
                {"name":"proto-set","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp",
                 "protocol":"HANDSHAKE"}
                """);
        assertEquals("HANDSHAKE", created.get("protocol").getAsString());
        var id = created.get("id").getAsLong();
        var back = PUT("/api/mcp-servers/" + id, "application/json", "{\"protocol\":\"AUTOMATIC\"}");
        assertIsOk(back);
        assertTrue(getContent(back).contains("\"protocol\":\"AUTOMATIC\""), getContent(back));
        assertStatus(400, PUT("/api/mcp-servers/" + id, "application/json", "{\"protocol\":\"STATELESS\"}"));
    }

    @Test
    void settingTheProtocolEndsTheNoteThatJclawSwitchedIt() {
        login();
        var id = created("""
                {"name":"proto-note","enabled":false,"transport":"HTTP","url":"http://127.0.0.1:1/mcp"}
                """).get("id").getAsLong();
        commitInFreshTx(() -> {
            models.McpServer row = models.McpServer.findById(id);
            row.protocol = models.McpServer.Protocol.HANDSHAKE;
            row.protocolSwitchedAt = java.time.Instant.parse("2026-10-11T08:00:00Z");
            row.protocolSwitchReason = "the first tools/list over 2026-07-28 failed";
            row.probeTimedOutAt = java.time.Instant.parse("2026-10-11T07:00:00Z");
            row.save();
            return null;
        });
        var unchanged = JsonParser.parseString(getContent(PUT("/api/mcp-servers/" + id, "application/json",
                "{\"protocol\":\"HANDSHAKE\"}"))).getAsJsonObject();
        assertEquals("the first tools/list over 2026-07-28 failed", unchanged.get("protocolSwitchReason").getAsString(),
                "saving the setting it already has is not the operator changing it");

        var changed = JsonParser.parseString(getContent(PUT("/api/mcp-servers/" + id, "application/json",
                "{\"protocol\":\"AUTOMATIC\"}"))).getAsJsonObject();
        assertEquals("AUTOMATIC", changed.get("protocol").getAsString());
        assertTrue(changed.get("protocolSwitchedAt") == null || changed.get("protocolSwitchedAt").isJsonNull());
        assertTrue(changed.get("protocolSwitchReason") == null || changed.get("protocolSwitchReason").isJsonNull());
        awaitCommitted(() -> models.McpServer.<models.McpServer>findById(id).probeTimedOutAt == null,
                "a changed setting gives the probe another chance");
    }

    // ==================== test connection ====================

    @Test
    void testConnectionReturnsErrorOnUnreachableServer() {
        login();
        var id = createHttpServer("dead", "http://127.0.0.1:1/mcp", false);
        var resp = POST("/api/mcp-servers/" + id + "/test", "application/json", "{}");
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"success\":false"),
                "unreachable server must report success=false: " + content);
        assertTrue(content.contains("\"message\""));
    }

    // ==================== MCP Apps view pages ====================

    private static final String PAGE = "<!doctype html><html><body><div id=\"root\"></div></body></html>";
    private static final String HOST_DIRECTIVES =
            "; object-src 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-scripts";

    @FunctionalInterface
    private interface ViewCheck {
        void run(String server) throws Exception;
    }

    /** A 2026-07-28 server with view pages; {@code check} runs against it while it is connected. */
    private static void withViewServer(ViewCheck check) throws Exception {
        withViewServer(false, check);
    }

    /** As {@link #withViewServer(ViewCheck)}, with {@code resources/list} failing when {@code listingFails}. */
    private static void withViewServer(boolean listingFails, ViewCheck check) throws Exception {
        withViewServer(listingFails, false, new CopyOnWriteArrayList<>(), check);
    }

    /** A {@code tools/call} the server answered, and the server's MCP_TOOL_INVOKE rows a fresh transaction saw as it arrived. */
    private record ToolCall(JsonObject params, List<String> auditAtCall) {}

    /**
     * {@code search_table} draws {@code ui://page.html} for model and view alike, {@code refresh} is for the view
     * alone, {@code summarize} for the model alone, {@code plain} declares no {@code _meta}, {@code erroring} answers
     * with {@code isError: true}, and {@code broken} answers every call with a JSON-RPC error.
     */
    private static final String VIEW_TOOLS = "{\"tools\":["
            + "{\"name\":\"search_table\",\"description\":\"Table.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"resourceUri\":\"ui://page.html\"}}},"
            + "{\"name\":\"refresh\",\"description\":\"Refresh.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"resourceUri\":\"ui://page.html\",\"visibility\":[\"app\"]}}},"
            + "{\"name\":\"summarize\",\"description\":\"Summary.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"visibility\":[\"model\"]}}},"
            + "{\"name\":\"plain\",\"description\":\"Plain.\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"erroring\",\"description\":\"Errs.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"visibility\":[\"app\"]}}},"
            + "{\"name\":\"broken\",\"description\":\"Fails.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"visibility\":[\"app\"]}}}]}";

    /**
     * As {@link #withViewServer(boolean, ViewCheck)}, the row's {@code requiresApproval} set as given, and every
     * {@code tools/call} the server answers added to {@code calls}.
     */
    private static void withViewServer(boolean listingFails, boolean requiresApproval, List<ToolCall> calls, ViewCheck check)
            throws Exception {
        ToolRegistrySync.canonicalForTest();
        var name = "views-" + UUID.randomUUID().toString().substring(0, 8);
        try (var http = new MockWebServer()) {
            http.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (!(JsonRpc.decode(request.getBody().utf8()) instanceof JsonRpc.Request r)) {
                        return new MockResponse.Builder().code(202).build();
                    }
                    return switch (r.method()) {
                        case "server/discover" -> rpc(r.id(),
                                "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{\"tools\":{},\"resources\":{}}}");
                        case "tools/list" -> rpc(r.id(), VIEW_TOOLS);
                        case "tools/call" -> {
                            var params = (JsonObject) Objects.requireNonNull(r.params());
                            calls.add(new ToolCall(params, invokeAudit(name)));
                            yield switch (params.get("name").getAsString()) {
                                case "broken" -> new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                                        .body("{\"jsonrpc\":\"2.0\",\"id\":" + r.id() + ",\"error\":{\"code\":-32603,"
                                                + "\"message\":\"upstream said: token sk-test-123 expired\"}}").build();
                                case "erroring" -> rpc(r.id(), "{\"content\":[{\"type\":\"text\",\"text\":\"Quota used up\"}],"
                                        + "\"isError\":true}");
                                default -> rpc(r.id(), "{\"content\":[{\"type\":\"text\",\"text\":\"Page 2\"}],"
                                        + "\"structuredContent\":{\"rows\":[4,5]},\"_meta\":{\"k\":\"v\"}}");
                            };
                        }
                        case "resources/list" -> listingFails ? rpcError(r.id(), -32603) : rpc(r.id(), "{\"resources\":["
                                + "{\"uri\":\"ui://listed.html\",\"mimeType\":\"text/html;profile=mcp-app\","
                                + "\"_meta\":{\"ui\":{\"csp\":{\"connectDomains\":[\"https://api.example.com\"]}}}},"
                                + "{\"uri\":\"ui://nocsp.html\",\"mimeType\":\"text/html;profile=mcp-app\","
                                + "\"_meta\":{\"ui\":{\"csp\":{\"frameDomains\":[\"https://www.youtube.com\"]}}}}]}");
                        case "resources/templates/list" -> rpc(r.id(), "{\"resourceTemplates\":[]}");
                        case "resources/read" -> read(r.id(), ((JsonObject) Objects.requireNonNull(r.params())).get("uri").getAsString());
                        default -> rpc(r.id(), "{}");
                    };
                }
            });
            http.start();
            var cfg = new JsonObject();
            cfg.addProperty("url", http.url("/mcp").toString());
            var row = commitInFreshTx(() -> {
                var srv = new McpServer();
                srv.name = name;
                srv.enabled = true;
                srv.requiresApproval = requiresApproval;
                srv.transport = McpServer.Transport.HTTP;
                srv.configJson = cfg.toString();
                srv.save();
                return srv;
            });
            McpConnectionManager.connect(row);
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (McpConnectionManager.status(name) != McpServer.Status.CONNECTED) {
                if (System.nanoTime() > deadline) fail("never connected: " + McpConnectionManager.lastError(name));
                Thread.sleep(20);
            }
            check.run(name);
        } finally {
            McpConnectionManager.stop(name);
            CircuitBreakers.remove("mcp:" + name);
            ToolRegistrySync.release();
        }
    }

    /**
     * {@code ui://page.html} declares its CSP in the read; {@code ui://listed.html} only in its listing entry;
     * {@code ui://nocsp.html} declares a {@code _meta.ui} without {@code csp}; {@code ui://bare.html} names no type.
     */
    private static MockResponse read(Object id, String uri) {
        var page = new JsonPrimitive(PAGE);
        var app = "\"mimeType\":\"text/html;profile=mcp-app\",\"text\":" + page;
        return switch (uri) {
            case "ui://page.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://page.html\"," + app
                    + ",\"_meta\":{\"ui\":{\"csp\":{\"resourceDomains\":[\"https://cdn.jsdelivr.net\"]}}}}]}");
            case "ui://listed.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://listed.html\"," + app + "}]}");
            case "ui://nocsp.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://nocsp.html\"," + app
                    + ",\"_meta\":{\"ui\":{\"prefersBorder\":true}}}]}");
            case "ui://bare.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://bare.html\",\"text\":" + page + "}]}");
            case "UI://Upper.html" -> rpc(id, "{\"contents\":[{\"uri\":\"UI://Upper.html\"," + app + "}]}");
            case "ui://plain.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://plain.html\",\"mimeType\":\"text/html\",\"text\":"
                    + page + "}]}");
            case "ui://broken.html" -> rpcError(id, -32603);
            case "ui://empty.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://empty.html\",\"mimeType\":\"text/html;profile=mcp-app\","
                    + "\"text\":\" \"}]}");
            case "ui://reach.html" -> rpc(id, "{\"contents\":[{\"uri\":\"ui://reach.html\"," + app + ",\"_meta\":{\"ui\":{\"csp\":"
                    + "{\"connectDomains\":[\"http://192.168.1.10:3001\",\"http://localhost:3001\",\"'self'\"]}}}}]}");
            default -> rpcError(id, -32602);
        };
    }

    private static MockResponse rpc(Object id, String result) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").build();
    }

    private static MockResponse rpcError(Object id, int code) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":\"no\"}}").build();
    }

    /** The policy of a page that declares no {@code csp}. */
    private static final String DEFAULT_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline';"
            + " img-src data:; media-src data:; connect-src 'none'; base-uri 'none'" + HOST_DIRECTIVES;

    /** The page as the chat's frame asks for it. */
    private Http.Response view(String server, String uri) {
        return view(server, uri, "iframe");
    }

    private Http.Response view(String server, String uri, @org.jspecify.annotations.Nullable String dest) {
        var request = newRequest();
        if (dest != null) request.headers.put("sec-fetch-dest", new Http.Header("sec-fetch-dest", dest));
        return GET(request, "/api/mcp-servers/view?server=" + URLEncoder.encode(server, StandardCharsets.UTF_8)
                + "&uri=" + URLEncoder.encode(uri, StandardCharsets.UTF_8));
    }

    private static void assertFrameHeaders(Http.Response response) {
        assertEquals("SAMEORIGIN", response.getHeader("X-Frame-Options"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
    }

    private void assertUnavailable(Http.Response response, int status, String body) {
        assertEquals(status, response.status.intValue());
        assertEquals("text/plain; charset=utf-8", response.contentType);
        assertEquals("default-src 'none'; sandbox", response.getHeader("Content-Security-Policy"));
        assertFrameHeaders(response);
        assertEquals(body, getContent(response));
    }

    @Test
    void aViewPageIsServedUnderTheCspItsReadDeclaresAndOnlyJclawMayFrameIt() throws Exception {
        login();
        withViewServer(server -> {
            var response = view(server, "ui://page.html");
            assertIsOk(response);
            assertEquals("text/html; charset=utf-8", response.contentType);
            var cdn = " https://cdn.jsdelivr.net";
            assertEquals("default-src 'none'; script-src 'unsafe-inline'" + cdn + "; style-src 'unsafe-inline'" + cdn
                            + "; img-src data:" + cdn + "; font-src" + cdn + "; media-src data:" + cdn
                            + "; connect-src 'none'; frame-src 'none'; base-uri 'none'" + HOST_DIRECTIVES,
                    response.getHeader("Content-Security-Policy"));
            assertFrameHeaders(response);
            assertEquals(PAGE, getContent(response));
        });
    }

    @Test
    void aPageWhoseReadCarriesNoUiMetaTakesItFromItsListingEntry() throws Exception {
        login();
        withViewServer(server -> {
            var response = view(server, "ui://listed.html");
            assertIsOk(response);
            assertEquals("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:;"
                            + " font-src 'none'; media-src data:; connect-src https://api.example.com; frame-src 'none';"
                            + " base-uri 'none'" + HOST_DIRECTIVES,
                    response.getHeader("Content-Security-Policy"));
            assertEquals(PAGE, getContent(response));
        });
    }

    @Test
    void aReadUiMetaWithoutCspTakesTheListingsCsp() throws Exception {
        login();
        withViewServer(server -> {
            var response = view(server, "ui://nocsp.html");
            assertIsOk(response);
            assertEquals("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:;"
                            + " font-src 'none'; media-src data:; connect-src 'none'; frame-src https://www.youtube.com;"
                            + " base-uri 'none'" + HOST_DIRECTIVES,
                    response.getHeader("Content-Security-Policy"));
        });
    }

    @Test
    void anUpperCaseUiSchemeIsAPageUri() throws Exception {
        login();
        withViewServer(server -> {
            var response = view(server, "UI://Upper.html");
            assertIsOk(response);
            assertEquals(PAGE, getContent(response));
        });
    }

    @Test
    void aReadContentNamingNoTypeIsTheRequestedPage() throws Exception {
        login();
        withViewServer(server -> {
            var response = view(server, "ui://bare.html");
            assertIsOk(response);
            assertEquals(DEFAULT_CSP, response.getHeader("Content-Security-Policy"));
            assertEquals(PAGE, getContent(response));
        });
    }

    @Test
    void aListingThatFailsLeavesThePageOnTheDefaultPolicy() throws Exception {
        login();
        withViewServer(true, server -> {
            var response = view(server, "ui://listed.html");
            assertIsOk(response);
            assertEquals(DEFAULT_CSP, response.getHeader("Content-Security-Policy"));
            assertEquals(PAGE, getContent(response));
        });
    }

    @Test
    void aPageThatCannotBeServedGetsOneLineUnderASandbox() throws Exception {
        login();
        withViewServer(server -> {
            assertUnavailable(view(server, "ui://missing.html"), 404,
                    "MCP server '" + server + "' has no page ui://missing.html.");
            assertUnavailable(view(server, "ui://plain.html"), 502,
                    "MCP server '" + server + "' did not return an MCP Apps page.");
            assertUnavailable(view(server, "serpapi://engines"), 400, "Not an MCP Apps page URI.");
            assertUnavailable(view(server, "ui://broken.html"), 502, "MCP server '" + server + "' could not read the page.");
            assertUnavailable(view(server, "ui://empty.html"), 502, "MCP server '" + server + "' did not return an MCP Apps page.");
        });
        assertUnavailable(view("gone", "ui://page.html"), 503, "MCP server 'gone' is not connected.");
    }

    @Test
    void theViewPageIsServedOnlyToAFrame() throws Exception {
        login();
        withViewServer(server -> {
            assertIsOk(view(server, "ui://page.html", "iframe"));
            assertUnavailable(view(server, "ui://page.html", "document"), 403, "A view page opens only inside the chat.");
            assertUnavailable(view(server, "ui://page.html", null), 403, "A view page opens only inside the chat.");
        });
    }

    @Test
    void aLocalServersPageKeepsTheLanButNeverJclawsHostAndOneWarningSaysSo() throws Exception {
        login();
        withViewServer(server -> {
            var warnings = new java.util.ArrayList<String>();
            services.EventLogger.captureMatchingForTest(e -> e.category().equals("MCP_APPS") && e.message().contains(server),
                    seen -> {
                        var response = view(server, "ui://reach.html");
                        assertIsOk(response);
                        assertEquals("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:;"
                                        + " font-src 'none'; media-src data:; connect-src http://192.168.1.10:3001; frame-src 'none';"
                                        + " base-uri 'none'" + HOST_DIRECTIVES,
                                response.getHeader("Content-Security-Policy"));
                        seen.forEach(e -> warnings.add(e.message()));
                    });
            assertEquals(List.of("MCP server '" + server + "' view page \"ui://reach.html\" declared origins that were left out"
                    + " of its CSP: {\"connectDomains\":[\"http://localhost:3001\",\"'self'\"]}"), warnings);
        });
    }

    // ==================== MCP Apps view tool calls ====================

    /** A web conversation of a new agent, which every connected server grants each of its tools. */
    private static long webConversation(String agentName) {
        return webConversation(agentName, null);
    }

    /** As {@link #webConversation(String)}, with {@code server} switched on for the agent, as a non-main agent needs. */
    private static long webConversation(String agentName, @org.jspecify.annotations.Nullable String server) {
        var id = commitInFreshTx(() -> {
            var agent = services.AgentService.create(agentName, "openrouter", "gpt-4.1");
            if (server != null) {
                var on = mcp.McpGrants.newRow(agent, McpServer.toolName(server, ""));
                on.enabled = true;
                on.save();
            }
            return services.ConversationService.create(agent, "web", null).id;
        });
        agents.ToolRegistry.clearDisabledToolsCache();
        return id;
    }

    private Http.Response viewCall(Object conversationId, String server, String tool, String arguments) {
        return viewCall(newRequest(), conversationId, server, tool, arguments);
    }

    private Http.Response viewCall(Http.Request request, Object conversationId, String server, String tool, String arguments) {
        var body = new JsonObject();
        body.add("conversationId", JsonParser.parseString(String.valueOf(conversationId)));
        body.addProperty("server", server);
        body.addProperty("tool", tool);
        body.add("arguments", JsonParser.parseString(arguments));
        return POST(request, "/api/mcp-servers/view/call", "application/json", body.toString());
    }

    /** Each MCP_TOOL_INVOKE row for {@code server}, oldest first, as {@code level message details}. */
    private static List<String> invokeAudit(String server) {
        return commitInFreshTx(() -> {
            List<models.EventLog> rows = models.EventLog.find("category = ?1 and message like ?2 order by id",
                    "MCP_TOOL_INVOKE", server + "/%").fetch();
            return rows.stream().map(e -> e.level + " " + e.message + " " + e.details).toList();
        });
    }

    private static void assertAudit(String server, List<String> expected) {
        awaitCommitted(() -> invokeAudit(server).equals(expected), () -> "audit rows: " + invokeAudit(server));
    }

    @Test
    void aViewCallsAToolOfItsOwnServerAsTheConversationsAgentAndGetsTheResultForTheView() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var conversation = webConversation("viewer", server);
            var appOnly = viewCall(conversation, server, "refresh", "{\"page\":2}");
            assertIsOk(appOnly);
            assertEquals("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Page 2\"}],"
                    + "\"structuredContent\":{\"rows\":[4,5]},\"_meta\":{\"k\":\"v\"},\"isError\":false}}", getContent(appOnly));
            var both = viewCall(conversation, server, "search_table", "{}");
            assertEquals(getContent(appOnly), getContent(both));
            assertEquals(List.of("refresh", "search_table"), calls.stream().map(c -> c.params().get("name").getAsString()).toList());
            assertEquals("{\"page\":2}", calls.get(0).params().get("arguments").toString());
            var refreshed = "INFO " + server + "/refresh by 'viewer' from its view [allowed] {\"page\":2}";
            var searched = "INFO " + server + "/search_table by 'viewer' from its view [allowed] {}";
            assertEquals(List.of(refreshed), calls.get(0).auditAtCall(), "the grant and its audit row commit before the call goes out");
            assertEquals(List.of(refreshed, searched), calls.get(1).auditAtCall());
            assertAudit(server, List.of(refreshed, searched));
        });
    }

    @Test
    void aToolWithNoMetaTakesTheDefaultVisibilityAndAViewMayCallIt() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var response = viewCall(webConversation("viewer", server), server, "plain", "{}");
            assertIsOk(response);
            assertEquals("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Page 2\"}],"
                    + "\"structuredContent\":{\"rows\":[4,5]},\"_meta\":{\"k\":\"v\"},\"isError\":false}}", getContent(response));
            assertEquals(List.of("plain"), calls.stream().map(c -> c.params().get("name").getAsString()).toList());
        });
    }

    @Test
    void aToolThatReportsAnErrorReachesTheViewAsItsResult() throws Exception {
        login();
        withViewServer(false, false, new CopyOnWriteArrayList<>(), server -> {
            var response = viewCall(webConversation("viewer", server), server, "erroring", "{}");
            assertIsOk(response);
            assertEquals("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Quota used up\"}],\"isError\":true}}",
                    getContent(response));
        });
    }

    @Test
    void absentArgumentsReachTheServerAsAnEmptyObject() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var body = new JsonObject();
            body.addProperty("conversationId", webConversation("viewer", server));
            body.addProperty("server", server);
            body.addProperty("tool", "refresh");
            assertIsOk(POST("/api/mcp-servers/view/call", "application/json", body.toString()));
            assertEquals(1, calls.size());
            assertEquals("{}", calls.get(0).params().get("arguments").toString());
        });
    }

    @Test
    void aViewActsOnlyInAWebChatConversation() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            webConversation("viewer", server);
            var telegram = commitInFreshTx(() -> services.ConversationService.create(
                    services.AgentService.findByName("viewer"), "telegram", "12345").id);
            var response = viewCall(telegram, server, "refresh", "{}");
            assertIsOk(response);
            assertEquals("{\"error\":\"Views act only in web chat.\"}", getContent(response));
            assertEquals(List.of(), calls);
            assertEquals(List.of(), invokeAudit(server));
        });
    }

    @Test
    void aToolTheViewMayNotCallIsRefusedAndNeverCalled() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var conversation = webConversation("viewer", server);
            for (var tool : List.of("summarize", "nope")) {
                var response = viewCall(conversation, server, tool, "{}");
                assertIsOk(response);
                assertEquals("{\"error\":\"This tool cannot be called from its view.\"}", getContent(response), tool);
            }
            assertEquals(List.of(), calls);
            assertEquals(List.of(), invokeAudit(server));
        });
    }

    @Test
    void aServerThatNeedsApprovalForEachCallTakesNoneFromAView() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, true, calls, server -> {
            var response = viewCall(webConversation("viewer", server), server, "refresh", "{}");
            assertIsOk(response);
            assertEquals("{\"error\":\"This server needs approval for each call, which a view cannot give.\"}", getContent(response));
            assertEquals(List.of(), calls);
        });
    }

    @Test
    void anAgentWithoutTheGrantIsRefusedAndTheRefusalIsAudited() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var conversation = webConversation("viewer", server);
            commitInFreshTx(() -> AgentSkillAllowedTool.delete("skillName = ?1 and toolName = ?2", "mcp:" + server, "refresh"));
            var response = viewCall(conversation, server, "refresh", "{\"page\":2}");
            assertIsOk(response);
            assertEquals("{\"error\":\"The conversation's agent is not allowed to call this tool.\"}", getContent(response));
            assertEquals(List.of(), calls);
            assertAudit(server, List.of("WARN " + server + "/refresh by 'viewer' from its view [denied] {\"page\":2}"));
        });
    }

    @Test
    void anAgentWithTheServerSwitchedOffIsRefusedAndTheRefusalIsAudited() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            // A non-main agent starts with every MCP server switched off.
            var response = viewCall(webConversation("viewer"), server, "refresh", "{}");
            assertIsOk(response);
            assertEquals("{\"error\":\"The conversation's agent is not allowed to call this tool.\"}", getContent(response));
            assertEquals(List.of(), calls);
            assertAudit(server, List.of("WARN " + server + "/refresh by 'viewer' from its view [denied] {}"));
        });
    }

    @Test
    void aServerThatIsNotConnectedIsRefused() {
        login();
        var response = viewCall(webConversation("viewer"), "gone", "refresh", "{}");
        assertIsOk(response);
        assertEquals("{\"error\":\"The server is not connected.\"}", getContent(response));
        assertEquals(List.of(), invokeAudit("gone"));
    }

    @Test
    void aServersErrorReachesTheEventLogButNeverTheView() throws Exception {
        login();
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var conversation = webConversation("viewer", server);
            var warnings = new java.util.ArrayList<String>();
            services.EventLogger.captureMatchingForTest(e -> e.category().equals("MCP_APPS") && e.message().contains(server),
                    seen -> {
                        var response = viewCall(conversation, server, "broken", "{}");
                        assertIsOk(response);
                        assertEquals("{\"error\":\"The tool call failed.\"}", getContent(response));
                        seen.forEach(e -> warnings.add(e.message()));
                    });
            assertEquals(1, calls.size());
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).startsWith("MCP server '" + server + "' tool 'broken' failed for its view: "), warnings.get(0));
        });
    }

    @Test
    void aViewCallNeedsAConversationAndObjectArguments() {
        login();
        var conversation = webConversation("viewer");
        assertEquals(404, viewCall(999_999L, "serpapi", "refresh", "{}").status.intValue());
        var noConversation = POST("/api/mcp-servers/view/call", "application/json",
                "{\"server\":\"serpapi\",\"tool\":\"refresh\",\"arguments\":{}}");
        assertEquals(400, noConversation.status.intValue());
        assertEquals("Field 'conversationId' is required",
                JsonParser.parseString(getContent(noConversation)).getAsJsonObject().get("message").getAsString());
        var listArguments = viewCall(conversation, "serpapi", "refresh", "[1]");
        assertEquals(400, listArguments.status.intValue());
        assertEquals("Field 'arguments' must be an object",
                JsonParser.parseString(getContent(listArguments)).getAsJsonObject().get("message").getAsString());
        assertEquals(400, viewCall(conversation, "serpapi", "", "{}").status.intValue());
    }

    @Test
    void onlyTheOperatorsSessionMayCallAToolForAView() throws Exception {
        var calls = new CopyOnWriteArrayList<ToolCall>();
        withViewServer(false, false, calls, server -> {
            var conversation = webConversation("viewer", server);
            assertEquals(401, viewCall(conversation, server, "refresh", "{}").status.intValue());
            var request = newRequest();
            request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
            var agent = viewCall(request, conversation, server, "refresh", "{}");
            assertEquals(403, agent.status.intValue());
            assertEquals("This endpoint is operator-only; an agent principal cannot call it.",
                    JsonParser.parseString(getContent(agent)).getAsJsonObject().get("message").getAsString());
            assertEquals(List.of(), calls);
        });
    }

    @Test
    void aViewPageNeedsTheOperatorsSession() {
        var response = view("any", "ui://page.html");
        assertEquals(401, response.status.intValue());
    }

    // ==================== helpers ====================

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private JsonObject created(String body) {
        var resp = POST("/api/mcp-servers", "application/json", body);
        assertIsOk(resp);
        return JsonParser.parseString(getContent(resp)).getAsJsonObject();
    }

    /** The row's stored config once a fresh Tx sees the update that renamed it to {@code name}. */
    private static String storedConfig(long id, String name) {
        awaitCommitted(() -> name.equals(commitInFreshTx(() -> models.McpServer.<models.McpServer>findById(id).name)),
                "the update never became visible to a fresh Tx");
        return commitInFreshTx(() -> models.McpServer.<models.McpServer>findById(id).configJson);
    }

    private String createHttpServer(String name, String url, boolean enabled) {
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"%s","enabled":%s,"transport":"HTTP","url":"%s"}
                """.formatted(name, enabled, url));
        assertIsOk(resp);
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(getContent(resp));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Run {@code block} in a fresh platform thread + Tx (JCLAW-688:
     *  a platform thread, NOT a virtual thread — under heavy suite load the VT
     *  carrier pool starves (JDK-8373224) and the fresh-VT commit is delayed past
     *  awaitCommitted's deadline, flaking the barrier) so the commit lands
     *  before the calling thread proceeds. Required for HTTP-visible seed
     *  data per the FunctionalTest Tx isolation memory. */
    private static <T> T commitInFreshTx(java.util.concurrent.Callable<T> block) {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try { holder.set(services.Tx.run(() -> {
                try { return block.call(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })); }
            catch (Throwable e) { err.set(e); }
        });
        try { t.join(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        if (err.get() != null) throw new RuntimeException(err.get());
        return holder.get();
    }

    /** Spin until {@code cond}, evaluated in a fresh committed Tx, holds — up to
     *  ~2s — then return; fail with {@code msg} if it never does. Defends against
     *  the rare cross-connection commit-visibility lag under the concurrent
     *  TestEngine (a fresh-VT commit not yet visible to a later request's
     *  connection). Normally returns on the first read with no sleep. */
    private static void awaitCommitted(java.util.concurrent.Callable<Boolean> cond, String msg) {
        awaitCommitted(cond, () -> msg);
    }

    private static void awaitCommitted(java.util.concurrent.Callable<Boolean> cond,
                                       java.util.function.Supplier<String> msg) {
        // 10s, not 2s (JCLAW-615 follow-up): the barrier polls every 10ms and
        // returns on the first success, so a generous deadline costs nothing
        // when timing is healthy — but 2s expired on a heavily loaded box.
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(commitInFreshTx(cond))) return;
            try { Thread.sleep(10); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        org.junit.jupiter.api.Assertions.fail(msg.get());
    }

    /** What stop()'s cleanup left behind. It logs and swallows a failed delete, and skips the sweep while
     *  EventLogger's shutdown latch is set (JCLAW-1454), so a timeout names which it was. */
    private static String cleanupDiagnostics() {
        services.EventLogger.flush();
        boolean latchSet = services.EventLogger.isShuttingDown();
        return commitInFreshTx(() -> {
            // clearAllowlistAndAudit saves its audit row directly, bypassing EventLogger, so only the table holds it.
            java.util.List<models.EventLog> events = models.EventLog.find("category = ?1 and message like ?2",
                    "MCP_TOOL_UNREGISTER", "%'clean'%").fetch();
            return "rows left %d, unregister events %s, shutdown latch set %s".formatted(
                    AgentSkillAllowedTool.count("skillName = ?1", "mcp:clean"),
                    events.stream().map(e -> e.level + " " + e.message).toList(), latchSet);
        });
    }
}
