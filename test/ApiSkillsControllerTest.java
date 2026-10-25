import agents.SkillLoader;
import agents.SkillSpec;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import models.SkillRegistryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.SkillPromotionService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.zip.ZipInputStream;

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
     * Run a JPA mutation on a fresh platform thread (JCLAW-688: not a VT — carrier-pool starvation under load) so the write commits before
     * the FunctionalTest carrier thread proceeds. Mirrors
     * {@code ApiAgentsControllerTest.createMainAgent}. Required whenever the
     * row needs to be visible to a subsequent HTTP request — the carrier
     * thread is already inside an uncommitted JPA transaction, so an inline
     * {@code Tx.run} would just join that uncommitted transaction.
     */
    private static void commitInFreshTx(Runnable block) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
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

    /**
     * {@code SkillLoader.parseSkillFile} swallows the IOException from an unreadable
     * file and answers null, so the detail endpoint has to check before handing the
     * result to {@code skillToMap} — otherwise the NPE pre-empts the 500 its own
     * catch block exists to render, and the operator gets a stack trace instead of
     * the reason.
     */
    @Test
    void getGlobalSkillReportsUnreadableFileAsAnError() throws Exception {
        login();
        var dir = seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        var md = dir.resolve("SKILL.md");
        try {
            Files.setPosixFilePermissions(md, Set.of());
            // chmod is a no-op for root, which would make this assert the old behavior.
            Assumptions.assumeTrue(!Files.isReadable(md), "chmod 000 not effective; skipping");

            var resp = GET("/api/skills/alpha-skill");
            assertEquals(500, resp.status.intValue());
            assertTrue(getContent(resp).contains("Failed to read skill"),
                    "renders the reason, not an NPE page: " + getContent(resp));
        } finally {
            Files.setPosixFilePermissions(md, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    /**
     * Global skills live under the repo's own {@code skills/}, so the droppings a
     * working copy accumulates are gitignored there and have no business in the
     * file browser — including the count, which reads off this same listing.
     */
    @Test
    void listGlobalSkillFilesOmitsIgnoredNoise() throws Exception {
        login();
        var dir = seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        Files.writeString(dir.resolve("README.md"), "Extra readme");
        Files.writeString(dir.resolve(".DS_Store"), "finder noise");
        var cache = Files.createDirectories(dir.resolve("__pycache__"));
        Files.writeString(cache.resolve("mod.cpython-313.pyc"), "bytecode");

        var resp = GET("/api/skills/alpha-skill/files");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("SKILL.md"), "authored file still listed: " + body);
        assertTrue(body.contains("README.md"), "authored file still listed: " + body);
        assertFalse(body.contains(".DS_Store"), "OS noise hidden: " + body);
        // Naming the directory has to take the whole subtree, not just the dir entry.
        assertFalse(body.contains("mod.cpython-313.pyc"), "ignored subtree hidden: " + body);
    }

    /**
     * Unknown-resource GETs that 404 with no per-test setup beyond login:
     * the global skill-files listing, the agent skills listing, the agent
     * skill-files listing, and the agent skill-file read. Each targets a
     * resource that doesn't exist (missing skill name or agent id 999999).
     */
    @ParameterizedTest(name = "get404[{0}]")
    @ValueSource(strings = {
            "/api/skills/missing/files",
            "/api/agents/999999/skills",
            "/api/agents/999999/skills/foo/files",
            "/api/agents/999999/skills/foo/files/SKILL.md"
    })
    void unknownResourceGetReturns404(String url) {
        login();
        assertEquals(404, GET(url).status.intValue());
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

    // listForAgentReturns404ForUnknownAgent merged into
    // unknownResourceGetReturns404 (GET /api/agents/999999/skills).

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

    @Test
    void updateForAgentEnableSucceedsWhenSkillIsInstalledInWorkspace() throws Exception {
        // Happy path for enable=true: the skill IS in the agent's workspace,
        // so the guard passes and the AgentSkillConfig flips enabled=true.
        login();
        var id = createAgent("toggle-enable-installed");
        seedAgentWorkspaceSkill("toggle-enable-installed", "alpha", "alpha");
        var resp = PUT("/api/agents/" + id + "/skills/alpha", "application/json",
                "{\"enabled\": true}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"enabled\":true"));
        assertTrue(body.contains("\"status\":\"ok\""));
    }

    @Test
    void updateForAgentEnableRejectsWhenSkillNotInstalledInWorkspace() {
        // Repro for the orphan-AgentSkillConfig failure mode: a caller
        // (e.g. an LLM via jclaw_api) calls PUT enabled=true on a skill
        // that lives only in the global registry but isn't copied into the
        // agent's workspace yet. The old behavior was a silent 200 that
        // wrote a config row with no SKILL.md backing it — skill would
        // appear "enabled" but SkillLoader couldn't find it and the
        // shell allowlist would never see its commands. Now we 400 with
        // a pointer to the install endpoint.
        login();
        var id = createAgent("toggle-enable-orphan");
        var resp = PUT("/api/agents/" + id + "/skills/never-installed", "application/json",
                "{\"enabled\": true}");
        assertEquals(400, resp.status.intValue());
        var body = getContent(resp);
        assertTrue(body.contains("not installed"),
                "error must explain the missing-workspace-file condition");
        assertTrue(body.contains("/copy"),
                "error must point to POST .../copy as the install path");
        assertTrue(body.contains("\"code\":\"invalid_request\""),
                "the refusal must be the error envelope the UI reads its reason from, not plain text: " + body);
        assertTrue(body.contains("drag the skill from the global list onto the agent"),
                "the template must say how to install the skill, not to fix malformed fields: " + body);
    }

    @Test
    void updateForAgentDisableAllowedEvenWhenSkillNotInstalled() {
        // Asymmetric: disabling a skill that doesn't exist in the workspace
        // is a no-op-ish operation (lets callers clean up stale configs
        // pointing at uninstalled skills). Only enable goes through the
        // installation guard.
        login();
        var id = createAgent("toggle-disable-orphan");
        var resp = PUT("/api/agents/" + id + "/skills/never-installed", "application/json",
                "{\"enabled\": false}");
        assertIsOk(resp);
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

    // listAgentSkillFilesReturns404ForUnknownAgent merged into
    // unknownResourceGetReturns404 (GET /api/agents/999999/skills/foo/files).

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

    // readAgentSkillFileReturns404ForUnknownAgent merged into
    // unknownResourceGetReturns404 (GET /api/agents/999999/skills/foo/files/SKILL.md).

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

    /**
     * Security regression (JCLAW-781): a {@code ../}-prefixed skillName in the
     * promote body must be rejected by the containment guard before the
     * controller reads — or the background job writes — outside the skills
     * root. We seed a SKILL.md one directory ABOVE the agent's {@code skills/}
     * folder ({@code workspace/<agent>/evil}); pre-fix the controller resolved
     * skillName raw, so {@code ../evil} escaped skills/, matched that file, and
     * returned 200 "promoting" (the same escaping name then drove the
     * {@code globalSkillsPath().resolve(skillName)} write). Post-fix the guard
     * rejects it with 404 and never touches the escaping target.
     */
    @Test
    void promoteRejectsTraversalSkillNameBeforeEscapingSkillsRoot() throws Exception {
        login();
        var idStr = createAgent("promote-traversal");

        // A SKILL.md just outside skills/ that "../evil" would reach if the raw
        // name were resolved against the skills root.
        var escapeDir = AgentService.workspacePath("promote-traversal").resolve("evil");
        Files.createDirectories(escapeDir);
        Files.writeString(escapeDir.resolve("SKILL.md"),
                "---\nname: evil\ndescription: outside skills root\nversion: 1.0.0\n---\n# evil\n");

        var resp = POST("/api/skills/promote", "application/json",
                "{\"agentId\": " + idStr + ", \"skillName\": \"../evil\"}");
        assertEquals(404, resp.status.intValue());
        assertFalse(getContent(resp).contains("\"status\":\"promoting\""),
                "a traversal skillName must never be accepted for promotion");
    }

    /**
     * Companion to the {@code ../} case: an absolute skillName must also be
     * rejected by the guard before it is read or promoted. We back it with a
     * real on-disk SKILL.md so the pre-fix raw-resolve
     * ({@code skillsRoot.resolve(absolutePath)} yields the absolute path) would
     * have found it and returned 200; post-fix the guard 404s first.
     */
    @Test
    void promoteRejectsAbsoluteSkillNameBeforeEscapingSkillsRoot() throws Exception {
        login();
        var idStr = createAgent("promote-absolute");

        var outsideDir = Files.createTempDirectory("promote-abs-outside-");
        try {
            Files.writeString(outsideDir.resolve("SKILL.md"),
                    "---\nname: evil\ndescription: absolute escape\nversion: 1.0.0\n---\n# evil\n");

            var resp = POST("/api/skills/promote", "application/json",
                    "{\"agentId\": " + idStr + ", \"skillName\": \"" + outsideDir + "\"}");
            assertEquals(404, resp.status.intValue());
            assertFalse(getContent(resp).contains("\"status\":\"promoting\""),
                    "an absolute skillName must never be accepted for promotion");
        } finally {
            SkillPromotionService.deleteRecursive(outsideDir);
        }
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
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: needs-fake-tool
                description: depends on a tool that does not exist
                version: 1.0.0
                tools: [definitely_not_a_real_tool_xyz]
                ---
                # body
                """);
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
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: declared-tools
                description: declared tools test
                version: 1.0.0
                tools: [exec]
                author: tester
                commands: [echo, ls]
                ---
                # body with no other tool names
                """);
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
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: bash-fence
                description: legacy skill, no tools declaration
                version: 1.0.0
                ---
                # Howto

                ```bash
                echo hello
                ```
                """);
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
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: alias-skill
                description: legacy skill with alias mention
                version: 1.0.0
                ---
                # Body

                Use readFile to load data.
                """);
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

    // ==================== detectTools branch coverage ====================

    private void seedHeuristicSkill(String folder, String bodySuffix) throws Exception {
        var dir = globalSkillsDir.resolve(folder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: " + folder + "\n"
              + "description: legacy skill, no tools declaration\n"
              + "version: 1.0.0\n"
              + "---\n"
              + "# Howto\n\n" + bodySuffix);
        SkillLoader.clearCache();
    }

    @Test
    void detectToolsImpliesExecFromShFence() throws Exception {
        login();
        seedHeuristicSkill("sh-fence", "```sh\necho hi\n```\n");
        var body = getContent(GET("/api/skills/sh-fence/files"));
        assertTrue(body.contains("\"name\":\"exec\""), "sh fence implies exec: " + body);
    }

    @Test
    void detectToolsImpliesExecFromShellFence() throws Exception {
        login();
        seedHeuristicSkill("shell-fence", "```shell\necho hi\n```\n");
        var body = getContent(GET("/api/skills/shell-fence/files"));
        assertTrue(body.contains("\"name\":\"exec\""), "shell fence implies exec: " + body);
    }

    @Test
    void detectToolsImpliesExecFromRunTheCommandPhrase() throws Exception {
        login();
        seedHeuristicSkill("run-phrase", "Please run the command and observe.\n");
        var body = getContent(GET("/api/skills/run-phrase/files"));
        assertTrue(body.contains("\"name\":\"exec\""), "run-the-command phrase implies exec: " + body);
    }

    @Test
    void detectToolsImpliesExecFromExecuteTheCommandPhrase() throws Exception {
        login();
        seedHeuristicSkill("exec-phrase", "Then execute the command directly.\n");
        var body = getContent(GET("/api/skills/exec-phrase/files"));
        assertTrue(body.contains("\"name\":\"exec\""), "execute-the-command phrase implies exec: " + body);
    }

    @Test
    void detectToolsDetectsShellAlias() throws Exception {
        login();
        seedHeuristicSkill("shell-alias", "Documentation mentions shell usage in prose.\n");
        var body = getContent(GET("/api/skills/shell-alias/files"));
        assertTrue(body.contains("\"name\":\"exec\""), "shell alias must map to exec: " + body);
    }

    @Test
    void detectToolsDetectsWriteFileAlias() throws Exception {
        login();
        seedHeuristicSkill("write-alias", "Use writeFile here to persist results.\n");
        var body = getContent(GET("/api/skills/write-alias/files"));
        assertTrue(body.contains("\"name\":\"filesystem\""),
                "writeFile alias must resolve to filesystem: " + body);
    }

    @Test
    void detectToolsDetectsListFilesAlias() throws Exception {
        login();
        seedHeuristicSkill("list-alias", "Call listFiles on the directory.\n");
        var body = getContent(GET("/api/skills/list-alias/files"));
        assertTrue(body.contains("\"name\":\"filesystem\""),
                "listFiles alias must resolve to filesystem: " + body);
    }

    /**
     * Wire-shape guard for the SkillToolRef refactor (JCLAW-398): each entry in
     * the "tools" array must serialize to the {name, description} object shape,
     * whether it comes from declared frontmatter or the body-text heuristic.
     * Gson serializes the record identically to the prior Map&lt;String,String&gt;,
     * so this asserts the contract that proves the wire format is unchanged.
     */
    @Test
    void listGlobalSkillFilesToolsEntriesCarryNameAndDescriptionKeys() throws Exception {
        login();
        // Declared-tools branch: 'exec' is a canonical ToolRegistry tool name.
        var declared = globalSkillsDir.resolve("ref-declared");
        Files.createDirectories(declared);
        Files.writeString(declared.resolve("SKILL.md"), """
                ---
                name: ref-declared
                description: declared tools wire-shape test
                version: 1.0.0
                tools: [exec]
                ---
                # body
                """);
        SkillLoader.clearCache();
        var declaredBody = getContent(GET("/api/skills/ref-declared/files"));
        assertTrue(declaredBody.contains("\"tools\":[{\"name\":\"exec\",\"description\":"),
                "declared tool must serialize as {name,description} object: " + declaredBody);

        // Heuristic branch: a bash fence implies exec via scanImplicitShellUsage.
        seedHeuristicSkill("ref-heuristic", "```bash\necho hi\n```\n");
        var heuristicBody = getContent(GET("/api/skills/ref-heuristic/files"));
        assertTrue(heuristicBody.contains("\"name\":\"exec\"")
                        && heuristicBody.contains("\"description\":"),
                "heuristic tool entry must carry both name and description keys: " + heuristicBody);
    }

    // ==================== readSkillFileFrom — global traversal ====================

    @Test
    void readGlobalSkillFileBlocksTraversal() throws Exception {
        login();
        seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        var resp = GET("/api/skills/alpha-skill/files/../../../etc/passwd");
        var status = resp.status.intValue();
        assertTrue(status == 403 || status == 404,
                "traversal must be blocked, got " + status);
    }

    // ==================== copyToAgent — replacing flag ====================

    @Test
    void copyToAgentReportsReplacingFlagWhenSkillAlreadyExists() throws Exception {
        login();
        var idStr = createAgent("copy-replace-agent");
        seedGlobalSkill("greeter", "greeter", "Says hi");

        // First copy — replacing should be false (no prior SKILL.md in workspace)
        var firstResp = POST("/api/agents/" + idStr + "/skills/greeter/copy",
                "application/json", "{}");
        assertIsOk(firstResp);
        assertTrue(getContent(firstResp).contains("\"replaced\":false"),
                "first copy must report replaced=false: " + getContent(firstResp));

        // Second copy — workspace already has SKILL.md, replacing should be true
        var secondResp = POST("/api/agents/" + idStr + "/skills/greeter/copy",
                "application/json", "{}");
        assertIsOk(secondResp);
        assertTrue(getContent(secondResp).contains("\"replaced\":true"),
                "second copy must report replaced=true: " + getContent(secondResp));
    }

    // ==================== resolveSkillTools — unresolved declared tool ====================

    /**
     * A skill declaring a tool the canonical {@code ToolRegistry} can't resolve
     * still surfaces that tool in the file listing — with an empty description
     * (the {@code lookupToolByName == null} branch of {@code resolveSkillTools}),
     * not dropped. Distinct from the copy path, which rejects the same skill via
     * a separate validator.
     */
    @Test
    void listGlobalSkillFilesSurfacesUnknownDeclaredToolWithEmptyDescription() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("unknown-tool-skill");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: unknown-tool-skill
                description: declares a tool the registry does not know
                version: 1.0.0
                tools: [totally_unknown_tool_zzz]
                ---
                # body
                """);
        SkillLoader.clearCache();

        var resp = GET("/api/skills/unknown-tool-skill/files");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"totally_unknown_tool_zzz\",\"description\":\"\""),
                "unknown declared tool must surface with an empty description: " + body);
    }

    // ==================== listForAgent — default enabled=true ====================

    /**
     * A workspace skill with no {@code AgentSkillConfig} row defaults to
     * {@code enabled:true} via {@code getOrDefault(name, true)}. The existing
     * suite only covers the config-present (enabled=false) case.
     */
    @Test
    void listForAgentDefaultsEnabledTrueWhenNoConfigRow() throws Exception {
        login();
        var id = createAgent("default-enabled-agent");
        seedAgentWorkspaceSkill("default-enabled-agent", "alpha", "alpha");

        var resp = GET("/api/agents/" + id + "/skills");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"alpha\""), "workspace skill listed: " + body);
        assertTrue(body.contains("\"enabled\":true"),
                "a skill with no config row defaults to enabled:true: " + body);
    }

    // ==================== updateForAgent — reuse existing config row ====================

    /**
     * When an {@code AgentSkillConfig} already exists, the toggle updates it in
     * place (the {@code config != null} branch) rather than inserting a duplicate.
     * Existing enable tests all start from no config row (the {@code config == null}
     * branch).
     */
    @Test
    void updateForAgentReusesExistingConfigRowInsteadOfDuplicating() throws Exception {
        login();
        var idStr = createAgent("toggle-reuse-config");
        var agentId = Long.parseLong(idStr);
        seedAgentWorkspaceSkill("toggle-reuse-config", "alpha", "alpha");

        // Pre-seed a disabled config row (committed so the handler sees it).
        commitInFreshTx(() -> {
            var cfg = new AgentSkillConfig();
            cfg.agent = Agent.findById(agentId);
            cfg.skillName = "alpha";
            cfg.enabled = false;
            cfg.save();
        });

        // Skill is installed → the enable guard passes and the existing row is
        // flipped to enabled=true in place.
        var resp = PUT("/api/agents/" + idStr + "/skills/alpha", "application/json",
                "{\"enabled\": true}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"enabled\":true"));

        var rowCount = fetchInFreshTx(() ->
                (int) AgentSkillConfig.findByAgent(Agent.<Agent>findById(agentId)).stream()
                        .filter(c -> "alpha".equals(c.skillName)).count());
        assertEquals(1, (int) rowCount,
                "the pre-existing config row must be reused, not duplicated");
        var enabled = fetchInFreshTx(() -> {
            var cfg = AgentSkillConfig.findByAgentAndSkill(Agent.findById(agentId), "alpha");
            return cfg != null && cfg.enabled;
        });
        assertTrue(enabled, "the reused row's enabled flag must flip to true");
    }

    // ==================== list — skip a directory with no SKILL.md ====================

    /**
     * The registry scan skips a subdirectory that has no {@code SKILL.md}
     * (the {@code Files.exists(skillFile)} false branch) — a stray folder must
     * not masquerade as a skill.
     */
    @Test
    void listGlobalSkillsSkipsDirectoryWithoutSkillMd() throws Exception {
        login();
        seedGlobalSkill("real-skill", "real", "A real skill");
        Files.createDirectories(globalSkillsDir.resolve("stray-no-skillmd"));

        var resp = GET("/api/skills");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"name\":\"real\""), "valid skill listed: " + body);
        assertFalse(body.contains("stray-no-skillmd"),
                "a directory without SKILL.md must not appear as a skill: " + body);
    }

    // ==================== GET /api/skills/{name}/export ====================

    @Test
    void anExportedSkillIsAZipThatClaudeAccepts() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("wa-notifier");
        Files.createDirectories(dir.resolve("tools"));
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: wa-notifier
                description: Sends WhatsApp messages. Use when the operator asks to message someone.
                version: 1.2.0
                author: main
                tools: [exec, filesystem]
                commands: [wacli]
                icon: 💬
                ---
                # WA Notifier

                Run `tools/wacli` with the `exec` tool.
                """);
        var binary = new byte[]{0x7F, 'E', 'L', 'F', 2, 0, 9};
        Files.write(dir.resolve("tools/wacli"), binary);
        Files.writeString(dir.resolve("tools/README.md"), "# wacli\n");
        Files.writeString(dir.resolve("credentials/api.json"), "{\"token\": \"sk-live-123\"}");
        Files.writeString(dir.resolve("credentials/notes.env"), "TOKEN=abc\n");
        var outside = Files.createTempFile("skill-export-outside", ".txt");
        Files.createSymbolicLink(dir.resolve("tools/link"), outside);
        SkillLoader.clearCache();

        try {
            var response = GET("/api/skills/wa-notifier/export");

            assertIsOk(response);
            assertEquals("application/zip", response.contentType);
            assertEquals("attachment; filename=\"wa-notifier.zip\"", response.getHeader("Content-Disposition"));
            var entries = unzip(response.out.toByteArray());
            assertEquals(java.util.List.of("wa-notifier/SKILL.md", "wa-notifier/credentials/api.json",
                    "wa-notifier/tools/README.md", "wa-notifier/tools/wacli"), java.util.List.copyOf(entries.keySet()));
            var skillMd = new String(entries.get("wa-notifier/SKILL.md"), StandardCharsets.UTF_8);
            assertEquals("""
                    ---
                    name: wa-notifier
                    description: >-
                      Sends WhatsApp messages. Use when the operator asks to message someone.
                    compatibility: "Written for JClaw; its instructions call these JClaw tools: exec"
                    metadata:
                      version: "1.2.0"
                      author: main
                      jclaw-icon: "💬"
                      jclaw-tools: exec filesystem
                      jclaw-commands: wacli
                    ---
                    # WA Notifier

                    Run `tools/wacli` with the `exec` tool.
                    """, skillMd);
            assertEquals(java.util.List.of(), SkillSpec.problems(skillMd, "wa-notifier"));
            assertEquals("{\n  \"token\": \"[CREDENTIAL]\"\n}",
                    new String(entries.get("wa-notifier/credentials/api.json"), StandardCharsets.UTF_8),
                    "a credential value never leaves; its key does");
            assertArrayEquals(binary, entries.get("wa-notifier/tools/wacli"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aSkillClaudeWouldRejectIsNotExported() throws Exception {
        login();
        seedGlobalSkill("app-maker", "app-maker", "Installs apps to public/apps/<slug>/");

        var response = GET("/api/skills/app-maker/export");

        assertStatus(422, response);
        var body = com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("not_exportable", body.get("code").getAsString());
        assertEquals("Claude would not accept this skill: Description cannot contain angle brackets (< or >)",
                body.get("message").getAsString());
        assertEquals("[\"Description cannot contain angle brackets (< or >)\"]", body.get("problems").toString());
    }

    @Test
    void exportingAnUnknownSkillIs404() {
        login();
        assertStatus(404, GET("/api/skills/no-such-skill/export"));
    }

    @Test
    void exportRequiresAuth() {
        assertEquals(401, GET("/api/skills/some-skill/export").status.intValue());
    }

    @Test
    void anAgentCannotExportASkill() throws Exception {
        seedGlobalSkill("probe", "probe", "A probe");
        var request = newRequest();
        request.headers.put("authorization",
                new play.mvc.Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        try {
            var response = GET(request, "/api/skills/probe/export");

            assertStatus(403, response);
            assertTrue(getContent(response).contains("operator_only"), "got: " + getContent(response));
        } finally {
            clearCookies();
        }
    }

    // ==================== credential files read masked ====================

    private static final String API_JSON = "{\"token\": \"sk-live-123\"}";
    private static final String MASKED_JSON = "{\n  \"token\": \"[CREDENTIAL]\"\n}";

    private play.mvc.Http.Response asAgentGet(String url) {
        clearCookies();
        var request = newRequest();
        request.headers.put("authorization",
                new play.mvc.Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        try {
            return GET(request, url);
        } finally {
            clearCookies();
        }
    }

    private static String fileContent(play.mvc.Http.Response response) {
        return com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject().get("content").getAsString();
    }

    private Path seedCredentialProbe() throws Exception {
        var dir = seedGlobalSkill("probe", "probe", "A probe");
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("credentials/api.json"), API_JSON);
        return dir;
    }

    @Test
    void anAgentReadsAGlobalSkillsCredentialFilesMasked() throws Exception {
        var dir = seedCredentialProbe();
        Files.writeString(dir.resolve("credentials/gamma-api-key.txt"), "sk-live-456\n");

        var json = asAgentGet("/api/skills/probe/files/credentials/api.json");
        var text = asAgentGet("/api/skills/probe/files/credentials/gamma-api-key.txt");
        var skillMd = asAgentGet("/api/skills/probe/files/SKILL.md");

        assertIsOk(json);
        assertEquals(MASKED_JSON, fileContent(json), "a JSON credential keeps its keys, never its values");
        assertEquals("[CREDENTIAL]", fileContent(text));
        assertEquals(Files.readString(dir.resolve("SKILL.md")), fileContent(skillMd), "only credentials/ is masked");
    }

    @Test
    void anAgentReadsAnotherAgentsWorkspaceCredentialFileMasked() throws Exception {
        login();
        var ownerId = createAgent("cred-owner");
        var dir = seedAgentWorkspaceSkill("cred-owner", "wa", "wa");
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("credentials/api.json"), API_JSON);

        var response = asAgentGet("/api/agents/" + ownerId + "/skills/wa/files/credentials/api.json");

        assertIsOk(response);
        assertEquals(MASKED_JSON, fileContent(response));
    }

    @Test
    void theOperatorsViewerReadsCredentialFilesMaskedToo() throws Exception {
        seedCredentialProbe();
        login();

        assertEquals(MASKED_JSON, fileContent(GET("/api/skills/probe/files/credentials/api.json")));
    }

    @Test
    void aSymlinkIntoCredentialsIsReadMasked() throws Exception {
        var dir = seedCredentialProbe();
        Files.createSymbolicLink(dir.resolve("notes.json"), dir.resolve("credentials/api.json"));

        assertEquals(MASKED_JSON, fileContent(asAgentGet("/api/skills/probe/files/notes.json")));
    }

    @Test
    void aCaseVariantOfTheCredentialsFolderIsReadMasked() throws Exception {
        var dir = seedCredentialProbe();
        Assumptions.assumeTrue(Files.exists(dir.resolve("CREDENTIALS/api.json")), "needs a case-insensitive file system");

        assertEquals(MASKED_JSON, fileContent(asAgentGet("/api/skills/probe/files/CREDENTIALS/api.json")));
    }

    // ==================== POST /api/skills/import ====================

    private static java.io.File zipFile(String... namesAndTexts) throws Exception {
        var file = Files.createTempFile("skill-upload-", ".zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndTexts.length; i += 2) {
                out.putNextEntry(new java.util.zip.ZipEntry(namesAndTexts[i]));
                out.write(namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file.toFile();
    }

    private static java.util.List<String> warnings(com.google.gson.JsonObject body) {
        var out = new java.util.ArrayList<String>();
        body.getAsJsonArray("warnings").forEach(w -> out.add(w.getAsString()));
        return out;
    }

    private static com.google.gson.JsonObject jsonBody(play.mvc.Http.Response response) {
        return com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    @Test
    void aJclawExportImportsBackAsTheSameSkill() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("wa-notifier");
        Files.createDirectories(dir.resolve("tools"));
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: wa-notifier
                description: Sends WhatsApp messages. Use when the operator asks to message someone.
                version: 1.2.0
                author: main
                tools: [exec, filesystem]
                commands: [wacli]
                icon: 💬
                ---
                # WA Notifier

                Run `tools/wacli` with the `exec` tool.
                """);
        Files.write(dir.resolve("tools/wacli"), new byte[]{0x7F, 'E', 'L', 'F', 2, 0, 9});
        Files.writeString(dir.resolve("credentials/api.json"), API_JSON);
        SkillLoader.clearCache();
        var before = SkillLoader.parseSkillFile(dir.resolve("SKILL.md"));

        var exported = GET("/api/skills/wa-notifier/export");
        assertIsOk(exported);
        var upload = Files.createTempFile("wa-notifier", ".zip");
        Files.write(upload, exported.out.toByteArray());
        SkillPromotionService.deleteRecursive(dir);
        SkillLoader.clearCache();

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload.toFile()));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("wa-notifier", body.get("skillName").getAsString());
        assertEquals("[]", body.get("flaggedFiles").toString());
        assertEquals(java.util.List.of("No malware scanner is set up, so this skill's binary files were not scanned: "
                        + "tools/wacli. Set one up in Settings → Malware and Virus Scanners."),
                warnings(body), "no scanner is set up in tests, so the binary is imported with a warning");
        assertEquals(before, SkillLoader.parseSkillFile(dir.resolve("SKILL.md")), "the round trip changes no field");
        assertTrue(Files.isExecutable(dir.resolve("tools/wacli")), "the command still runs after the round trip");
        assertEquals(MASKED_JSON, Files.readString(dir.resolve("credentials/api.json")));
        assertEquals(java.util.List.of("wacli"),
                SkillRegistryTool.findBySkill("wa-notifier").stream().map(r -> r.toolName).toList());
    }

    @Test
    void anUploadFlagsAFileOutsideCredentialsThatLooksLikeItHoldsAKey() throws Exception {
        login();
        var upload = zipFile(
                "notes/SKILL.md", "---\nname: notes\ndescription: Takes notes.\nmetadata:\n  jclaw-tools: \"\"\n---\n# Notes\n",
                "notes/config.json", "{\"apiKey\": \"sk-live-4f3c2a1b9e8d7c6b5a4f\"}",
                "notes/credentials/api.json", API_JSON,
                "notes/README.md", "Keep keys in credentials/.");

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("[\"config.json\"]", body.get("flaggedFiles").toString(),
                "a key outside credentials/ is named; the one inside it and the README are not");
        assertEquals(java.util.List.of("These files look like they hold a key and stay readable to your agents: "
                + "config.json. Move the keys into credentials/."), warnings(body));
        assertEquals("{\"apiKey\": \"sk-live-4f3c2a1b9e8d7c6b5a4f\"}",
                Files.readString(globalSkillsDir.resolve("notes/config.json")), "the upload keeps its layout");
    }

    @Test
    void anUnsafeZipIsRefusedAndNothingIsWritten() throws Exception {
        login();
        var upload = zipFile("SKILL.md", "---\nname: x\n---\n", "../evil.txt", "x");

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("failed", body.get("status").getAsString());
        assertEquals("the zip holds a path outside its folder: ../evil.txt", body.get("message").getAsString());
        try (var listing = Files.list(globalSkillsDir)) {
            assertEquals(java.util.List.of(), listing.toList(), "nothing reached the registry");
        }
    }

    private static final String NEW_NOTES =
            "---\nname: notes\ndescription: Takes better notes.\nmetadata:\n  jclaw-tools: \"\"\n---\n# Notes\n";

    @Test
    void anUploadThatWouldReplaceALibrarySkillWaitsForConfirmation() throws Exception {
        login();
        var dir = seedGlobalSkill("notes", "notes", "Takes notes.");
        var original = Files.readString(dir.resolve("SKILL.md"));
        var upload = zipFile("notes/SKILL.md", NEW_NOTES);

        var asked = jsonBody(POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload)));

        assertEquals("exists", asked.get("status").getAsString(), asked.toString());
        assertEquals("notes", asked.get("skillName").getAsString());
        assertEquals("'notes' is already in the library at version 1.0.0. Replace it with this upload?",
                asked.get("message").getAsString());
        assertEquals(original, Files.readString(dir.resolve("SKILL.md")), "nothing is replaced before the operator says so");

        var replaced = jsonBody(POST("/api/skills/import", java.util.Map.of("replace", "true"),
                java.util.Map.of("file", upload)));

        assertEquals("imported", replaced.get("status").getAsString(), replaced.toString());
        assertEquals("Takes better notes.", SkillLoader.parseSkillFile(dir.resolve("SKILL.md")).description());
    }

    @Test
    void anUploadOlderThanTheLibrarysSkillIsRefusedEvenWhenConfirmed() throws Exception {
        login();
        var dir = globalSkillsDir.resolve("notes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: notes\ndescription: Takes notes.\nmetadata:\n  version: \"1.3.0\"\n---\n# Notes\n");
        var original = Files.readString(dir.resolve("SKILL.md"));
        SkillLoader.clearCache();
        var upload = zipFile("notes/SKILL.md", NEW_NOTES.replace("metadata:\n", "metadata:\n  version: \"1.2.0\"\n"));

        var body = jsonBody(POST("/api/skills/import", java.util.Map.of("replace", "true"), java.util.Map.of("file", upload)));

        assertEquals("failed", body.get("status").getAsString(), body.toString());
        assertEquals("'notes' is in the library at version 1.3.0, and this upload is the older 1.2.0.",
                body.get("message").getAsString());
        assertEquals(original, Files.readString(dir.resolve("SKILL.md")));
    }

    @Test
    void anImportWithNoFileIsABadRequest() {
        login();
        assertStatus(400, POST("/api/skills/import", java.util.Map.of(), java.util.Map.of()));
    }

    @Test
    void importRequiresAuth() throws Exception {
        assertEquals(401, POST("/api/skills/import", java.util.Map.of(),
                java.util.Map.of("file", zipFile("SKILL.md", "x"))).status.intValue());
    }

    @Test
    void anAgentCannotImportAZip() throws Exception {
        clearCookies();
        var request = newRequest();
        request.headers.put("authorization",
                new play.mvc.Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        try {
            var response = POST(request, "/api/skills/import", java.util.Map.of(),
                    java.util.Map.of("file", zipFile("SKILL.md", "x")));

            assertStatus(403, response);
            assertTrue(getContent(response).contains("operator_only"), "got: " + getContent(response));
        } finally {
            clearCookies();
        }
    }

    private static java.util.Map<String, byte[]> unzip(byte[] archive) throws java.io.IOException {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
