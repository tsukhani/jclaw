import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class ApiAgentsControllerTest extends FunctionalTest {

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
    // JCLAW-534: per-agent memory auto-capture settings
    // =====================

    @Test
    void memoryAutocaptureSettingsRoundTrip() {
        login();
        var id = createAgent("mem-roundtrip");

        // Default: enabled + inherited (effective model = the agent's default).
        var initial = getContent(GET("/api/agents/" + id));
        assertTrue(initial.contains("\"memoryAutocaptureEnabled\":true"), initial);
        assertTrue(initial.contains("\"memoryAutocaptureModelInherited\":true"), initial);
        assertTrue(initial.contains("\"memoryAutocaptureModel\":\"gpt-4.1\""), initial);

        // Disable + override the extractor model.
        var put = PUT("/api/agents/" + id, "application/json",
                "{\"memoryAutocaptureEnabled\":false,\"memoryAutocaptureProvider\":\"openrouter\","
                + "\"memoryAutocaptureModel\":\"cheap-model\"}");
        assertIsOk(put);
        var after = getContent(GET("/api/agents/" + id));
        assertTrue(after.contains("\"memoryAutocaptureEnabled\":false"), after);
        assertTrue(after.contains("\"memoryAutocaptureModelInherited\":false"), after);
        assertTrue(after.contains("\"memoryAutocaptureModel\":\"cheap-model\""), after);

        // Clearing the override (null) returns to inherit = the agent's default.
        var put2 = PUT("/api/agents/" + id, "application/json",
                "{\"memoryAutocaptureProvider\":null,\"memoryAutocaptureModel\":null}");
        assertIsOk(put2);
        var after2 = getContent(GET("/api/agents/" + id));
        assertTrue(after2.contains("\"memoryAutocaptureModelInherited\":true"), after2);
        assertTrue(after2.contains("\"memoryAutocaptureModel\":\"gpt-4.1\""), after2);
    }

    // =====================
    // GET /api/agents/{id}/shell/effective-allowlist
    // =====================

    /**
     * Every agent-scoped GET surface (effective-allowlist, workspace-file
     * JSON, workspace-file binary serve) must reject an unauthenticated
     * request with 401 before any agent lookup.
     */
    @ParameterizedTest(name = "requiresAuth[{0}]")
    @ValueSource(strings = {
            "/api/agents/1/shell/effective-allowlist",
            "/api/agents/1/workspace/AGENT.md",
            "/api/agents/1/files/AGENT.md"
    })
    void agentGetEndpointRequiresAuth(String url) {
        var response = GET(url);
        assertEquals(401, response.status.intValue());
    }

    /**
     * Agent-scoped GETs return 404 when the agent id doesn't exist:
     * effective-allowlist, workspace-file JSON, and prompt-breakdown all
     * resolve the agent first and 404 on a missing id (999999).
     */
    @ParameterizedTest(name = "unknownAgent404[{0}]")
    @ValueSource(strings = {
            "/api/agents/999999/shell/effective-allowlist",
            "/api/agents/999999/workspace/AGENT.md",
            "/api/agents/999999/prompt-breakdown?channelType=web"
    })
    void agentGetEndpointReturns404ForUnknownAgent(String url) {
        login();
        var response = GET(url);
        assertEquals(404, response.status.intValue());
    }

    @Test
    void effectiveShellAllowlistReturnsGlobalAndPerSkillBreakdown() {
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

    @Test
    void createRejectsMissingOrNullRequiredFieldsWith400() {
        // Regression: a create body missing "name" (or modelProvider / modelId),
        // or sending an explicit null, used to NPE → HTTP 500 on
        // get(key).getAsString(). It must surface as an actionable 400 instead —
        // important on the agent-facing jclaw_api path.
        login();
        assertStatus(400, POST("/api/agents", "application/json",
                "{\"modelProvider\": \"openrouter\", \"modelId\": \"gpt-4.1\"}"));        // missing name
        assertStatus(400, POST("/api/agents", "application/json",
                "{\"name\": null, \"modelProvider\": \"openrouter\", \"modelId\": \"gpt-4.1\"}")); // null name
        assertStatus(400, POST("/api/agents", "application/json",
                "{\"name\": \"no-provider\", \"modelId\": \"gpt-4.1\"}"));                 // missing modelProvider
        assertStatus(400, POST("/api/agents", "application/json",
                "{\"name\": \"no-model\", \"modelProvider\": \"openrouter\"}"));           // missing modelId
    }

    // =====================
    // Workspace file endpoints
    // =====================

    // getWorkspaceFileRequiresAuth merged into agentGetEndpointRequiresAuth
    // (GET /api/agents/1/workspace/AGENT.md).

    @Test
    void getWorkspaceFileReturnsSeededAgentMd() {
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
    void getWorkspaceFileReturns404ForMissingFile() {
        login();
        var id = createAgent("workspace-missing");
        var response = GET("/api/agents/" + id + "/workspace/does-not-exist.md");
        assertEquals(404, response.status.intValue());
    }

    // getWorkspaceFileReturns404ForUnknownAgent merged into
    // agentGetEndpointReturns404ForUnknownAgent (GET /api/agents/999999/workspace/AGENT.md).

    @Test
    void saveWorkspaceFileRoundTrips() {
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
    void saveWorkspaceFileRejectsMissingContent() {
        login();
        var id = createAgent("workspace-missing-content");
        var response = PUT("/api/agents/" + id + "/workspace/AGENT.md",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void saveWorkspaceFileReturns404ForUnknownAgent() {
        login();
        var body = """
                {"content": "irrelevant"}
                """;
        var response = PUT("/api/agents/999999/workspace/AGENT.md",
                "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    void serveWorkspaceFileReturnsBinaryWithContentType() {
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
    void serveWorkspaceFileReturns404ForMissingFile() {
        login();
        var id = createAgent("serve-missing-file");
        var response = GET("/api/agents/" + id + "/files/does-not-exist.md");
        assertEquals(404, response.status.intValue());
    }

    // serveWorkspaceFileRequiresAuth merged into agentGetEndpointRequiresAuth
    // (GET /api/agents/1/files/AGENT.md).

    @Test
    void serveWorkspaceFileRejectsTraversalWith403() {
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

    @ParameterizedTest(name = "promptBreakdownAccepts{0}Channel")
    @ValueSource(strings = {"telegram", "slack", "whatsapp"})
    void promptBreakdownAcceptsNonWebChannel(String channelType) {
        login();
        var id = createAgent("prompt-" + channelType);
        var response = GET("/api/agents/" + id + "/prompt-breakdown?channelType=" + channelType);
        assertIsOk(response);
        assertContentType("application/json", response);
    }

    // promptBreakdownReturns404ForUnknownAgent merged into
    // agentGetEndpointReturns404ForUnknownAgent (GET /api/agents/999999/prompt-breakdown?channelType=web).

// =====================
    // Main-agent invariants
    // =====================

    @Test
    void deleteOnMainAgentReturns409() {
        login();
        var mainId = createMainAgent();
        var response = DELETE("/api/agents/" + mainId);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateRejectsDisablingMainAgent() {
        login();
        var mainId = createMainAgent();
        var body = """
                {"enabled": false}
                """;
        var response = PUT("/api/agents/" + mainId, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateRejectsRenamingMainAgentAway() {
        login();
        var mainId = createMainAgent();
        var body = """
                {"name": "not-main-anymore"}
                """;
        var response = PUT("/api/agents/" + mainId, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateRejectsRenamingNonMainAgentToMain() {
        login();
        var id = createAgent("non-main-rename-target");
        var body = """
                {"name": "main"}
                """;
        var response = PUT("/api/agents/" + id, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateRejectsRenameCollisionAcrossExistingAgent() {
        // Two agents A and B; PUT on A trying to rename to B's name → 409.
        login();
        createAgent("collision-target");
        var renamerId = createAgent("collision-renamer");
        var body = """
                {"name": "collision-target"}
                """;
        var response = PUT("/api/agents/" + renamerId, "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateThinkingModeExplicitNullClears() {
        // thinkingMode is optional on update: explicit null clears it.
        // (Setting a non-null mode requires the agent's model to advertise
        // that level in ProviderRegistry, which the test JVM doesn't seed.)
        // Exercises the readOptionalString branch with the present-null shape.
        login();
        var id = createAgent("thinking-mode-clear-agent");
        var resp = PUT("/api/agents/" + id, "application/json", """
                {"thinkingMode": null}
                """);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"thinkingMode\":null"));
    }

    @Test
    void updateDescriptionRoundTrips() {
        login();
        var id = createAgent("desc-agent");
        var resp = PUT("/api/agents/" + id, "application/json", """
                {"description": "a helpful agent"}
                """);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"description\":\"a helpful agent\""));
    }

    @Test
    void updateLeavesUnspecifiedFieldsUnchanged() {
        // PUT with only enabled=false on a non-main agent — name, provider,
        // model, thinkingMode all stay as-is. Exercises the absent-key
        // default branches in update.
        login();
        var id = createAgent("partial-update-agent");
        var resp = PUT("/api/agents/" + id, "application/json", """
                {"enabled": false}
                """);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"enabled\":false"));
        assertTrue(body.contains("\"name\":\"partial-update-agent\""),
                "name should be preserved: " + body);
    }

    @Test
    void mainAgentIsHiddenFromListEndpointWhenReservedFilterApplies() {
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

    /**
     * Subagent rows (Agent.parentAgent != null) belong to a parent's
     * spawn tree, not to the user-facing dropdown. The /subagents admin
     * page is the only surface that exposes them. The list endpoint must
     * filter them out so the chat header's "Agent" selector stays clean.
     */
    @Test
    void subagentsAreFilteredFromListEndpoint() {
        login();
        var parentId = createAgent("subagent-parent");
        createChildAgent("subagent-child", parentId);

        var response = GET("/api/agents");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"name\":\"subagent-parent\""),
                "parent agent must be present: " + body);
        assertFalse(body.contains("\"name\":\"subagent-child\""),
                "subagent child must be filtered from list: " + body);
    }

    /**
     * Seed a subagent row (parentAgent set) directly via JPA. Same
     * virtual-thread + Tx.run pattern as {@link #createMainAgent} since
     * the FunctionalTest carrier thread is already inside an uncommitted
     * JPA transaction.
     */
    private Long createChildAgent(String name, String parentIdStr) {
        var parentId = Long.parseLong(parentIdStr);
        var holder = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var parent = Agent.<Agent>findById(parentId);
                    var child = new Agent();
                    child.name = name;
                    child.modelProvider = "openrouter";
                    child.modelId = "gpt-4.1";
                    child.enabled = true;
                    child.parentAgent = parent;
                    child.save();
                    holder.set(child.id);
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
}
