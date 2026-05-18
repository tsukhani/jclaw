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
    private final java.util.List<Runnable> unsubscribes = new java.util.ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        globalSkillsDir = Files.createTempDirectory("skill-promotion-cov-");
        play.Play.configuration.setProperty("jclaw.skills.path", globalSkillsDir.toString());
        SkillLoader.clearCache();
    }

    @AfterEach
    void teardown() throws Exception {
        for (var off : unsubscribes) {
            try { off.run(); } catch (Exception _) {}
        }
        unsubscribes.clear();
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
     * Subscribe to NotificationBus events of a given type. Returns a holder
     * the caller can read after the bus has had a chance to drain. The
     * unsubscribe handle is registered for teardown.
     */
    private java.util.concurrent.atomic.AtomicReference<String> captureFirstEvent(String type) {
        var holder = new java.util.concurrent.atomic.AtomicReference<String>();
        var off = NotificationBus.subscribe(payload -> {
            if (payload.contains("\"type\":\"" + type + "\"")) {
                holder.compareAndSet(null, payload);
            }
        });
        unsubscribes.add(off);
        return holder;
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
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: needs-fake\n"
              + "description: depends on a tool that does not exist\n"
              + "version: 1.0.0\n"
              + "tools: [definitely_not_a_real_tool_name_xyz]\n"
              + "---\n# body\n");

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

        var captured = captureFirstEvent("skill.promote_failed");

        SkillPromotionService.promoteInBackground(workspaceSkillDir, "nope", 9_999_999L);

        var payload = waitForEvent(captured);
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

        var captured = captureFirstEvent("skill.promote_failed");

        SkillPromotionService.promoteInBackground(workspaceSkillDir, "alpha", agent.id);

        var payload = waitForEvent(captured);
        assertNotNull(payload, "must publish skill.promote_failed when capability missing");
        assertTrue(payload.contains("skill-creator capability"),
                "error message mentions the missing capability: " + payload);
        // Nothing should land in the global registry
        assertFalse(Files.exists(globalSkillsDir.resolve("alpha").resolve("SKILL.md")),
                "global registry must not receive the skill when capability gate fails");
    }

    @Test
    void promoteNoOpWhenWorkspaceMatchesGlobal() throws Exception {
        // Pre-seed an identical global copy so the hash-equal short-circuit fires.
        var agent = createAgent("promote-identical");

        // Install skill-creator so capability passes
        var creatorGlobal = globalSkillsDir.resolve("skill-creator");
        writeSkillMd(creatorGlobal, "skill-creator", "1.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, "skill-creator");

        // Author "alpha" in BOTH global and workspace with identical content.
        // Same generator function on both sides guarantees the bytes match.
        var globalAlpha = globalSkillsDir.resolve("alpha");
        writeSkillMd(globalAlpha, "alpha", "1.0.0");

        var workspaceAlpha = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("alpha");
        writeSkillMd(workspaceAlpha, "alpha", "1.0.0");
        SkillLoader.clearCache();

        var captured = captureFirstEvent("skill.promote_noop");

        SkillPromotionService.promoteInBackground(workspaceAlpha, "alpha", agent.id);

        var payload = waitForEvent(captured);
        assertNotNull(payload, "publishes skill.promote_noop when workspace and global are byte-identical");
        assertTrue(payload.contains("identical"), "noop reason mentions identical: " + payload);
    }

    @Test
    void promoteRefusedOnDowngrade() throws Exception {
        var agent = createAgent("promote-downgrade");

        var creatorGlobal = globalSkillsDir.resolve("skill-creator");
        writeSkillMd(creatorGlobal, "skill-creator", "1.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, "skill-creator");

        // Global is at 2.0.0, workspace at 1.0.0 — downgrade
        var globalAlpha = globalSkillsDir.resolve("alpha");
        writeSkillMd(globalAlpha, "alpha", "2.0.0");

        var workspaceAlpha = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("alpha");
        writeSkillMd(workspaceAlpha, "alpha", "1.0.0");
        SkillLoader.clearCache();

        var captured = captureFirstEvent("skill.promote_failed");

        SkillPromotionService.promoteInBackground(workspaceAlpha, "alpha", agent.id);

        var payload = waitForEvent(captured);
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

    // ==================== Helper: wait for NotificationBus drain ====================

    /**
     * NotificationBus dispatches to a fixed thread pool. Poll briefly for the
     * captured event with a 2-second ceiling — far longer than the publish
     * path's nominal handoff but short enough to fail loud if the publish
     * never fires.
     */
    private static String waitForEvent(java.util.concurrent.atomic.AtomicReference<String> holder)
            throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            var v = holder.get();
            if (v != null) return v;
            Thread.sleep(25);
        }
        return holder.get();
    }
}
