import mcp.McpConnectionManager;
import models.AgentSkillAllowedTool;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

/**
 * Functional HTTP tests for {@code ApiMcpServersController} (JCLAW-33).
 * Covers auth gating, CRUD round-trips, the test endpoint, and the
 * delete path's downstream cleanup (allowlist + tool registry).
 */
public class ApiMcpServersControllerTest extends FunctionalTest {

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
    public void listRequiresAuth() {
        var response = GET("/api/mcp-servers");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void createRequiresAuth() {
        var response = POST("/api/mcp-servers", "application/json",
                "{\"name\":\"x\",\"transport\":\"HTTP\",\"url\":\"http://x\"}");
        assertEquals(401, response.status.intValue());
    }

    // ==================== list ====================

    @Test
    public void listReturnsEmptyArrayWithNoServers() {
        login();
        var response = GET("/api/mcp-servers");
        assertIsOk(response);
        assertEquals("[]", getContent(response).trim());
    }

    @Test
    public void listReturnsCreatedServerWithRuntimeStatus() {
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
    public void createPersistsHttpServer() {
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
    public void createPersistsStdioServer() {
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
    public void createRejectsDuplicateName() {
        login();
        createHttpServer("dup", "http://127.0.0.1:1/mcp", false);
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"dup","enabled":false,"transport":"HTTP","url":"http://x/mcp"}
                """);
        assertEquals(409, resp.status.intValue());
    }

    @Test
    public void createRejectsBadName() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"bad name","enabled":false,"transport":"HTTP","url":"http://x/mcp"}
                """);
        assertEquals(400, resp.status.intValue());
    }

    @Test
    public void createRejectsHttpWithoutUrl() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"noUrl","enabled":false,"transport":"HTTP"}
                """);
        assertEquals(400, resp.status.intValue());
    }

    @Test
    public void createRejectsStdioWithoutCommand() {
        login();
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"noCmd","enabled":false,"transport":"STDIO"}
                """);
        assertEquals(400, resp.status.intValue());
    }

    // ==================== update ====================

    @Test
    public void updateTogglesEnabled() {
        login();
        var id = createHttpServer("toggle", "http://127.0.0.1:1/mcp", false);
        var resp = PUT("/api/mcp-servers/" + id, "application/json", "{\"enabled\":true}");
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"enabled\":true"));
    }

    @Test
    public void updateChangesTransportRebuildsConfig() {
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
    public void updateRejectsRenameToExistingName() {
        login();
        createHttpServer("a", "http://x/mcp", false);
        var idB = createHttpServer("b", "http://y/mcp", false);
        var resp = PUT("/api/mcp-servers/" + idB, "application/json", "{\"name\":\"a\"}");
        assertEquals(409, resp.status.intValue());
    }

    @Test
    public void updateUnknownIdReturns404() {
        login();
        var resp = PUT("/api/mcp-servers/999999", "application/json", "{\"enabled\":true}");
        assertEquals(404, resp.status.intValue());
    }

    // ==================== delete ====================

    @Test
    public void deleteRemovesRowAndCallsStop() {
        login();
        var id = createHttpServer("doomed", "http://127.0.0.1:1/mcp", false);
        var resp = DELETE("/api/mcp-servers/" + id);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":true"));
        // Verify the row really is gone via the list endpoint.
        var listing = getContent(GET("/api/mcp-servers"));
        assertFalse(listing.contains("\"name\":\"doomed\""),
                "deleted row must not appear in list: " + listing);
    }

    @Test
    public void deleteAlsoClearsAllowlistRows() {
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
        var resp = DELETE("/api/mcp-servers/" + id);
        assertIsOk(resp);
        // Verify in a fresh VT/Tx so the read sees the controller's committed
        // state, not the test's stale carrier-tx snapshot.
        var remaining = commitInFreshTx(() ->
                AgentSkillAllowedTool.count("skillName = ?1", "mcp:clean"));
        assertEquals(0L, (long) remaining,
                "delete must cascade through stop() and clear allowlist rows");
    }

    // ==================== test connection ====================

    @Test
    public void testConnectionReturnsErrorOnUnreachableServer() {
        login();
        var id = createHttpServer("dead", "http://127.0.0.1:1/mcp", false);
        var resp = POST("/api/mcp-servers/" + id + "/test", "application/json", "{}");
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"success\":false"),
                "unreachable server must report success=false: " + content);
        assertTrue(content.contains("\"message\""));
    }

    // ==================== helpers ====================

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private String createHttpServer(String name, String url, boolean enabled) {
        var resp = POST("/api/mcp-servers", "application/json", """
                {"name":"%s","enabled":%s,"transport":"HTTP","url":"%s"}
                """.formatted(name, enabled, url));
        assertIsOk(resp);
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(getContent(resp));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Run {@code block} in a fresh virtual thread + Tx so the commit lands
     *  before the calling thread proceeds. Required for HTTP-visible seed
     *  data per the FunctionalTest Tx isolation memory. */
    private static <T> T commitInFreshTx(java.util.concurrent.Callable<T> block) {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try { holder.set(services.Tx.run(() -> {
                try { return block.call(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })); }
            catch (Throwable e) { err.set(e); }
        });
        try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (err.get() != null) throw new RuntimeException(err.get());
        return holder.get();
    }
}
