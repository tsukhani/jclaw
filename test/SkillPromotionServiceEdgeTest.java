import agents.SkillLoader;
import models.Agent;
import models.AgentSkillConfig;
import models.AgentToolConfig;
import models.SkillRegistryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.KeptSkillFiles;
import services.NotificationBus;
import services.SkillPromotionService;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 *   <li>{@code copyToAgentWorkspace} replacing a copy — what the operator put in
 *       the old copy is kept outside the workspace, and nothing else (JF-352).</li>
 * </ul>
 *
 * <p>Permission-based tests use POSIX file permissions (chmod) and always
 * restore them in {@code finally} so teardown can clean the temp trees.
 */
class SkillPromotionServiceEdgeTest extends UnitTest {

    private Path globalSkillsDir;
    private final java.util.List<String> seededAgentNames = new java.util.ArrayList<>();
    private final java.util.List<Long> seededAgentIds = new java.util.ArrayList<>();

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
        for (var id : seededAgentIds) KeptSkillFiles.deleteForAgent(id);
        seededAgentIds.clear();
    }

    // ==================== Helpers ====================

    private Agent createAgent(String name) {
        seededAgentNames.add(name);
        var agent = AgentService.create(name, "openrouter", "gpt-4.1");
        seededAgentIds.add(agent.id);
        // The in-memory database numbers agents from 1 again in every run, and the kept root under tmp/ outlives it.
        try {
            KeptSkillFiles.deleteForAgent(agent.id);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return agent;
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

    // ==================== validateToolRequirements — declared tools ====================

    private void writeSkillDeclaringTool(String skill, String tool) throws Exception {
        var dir = globalSkillsDir.resolve(skill);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: declares a real tool
                version: 1.0.0
                tools: [%s]
                ---
                # body
                """.formatted(skill, tool));
        SkillLoader.clearCache();
    }

    @Test
    void validateToolRequirementsPassesWhenDeclaredToolsAllAvailable() throws Exception {
        // filesystem is on for a new agent, so the validation reaches the
        // ToolCatalog check and passes there, not on "no declaration".
        var agent = createAgent("vtool-happy");
        writeSkillDeclaringTool("needs-files", "filesystem");

        var result = SkillPromotionService.validateToolRequirements(agent, "needs-files");
        assertTrue(result.ok(), "declared, registered, enabled tool must validate: " + result.message());
        assertNull(result.message(), "success carries no message");
    }

    @Test
    void aSkillThatNeedsTheShellIsRefusedForAnAgentWithoutIt() throws Exception {
        // exec is opt-in for every agent but main (JF-288).
        var agent = createAgent("vtool-no-shell");
        writeSkillDeclaringTool("needs-exec", "exec");

        var result = SkillPromotionService.validateToolRequirements(agent, "needs-exec");
        assertFalse(result.ok(), "a custom agent has no shell until the operator switches it on");
        assertEquals("Cannot add skill 'needs-exec' to agent 'vtool-no-shell': missing tools — disabled: [exec]."
                + " Enable the required tools for this agent and try again.", result.message());
    }

    @Test
    void aSkillThatNeedsTheShellValidatesOnceTheAgentIsGrantedIt() throws Exception {
        var agent = createAgent("vtool-shell-granted");
        writeSkillDeclaringTool("needs-exec", "exec");
        Tx.run(() -> {
            var grant = new AgentToolConfig();
            grant.agent = agent;
            grant.toolName = "exec";
            grant.enabled = true;
            grant.save();
            return null;
        });

        var result = SkillPromotionService.validateToolRequirements(agent, "needs-exec");
        assertTrue(result.ok(), "the operator's grant must let the skill validate: " + result.message());
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

    // ==================== copyToAgentWorkspace — what an update keeps (JF-352) ====================

    private static Path agentCopy(Agent agent, String skill) {
        return AgentService.workspacePath(agent.name).resolve("skills").resolve(skill);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Every file kept for the skill, path to content. */
    private static Map<String, String> keptFiles(Agent agent, String skill) throws IOException {
        var out = new TreeMap<String, String>();
        for (var kept : KeptSkillFiles.list(agent, skill)) {
            out.put(kept.path(), Files.readString(KeptSkillFiles.find(agent, skill, kept.id())));
        }
        return out;
    }

    /** The regular files of a folder, relative and in order. */
    private static List<String> filesIn(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    /** Install v1 of {@code skill} on the agent, then publish v2 to the registry, as an update finds them. */
    private Agent agentWithOldCopy(String agentName, String skill) throws Exception {
        var agent = createAgent(agentName);
        writeSkillMd(globalSkillsDir.resolve(skill), skill, "1.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, skill);
        writeSkillMd(globalSkillsDir.resolve(skill), skill, "2.0.0");
        return agent;
    }

    @Test
    void anUpdateKeepsACopyOfTheKeyFileAndPutsTheFileBackInTheNewCopy() throws Exception {
        var agent = agentWithOldCopy("keep-key-file", "gamma");
        write(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt"), "sk-gamma-fake-jf352\n");

        SkillPromotionService.copyToAgentWorkspace(agent, "gamma");

        assertEquals(Map.of("credentials/gamma-api-key.txt", "sk-gamma-fake-jf352\n"), keptFiles(agent, "gamma"));
        assertEquals(List.of("SKILL.md", "credentials/gamma-api-key.txt"), filesIn(agentCopy(agent, "gamma")));
        assertEquals("sk-gamma-fake-jf352\n", Files.readString(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt")));
        assertTrue(Files.readString(agentCopy(agent, "gamma").resolve("SKILL.md")).contains("version: 2.0.0"));
        var kept = KeptSkillFiles.find(agent, "gamma", KeptSkillFiles.list(agent, "gamma").getFirst().id());
        assertTrue(kept.startsWith(KeptSkillFiles.root().resolve(String.valueOf(agent.id)).resolve("gamma")),
                "kept beneath the kept root, outside every workspace: " + kept);
        assertFalse(kept.startsWith(AgentService.workspaceRoot().toAbsolutePath()), kept.toString());
    }

    @Test
    void anEditedConfigBesideItsExampleIsKeptAndTheExampleIsNot() throws Exception {
        var agent = agentWithOldCopy("keep-config", "jira");
        write(globalSkillsDir.resolve("jira/config.env.example"), "JIRA_URL=\"https://jira.example.com\"\n");
        write(agentCopy(agent, "jira").resolve("config.env.example"), "JIRA_URL=\"https://jira.example.com\"\n");
        write(agentCopy(agent, "jira").resolve("config.env"), "JIRA_URL=\"https://jira.internal.example\"\n");

        SkillPromotionService.copyToAgentWorkspace(agent, "jira");

        assertEquals(Map.of("config.env", "JIRA_URL=\"https://jira.internal.example\"\n"), keptFiles(agent, "jira"));
        assertEquals(List.of("SKILL.md", "config.env", "config.env.example"), filesIn(agentCopy(agent, "jira")));
        assertEquals("JIRA_URL=\"https://jira.internal.example\"\n", Files.readString(agentCopy(agent, "jira").resolve("config.env")));
    }

    @Test
    void anEditedConfigIsKeptWhenOnlyTheNewVersionShipsItsExample() throws Exception {
        var agent = agentWithOldCopy("keep-new-example", "jira");
        write(globalSkillsDir.resolve("jira/config.env.example"), "JIRA_URL=\n");
        write(agentCopy(agent, "jira").resolve("config.env"), "JIRA_URL=\"https://jira.internal.example\"\n");

        SkillPromotionService.copyToAgentWorkspace(agent, "jira");

        assertEquals(Map.of("config.env", "JIRA_URL=\"https://jira.internal.example\"\n"), keptFiles(agent, "jira"));
        assertEquals(List.of("SKILL.md", "config.env", "config.env.example"), filesIn(agentCopy(agent, "jira")));
        assertEquals("JIRA_URL=\"https://jira.internal.example\"\n", Files.readString(agentCopy(agent, "jira").resolve("config.env")));
    }

    @Test
    void anUpdateOfACopyHoldingOnlyShippedFilesKeepsNothing() throws Exception {
        var agent = agentWithOldCopy("keep-nothing", "plain");
        write(globalSkillsDir.resolve("plain/credentials/README.md"), "Put your key here.\n");
        write(agentCopy(agent, "plain").resolve("credentials/README.md"), "Put your key here.\n");
        write(agentCopy(agent, "plain").resolve("notes.md"), "an agent's note\n");

        SkillPromotionService.copyToAgentWorkspace(agent, "plain");

        assertEquals(Map.of(), keptFiles(agent, "plain"));
        assertFalse(Files.exists(KeptSkillFiles.root().resolve(String.valueOf(agent.id))), "no folder is made for nothing");
    }

    @Test
    void aShippedFileIsKeptOnceItsBytesDifferFromTheNewVersion() throws Exception {
        var agent = agentWithOldCopy("keep-changed", "plain");
        write(globalSkillsDir.resolve("plain/credentials/README.md"), "Put your key here.\n");
        write(globalSkillsDir.resolve("plain/credentials/token.txt"), "replace me\n");
        write(agentCopy(agent, "plain").resolve("credentials/README.md"), "Put your key here.\n");
        write(agentCopy(agent, "plain").resolve("credentials/token.txt"), "fake-token-jf352\n");

        assertEquals(List.of("credentials/token.txt"),
                KeptSkillFiles.select(agentCopy(agent, "plain"), globalSkillsDir.resolve("plain")));
        SkillPromotionService.copyToAgentWorkspace(agent, "plain");

        assertEquals(Map.of("credentials/token.txt", "fake-token-jf352\n"), keptFiles(agent, "plain"));
        assertEquals("replace me\n", Files.readString(agentCopy(agent, "plain").resolve("credentials/token.txt")));
    }

    @Test
    void aFailedSwapLeavesTheOldCopyInPlaceAndRemovesWhatWasKept() throws Exception {
        var agent = agentWithOldCopy("keep-swap-fails", "gamma");
        write(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt"), "sk-gamma-fake-jf352\n");
        var locked = globalSkillsDir.resolve("gamma/locked.md");
        Files.writeString(locked, "cannot read me");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Assertions.assertThrows(IOException.class, () -> SkillPromotionService.copyToAgentWorkspace(agent, "gamma"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
        }

        assertEquals("sk-gamma-fake-jf352\n",
                Files.readString(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt")));
        assertTrue(Files.readString(agentCopy(agent, "gamma").resolve("SKILL.md")).contains("version: 1.0.0"));
        assertEquals(Map.of(), keptFiles(agent, "gamma"));
        assertFalse(Files.exists(KeptSkillFiles.root().resolve(String.valueOf(agent.id))));
    }

    @Test
    void aLinkIsNeverKeptOrFollowed() throws Exception {
        var agent = agentWithOldCopy("keep-links", "gamma");
        var outside = Files.createTempDirectory("jf352-outside-");
        try {
            Files.writeString(outside.resolve("secret.txt"), "not the skill's\n");
            var copy = agentCopy(agent, "gamma");
            write(copy.resolve("credentials/real.txt"), "fake-real-jf352\n");
            Files.createSymbolicLink(copy.resolve("credentials/key"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(copy.resolve("credentials/folder"), outside);
            Files.writeString(copy.resolve("config.env.example"), "X=1\n");
            Files.createSymbolicLink(copy.resolve("config.env"), outside.resolve("secret.txt"));

            assertEquals(List.of("credentials/real.txt"), KeptSkillFiles.select(copy, globalSkillsDir.resolve("gamma")));
            SkillPromotionService.copyToAgentWorkspace(agent, "gamma");

            assertEquals(Map.of("credentials/real.txt", "fake-real-jf352\n"), keptFiles(agent, "gamma"));
            assertEquals("not the skill's\n", Files.readString(outside.resolve("secret.txt")),
                    "replacing the copy deletes a link, never what it leads to");
        } finally {
            SkillPromotionService.deleteRecursive(outside);
        }
    }

    @Test
    void aCopyWhoseCredentialsFolderIsALinkKeepsNothing() throws Exception {
        var agent = agentWithOldCopy("keep-linked-folder", "gamma");
        var outside = Files.createTempDirectory("jf352-outside-");
        try {
            Files.writeString(outside.resolve("gamma-api-key.txt"), "not the skill's\n");
            Files.createSymbolicLink(agentCopy(agent, "gamma").resolve("credentials"), outside);

            SkillPromotionService.copyToAgentWorkspace(agent, "gamma");

            assertEquals(Map.of(), keptFiles(agent, "gamma"));
            assertEquals("not the skill's\n", Files.readString(outside.resolve("gamma-api-key.txt")));
        } finally {
            SkillPromotionService.deleteRecursive(outside);
        }
    }

    @Test
    void aSecondUpdateKeepsItsFilesBesideTheFirstUpdatesAndListsTheNewestFirst() throws Exception {
        var agent = agentWithOldCopy("keep-twice", "gamma");
        write(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt"), "first\n");
        SkillPromotionService.copyToAgentWorkspace(agent, "gamma");
        write(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt"), "second\n");
        writeSkillMd(globalSkillsDir.resolve("gamma"), "gamma", "3.0.0");

        SkillPromotionService.copyToAgentWorkspace(agent, "gamma");

        var listed = KeptSkillFiles.list(agent, "gamma");
        assertEquals(2, listed.size());
        assertEquals("second\n", Files.readString(KeptSkillFiles.find(agent, "gamma", listed.get(0).id())));
        assertEquals("first\n", Files.readString(KeptSkillFiles.find(agent, "gamma", listed.get(1).id())));
        assertFalse(listed.get(0).keptAt().isBefore(listed.get(1).keptAt()));
    }

    @Test
    void aCredentialsFolderInAnotherLetterCaseIsKept() throws Exception {
        var agent = agentWithOldCopy("keep-case", "gamma");
        write(agentCopy(agent, "gamma").resolve("Credentials/gamma-api-key.txt"), "sk-gamma-fake-jf352\n");

        SkillPromotionService.copyToAgentWorkspace(agent, "gamma");

        assertEquals(Map.of("Credentials/gamma-api-key.txt", "sk-gamma-fake-jf352\n"), keptFiles(agent, "gamma"));
        assertEquals(List.of("Credentials/gamma-api-key.txt", "SKILL.md"), filesIn(agentCopy(agent, "gamma")));
    }

    @Test
    void anUpdatesKeptFolderIsOpenOnlyToTheAccountJClawRunsAs() throws Exception {
        var agent = agentWithOldCopy("keep-mode", "gamma");
        write(agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt"), "sk-gamma-fake-jf352\n");

        SkillPromotionService.copyToAgentWorkspace(agent, "gamma");

        List<Path> folders;
        try (var entries = Files.list(KeptSkillFiles.root().resolve(String.valueOf(agent.id)).resolve("gamma"))) {
            folders = entries.toList();
        }
        assertEquals(1, folders.size(), folders.toString());
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folders.getFirst())));
    }

    @Test
    void anUnreadableFileInTheOldCopyRefusesTheUpdateKeepsNothingAndLeavesTheOldCopyWhole() throws Exception {
        var agent = agentWithOldCopy("keep-unreadable", "gamma");
        // Sorts ahead of the unreadable one, so it is copied out before the failure and must be removed with it.
        write(agentCopy(agent, "gamma").resolve("credentials/a-readable.txt"), "fake-readable-jf352\n");
        var key = agentCopy(agent, "gamma").resolve("credentials/gamma-api-key.txt");
        write(key, "sk-gamma-fake-jf352\n");
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("---------"));
        try {
            Assertions.assertThrows(java.nio.file.AccessDeniedException.class,
                    () -> SkillPromotionService.copyToAgentWorkspace(agent, "gamma"));
        } finally {
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        }

        assertEquals(List.of("SKILL.md", "credentials/a-readable.txt", "credentials/gamma-api-key.txt"),
                filesIn(agentCopy(agent, "gamma")));
        assertTrue(Files.readString(agentCopy(agent, "gamma").resolve("SKILL.md")).contains("version: 1.0.0"));
        assertEquals("sk-gamma-fake-jf352\n", Files.readString(key));
        assertEquals(Map.of(), keptFiles(agent, "gamma"));
        assertFalse(Files.exists(KeptSkillFiles.root().resolve(String.valueOf(agent.id))), "nothing is left half-kept");
    }
}
