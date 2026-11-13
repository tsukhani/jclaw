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
import services.KeptSkillFiles;
import services.SkillPromotionService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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
    private final java.util.List<Long> seededAgentIds = new java.util.ArrayList<>();

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
        for (var id : seededAgentIds) KeptSkillFiles.deleteForAgent(id);
        seededAgentIds.clear();
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
        var id = extractId(getContent(resp));
        seededAgentIds.add(Long.parseLong(id));
        // The in-memory database numbers agents from 1 again in every run, and the kept root under tmp/ outlives it.
        try {
            KeptSkillFiles.deleteForAgent(Long.parseLong(id));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return id;
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
        assertEquals(400, resp.status.intValue());
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
    void listGlobalSkillFilesSizesSkillMdAndItsReferences() throws Exception {
        login();
        var dir = seedGlobalSkill("alpha-skill", "alpha", "First test skill");
        var skillMd = Files.readString(dir.resolve("SKILL.md"));
        var reference = "Call the API with a keyword. ".repeat(12);
        Files.createDirectories(dir.resolve("references"));
        Files.writeString(dir.resolve("references/api.md"), reference);
        Files.createDirectories(dir.resolve("tools"));
        Files.writeString(dir.resolve("tools/README.md"), "How to run the tool.");

        var files = com.google.gson.JsonParser.parseString(getContent(GET("/api/skills/alpha-skill/files")))
                .getAsJsonObject().getAsJsonArray("files");
        var byPath = new java.util.HashMap<String, com.google.gson.JsonObject>();
        files.forEach(f -> byPath.put(f.getAsJsonObject().get("path").getAsString(), f.getAsJsonObject()));

        assertEquals(skillMd.length(), byPath.get("SKILL.md").get("chars").getAsInt());
        assertEquals(llm.TokenUsageEstimator.estimateText(null, skillMd).tokens(),
                byPath.get("SKILL.md").get("estimatedTokens").getAsInt());
        assertEquals(reference.length(), byPath.get("references/api.md").get("chars").getAsInt());
        assertEquals(llm.TokenUsageEstimator.estimateText(null, reference).tokens(),
                byPath.get("references/api.md").get("estimatedTokens").getAsInt());
        assertFalse(byPath.get("tools/README.md").has("chars"), "only what an agent reads into its conversation is sized");
        assertFalse(byPath.get("tools/README.md").has("estimatedTokens"));
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
     * AgentSkillConfig.enabled back to true. Without the toggle, a user who disabled then
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
     * the body-text heuristic. The
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
     * the implicit 'exec' detection.
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
     * 'filesystem' tool.
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
        return asAgentGet(url, null);
    }

    /** A bearer GET stamped as {@code agentId}, as jclaw_api sends it; null sends no agent. */
    private play.mvc.Http.Response asAgentGet(String url, Long agentId) {
        clearCookies();
        var request = newRequest();
        request.headers.put("authorization",
                new play.mvc.Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        if (agentId != null) {
            request.headers.put(controllers.RequestPrincipal.AGENT_ID_HEADER,
                    new play.mvc.Http.Header(controllers.RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        }
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
    void anAgentReadsItsOwnWorkspaceCredentialFileMaskedAndNotAnotherAgents() throws Exception {
        login();
        var ownerId = createAgent("cred-owner");
        var otherId = createAgent("cred-other");
        var dir = seedAgentWorkspaceSkill("cred-owner", "wa", "wa");
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("credentials/api.json"), API_JSON);
        var url = "/api/agents/" + ownerId + "/skills/wa/files/credentials/api.json";

        var own = asAgentGet(url, Long.valueOf(ownerId));
        assertIsOk(own);
        assertEquals(MASKED_JSON, fileContent(own));

        // JF-285: another agent's skill files are its settings, not this agent's.
        var other = asAgentGet(url, Long.valueOf(otherId));
        assertStatus(403, other);
        assertEquals("agent_scope",
                com.google.gson.JsonParser.parseString(getContent(other)).getAsJsonObject().get("code").getAsString());
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
                + "config.json. Remove the keys from them, and add each one in the skill's Secrets panel on the agent that uses it."), warnings(body));
        assertEquals("{\"apiKey\": \"sk-live-4f3c2a1b9e8d7c6b5a4f\"}",
                Files.readString(globalSkillsDir.resolve("notes/config.json")), "the upload keeps its layout");
    }

    /** JF-353: a SKILL.md's secrets block names secrets by design, so naming one is not "holding a key". */
    @Test
    void anUploadIsNotFlaggedForDeclaringItsSecrets() throws Exception {
        login();
        var skillMd = "---\nname: weather\ndescription: Tells the weather.\nmetadata:\n  jclaw-tools: \"\"\n---\n# Weather\n\n"
                + "## Secrets\n\n```secrets\nWEATHER_API_KEY=api.example.com   # The service's API key\n```\n\n"
                + "```bash\ncurl -s -H \"X-API-Key: $WEATHER_API_KEY\" \"https://api.example.com/v1/today\"\n```\n";
        var upload = zipFile("weather/SKILL.md", skillMd);

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("[]", body.get("flaggedFiles").toString());
        assertEquals(java.util.List.of(), warnings(body));
        assertEquals(java.util.List.of("WEATHER_API_KEY"),
                services.SkillSecrets.declared(globalSkillsDir.resolve("weather")).stream().map(d -> d.name()).toList(),
                "the imported skill still declares its secret");
    }

    @Test
    void anUploadIsFlaggedWhenItsCommandsCarryAKeyRatherThanTheVariable() throws Exception {
        login();
        var skillMd = "---\nname: weather\ndescription: Tells the weather.\nmetadata:\n  jclaw-tools: \"\"\n---\n# Weather\n\n"
                + "## Secrets\n\n```secrets\nWEATHER_API_KEY=api.example.com   # The service's API key\n```\n\n"
                + "```bash\ncurl -s -H \"X-API-Key: 0123456789abcdef0123456789abcdef\" \"https://api.example.com/v1/today\"\n```\n";
        var upload = zipFile("weather/SKILL.md", skillMd);

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("[\"SKILL.md\"]", body.get("flaggedFiles").toString());
    }

    /** JF-357: a sign-in made of a declared setting and a declared secret is two references, not a value. */
    @Test
    void anUploadIsNotFlaggedForSigningInWithADeclaredSettingAndSecret() throws Exception {
        login();
        var skillMd = "---\nname: nas\ndescription: Lists the NAS.\nmetadata:\n  jclaw-tools: \"\"\n---\n# NAS\n\n"
                + "```secrets\nNAS_PASS=nas.example.com:5001   # The password\nsetting NAS_USER=admin   # The user\n```\n\n"
                + "```bash\ncurl -s -u \"$NAS_USER:$NAS_PASS\" \"https://nas.example.com:5001/webapi/entry.cgi\"\n```\n";
        var upload = zipFile("nas/SKILL.md", skillMd);

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("[]", body.get("flaggedFiles").toString());
    }

    /** Only a declared secret's reference is not a value: an undeclared variable after a key header is judged as usual. */
    @Test
    void anUploadIsFlaggedForAKeyHeaderThatNamesNoDeclaredSecret() throws Exception {
        login();
        var skillMd = "---\nname: weather\ndescription: Tells the weather.\nmetadata:\n  jclaw-tools: \"\"\n---\n# Weather\n\n"
                + "## Secrets\n\n```secrets\nWEATHER_API_KEY=api.example.com   # The service's API key\n```\n\n"
                + "```bash\ncurl -s -H \"X-API-Key: $OTHER_KEY\" \"https://api.example.com/v1/today\"\n```\n";
        var upload = zipFile("weather/SKILL.md", skillMd);

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("[\"SKILL.md\"]", body.get("flaggedFiles").toString());
    }

    @Test
    void anUploadIsFlaggedWhenItsSecretsBlockHoldsAValue() throws Exception {
        login();
        var skillMd = "---\nname: leaky\ndescription: Leaks.\nmetadata:\n  jclaw-tools: \"\"\n---\n# Leaky\n\n"
                + "```secrets\nLEAKY_API_KEY=0123456789abcdef0123456789abcdef   # pasted by mistake\n```\n";
        var upload = zipFile("leaky/SKILL.md", skillMd);

        var response = POST("/api/skills/import", java.util.Map.of(), java.util.Map.of("file", upload));

        assertIsOk(response);
        var body = jsonBody(response);
        assertEquals("imported", body.get("status").getAsString(), body.toString());
        assertEquals("[\"SKILL.md\"]", body.get("flaggedFiles").toString());
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

    // ==================== an update keeps the operator's files (JF-352) ====================

    private static final String KEY = "sk-gamma-fake-jf352";

    /** Agent {@code agentName} holds v1 of {@code skill}; the registry now ships v2, declaring {@code secretsBlock}. */
    private String agentWithOldCopy(String agentName, String skill, String secretsBlock) throws Exception {
        var id = createAgent(agentName);
        seedGlobalSkill(skill, skill, "A skill");
        assertIsOk(POST("/api/agents/" + id + "/skills/" + skill + "/copy", "application/json", "{}"));
        Files.writeString(globalSkillsDir.resolve(skill).resolve("SKILL.md"), "---\nname: " + skill
                + "\ndescription: A skill\nversion: 2.0.0\n---\n# " + skill + "\n\n" + secretsBlock);
        SkillLoader.clearCache();
        return id;
    }

    private static Path copyOf(String agentName, String skill) {
        return AgentService.workspacePath(agentName).resolve("skills").resolve(skill);
    }

    private static com.google.gson.JsonArray keptOf(play.mvc.Http.Response response) {
        return com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject().getAsJsonArray("kept");
    }

    @Test
    void anUpdateNamesWhatItNeedsAndKeepsAndThenListsTheKeptFileWithoutItsContent() throws Exception {
        login();
        var id = agentWithOldCopy("keep-preview", "gamma",
                "```secrets\nGAMMA_API_KEY=public-api.gamma.app   # The Gamma key\n```\n");
        Files.createDirectories(copyOf("keep-preview", "gamma").resolve("credentials"));
        Files.writeString(copyOf("keep-preview", "gamma").resolve("credentials/gamma-api-key.txt"), KEY + "\n");
        var base = "/api/agents/" + id + "/skills/gamma";

        var preview = GET(base + "/update-preview");
        assertIsOk(preview);
        assertEquals("{\"needs\":[\"GAMMA_API_KEY\"],\"heldBySwitchedOff\":{},\"keeps\":[\"credentials/gamma-api-key.txt\"]}", getContent(preview));
        assertEquals(KEY + "\n", Files.readString(copyOf("keep-preview", "gamma").resolve("credentials/gamma-api-key.txt")),
                "a preview changes nothing");
        assertEquals("{\"kept\":[]}", getContent(GET(base + "/kept")));

        var before = java.time.Instant.now().minusSeconds(1);
        assertIsOk(POST(base + "/copy", "application/json", "{}"));

        var listing = GET(base + "/kept");
        assertIsOk(listing);
        var kept = keptOf(listing);
        assertEquals(1, kept.size());
        var entry = kept.get(0).getAsJsonObject();
        assertEquals(List.of("id", "keptAt", "path", "size"), entry.keySet().stream().sorted().toList());
        assertEquals("credentials/gamma-api-key.txt", entry.get("path").getAsString());
        assertEquals(KEY.length() + 1, entry.get("size").getAsLong());
        var keptAt = java.time.Instant.parse(entry.get("keptAt").getAsString());
        assertFalse(keptAt.isBefore(before) || keptAt.isAfter(java.time.Instant.now().plusSeconds(1)), keptAt.toString());
        assertFalse(getContent(listing).contains(KEY), getContent(listing));
        assertEquals(List.of("SKILL.md", "credentials/gamma-api-key.txt"), filesOf(copyOf("keep-preview", "gamma")),
                "the new copy holds what the registry ships and the operator's credentials (JF-405)");
    }

    private static String statusAndMessage(play.mvc.Http.Response response) {
        return response.status + " " + com.google.gson.JsonParser.parseString(getContent(response))
                .getAsJsonObject().get("message").getAsString();
    }

    private static List<String> filesOf(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void anUpdatePreviewKeepsAnEditedConfigButNotItsExample() throws Exception {
        login();
        var id = agentWithOldCopy("keep-preview-config", "jira", "");
        Files.writeString(copyOf("keep-preview-config", "jira").resolve("config.env"), "JIRA_URL=\"https://jira.example.org\"\n");
        Files.writeString(copyOf("keep-preview-config", "jira").resolve("config.env.example"), "JIRA_URL=\n");

        assertEquals("{\"needs\":[],\"heldBySwitchedOff\":{},\"keeps\":[\"config.env\"]}",
                getContent(GET("/api/agents/" + id + "/skills/jira/update-preview")));
    }

    /** A value no key-shape mask matches, so a command's output shows it as it is. */
    private static final String NAS_KEY = "fake-nas-key-jf405";

    @Test
    void anUpdateCarriesTheCredentialsFolderIntoTheNewCopyAndTheSkillsScriptStillReadsTheKey() throws Exception {
        login();
        var id = agentWithOldCopy("carry-creds", "nas", "");
        Files.writeString(globalSkillsDir.resolve("nas").resolve("run.sh"),
                ". \"$(dirname \"$0\")/credentials/nas.env\"; echo \"read $NAS_KEY\"\n");
        var old = copyOf("carry-creds", "nas");
        Files.createDirectories(old.resolve("credentials"));
        Files.writeString(old.resolve("credentials/nas.env"), "NAS_KEY=" + NAS_KEY + "\n");
        Files.setPosixFilePermissions(old.resolve("credentials/nas.env"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var base = "/api/agents/" + id + "/skills/nas";

        assertIsOk(POST(base + "/copy", "application/json", "{}"));

        var updated = copyOf("carry-creds", "nas");
        assertEquals(List.of("SKILL.md", "credentials/nas.env", "run.sh"), filesOf(updated));
        assertEquals("NAS_KEY=" + NAS_KEY + "\n", Files.readString(updated.resolve("credentials/nas.env")));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(updated.resolve("credentials/nas.env"))));
        var kept = keptOf(GET(base + "/kept"));
        assertEquals(1, kept.size(), "the kept copy and its import offer stay as JF-352 made them");
        assertEquals("credentials/nas.env", kept.get(0).getAsJsonObject().get("path").getAsString());

        // The subject is the file being there for the script, not confinement: a host with no working sandbox
        // would refuse a custom agent's confined run.
        ShellSandboxSync.acquireUnconfined();
        var previous = services.ConfigService.get("shell.allowlist");
        try {
            services.ConfigService.set("shell.allowlist", "sh");
            var args = new com.google.gson.JsonObject();
            args.addProperty("command", "sh skills/nas/run.sh");
            var text = new tools.ShellExecTool().execute(args.toString(), Agent.findByName("carry-creds"));
            var result = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            assertEquals("0 read " + NAS_KEY, result.get("exitCode").getAsInt() + " " + result.get("output").getAsString().strip(), text);
        } finally {
            if (previous == null) services.ConfigService.delete("shell.allowlist");
            else services.ConfigService.set("shell.allowlist", previous);
            ShellSandboxSync.release();
        }
    }

    @Test
    void anUpdateWhoseCarryOverFailsIsRefusedAndLeavesTheOldCopyAndNoKeptFiles() throws Exception {
        login();
        var id = agentWithOldCopy("carry-fails", "nas", "");
        // The library ships a file where the old copy's credentials folder must go, so carrying it over fails.
        Files.writeString(globalSkillsDir.resolve("nas").resolve("credentials"), "shipped");
        var old = copyOf("carry-fails", "nas");
        var skillMd = Files.readString(old.resolve("SKILL.md"));
        Files.createDirectories(old.resolve("credentials"));
        Files.writeString(old.resolve("credentials/nas.env"), "NAS_KEY=" + NAS_KEY + "\n");

        var response = POST("/api/agents/" + id + "/skills/nas/copy", "application/json", "{}");

        var body = getContent(response);
        assertEquals(500, (int) response.status, body);
        assertTrue(body.matches("Failed to copy skill: " + java.util.regex.Pattern.quote(old.toString()) + "\\.copying-\\d+/credentials"),
                body);
        assertEquals(List.of("SKILL.md", "credentials/nas.env"), filesOf(old));
        assertEquals(skillMd, Files.readString(old.resolve("SKILL.md")), "the old copy is still in place");
        try (var entries = Files.list(old.getParent())) {
            assertEquals(List.of("nas"), entries.map(p -> p.getFileName().toString()).toList(), "no staging copy is left");
        }
        assertEquals("{\"kept\":[]}", getContent(GET("/api/agents/" + id + "/skills/nas/kept")),
                "what the update kept goes with it");
    }

    @Test
    void anUpdateOfAnUntouchedCopyNeedsAndKeepsNothing() throws Exception {
        login();
        var id = agentWithOldCopy("keep-preview-none", "plain", "");
        var base = "/api/agents/" + id + "/skills/plain";

        assertEquals("{\"needs\":[],\"heldBySwitchedOff\":{},\"keeps\":[]}", getContent(GET(base + "/update-preview")));
        assertIsOk(POST(base + "/copy", "application/json", "{}"));
        assertEquals("{\"kept\":[]}", getContent(GET(base + "/kept")));
    }

    @Test
    void aFirstInstallPreviewKeepsNothingAndAnUnknownSkillOrAgentIsNotFound() throws Exception {
        login();
        var id = createAgent("keep-preview-fresh");
        seedGlobalSkill("gamma", "gamma", "Gamma");

        assertEquals("{\"needs\":[],\"heldBySwitchedOff\":{},\"keeps\":[]}", getContent(GET("/api/agents/" + id + "/skills/gamma/update-preview")));
        assertEquals("404 not_found Global skill 'absent' not found",
                errorOf(GET("/api/agents/" + id + "/skills/absent/update-preview")));
        assertEquals("404 not_found No agent 999999999.", errorOf(GET("/api/agents/999999999/skills/gamma/update-preview")));
        assertEquals("404 not_found No agent 999999999.", errorOf(GET("/api/agents/999999999/skills/gamma/kept")));
    }

    /** An error answer's status, code and message, which together fix its whole body. */
    private static String errorOf(play.mvc.Http.Response response) {
        var body = com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(List.of("code", "message", "template", "type"), body.keySet().stream().sorted().toList());
        assertEquals("error", body.get("type").getAsString());
        return response.status + " " + body.get("code").getAsString() + " " + body.get("message").getAsString();
    }

    @Test
    void aKeptFileIsDeletedOnceAndThenNotFound() throws Exception {
        login();
        var id = agentWithOldCopy("keep-delete", "gamma", "");
        Files.createDirectories(copyOf("keep-delete", "gamma").resolve("credentials"));
        Files.writeString(copyOf("keep-delete", "gamma").resolve("credentials/gamma-api-key.txt"), KEY + "\n");
        var base = "/api/agents/" + id + "/skills/gamma";
        assertIsOk(POST(base + "/copy", "application/json", "{}"));
        var keptId = keptOf(GET(base + "/kept")).get(0).getAsJsonObject().get("id").getAsString();

        var deleted = DELETE(base + "/kept/" + keptId);
        assertIsOk(deleted);
        assertEquals("{\"deleted\":true}", getContent(deleted));
        assertEquals("{\"kept\":[]}", getContent(GET(base + "/kept")));
        assertEquals("404 No kept file '" + keptId + "' on skill 'gamma'.", statusAndMessage(DELETE(base + "/kept/" + keptId)));
    }

    @Test
    void aKeptFileIdThatLeadsOutOfTheKeptFolderIsRefused() throws Exception {
        login();
        var id = createAgent("keep-traversal");
        var victim = AgentService.workspacePath("keep-traversal").resolve("notes.txt");
        Files.createDirectories(victim.getParent());
        Files.writeString(victim, "keep\n");
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        for (var path : List.of("../../../../workspace-test/keep-traversal/notes.txt",
                "20261113T000000.000Z/../../../../../notes.txt", "20261113T000000.000Z", "20261113T000000.000Z//x",
                "/etc/passwd", "not-a-time/x")) {
            var keptId = encoder.encodeToString(path.getBytes(StandardCharsets.UTF_8));
            assertEquals("400 '" + keptId + "' is not a kept file's id.",
                    statusAndMessage(DELETE("/api/agents/" + id + "/skills/gamma/kept/" + keptId)), path);
        }
        assertEquals("400 'not*base64' is not a kept file's id.",
                statusAndMessage(DELETE("/api/agents/" + id + "/skills/gamma/kept/not*base64")));
        var wellFormed = encoder.encodeToString("20261113T000000.000Z/credentials/x.txt".getBytes(StandardCharsets.UTF_8));
        assertEquals("404 No kept file '" + wellFormed + "' on skill 'gamma'.",
                statusAndMessage(DELETE("/api/agents/" + id + "/skills/gamma/kept/" + wellFormed)));
        assertEquals("400 '..' is not a skill name: pass the skill's folder name, not a path.",
                statusAndMessage(GET("/api/agents/" + id + "/skills/../kept")));
        assertEquals("keep\n", Files.readString(victim));
    }

    @Test
    void removingTheSkillFromTheAgentDeletesItsKeptFiles() throws Exception {
        login();
        var id = agentWithOldCopy("keep-remove", "gamma", "");
        Files.createDirectories(copyOf("keep-remove", "gamma").resolve("credentials"));
        Files.writeString(copyOf("keep-remove", "gamma").resolve("credentials/gamma-api-key.txt"), KEY + "\n");
        var base = "/api/agents/" + id + "/skills/gamma";
        assertIsOk(POST(base + "/copy", "application/json", "{}"));
        assertEquals(1, keptOf(GET(base + "/kept")).size());

        assertIsOk(DELETE(base + "/delete"));

        assertEquals("{\"kept\":[]}", getContent(GET(base + "/kept")));
        assertFalse(Files.exists(KeptSkillFiles.root().resolve(id)), "nothing is left for the agent");
    }

    /** A name is used once on an agent: one a switched-off skill holds is to switch on, not to add (JF-352). */
    @Test
    void anUpdatePreviewNamesTheSwitchedOffSkillThatHoldsANeededValue() throws Exception {
        login();
        var id = agentWithOldCopy("keep-preview-held", "gamma",
                "```secrets\nNAS_PASS=nas.example.com   # The DSM password\nGAMMA_API_KEY=public-api.gamma.app   # The key\n```\n");
        seedGlobalSkill("nas", "nas", "NAS");
        assertIsOk(POST("/api/agents/" + id + "/skills/nas/copy", "application/json", "{}"));
        assertIsOk(PUT("/api/agents/" + id + "/skills/nas/secrets/NAS_PASS", "application/json",
                "{\"value\":\"fake-nas-pass-jf352\",\"hosts\":[\"nas.example.com\"]}"));
        var base = "/api/agents/" + id + "/skills/gamma/update-preview";
        assertEquals("{\"needs\":[\"GAMMA_API_KEY\"],\"heldBySwitchedOff\":{},\"keeps\":[]}", getContent(GET(base)),
                "a value a switched-on skill holds is given to every skill");

        assertIsOk(PUT("/api/agents/" + id + "/skills/nas", "application/json", "{\"enabled\":false}"));

        assertEquals("{\"needs\":[\"NAS_PASS\",\"GAMMA_API_KEY\"],\"heldBySwitchedOff\":{\"NAS_PASS\":\"nas\"},\"keeps\":[]}",
                getContent(GET(base)));
    }

    /** The skill's secrets are gone by then: stopping would leave it in place without them. */
    @Test
    void removingASkillWhoseKeptFilesCannotBeDeletedStillRemovesTheSkillAndWarns() throws Exception {
        login();
        var id = agentWithOldCopy("keep-remove-fails", "gamma", "");
        Files.createDirectories(copyOf("keep-remove-fails", "gamma").resolve("credentials"));
        Files.writeString(copyOf("keep-remove-fails", "gamma").resolve("credentials/gamma-api-key.txt"), KEY + "\n");
        var base = "/api/agents/" + id + "/skills/gamma";
        assertIsOk(POST(base + "/copy", "application/json", "{}"));
        List<Path> folders;
        try (var entries = Files.list(KeptSkillFiles.root().resolve(id).resolve("gamma"))) {
            folders = entries.toList();
        }
        var folder = folders.getFirst();
        // An unreadable folder makes the walk under the delete throw UncheckedIOException, not IOException.
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("---------"));
        try {
            var events = services.EventLogger.captureMatchingForTest(e -> "keep-remove-fails".equals(e.agentId()),
                    seen -> assertIsOk(DELETE(base + "/delete")));

            assertFalse(Files.exists(copyOf("keep-remove-fails", "gamma")), "the skill's folder is still removed");
            var warnings = events.stream().filter(e -> "WARN".equals(e.level())).map(services.EventLogger.Captured::message).toList();
            assertEquals(List.of("Could not delete the kept files of skill gamma: java.nio.file.AccessDeniedException: " + folder),
                    warnings);
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void anAgentCannotPreviewAnUpdateOrReadOrDeleteKeptFiles() throws Exception {
        for (var action : List.of("updatePreview", "listKept", "deleteKept")) {
            var method = java.util.Arrays.stream(controllers.ApiSkillsController.class.getMethods())
                    .filter(m -> m.getName().equals(action)).findFirst().orElseThrow();
            assertEquals(controllers.AgentAccess.Level.OPERATOR_ONLY,
                    method.getAnnotation(controllers.AgentAccess.class).value(), action);
        }
        login();
        var id = agentWithOldCopy("keep-agent", "gamma", "");
        Files.createDirectories(copyOf("keep-agent", "gamma").resolve("credentials"));
        Files.writeString(copyOf("keep-agent", "gamma").resolve("credentials/gamma-api-key.txt"), KEY + "\n");
        var base = "/api/agents/" + id + "/skills/gamma";
        assertIsOk(POST(base + "/copy", "application/json", "{}"));
        var keptId = keptOf(GET(base + "/kept")).get(0).getAsJsonObject().get("id").getAsString();

        var codes = new java.util.ArrayList<String>();
        for (var response : List.of(asAgent("GET", base + "/update-preview", Long.parseLong(id)),
                asAgent("GET", base + "/kept", Long.parseLong(id)),
                asAgent("DELETE", base + "/kept/" + keptId, Long.parseLong(id)))) {
            codes.add(response.status + " " + com.google.gson.JsonParser.parseString(getContent(response))
                    .getAsJsonObject().get("code").getAsString());
            assertFalse(getContent(response).contains(KEY));
        }
        assertEquals(List.of("403 operator_only", "403 operator_only", "403 operator_only"), codes);
        login();
        assertEquals(1, keptOf(GET(base + "/kept")).size(), "the kept file is still there");
    }

    /** A bearer request stamped as {@code agentId}, as jclaw_api sends it. */
    private play.mvc.Http.Response asAgent(String method, String url, Long agentId) {
        clearCookies();
        var request = newRequest();
        request.headers.put("authorization",
                new play.mvc.Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        request.headers.put(controllers.RequestPrincipal.AGENT_ID_HEADER,
                new play.mvc.Http.Header(controllers.RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        try {
            return "DELETE".equals(method) ? DELETE(request, url) : GET(request, url);
        } finally {
            clearCookies();
        }
    }
}
