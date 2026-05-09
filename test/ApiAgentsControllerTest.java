import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;

/**
 * Functional HTTP tests for {@code ApiAgentsController} covering the surface
 * NOT exercised by {@link ControllerApiTest}: workspace file endpoints,
 * effective-shell-allowlist, non-web prompt-breakdown channels, and the main
 * agent invariants (cannot delete, cannot disable, cannot rename away).
 *
 * <p>{@code ControllerApiTest} owns the slug-validation matrix and basic CRUD;
 * this file deliberately avoids those scenarios to keep the suite DRY.
 */
public class ApiAgentsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    // --- Auth + helpers ---

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private String createAgent(String name) {
        var body = """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name);
        var resp = POST("/api/agents", "application/json", body);
        assertIsOk(resp);
        return extractId(getContent(resp));
    }

    /**
     * Seed the singleton "main" agent directly via the model, bypassing the
     * controller's create-time reservation. Runs inside a separate virtual
     * thread so the inner {@link services.Tx#run} starts a fresh transaction
     * and commits — mirrors {@code WebhookControllerTest.commitInFreshTx}.
     * The FunctionalTest carrier thread already has a JPA transaction open,
     * so an inline {@code Tx.run} would just join that uncommitted transaction
     * and the subsequent HTTP request wouldn't see the row.
     */
    private Long createMainAgent() {
        var holder = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var main = new Agent();
                    main.name = Agent.MAIN_AGENT_NAME;
                    main.modelProvider = "openrouter";
                    main.modelId = "gpt-4.1";
                    main.enabled = true;
                    main.save();
                    holder.set(main.id);
                });
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
        return holder.get();
    }

    private String extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    // =====================
    // GET /api/agents/{id}/shell/effective-allowlist
    // =====================

    @Test
    public void effectiveShellAllowlistRequiresAuth() {
        var response = GET("/api/agents/1/shell/effective-allowlist");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void effectiveShellAllowlistReturns404ForUnknownAgent() {
        login();
        var response = GET("/api/agents/999999/shell/effective-allowlist");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void effectiveShellAllowlistReturnsGlobalAndPerSkillBreakdown() {
        login();
        var id = createAgent("shell-allowlist-agent");
        var response = GET("/api/agents/" + id + "/shell/effective-allowlist");
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertTrue(content.contains("\"global\""),
                "response must carry the global allowlist key: " + content);
        assertTrue(content.contains("\"bySkill\""),
                "response must carry the bySkill map key: " + content);
    }

    // =====================
    // Workspace file endpoints
    // =====================

    @Test
    public void getWorkspaceFileRequiresAuth() {
        var response = GET("/api/agents/1/workspace/AGENT.md");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void getWorkspaceFileReturnsSeededAgentMd() {
        // AgentService.create seeds AGENT.md (and IDENTITY/USER/SOUL/BOOTSTRAP)
        // when the agent is created. The endpoint returns {filename, content}.
        login();
        var id = createAgent("workspace-read-agent");
        var response = GET("/api/agents/" + id + "/workspace/AGENT.md");
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertTrue(content.contains("\"filename\":\"AGENT.md\""),
                "filename must echo back: " + content);
        assertTrue(content.contains("\"content\""),
                "response must carry the content key: " + content);
        assertTrue(content.contains("Agent Instructions") || content.contains("helpful AI assistant"),
                "AGENT.md template content should appear: " + content);
    }

    @Test
    public void getWorkspaceFileReturns404ForMissingFile() {
        login();
        var id = createAgent("workspace-missing");
        var response = GET("/api/agents/" + id + "/workspace/does-not-exist.md");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void getWorkspaceFileReturns404ForUnknownAgent() {
        login();
        var response = GET("/api/agents/999999/workspace/AGENT.md");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void saveWorkspaceFileRoundTrips() {
        login();
        var id = createAgent("workspace-write-agent");

        var saveBody = """
                {"content": "# Updated\\n\\nFresh contents from the test."}
                """;
        var saveResp = PUT("/api/agents/" + id + "/workspace/AGENT.md",
                "application/json", saveBody);
        assertIsOk(saveResp);
        var saveContent = getContent(saveResp);
        assertTrue(saveContent.contains("\"status\":\"ok\""),
                "save response must include status:ok: " + saveContent);
        assertTrue(saveContent.contains("\"filename\":\"AGENT.md\""),
                "save response must include filename: " + saveContent);

        var getResp = GET("/api/agents/" + id + "/workspace/AGENT.md");
        assertIsOk(getResp);
        var getContentBody = getContent(getResp);
        assertTrue(getContentBody.contains("Fresh contents from the test."),
                "round-tripped content must be readable: " + getContentBody);
    }

    @Test
    public void saveWorkspaceFileRejectsMissingContent() {
        login();
        var id = createAgent("workspace-missing-content");
        var response = PUT("/api/agents/" + id + "/workspace/AGENT.md",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void saveWorkspaceFileReturns404ForUnknownAgent() {
        login();
        var body = """
                {"content": "irrelevant"}
                """;
        var response = PUT("/api/agents/999999/workspace/AGENT.md",
                "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void serveWorkspaceFileReturnsBinaryWithContentType() {
        // We only assert the contract — 200 + Content-Type header + a
        // Cache-Control hint — since {@code renderBinary} streams the file
        // through Play's binary pipeline rather than the response.out buffer
        // FunctionalTest captures, so {@code getContent} can come back empty
        // even on a successful serve. The text body is exercised by the
        // companion JSON endpoint test (getWorkspaceFileReturnsSeededAgentMd).
        login();
        var id = createAgent("serve-workspace-agent");
        var response = GET("/api/agents/" + id + "/files/AGENT.md");
        assertIsOk(response);
        assertNotNull(response.contentType, "Content-Type header must be set");
        assertNotNull(response.getHeader("Cache-Control"),
                "Cache-Control hint must be set on workspace file responses");
    }

    @Test
    public void serveWorkspaceFileReturns404ForMissingFile() {
        login();
        var id = createAgent("serve-missing-file");
        var response = GET("/api/agents/" + id + "/files/does-not-exist.md");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void serveWorkspaceFileRequiresAuth() {
        var response = GET("/api/agents/1/files/AGENT.md");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void serveWorkspaceFileRejectsTraversalWith403() {
        // The two-layer (lexical + canonical) path validation in
        // AgentService.acquireWorkspacePath rejects ".." traversal before any
        // file is opened. The controller maps SecurityException → forbidden().
        login();
        var id = createAgent("serve-traversal-agent");
        var response = GET("/api/agents/" + id + "/files/../../../etc/passwd");
        // Either the controller forbids (403) or the route fails to match
        // (404 from Play's router decoding the dotted segments). Both outcomes
        // prove the file is not served. We accept either, but never 200.
        var status = response.status.intValue();
        assertTrue(status == 403 || status == 404,
                "traversal must be blocked, got " + status);
    }

    // =====================
    // Prompt breakdown — non-web channels
    // =====================

    @Test
    public void promptBreakdownAcceptsTelegramChannel() {
        login();
        var id = createAgent("prompt-telegram");
        var response = GET("/api/agents/" + id + "/prompt-breakdown?channelType=telegram");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void promptBreakdownAcceptsSlackChannel() {
        login();
        var id = createAgent("prompt-slack");
        var response = GET("/api/agents/" + id + "/prompt-breakdown?channelType=slack");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void promptBreakdownAcceptsWhatsAppChannel() {
        login();
        var id = createAgent("prompt-whatsapp");
        var response = GET("/api/agents/" + id + "/prompt-breakdown?channelType=whatsapp");
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    @Test
    public void promptBreakdownReturns404ForUnknownAgent() {
        login();
        var response = GET("/api/agents/999999/prompt-breakdown?channelType=web");
        assertEquals(404, response.status.intValue());
    }

    // =====================
    // Vision toggle persistence on update — JCLAW-165 retired the audio toggle
    // since the transcription pipeline lets every model consume audio.
    // =====================

    @Test
    public void updatePersistsExplicitVisionToggle() {
        login();
        var id = createAgent("vision-agent");
        var body = """
                {"visionEnabled": true}
                """;
        var resp = PUT("/api/agents/" + id, "application/json", body);
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"visionEnabled\":true"),
                "visionEnabled must persist as true: " + content);

        // GET round-trip confirms persistence (not just response shaping).
        var getResp = GET("/api/agents/" + id);
        assertIsOk(getResp);
        var getContentBody = getContent(getResp);
        assertTrue(getContentBody.contains("\"visionEnabled\":true"));
    }

    // =====================
    // Main-agent invariants
    // =====================

    @Test
    public void deleteOnMainAgentReturns409() {
        login();
        var mainId = createMainAgent();
        var response = DELETE("/api/agents/" + mainId);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void updateRejectsDisablingMainAgent() {
        login();
        var mainId = createMainAgent();
        var body = """
                {"enabled": false}
                """;
        var response = PUT("/api/agents/" + mainId, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void updateRejectsRenamingMainAgentAway() {
        login();
        var mainId = createMainAgent();
        var body = """
                {"name": "not-main-anymore"}
                """;
        var response = PUT("/api/agents/" + mainId, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void updateRejectsRenamingNonMainAgentToMain() {
        login();
        var id = createAgent("non-main-rename-target");
        var body = """
                {"name": "main"}
                """;
        var response = PUT("/api/agents/" + id, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    public void mainAgentIsHiddenFromListEndpointWhenReservedFilterApplies() {
        // Sanity check: the "main" agent IS visible via the list endpoint
        // (only __loadtest__ is hidden). This test guards against an
        // accidental over-broadening of the reserved-name filter.
        login();
        createMainAgent();
        var response = GET("/api/agents");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"name\":\"main\""),
                "main agent must remain visible in /api/agents listing");
    }
}
