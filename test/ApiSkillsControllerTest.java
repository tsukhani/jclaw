import agents.SkillLoader;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import models.SkillRegistryTool;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.SkillPromotionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Functional HTTP tests for {@code ApiSkillsController} covering the
 * Settings &gt; Skills surface: auth gating, global-skills CRUD, agent-scoped
 * install/uninstall, file listings/reads, the rename + promote endpoints, and
 * the path-traversal defense for skill-file downloads.
 *
 * <p>Each test points the global skills registry at a fresh temp directory
 * (via {@code jclaw.skills.path}) so the repo's shipped {@code skills/} folder
 * never bleeds into the assertions. {@code Fixtures.deleteDatabase()} clears
 * the JPA tables backing per-agent skill state ({@link AgentSkillConfig},
 * {@link AgentSkillAllowedTool}, {@link SkillRegistryTool}) before each test.
 */
class ApiSkillsControllerTest extends FunctionalTest {

    private Path globalSkillsDir;
    private final java.util.List<String> seededAgentNames = new java.util.ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");

        globalSkillsDir = Files.createTempDirectory("api-skills-test-global-");
        play.Play.configuration.setProperty("jclaw.skills.path", globalSkillsDir.toString());
        SkillLoader.clearCache();
    }

    @AfterEach
    void teardown() throws Exception {
        if (globalSkillsDir != null && Files.exists(globalSkillsDir)) {
            SkillPromotionService.deleteRecursive(globalSkillsDir);
        }
        play.Play.configuration.remove("jclaw.skills.path");
        SkillLoader.clearCache();
        for (var name : seededAgentNames) {
            try {
                var ws = AgentService.workspacePath(name);
                if (Files.exists(ws)) SkillPromotionService.deleteRecursive(ws);
            } catch (Exception e) {
                // Use System.err rather than EventLogger so teardown stays
                // independent of the DB-backed logger (which has its own
                // teardown ordering against Fixtures.deleteDatabase()).
                System.err.println("Workspace teardown failed for " + name + ": " + e.getMessage());
            }
        }
    }

    // ==================== Helpers ====================

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private String createAgent(String name) {
        seededAgentNames.add(name);
        var body = """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name);
        var resp = POST("/api/agents", "application/json", body);
        assertIsOk(resp);
        return extractId(getContent(resp));
    }

    private String extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Run a JPA mutation on a fresh virtual thread so the write commits before
     * the FunctionalTest carrier thread proceeds. Mirrors
     * {@code ApiAgentsControllerTest.createMainAgent}. Required whenever the
     * row needs to be visible to a subsequent HTTP request — the carrier
     * thread is already inside an uncommitted JPA transaction, so an inline
     * {@code Tx.run} would just join that uncommitted transaction.
     */
    private static void commitInFreshTx(Runnable block) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(block);
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
    }

    private static <T> T fetchInFreshTx(java.util.function.Supplier<T> block) {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        commitInFreshTx(() -> holder.set(block.get()));
        return holder.get();
    }

    /**
     * Write a minimal valid SKILL.md to the global skills registry. The
     * promote/sanitize pipeline is bypassed — these are the on-disk artefacts
     * the controller's read endpoints should report on.
     */
    private Path seedGlobalSkill(String folder, String name, String description) throws Exception {
        var dir = globalSkillsDir.resolve(folder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: " + name + "\n"
              + "description: " + description + "\n"
              + "version: 1.0.0\n"
              + "icon: 🧪\n"
              + "author: test\n"
              + "---\n"
              + "# " + name + "\n\nBody.\n");
        SkillLoader.clearCache();
        return dir;
    }

    private Path seedAgentWorkspaceSkill(String agentName, String folder, String name) throws Exception {
        var dir = AgentService.workspacePath(agentName).resolve("skills").resolve(folder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: " + name + "\n"
              + "description: workspace copy\n"
              + "version: 1.0.0\n"
              + "---\n"
              + "# " + name + "\n");
        SkillLoader.clearCache();
        return dir;
    }

    // ==================== Auth gate (all endpoints require login) ====================

    @Test
    void listGlobalSkillsRequiresAuth() {
        assertEquals(401, GET("/api/skills").status.intValue());
    }

    @Test
    void getGlobalSkillRequiresAuth() {
        assertEquals(401, GET("/api/skills/some-skill").status.intValue());
    }

    @Test
    void listGlobalFilesRequiresAuth() {
        assertEquals(401, GET("/api/skills/some-skill/files").status.intValue());
    }

    @Test
    void readGlobalFileRequiresAuth() {
        assertEquals(401, GET("/api/skills/some-skill/files/SKILL.md").status.intValue());
    }

    @Test
    void deleteGlobalSkillRequiresAuth() {
        assertEquals(401, DELETE("/api/skills/some-skill").status.intValue());
    }

    @Test
    void renameGlobalSkillRequiresAuth() {
        var resp = PUT("/api/skills/some-skill/rename", "application/json",
                "{\"newName\": \"renamed\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void promoteRequiresAuth() {
        var resp = POST("/api/skills/promote", "application/json",
                "{\"agentId\": 1, \"skillName\": \"x\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void listForAgentRequiresAuth() {
        assertEquals(401, GET("/api/agents/1/skills").status.intValue());
    }

    @Test
    void updateForAgentRequiresAuth() {
        var resp = PUT("/api/agents/1/skills/foo", "application/json",
                "{\"enabled\": false}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void copyToAgentRequiresAuth() {
        assertEquals(401, POST("/api/agents/1/skills/foo/copy", "application/json", "{}").status.intValue());
    }

    @Test
    void listAgentSkillFilesRequiresAuth() {
        assertEquals(401, GET("/api/agents/1/skills/foo/files").status.intValue());
    }

    @Test
    void readAgentSkillFileRequiresAuth() {
        assertEquals(401, GET("/api/agents/1/skills/foo/files/SKILL.md").status.intValue());
    }

    @Test
    void deleteAgentSkillRequiresAuth() {
        assertEquals(401, DELETE("/api/agents/1/skills/foo/delete").status.intValue());
    }

    // ==================== GET /api/skills ====================

    @Test
    void listGlobalSkillsReturnsEmptyArrayWhenRegistryEmpty() {
        login();
        var resp = GET("/api/skills");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        assertEquals("[]", getContent(resp).strip());
    }

    @Test
    void listGlobalSkillsIncludesSeededSkill() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        seedGlobalSkill("beta-skill", "beta", "Second test skill");

        var resp = GET("/api/skills");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"alpha\""), "alpha in payload: " + body);
        assertTrue(body.contains("\"name\":\"beta\""), "beta in payload: " + body);
        assertTrue(body.contains("\"isGlobal\":true"));
    }

    // ==================== GET /api/skills/{name} ====================

    @Test
    void getGlobalSkillReturnsFullContent() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First test skill");

        var resp = GET("/api/skills/alpha-skill");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"alpha\""), "payload echoes name: " + body);
        assertTrue(body.contains("\"content\":"), "payload carries content key: " + body);
        assertTrue(body.contains("First test skill"));
    }

    @Test
    void getGlobalSkillReturns404ForUnknown() {
        login();
        var resp = GET("/api/skills/does-not-exist");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void getGlobalSkillRejectsTraversalName() {
        login();
        var resp = GET("/api/skills/..%2F..%2Fetc");
        var status = resp.status.intValue();
        assertTrue(status == 404 || status == 403,
                "traversal must be blocked, got " + status);
    }

    // ==================== GET /api/skills/{name}/files ====================

    @Test
    void listGlobalSkillFilesReturnsEntries() throws Exception {
        login();
        var dir = seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        Files.writeString(dir.resolve("README.md"), "Extra readme");

        var resp = GET("/api/skills/alpha-skill/files");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"files\""), "files key present: " + body);
        assertTrue(body.contains("SKILL.md"), "SKILL.md listed: " + body);
        assertTrue(body.contains("README.md"), "extra file listed: " + body);
        assertTrue(body.contains("\"tools\""));
        assertTrue(body.contains("\"commands\""));
    }

    @Test
    void listGlobalSkillFilesReturns404ForUnknown() {
        login();
        assertEquals(404, GET("/api/skills/missing/files").status.intValue());
    }

    // ==================== GET /api/skills/{name}/files/{filePath} ====================

    @Test
    void readGlobalSkillFileReturnsBody() throws Exception {
        login();
        var dir = seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        Files.writeString(dir.resolve("notes.md"), "hello-from-notes");

        var resp = GET("/api/skills/alpha-skill/files/notes.md");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("hello-from-notes"), "file body returned: " + body);
        assertTrue(body.contains("\"path\":\"notes.md\""));
    }

    @Test
    void readGlobalSkillFileReturns404ForMissing() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        assertEquals(404, GET("/api/skills/alpha-skill/files/does-not-exist.md").status.intValue());
    }

    // ==================== DELETE /api/skills/{name} ====================

    @Test
    void deleteSkillCreatorIsForbidden() throws Exception {
        login();
        // Seed it so the controller doesn't 404 before hitting the guard
        seedGlobalSkill("skill-creator", "skill-creator", "built-in");
        var resp = DELETE("/api/skills/skill-creator");
        assertEquals(403, resp.status.intValue());
        assertTrue(Files.exists(globalSkillsDir.resolve("skill-creator").resolve("SKILL.md")),
                "delete must not remove skill-creator from disk");
    }

    @Test
    void deleteGlobalSkillReturnsOk() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First test skill");

        var resp = DELETE("/api/skills/alpha-skill");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
        assertFalse(Files.exists(globalSkillsDir.resolve("alpha-skill")),
                "skill directory must be gone after delete");
    }

    @Test
    void deleteUnknownGlobalSkillReturns404() {
        login();
        assertEquals(404, DELETE("/api/skills/missing").status.intValue());
    }

    // ==================== GET /api/agents/{id}/skills ====================

    @Test
    void listForAgentReturns404ForUnknownAgent() {
        login();
        assertEquals(404, GET("/api/agents/999999/skills").status.intValue());
    }

    @Test
    void listForAgentReturnsEmptyArrayWhenNoWorkspaceSkills() {
        login();
        var id = createAgent("list-skills-agent");
        var resp = GET("/api/agents/" + id + "/skills");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        assertEquals("[]", getContent(resp).strip());
    }

    @Test
    void listForAgentReflectsEnabledFlagFromConfig() throws Exception {
        login();
        var idStr = createAgent("list-skills-agent2");
        var agentId = Long.parseLong(idStr);
        seedAgentWorkspaceSkill("list-skills-agent2", "alpha", "alpha");

        // Disable via the config table on a fresh tx so the row is committed
        // and visible to the subsequent HTTP request handler.
        commitInFreshTx(() -> {
            var cfg = new AgentSkillConfig();
            cfg.agent = Agent.findById(agentId);
            cfg.skillName = "alpha";
            cfg.enabled = false;
            cfg.save();
        });

        var resp = GET("/api/agents/" + idStr + "/skills");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"alpha\""), "skill listed: " + body);
        assertTrue(body.contains("\"enabled\":false"),
                "config.enabled=false should surface as enabled:false in JSON: " + body);
    }

    // ==================== PUT /api/agents/{id}/skills/{name} ====================

    @Test
    void updateForAgentTogglesEnabled() {
        login();
        var id = createAgent("toggle-agent");
        var resp = PUT("/api/agents/" + id + "/skills/foo", "application/json",
                "{\"enabled\": false}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"foo\""));
        assertTrue(body.contains("\"enabled\":false"));
        assertTrue(body.contains("\"status\":\"ok\""));
    }

    @Test
    void updateForAgentRejectsMissingEnabledField() {
        login();
        var id = createAgent("toggle-agent-2");
        var resp = PUT("/api/agents/" + id + "/skills/foo", "application/json", "{}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void updateForAgentReturns404ForUnknownAgent() {
        login();
        var resp = PUT("/api/agents/999999/skills/foo", "application/json",
                "{\"enabled\": true}");
        assertEquals(404, resp.status.intValue());
    }

    // ==================== POST /api/agents/{id}/skills/{name}/copy ====================

    @Test
    void copyToAgentReturns404WhenAgentMissing() {
        login();
        var resp = POST("/api/agents/999999/skills/foo/copy", "application/json", "{}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void copyToAgentReturns404WhenGlobalSkillMissing() {
        login();
        var id = createAgent("copy-agent-missing");
        var resp = POST("/api/agents/" + id + "/skills/no-such-skill/copy",
                "application/json", "{}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void copyToAgentSucceedsAndPopulatesWorkspace() throws Exception {
        login();
        var id = createAgent("copy-agent-ok");
        seedGlobalSkill("alpha-skill", "alpha", "First");

        var resp = POST("/api/agents/" + id + "/skills/alpha-skill/copy",
                "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"ok\""));
        assertTrue(body.contains("\"replaced\":false"));

        var copied = AgentService.workspacePath("copy-agent-ok")
                .resolve("skills").resolve("alpha-skill").resolve("SKILL.md");
        assertTrue(Files.exists(copied), "SKILL.md must land in agent workspace");
    }

    // ==================== GET /api/agents/{id}/skills/{name}/files ====================

    @Test
    void listAgentSkillFilesReturnsEntries() throws Exception {
        login();
        var id = createAgent("agent-files");
        seedAgentWorkspaceSkill("agent-files", "alpha", "alpha");

        var resp = GET("/api/agents/" + id + "/skills/alpha/files");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("SKILL.md"));
        assertTrue(body.contains("\"files\""));
    }

    @Test
    void listAgentSkillFilesReturns404ForUnknownAgent() {
        login();
        assertEquals(404, GET("/api/agents/999999/skills/foo/files").status.intValue());
    }

    @Test
    void listAgentSkillFilesReturns404ForUnknownSkill() {
        login();
        var id = createAgent("agent-files-missing");
        assertEquals(404, GET("/api/agents/" + id + "/skills/no-such/files").status.intValue());
    }

    // ==================== GET /api/agents/{id}/skills/{name}/files/{filePath} ====================

    @Test
    void readAgentSkillFileReturnsBody() throws Exception {
        login();
        var id = createAgent("agent-read-file");
        seedAgentWorkspaceSkill("agent-read-file", "alpha", "alpha");

        var resp = GET("/api/agents/" + id + "/skills/alpha/files/SKILL.md");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"path\":\"SKILL.md\""));
        assertTrue(body.contains("workspace copy"));
    }

    @Test
    void readAgentSkillFileReturns404ForUnknownAgent() {
        login();
        assertEquals(404, GET("/api/agents/999999/skills/foo/files/SKILL.md").status.intValue());
    }

    @Test
    void readAgentSkillFileReturns404ForMissingFile() throws Exception {
        login();
        var id = createAgent("agent-read-missing");
        seedAgentWorkspaceSkill("agent-read-missing", "alpha", "alpha");
        assertEquals(404,
                GET("/api/agents/" + id + "/skills/alpha/files/no-such.md").status.intValue());
    }

    @Test
    void readAgentSkillFileBlocksTraversal() throws Exception {
        // AgentService.acquireContained rejects any ../.. that escapes the
        // skill directory. Controller maps SecurityException → 403, but Play's
        // router may decode the dotted path as a different route → 404 is also
        // an acceptable "not served" outcome.
        login();
        var id = createAgent("agent-traverse");
        seedAgentWorkspaceSkill("agent-traverse", "alpha", "alpha");
        var resp = GET("/api/agents/" + id + "/skills/alpha/files/../../../etc/passwd");
        var status = resp.status.intValue();
        assertTrue(status == 403 || status == 404,
                "traversal must be blocked, got " + status);
    }

    // ==================== DELETE /api/agents/{id}/skills/{name}/delete ====================

    @Test
    void deleteAgentSkillRemovesWorkspaceCopy() throws Exception {
        login();
        var idStr = createAgent("delete-agent-skill");
        var agentId = Long.parseLong(idStr);
        var dir = seedAgentWorkspaceSkill("delete-agent-skill", "alpha", "alpha");

        // Seed an allowlist row on a fresh tx so the controller's revoke can
        // observe it; without commitInFreshTx the test-side row would sit
        // uncommitted and the controller's bulk delete (in its own tx) would
        // be a no-op against an empty table.
        commitInFreshTx(() -> {
            var agent = Agent.<Agent>findById(agentId);
            var row = new AgentSkillAllowedTool();
            row.agent = agent;
            row.skillName = "alpha";
            row.toolName = "echo";
            row.save();
        });
        var before = fetchInFreshTx(() ->
                AgentSkillAllowedTool.findByAgentAndSkill(Agent.<Agent>findById(agentId), "alpha").size());
        assertEquals(1, (int) before, "allowlist row visible to fresh tx before delete");

        var resp = DELETE("/api/agents/" + idStr + "/skills/alpha/delete");
        assertIsOk(resp);
        assertFalse(Files.exists(dir), "skill directory removed from workspace");

        var after = fetchInFreshTx(() ->
                AgentSkillAllowedTool.findByAgentAndSkill(Agent.<Agent>findById(agentId), "alpha").size());
        assertEquals(0, (int) after,
                "allowlist rows revoked alongside the skill folder");
    }

    @Test
    void deleteAgentSkillReturns404ForUnknownAgent() {
        login();
        assertEquals(404, DELETE("/api/agents/999999/skills/foo/delete").status.intValue());
    }

    @Test
    void deleteAgentSkillReturns404ForUnknownSkill() {
        login();
        var id = createAgent("delete-missing-agent-skill");
        assertEquals(404,
                DELETE("/api/agents/" + id + "/skills/no-such/delete").status.intValue());
    }

    // ==================== POST /api/skills/promote ====================

    @Test
    void promoteReturns400OnMissingFields() {
        login();
        assertEquals(400, POST("/api/skills/promote", "application/json", "{}").status.intValue());
    }

    @Test
    void promoteReturns400OnEmptyBody() {
        login();
        var resp = POST("/api/skills/promote", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void promoteReturns404ForUnknownAgent() {
        login();
        var resp = POST("/api/skills/promote", "application/json",
                "{\"agentId\": 999999, \"skillName\": \"foo\"}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void promoteReturns404WhenWorkspaceSkillMissing() {
        login();
        var idStr = createAgent("promote-no-skill");
        var resp = POST("/api/skills/promote", "application/json",
                "{\"agentId\": " + idStr + ", \"skillName\": \"no-such\"}");
        assertEquals(404, resp.status.intValue());
    }

    // Background path is inert here: agent has no skill-creator workspace skill, so
    // hasSkillCreatorCapability() returns false before sanitizeWithLlm is reached.
    // Future capability-gate reordering would invalidate this assumption.
    @Test
    void promoteAcceptedReturns200StatusPromoting() throws Exception {
        // The endpoint returns 200 immediately and dispatches the actual work
        // to a virtual thread. We only verify the synchronous contract — the
        // background job's gate is exercised in SkillPromotionService unit tests.
        login();
        var idStr = createAgent("promote-accepted");
        seedAgentWorkspaceSkill("promote-accepted", "alpha", "alpha");

        var resp = POST("/api/skills/promote", "application/json",
                "{\"agentId\": " + idStr + ", \"skillName\": \"alpha\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"promoting\""), "promoting status: " + body);
        assertTrue(body.contains("\"skillName\":\"alpha\""));
    }

    // ==================== PUT /api/skills/{name}/rename ====================

    @Test
    void renameMovesGlobalSkill() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First");

        var resp = PUT("/api/skills/alpha-skill/rename", "application/json",
                "{\"newName\": \"alpha-renamed\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"oldName\":\"alpha-skill\""));
        assertTrue(body.contains("\"newName\":\"alpha-renamed\""));
        assertFalse(Files.exists(globalSkillsDir.resolve("alpha-skill")));
        assertTrue(Files.exists(globalSkillsDir.resolve("alpha-renamed").resolve("SKILL.md")));
    }

    @Test
    void renameReturns400OnMissingNewName() {
        login();
        var resp = PUT("/api/skills/anything/rename", "application/json", "{}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void renameReturns400OnEmptyNewName() {
        login();
        var resp = PUT("/api/skills/anything/rename", "application/json",
                "{\"newName\": \"   \"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void renameReturns404ForUnknownSkill() {
        login();
        var resp = PUT("/api/skills/no-such/rename", "application/json",
                "{\"newName\": \"renamed\"}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void renameReturns409WhenTargetExists() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First");
        seedGlobalSkill("beta-skill", "beta", "Second");

        var resp = PUT("/api/skills/alpha-skill/rename", "application/json",
                "{\"newName\": \"beta-skill\"}");
        assertEquals(409, resp.status.intValue());
    }

    // ==================== copyToAgent — tool-validation rejection ====================

    /**
     * Copy is refused with 400 when the global skill declares a tool the
     * canonical {@code ToolRegistry} doesn't recognise. Verifies the
     * controller surfaces the validator's message verbatim via renderText.
     */
    @Test
    void copyToAgentRejectsSkillDeclaringUnknownTool() throws Exception {
        login();
        var idStr = createAgent("copy-unknown-tool");

        // Skill declares a tool that the canonical ToolRegistry cannot resolve.
        // resolveSkillTools' validateSkillTools call falls into the !ok path
        // and the controller responds 400 with the validator's message.
        var dir = globalSkillsDir.resolve("needs-fake-tool");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: needs-fake-tool\n"
              + "description: depends on a tool that does not exist\n"
              + "version: 1.0.0\n"
              + "tools: [definitely_not_a_real_tool_xyz]\n"
              + "---\n"
              + "# body\n");
        SkillLoader.clearCache();

        var resp = POST("/api/agents/" + idStr + "/skills/needs-fake-tool/copy",
                "application/json", "{}");
        assertEquals(400, resp.status.intValue());
        var body = getContent(resp);
        assertTrue(body.contains("definitely_not_a_real_tool_xyz"),
                "error body names the unknown tool: " + body);
    }

    /**
     * Re-copy of a previously-disabled workspace skill flips
     * AgentSkillConfig.enabled back to true (lines 309-313 of
     * ApiSkillsController). Without the toggle, a user who disabled then
     * re-installed a skill would silently keep it disabled.
     */
    @Test
    void copyToAgentReEnablesPreviouslyDisabledSkill() throws Exception {
        login();
        var idStr = createAgent("copy-re-enable");
        var agentId = Long.parseLong(idStr);
        seedGlobalSkill("widget-skill", "widget", "A widget");

        // Pre-seed a disabled config row. Fresh tx so the HTTP handler sees it.
        commitInFreshTx(() -> {
            var cfg = new AgentSkillConfig();
            cfg.agent = Agent.findById(agentId);
            cfg.skillName = "widget-skill";
            cfg.enabled = false;
            cfg.save();
        });

        var resp = POST("/api/agents/" + idStr + "/skills/widget-skill/copy",
                "application/json", "{}");
        assertIsOk(resp);

        var enabled = fetchInFreshTx(() -> {
            var cfg = AgentSkillConfig.findByAgentAndSkill(
                    Agent.findById(agentId), "widget-skill");
            return cfg != null && cfg.enabled;
        });
        assertTrue(enabled, "copy must flip a disabled config row back to enabled");
    }

    // ==================== listFiles — declared tools, body heuristic, author ====================

    /**
     * When a skill SKILL.md frontmatter declares {@code tools:}, the file
     * listing's "tools" key reflects the declared list verbatim and skips
     * the body-text heuristic (lines 138-151 of ApiSkillsController). The
     * declared-tools branch never executes against the SonarQube fixture
     * because shipped skills don't declare tools — covered now.
     */
    @Test
    void listGlobalSkillFilesUsesDeclaredToolsFromFrontmatter() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("declared-tools");
        Files.createDirectories(dir);
        // 'exec' is a known canonical tool name in ToolRegistry
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: declared-tools\n"
              + "description: declared tools test\n"
              + "version: 1.0.0\n"
              + "tools: [exec]\n"
              + "author: tester\n"
              + "commands: [echo, ls]\n"
              + "---\n"
              + "# body with no other tool names\n");
        SkillLoader.clearCache();

        var resp = GET("/api/skills/declared-tools/files");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"exec\""),
                "declared 'exec' must appear in tools: " + body);
        assertTrue(body.contains("\"commands\":[\"echo\",\"ls\"]"),
                "commands key reflects frontmatter: " + body);
        assertTrue(body.contains("\"author\":\"tester\""),
                "author key reflects frontmatter: " + body);
    }

    /**
     * Skill without {@code tools:} frontmatter falls back to the body-text
     * heuristic. A SKILL.md body that contains a bash code fence triggers
     * the implicit 'exec' detection (lines 184-193 of ApiSkillsController).
     */
    @Test
    void listGlobalSkillFilesDetectsImplicitShellFromBashFence() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("bash-fence");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: bash-fence\n"
              + "description: legacy skill, no tools declaration\n"
              + "version: 1.0.0\n"
              + "---\n"
              + "# Howto\n\n"
              + "```bash\necho hello\n```\n");
        SkillLoader.clearCache();

        var resp = GET("/api/skills/bash-fence/files");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"exec\""),
                "bash code fence implies exec tool: " + body);
    }

    /**
     * Skill without {@code tools:} frontmatter whose body literally mentions
     * the alias "readFile" triggers the TOOL_ALIASES map → canonical
     * 'filesystem' tool (lines 171-180 of ApiSkillsController).
     */
    @Test
    void listGlobalSkillFilesDetectsFilesystemAlias() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("alias-skill");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: alias-skill\n"
              + "description: legacy skill with alias mention\n"
              + "version: 1.0.0\n"
              + "---\n"
              + "# Body\n\nUse readFile to load data.\n");
        SkillLoader.clearCache();

        var resp = GET("/api/skills/alias-skill/files");
        assertIsOk(resp);
        var body = getContent(resp);
        // 'filesystem' is the canonical tool name for the readFile alias
        assertTrue(body.contains("\"name\":\"filesystem\""),
                "readFile alias must resolve to filesystem tool: " + body);
    }

    // ==================== promote — agent without skill-creator capability ====================

    /**
     * Pushes the agent past the controller's synchronous 404 gate (the
     * workspace skill exists), then verifies the background promotion job
     * publishes {@code skill.promote_failed} for the missing capability. This
     * proves the controller wires the agent id through correctly without
     * sneaking past the capability check.
     */
    @Test
    void promoteBackgroundFailsWhenAgentLacksSkillCreator() throws Exception {
        login();
        var idStr = createAgent("promote-no-cap");
        seedAgentWorkspaceSkill("promote-no-cap", "alpha", "alpha");

        // Subscribe BEFORE firing the POST so we don't miss the event
        var latch = new java.util.concurrent.CountDownLatch(1);
        var ref = new java.util.concurrent.atomic.AtomicReference<String>();
        var off = services.NotificationBus.subscribe(payload -> {
            if (payload.contains("\"type\":\"skill.promote_failed\"")
                    && ref.compareAndSet(null, payload)) {
                latch.countDown();
            }
        });
        try {
            var resp = POST("/api/skills/promote", "application/json",
                    "{\"agentId\": " + idStr + ", \"skillName\": \"alpha\"}");
            assertIsOk(resp);

            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "background job must publish skill.promote_failed");
            assertTrue(ref.get().contains("skill-creator capability"),
                    "failure message names the missing capability: " + ref.get());
        } finally {
            off.run();
        }
    }
}
