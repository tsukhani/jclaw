import mcp.McpConnectionManager;
import models.AgentSkillAllowedTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.Fixtures;
import play.test.FunctionalTest;

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
        assertTrue(getContent(resp).contains("\"deleted\":true"));
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
        // Barrier: the play1 TestEngine runs the unit lane concurrently with
        // functional tests on the shared H2 DB; under that load the fresh-VT commit
        // above can lag becoming visible to the DELETE request's connection, so
        // stop()'s cleanup finds no row and this assert flakes on CI (passes locally
        // even at 600x amplification). Spin on a fresh-Tx read until the seed is
        // globally visible before issuing the DELETE — normally one no-spin read.
        awaitCommitted(() ->
                AgentSkillAllowedTool.count("skillName = ?1", "mcp:clean") == 1L,
                "seeded allowlist row never became visible to a fresh Tx");
        var resp = DELETE("/api/mcp-servers/" + id);
        assertIsOk(resp);
        // JCLAW-615: the same visibility lag the seed barrier above guards
        // against applies symmetrically to the controller's commit — a single
        // fresh-Tx read raced it ("expected 0 but was 1" under concurrent-lane
        // load). Spin until the delete's cascade is globally visible; normally
        // one no-spin read.
        awaitCommitted(() -> AgentSkillAllowedTool.count("skillName = ?1", "mcp:clean") == 0L,
                "delete must cascade through stop() and clear allowlist rows");
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
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(commitInFreshTx(cond))) return;
            try { Thread.sleep(10); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        org.junit.jupiter.api.Assertions.fail(msg);
    }
}
