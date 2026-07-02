import agents.SkillLoader;
import models.Agent;
import models.AgentSkillConfig;
import models.SkillRegistryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.NotificationBus;
import services.SkillPromotionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Edge coverage for {@code SkillPromotionService} complementing
 * {@code SkillPromotionServiceTest} (primitive helpers) and
 * {@code SkillPromotionServiceCoverageTest} (gates + e2e happy paths):
 * <ul>
 *   <li>{@code validateToolRequirements} — declared tools all available (the
 *       happy declared-tools pass, distinct from the no-declaration pass).</li>
 *   <li>{@code hasSkillCreatorCapability} — all four decision arms.</li>
 *   <li>{@code copyToAgentWorkspace} — unreadable source file surfaces the
 *       underlying I/O cause and cleans the staging directory.</li>
 *   <li>{@code promoteInBackground} — identical-to-global noop through the
 *       full pipeline entrypoint; unwritable global registry publishes
 *       {@code skill.promote_failed}.</li>
 *   <li>Structure enforcement — binaries already under {@code tools/} stay
 *       put; empty {@code credentials/}/{@code tools/} convention dirs are
 *       pruned from the published copy.</li>
 *   <li>{@code deleteRecursive} — tolerates undeletable entries.</li>
 * </ul>
 *
 * <p>Permission-based tests use POSIX file permissions (chmod) and always
 * restore them in {@code finally} so teardown can clean the temp trees.
 */
class SkillPromotionServiceEdgeTest extends UnitTest {

