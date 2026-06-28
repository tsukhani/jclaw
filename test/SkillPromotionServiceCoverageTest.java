import agents.SkillLoader;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.SkillRegistryTool;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.NotificationBus;
import services.SkillPromotionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supplemental coverage for {@code SkillPromotionService} — focuses on the
 * branches NOT exercised by {@code SkillPromotionServiceTest} (primitive
 * helpers) or {@code SkillAllowlistTest} (install/revoke happy paths):
 * <ul>
 *     <li>{@code validateToolRequirements} — missing tool catalog overlap.</li>
 *     <li>{@code promoteInBackground} — capability gate denials, hash-noop
 *         shortcut, downgrade refusal, malware refusal.</li>
 *     <li>{@code copyToAgentWorkspace} replace-existing path.</li>
 *     <li>{@code atomicSwap} rollback on a move failure.</li>
 * </ul>
 *
 * <p>Each test points the global skills registry at a fresh temp dir so the
 * repo's shipped {@code skills/} folder cannot contaminate assertions. Agent
 * workspaces are scrubbed in {@code @AfterEach}.
 */
class SkillPromotionServiceCoverageTest extends UnitTest {

    private Path globalSkillsDir;
    private final java.util.List<String> seededAgentNames = new java.util.ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        globalSkillsDir = Files.createTempDirectory("skill-promotion-cov-");
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
            } catch (Exception _) {}
        }
        seededAgentNames.clear();
        // Drop SkillRegistryTool rows seeded by syncAgentAllowlistMirrorsRegistry
        // (the only test in this class that inserts them) so leftover rows can't
        // leak into the next test's SkillRegistryTool.findBySkill calls.
        SkillRegistryTool.deleteBySkill("demo");
        SkillRegistryTool.deleteBySkill("phantom");
    }

    // ==================== Helpers ====================

    private Agent createAgent(String name) {
        seededAgentNames.add(name);
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    private static void writeSkillMd(Path dir, String name, String version, String... commands) throws Exception {
        Files.createDirectories(dir);
        var cmdList = commands.length == 0 ? "[]"
                : "[" + String.join(", ", java.util.Arrays.stream(commands)
                        .map(c -> "\"" + c + "\"").toList()) + "]";
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: " + name + "\n"
              + "description: test skill\n"
              + "version: " + version + "\n"
              + "commands: " + cmdList + "\n"
              + "---\n"
              + "# " + name + "\n");
    }

    /**
     * Subscribe to NotificationBus, run {@code trigger}, then block until an
     * event with the given {@code type} fires (or {@code timeout} elapses).
     * Returns the SSE payload of the first matching event, or {@code null} on
     * timeout — caller assertions decide whether absence is acceptable.
     *
     * <p>Uses a {@link CountDownLatch} instead of a Thread.sleep polling loop:
     * the latch is released by the bus's dispatch thread the instant the
     * matching event arrives, so the helper wakes immediately rather than at
     * the next poll tick.
     */
    private String waitForEvent(String type, Runnable trigger, Duration timeout) throws InterruptedException {
        var latch = new CountDownLatch(1);
        var holder = new AtomicReference<String>();
        var off = NotificationBus.subscribe(payload -> {
            if (payload.contains("\"type\":\"" + type + "\"") && holder.compareAndSet(null, payload)) {
                latch.countDown();
            }
        });
        try {
            trigger.run();
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return holder.get();
        } finally {
            off.run();
        }
    }

    // ==================== validateToolRequirements ====================

    @Test
    void validateToolRequirementsPassesWhenSkillFileAbsent() {
        // No global skill on disk → validation is a no-op pass.
        var agent = createAgent("vtool-no-skill");
        var result = SkillPromotionService.validateToolRequirements(agent, "no-such-skill");
        assertTrue(result.ok(), "missing SKILL.md must short-circuit to ok");
        assertNull(result.message());
    }

    @Test
    void validateToolRequirementsPassesWhenSkillDeclaresNoTools() throws Exception {
        var agent = createAgent("vtool-empty-tools");
        var dir = globalSkillsDir.resolve("empty-tools");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: empty-tools\ndescription: test\nversion: 1.0.0\n---\n# body\n");

        var result = SkillPromotionService.validateToolRequirements(agent, "empty-tools");
        assertTrue(result.ok(), "skill with no tools: declaration passes");
    }

    @Test
    void validateToolRequirementsFailsWithUnknownTools() throws Exception {
        var agent = createAgent("vtool-unknown");
        var dir = globalSkillsDir.resolve("needs-fake");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: needs-fake
                description: depends on a tool that does not exist
                version: 1.0.0
                tools: [definitely_not_a_real_tool_name_xyz]
                ---
                # body
                """);

        var result = SkillPromotionService.validateToolRequirements(agent, "needs-fake");
        assertFalse(result.ok(), "unknown tool dependency must fail validation");
        assertNotNull(result.message());
        assertTrue(result.message().contains("definitely_not_a_real_tool_name_xyz"),
                "error message names the offending tool: " + result.message());
        assertTrue(result.message().contains("vtool-unknown"),
                "error message names the agent: " + result.message());
    }

    // ==================== copyToAgentWorkspace — replace existing ====================

    @Test
    void copyToAgentWorkspaceReplacesExisting() throws Exception {
        var agent = createAgent("copy-replace-agent");

        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "1.0.0");

        // First install
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");
        var workspaceMd = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("demo").resolve("SKILL.md");
        assertTrue(Files.exists(workspaceMd));

        // Update the global copy, then re-install — copyToAgentWorkspace must
        // overwrite the existing workspace folder (the {@code replacing} branch
        // in the atomic-swap path).
        writeSkillMd(skillDir, "demo", "2.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");

        assertTrue(Files.readString(workspaceMd).contains("version: 2.0.0"),
                "second copy must overwrite the workspace copy with the new version");
    }

    // ==================== promoteInBackground — gates ====================

    @Test
    void promoteRefusedWhenAgentNotFound() throws Exception {
        var agent = createAgent("promote-bogus-id");
        var workspaceSkillDir = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("nope");
        Files.createDirectories(workspaceSkillDir);
        writeSkillMd(workspaceSkillDir, "nope", "1.0.0");

        var payload = waitForEvent("skill.promote_failed",
                () -> SkillPromotionService.promoteInBackground(workspaceSkillDir, "nope", 9_999_999L),
                Duration.ofSeconds(2));
        assertNotNull(payload, "publishes skill.promote_failed when the agent id is unknown");
        assertTrue(payload.contains("Requesting agent not found"),
                "error message indicates missing agent: " + payload);
    }

    @Test
    void promoteRefusedWhenAgentLacksSkillCreator() throws Exception {
        var agent = createAgent("promote-no-creator");
        var workspaceSkillDir = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("alpha");
        Files.createDirectories(workspaceSkillDir);
        writeSkillMd(workspaceSkillDir, "alpha", "1.0.0");

        var payload = waitForEvent("skill.promote_failed",
                () -> SkillPromotionService.promoteInBackground(workspaceSkillDir, "alpha", agent.id),
                Duration.ofSeconds(2));
        assertNotNull(payload, "must publish skill.promote_failed when capability missing");
        assertTrue(payload.contains("skill-creator capability"),
                "error message mentions the missing capability: " + payload);
        // Nothing should land in the global registry
        assertFalse(Files.exists(globalSkillsDir.resolve("alpha").resolve("SKILL.md")),
                "global registry must not receive the skill when capability gate fails");
    }

    @Test
    void isIdenticalToGlobalPublishesPromoteNoopForByteIdenticalCopies() throws Exception {
        // Tests the isIdenticalToGlobal predicate directly rather than driving
        // it through promoteInBackground — a future refactor that moved
        // sanitizeWithLlm (a real LLM HTTP call) earlier than the hash-equal
        // short-circuit would silently take this test to the network. The
        // predicate publishes skill.promote_noop as a side effect, so calling
        // it directly is sufficient to pin the noop-event contract.
        var agent = createAgent("promote-identical");

        // Author "alpha" in BOTH global and workspace with identical content.
        // Same generator function on both sides guarantees the bytes match.
        var globalAlpha = globalSkillsDir.resolve("alpha");
        writeSkillMd(globalAlpha, "alpha", "1.0.0");

        var workspaceAlpha = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("alpha");
        writeSkillMd(workspaceAlpha, "alpha", "1.0.0");
        SkillLoader.clearCache();

        var payload = waitForEvent("skill.promote_noop",
                () -> assertTrue(
                        SkillPromotionService.isIdenticalToGlobal(workspaceAlpha, globalAlpha, "alpha"),
                        "byte-identical workspace + global must short-circuit to true"),
                Duration.ofSeconds(2));
        assertNotNull(payload, "publishes skill.promote_noop when workspace and global are byte-identical");
        assertTrue(payload.contains("identical"), "noop reason mentions identical: " + payload);
    }

    @Test
    void isDowngradePublishesPromoteFailedForOlderWorkspaceVersion() throws Exception {
        // Tests the isDowngrade predicate directly rather than driving it
        // through promoteInBackground — see the noop test above for the same
        // rationale. The predicate publishes skill.promote_failed as a side
        // effect, so calling it directly is sufficient to pin the contract.
        var agent = createAgent("promote-downgrade");

        // Global is at 2.0.0, workspace at 1.0.0 — downgrade
        var globalAlpha = globalSkillsDir.resolve("alpha");
        writeSkillMd(globalAlpha, "alpha", "2.0.0");

        var workspaceAlpha = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("alpha");
        writeSkillMd(workspaceAlpha, "alpha", "1.0.0");
        SkillLoader.clearCache();

        var payload = waitForEvent("skill.promote_failed",
                () -> assertTrue(
                        SkillPromotionService.isDowngrade(workspaceAlpha, globalAlpha, "alpha"),
                        "workspace 1.0.0 vs global 2.0.0 must be detected as a downgrade"),
                Duration.ofSeconds(2));
        assertNotNull(payload, "downgrade must publish skill.promote_failed");
        assertTrue(payload.contains("older than the global version"),
                "downgrade reason surfaced: " + payload);
        // Global must not be replaced — verify the version is still 2.0.0
        assertTrue(Files.readString(globalAlpha.resolve("SKILL.md")).contains("version: 2.0.0"),
                "global skill must remain at the higher version after refused downgrade");
    }

    // ==================== syncAgentAllowlistFromRegistry — empty registry ====================

    @Test
    void syncAgentAllowlistClearsRowsWhenRegistryEmpty() {
        var agent = createAgent("sync-empty");

        // Pre-seed a stale row to prove it gets cleared
        var stale = new AgentSkillAllowedTool();
        stale.agent = agent;
        stale.skillName = "phantom";
        stale.toolName = "rm";
        stale.save();
        assertEquals(1, AgentSkillAllowedTool.findByAgentAndSkill(agent, "phantom").size());

        // No SkillRegistryTool rows for "phantom" → sync drops the stale row
        SkillPromotionService.syncAgentAllowlistFromRegistry(agent, "phantom");

        assertEquals(0, AgentSkillAllowedTool.findByAgentAndSkill(agent, "phantom").size(),
                "stale rows must be cleared when registry has no blessings for the skill");
    }

    @Test
    void syncAgentAllowlistMirrorsRegistry() {
        var agent = createAgent("sync-mirror");

        // Seed registry rows
        for (var cmd : java.util.List.of("foo", "bar", "baz")) {
            var row = new SkillRegistryTool();
            row.skillName = "demo";
            row.toolName = cmd;
            row.save();
        }

        SkillPromotionService.syncAgentAllowlistFromRegistry(agent, "demo");

        var snapshot = AgentSkillAllowedTool.findByAgentAndSkill(agent, "demo");
        assertEquals(3, snapshot.size());
        var tools = snapshot.stream().map(r -> r.toolName).sorted().toList();
        assertEquals(java.util.List.of("bar", "baz", "foo"), tools);
    }

    // ==================== atomicSwap — failure path leaves backup intact ====================

    @Test
    void atomicSwapFailureRestoresBackup() throws Exception {
        var workDir = Files.createTempDirectory("atomic-swap-fail-");
        try {
            var target = workDir.resolve("target");
            var staging = workDir.resolve("staging-does-not-exist"); // intentionally absent
            var backup = workDir.resolve("backup");

            Files.createDirectories(target);
            Files.writeString(target.resolve("keep.txt"), "original");

            Assertions.assertThrows(java.io.IOException.class, () ->
                    SkillPromotionService.atomicSwap(target, staging, backup, true),
                    "moving a non-existent staging directory must throw");

            // Failure path: target must still exist with original content, backup gone or restored.
            assertTrue(Files.exists(target.resolve("keep.txt")),
                    "original content must be restored from backup on swap failure");
            assertEquals("original", Files.readString(target.resolve("keep.txt")));
        } finally {
            SkillPromotionService.deleteRecursive(workDir);
        }
    }

    // ==================== promoteInBackground end-to-end (LLM-skipped) ====================

    /**
     * Install skill-creator into {@code agent}'s workspace so
     * {@link SkillPromotionService#hasSkillCreatorCapability} passes. The
     * promotion pipeline's capability gate at the top of
     * {@code promoteInBackground} is exercised in
     * {@link #promoteRefusedWhenAgentLacksSkillCreator}; here we want to push
     * past it to cover the file walk, frontmatter preservation, sanitize
     * pass-through, and atomic-write branches.
     */
    private void installSkillCreatorWorkspace(Agent agent) throws Exception {
        var skillCreatorDir = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve(SkillPromotionService.SKILL_CREATOR_NAME);
        writeSkillMd(skillCreatorDir, SkillPromotionService.SKILL_CREATOR_NAME, "1.0.0");
    }

    /**
     * End-to-end promotion happy path with LLM sanitization short-circuited by
     * absence of a configured provider and no main agent — covers
     * writeToGlobalRegistry, syncRegistryToolRows, atomic swap into the global
     * registry, and the skill.promoted notification.
     */
    @Test
    void promoteEndToEndWritesGlobalRegistryWhenNoLlmConfigured() throws Exception {
        var agent = createAgent("promote-e2e-no-llm");
        installSkillCreatorWorkspace(agent);
        SkillLoader.clearCache();

        // No main agent + no skillsPromotion.provider config → sanitizeWithLlm
        // returns the inputs unchanged (lines 588-592 of SkillPromotionService).
        var workspaceSkillDir = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceSkillDir, "widget", "1.0.0", "echo", "ls");

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceSkillDir, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload, "must publish skill.promoted after writeToGlobalRegistry");
        assertTrue(payload.contains("\"replaced\":false"), "first promotion is a create: " + payload);

        // Global registry now contains the promoted skill.
        var globalWidget = globalSkillsDir.resolve("widget").resolve("SKILL.md");
        assertTrue(Files.exists(globalWidget),
                "promoted skill must land in global registry");
        assertTrue(Files.readString(globalWidget).contains("name: widget"));

        // syncRegistryToolRows wrote one row per command from the SKILL.md frontmatter
        var rows = SkillRegistryTool.findBySkill("widget").stream()
                .map(r -> r.toolName).sorted().toList();
        assertEquals(java.util.List.of("echo", "ls"), rows,
                "syncRegistryToolRows must mirror the commands frontmatter");

        // Cleanup so SkillRegistryTool.findBySkill("widget") doesn't leak into other tests
        SkillRegistryTool.deleteBySkill("widget");
    }

    /**
     * Same flow as above but on a re-promotion: global already has the skill,
     * workspace is at a higher version. Covers the {@code replacing=true} branch
     * of writeToGlobalRegistry / atomicSwap.
     */
    @Test
    void promoteReplacesExistingGlobalSkill() throws Exception {
        var agent = createAgent("promote-e2e-replace");
        installSkillCreatorWorkspace(agent);
        SkillLoader.clearCache();

        // Pre-existing global skill at 1.0.0
        var globalWidget = globalSkillsDir.resolve("widget");
        writeSkillMd(globalWidget, "widget", "1.0.0");

        // Workspace at 2.0.0 — newer, no downgrade, hashes differ
        var workspaceWidget = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceWidget, "widget", "2.0.0", "echo");

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceWidget, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload, "publishes skill.promoted on replacement");
        assertTrue(payload.contains("\"replaced\":true"), "second promotion replaces: " + payload);

        // New version landed in global
        assertTrue(Files.readString(globalWidget.resolve("SKILL.md")).contains("version: 2.0.0"),
                "global must carry the new version after replace");

        // Cleanup
        SkillRegistryTool.deleteBySkill("widget");
    }

    /**
     * promoteInBackground with a credentials/ file in the workspace exercises
     * the stripCredentialsJson branch (lines 297-301 of SkillPromotionService).
     * The promoted global copy must have the credentials value redacted but
     * keep the JSON key.
     */
    @Test
    void promoteStripsCredentialsBeforeGlobalRegistry() throws Exception {
        var agent = createAgent("promote-e2e-credentials");
        installSkillCreatorWorkspace(agent);
        SkillLoader.clearCache();

        var workspaceWidget = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceWidget, "widget", "1.0.0");
        var credsDir = workspaceWidget.resolve("credentials");
        Files.createDirectories(credsDir);
        Files.writeString(credsDir.resolve("config.json"),
                "{\"apiKey\": \"sk-leak-me\", \"baseUrl\": \"https://example.com\"}");

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceWidget, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload);

        var promotedConfig = Files.readString(
                globalSkillsDir.resolve("widget").resolve("credentials").resolve("config.json"));
        assertFalse(promotedConfig.contains("sk-leak-me"),
                "credentials value must be stripped: " + promotedConfig);
        assertTrue(promotedConfig.contains("apiKey"),
                "credentials key preserved: " + promotedConfig);
        assertTrue(promotedConfig.contains("[CREDENTIAL]"),
                "stripped values replaced with placeholder: " + promotedConfig);

        SkillRegistryTool.deleteBySkill("widget");
    }

    /**
     * Binary files in the workspace get relocated under tools/ in the global
     * copy (lines 285-294). Workspace has a stray .png at the root; the
     * promoted global registry should carry it under tools/.
     */
    @Test
    void promoteRelocatesBinaryFilesUnderTools() throws Exception {
        var agent = createAgent("promote-e2e-binary");
        installSkillCreatorWorkspace(agent);
        SkillLoader.clearCache();

        var workspaceWidget = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceWidget, "widget", "1.0.0");
        // 1x1 PNG (a tiny but plausible binary) at the root of the workspace skill
        var png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        Files.write(workspaceWidget.resolve("icon.png"), png);

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceWidget, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload);

        assertTrue(Files.exists(globalSkillsDir.resolve("widget").resolve("tools").resolve("icon.png")),
                "binary at root must be relocated under tools/");
        assertFalse(Files.exists(globalSkillsDir.resolve("widget").resolve("icon.png")),
                "binary must NOT remain at the root of the promoted skill");

        SkillRegistryTool.deleteBySkill("widget");
    }

    /**
     * End-to-end happy path but with a main agent present whose
     * {@code modelProvider} isn't registered (no API key). Covers the
     * second {@code sanitizeWithLlm} skip branch (lines 597-599) where
     * {@code provider == null} after the main-agent fallback. The downstream
     * writeToGlobalRegistry still runs, so the promotion succeeds.
     */
    @Test
    void promoteSkipsLlmWhenMainAgentProviderUnregistered() throws Exception {
        // Main agent exists, but provider isn't in the registry — sanitize
        // pipeline falls through and inputs pass unchanged.
        AgentService.create(Agent.MAIN_AGENT_NAME, "no-such-provider-xyz", "no-such-model");
        seededAgentNames.add(Agent.MAIN_AGENT_NAME);
        llm.ProviderRegistry.refresh();

        var agent = createAgent("promote-e2e-main-no-provider");
        installSkillCreatorWorkspace(agent);
        SkillLoader.clearCache();

        var workspaceSkillDir = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceSkillDir, "widget", "1.0.0");

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceSkillDir, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload, "promotion still succeeds when sanitize is skipped");
        assertTrue(Files.exists(globalSkillsDir.resolve("widget").resolve("SKILL.md")));

        SkillRegistryTool.deleteBySkill("widget");
    }

    @Test
    void buildBatchesBySizeFitsEverythingInOneBatchWhenUnderLimit() throws Exception {
        var input = new java.util.LinkedHashMap<String, String>();
        input.put("a.md", "short");      // 5 chars × 2 = 10 bytes (estimate)
        input.put("b.md", "also short"); // 10 chars × 2 = 20 bytes
        var result = invokeBuildBatchesBySize(input, 1000);
        assertEquals(1, result.size(), "everything fits in one batch");
        assertEquals(2, result.get(0).size());
    }

    @Test
    void buildBatchesBySizeSplitsAcrossBatchesWhenOverLimit() throws Exception {
        // Each entry is ~200 bytes (100 chars × 2). maxBatchBytes=250 forces
        // each entry into its own batch — accumulation > 250 between entries.
        var input = new java.util.LinkedHashMap<String, String>();
        input.put("a.md", "x".repeat(100));
        input.put("b.md", "y".repeat(100));
        input.put("c.md", "z".repeat(100));
        var result = invokeBuildBatchesBySize(input, 250);
        // First entry alone (200B) fits, second triggers a new batch (400B
        // would exceed 250), etc.
        assertTrue(result.size() >= 2,
                "size cap must split into multiple batches: got " + result.size() + " batches");
    }

    @Test
    void buildBatchesBySizeSendsAlwaysAlongWhenSingleEntryExceedsLimit() throws Exception {
        // An entry larger than maxBatchBytes is sent alone (the controller
        // can't split inside a single file). currentBatch is initially
        // empty, so the "isEmpty + over limit" guard doesn't trigger — the
        // entry just lands in the first batch by itself.
        var input = new java.util.LinkedHashMap<String, String>();
        input.put("huge.md", "x".repeat(5000)); // 10_000 bytes
        var result = invokeBuildBatchesBySize(input, 100); // limit way smaller
        assertEquals(1, result.size(), "single oversize entry yields one batch");
        assertEquals(1, result.get(0).size());
    }

    @Test
    void buildBatchesBySizeReturnsEmptyForEmptyInput() throws Exception {
        var input = new java.util.LinkedHashMap<String, String>();
        var result = invokeBuildBatchesBySize(input, 1000);
        assertTrue(result.isEmpty(), "empty input → no batches");
    }

    @SuppressWarnings("unchecked")
    private java.util.List<java.util.List<java.util.Map.Entry<String, String>>>
            invokeBuildBatchesBySize(java.util.LinkedHashMap<String, String> input, int max)
            throws Exception {
        var m = SkillPromotionService.class.getDeclaredMethod(
                "buildBatchesBySize", java.util.LinkedHashMap.class, int.class);
        m.setAccessible(true);
        return (java.util.List<java.util.List<java.util.Map.Entry<String, String>>>)
                m.invoke(null, input, max);
    }

    @Test
    void validateToolRequirementsFailsWhenAgentDisabledTheRequiredTool() throws Exception {
        // The skill declares a real, registered tool (filesystem), but the
        // agent has explicitly disabled it via AgentToolConfig. Exercises the
        // "disabled" arm of validateToolRequirements that the existing
        // unknown-tool test doesn't reach.
        var agent = createAgent("vtool-disabled");
        var dir = globalSkillsDir.resolve("needs-disabled");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: needs-disabled
                description: depends on a tool the agent has disabled
                version: 1.0.0
                tools: [filesystem]
                ---
                # body
                """);
        agents.SkillLoader.clearCache();

        services.Tx.run(() -> {
            var cfg = new models.AgentToolConfig();
            cfg.agent = agent;
            cfg.toolName = "filesystem";
            cfg.enabled = false;
            cfg.save();
            return null;
        });

        var result = SkillPromotionService.validateToolRequirements(agent, "needs-disabled");
        assertFalse(result.ok(),
                "disabled-tool dependency must fail validation");
        assertTrue(result.message().contains("disabled"),
                "error message must call out the disabled list: " + result.message());
    }

    // ==================== version stamping (write path) ====================

    @Test
    void stampSkillVersionInjects1_0_0ForNewSkill() {
        // The reported bug: a conformed/imported SKILL.md omits version:, so the
        // write path must stamp INITIAL 1.0.0 — otherwise it reads back as 0.0.0.
        var payload = new LinkedHashMap<String, String>();
        payload.put("SKILL.md", "---\nname: humanizer\ndescription: d\n---\n# Body");
        var target = globalSkillsDir.resolve("humanizer"); // no prior SKILL.md

        SkillPromotionService.stampSkillVersion(payload, target);

        assertTrue(payload.get("SKILL.md").contains("version: 1.0.0"),
                "new skill without version: must be stamped 1.0.0, got:\n" + payload.get("SKILL.md"));
    }

    @Test
    void stampSkillVersionPatchBumpsOnMaterialRepublish() throws Exception {
        var target = globalSkillsDir.resolve("humanizer");
        writeSkillMd(target, "humanizer", "1.4.2"); // existing global skill at 1.4.2

        var payload = new LinkedHashMap<String, String>();
        payload.put("SKILL.md", "---\nname: humanizer\ndescription: changed\n---\n# New body");
        SkillPromotionService.stampSkillVersion(payload, target);

        assertTrue(payload.get("SKILL.md").contains("version: 1.4.3"),
                "material re-publish must auto patch-bump 1.4.2 -> 1.4.3, got:\n" + payload.get("SKILL.md"));
    }

    @Test
    void stampSkillVersionNoopWhenNoSkillMd() {
        var payload = new LinkedHashMap<String, String>();
        payload.put("notes.md", "no frontmatter here");

        SkillPromotionService.stampSkillVersion(payload, globalSkillsDir.resolve("x"));

        assertFalse(payload.containsKey("SKILL.md"), "must not fabricate a SKILL.md");
        assertEquals("no frontmatter here", payload.get("notes.md"), "other files untouched");
    }

}