    private Path globalSkillsDir;
    private final java.util.List<String> seededAgentNames = new java.util.ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        globalSkillsDir = Files.createTempDirectory("skill-promotion-edge-");
        play.Play.configuration.setProperty("jclaw.skills.path", globalSkillsDir.toString());
        SkillLoader.clearCache();
    }

    @AfterEach
    void teardown() throws Exception {
        if (globalSkillsDir != null && Files.exists(globalSkillsDir)) {
            // Restore write permission in case a chmod test failed mid-way.
            Files.setPosixFilePermissions(globalSkillsDir, PosixFilePermissions.fromString("rwxr-xr-x"));
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

    private static void writeSkillMd(Path dir, String name, String version) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: " + name + "\n"
              + "description: test skill\n"
              + "version: " + version + "\n"
              + "---\n"
              + "# " + name + "\n");
    }

    private void installSkillCreatorWorkspace(Agent agent) throws Exception {
        var dir = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve(SkillPromotionService.SKILL_CREATOR_NAME);
        writeSkillMd(dir, SkillPromotionService.SKILL_CREATOR_NAME, "1.0.0");
    }

    /** See SkillPromotionServiceCoverageTest#waitForEvent — latch-based event wait. */
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
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) return null;
            return holder.get();
        } finally {
            off.run();
        }
    }

    // ==================== validateToolRequirements — declared-tools pass ====================

    @Test
    void validateToolRequirementsPassesWhenDeclaredToolsAllAvailable() throws Exception {
        // The skill DECLARES a tool ('exec' — always registered) and the agent
        // hasn't disabled it: the validation must reach the ToolCatalog check
        // and come back ok, not just short-circuit on "no declaration".
        var agent = createAgent("vtool-happy");
        var dir = globalSkillsDir.resolve("needs-exec");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: needs-exec
                description: declares a real tool
                version: 1.0.0
                tools: [exec]
                ---
                # body
                """);
        SkillLoader.clearCache();

        var result = SkillPromotionService.validateToolRequirements(agent, "needs-exec");
        assertTrue(result.ok(), "declared, registered, enabled tool must validate: " + result.message());
        assertNull(result.message(), "success carries no message");
    }

    // ==================== hasSkillCreatorCapability ====================

    @Test
    void hasSkillCreatorCapabilityFalseForNullAgent() {
        assertFalse(SkillPromotionService.hasSkillCreatorCapability(null));
    }

    @Test
    void hasSkillCreatorCapabilityFalseWhenNotInstalledInWorkspace() {
        var agent = createAgent("cap-not-installed");
        assertFalse(SkillPromotionService.hasSkillCreatorCapability(agent),
                "no skill-creator SKILL.md in the workspace means no capability");
    }

    @Test
    void hasSkillCreatorCapabilityTrueWhenInstalledWithoutConfigRow() throws Exception {
        // Absence of an AgentSkillConfig row is "enabled by default".
        var agent = createAgent("cap-installed-default");
        installSkillCreatorWorkspace(agent);
        assertTrue(SkillPromotionService.hasSkillCreatorCapability(agent),
                "installed skill with no config row is enabled by default");
    }

    @Test
    void hasSkillCreatorCapabilityFalseWhenExplicitlyDisabled() throws Exception {
        var agent = createAgent("cap-installed-disabled");
        installSkillCreatorWorkspace(agent);

        var cfg = new AgentSkillConfig();
        cfg.agent = agent;
        cfg.skillName = SkillPromotionService.SKILL_CREATOR_NAME;
        cfg.enabled = false;
        cfg.save();

        assertFalse(SkillPromotionService.hasSkillCreatorCapability(agent),
                "an explicit enabled=false config row must revoke the capability");
    }

    // ==================== copyToAgentWorkspace — I/O failure path ====================

    @Test
    void copyToAgentWorkspaceSurfacesUnreadableSourceAndCleansStaging() throws Exception {
        var agent = createAgent("copy-unreadable");
        var skillDir = globalSkillsDir.resolve("broken");
        writeSkillMd(skillDir, "broken", "1.0.0");
        var locked = skillDir.resolve("locked.md");
        Files.writeString(locked, "cannot read me");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));

        try {
            var ex = Assertions.assertThrows(IOException.class,
                    () -> SkillPromotionService.copyToAgentWorkspace(agent, "broken"),
                    "an unreadable source file must fail the copy");
            assertTrue(ex.toString().contains("locked.md"),
                    "the surfaced I/O error must name the unreadable file: " + ex);

            var agentSkillsDir = AgentService.workspacePath(agent.name).resolve("skills");
            assertFalse(Files.exists(agentSkillsDir.resolve("broken").resolve("SKILL.md")),
                    "no partial skill may land in the workspace");
            if (Files.isDirectory(agentSkillsDir)) {
                try (var entries = Files.list(agentSkillsDir)) {
                    assertTrue(entries.noneMatch(p -> p.getFileName().toString().contains(".copying-")),
                            "the staging directory must be cleaned up after the failure");
                }
            }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    // ==================== promoteInBackground — noop through the full pipeline ====================

    @Test
    void promoteInBackgroundNoopsWhenWorkspaceIdenticalToGlobal() throws Exception {
        // Unlike the direct isIdenticalToGlobal predicate test, this drives the
        // FULL promoteInBackground entrypoint: capability gate passes, then the
        // hash-noop check short-circuits before publishToGlobal.
        var agent = createAgent("promote-noop-e2e");
        installSkillCreatorWorkspace(agent);

        var globalAlpha = globalSkillsDir.resolve("alpha");
        writeSkillMd(globalAlpha, "alpha", "1.0.0");
        var workspaceAlpha = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("alpha");
        writeSkillMd(workspaceAlpha, "alpha", "1.0.0");
        SkillLoader.clearCache();

        var globalBefore = Files.readString(globalAlpha.resolve("SKILL.md"));

        var payload = waitForEvent("skill.promote_noop",
                () -> SkillPromotionService.promoteInBackground(workspaceAlpha, "alpha", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload, "identical copies must publish skill.promote_noop via promoteInBackground");

        assertEquals(globalBefore, Files.readString(globalAlpha.resolve("SKILL.md")),
                "a noop promotion must not rewrite the global copy");
        assertEquals(0, SkillRegistryTool.findBySkill("alpha").size(),
                "a noop promotion must not touch registry tool rows");
    }

    // ==================== promote — unwritable global registry ====================

    @Test
    void promotePublishesFailureWhenGlobalRegistryUnwritable() throws Exception {
        var agent = createAgent("promote-readonly-global");
        installSkillCreatorWorkspace(agent);

        var workspaceWidget = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceWidget, "widget", "1.0.0");
        SkillLoader.clearCache();

        // Read-only registry root: staging dir creation inside it must fail.
        Files.setPosixFilePermissions(globalSkillsDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var payload = waitForEvent("skill.promote_failed",
                    () -> SkillPromotionService.promoteInBackground(workspaceWidget, "widget", agent.id),
                    Duration.ofSeconds(5));
            assertNotNull(payload,
                    "an unwritable global registry must publish skill.promote_failed");
        } finally {
            Files.setPosixFilePermissions(globalSkillsDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertFalse(Files.exists(globalSkillsDir.resolve("widget")),
                "nothing may land in the registry when the write fails");
    }

    // ==================== structure enforcement on publish ====================

    @Test
    void promoteKeepsBinariesAlreadyUnderTools() throws Exception {
        var agent = createAgent("promote-tools-in-place");
        installSkillCreatorWorkspace(agent);

        var workspaceWidget = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceWidget, "widget", "1.0.0");
        var toolsDir = workspaceWidget.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.write(toolsDir.resolve("helper.bin"), new byte[]{1, 2, 3, 4});
        SkillLoader.clearCache();

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceWidget, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload, "promotion with a conforming tools/ binary must succeed");

        var globalWidget = globalSkillsDir.resolve("widget");
        assertTrue(Files.exists(globalWidget.resolve("tools").resolve("helper.bin")),
                "a binary already under tools/ stays at tools/");
        assertFalse(Files.exists(globalWidget.resolve("tools").resolve("tools")),
                "no double-nesting of the tools/ prefix");

        SkillRegistryTool.deleteBySkill("widget");
    }

    @Test
    void promotePrunesEmptyConventionDirsFromPublishedCopy() throws Exception {
        // The publish path pre-creates credentials/ and tools/ in staging; when
        // the skill ships neither, both must be pruned from the final copy.
        var agent = createAgent("promote-prune-empty");
        installSkillCreatorWorkspace(agent);

        var workspaceWidget = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("widget");
        writeSkillMd(workspaceWidget, "widget", "1.0.0");
        SkillLoader.clearCache();

        var payload = waitForEvent("skill.promoted",
                () -> SkillPromotionService.promoteInBackground(workspaceWidget, "widget", agent.id),
                Duration.ofSeconds(5));
        assertNotNull(payload);

        var globalWidget = globalSkillsDir.resolve("widget");
        assertTrue(Files.exists(globalWidget.resolve("SKILL.md")), "skill published");
        assertFalse(Files.exists(globalWidget.resolve("credentials")),
                "empty credentials/ must be pruned");
        assertFalse(Files.exists(globalWidget.resolve("tools")),
                "empty tools/ must be pruned");

        SkillRegistryTool.deleteBySkill("widget");
    }

    // ==================== deleteRecursive — undeletable entries ====================

    @Test
    void deleteRecursiveToleratesUndeletableEntries() throws Exception {
        var dir = Files.createTempDirectory("delete-locked-");
        var child = dir.resolve("pinned.txt");
        Files.writeString(child, "pinned");
        // Read-only parent: deleting the child (and then the non-empty parent) fails.
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assertions.assertDoesNotThrow(() -> SkillPromotionService.deleteRecursive(dir),
                    "per-entry delete failures must be swallowed, not thrown");
            assertTrue(Files.exists(child), "the undeletable entry survives");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            SkillPromotionService.deleteRecursive(dir);
        }
    }
}
