import agents.SkillLoader;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.AtomicDirSwap;
import services.EventLogger;
import services.KeptSkillFiles;
import services.SkillPromotionService;
import services.WorkspaceFiles;
import tools.HarnessSandbox;
import utils.AppClock;
import utils.SandboxHiddenRoots;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-406: an agent's new copy of a skill is built where no agent's sandbox can write, then renamed into its
 * {@code skills/} folder. Before, it was built in that folder under {@code <skill>.copying-<epoch ms>}, beside the old
 * copy's {@code <skill>.replacing-<epoch ms>} backup, and a link planted at either name was followed.
 */
class SkillCopyStagingTest extends UnitTest {

    /** The millisecond the clock is frozen at, so the names the copy used to take are known in advance. */
    private static final long STAMP = 1_800_000_000_000L;
    private static final Clock FROZEN = Clock.fixed(Instant.ofEpochMilli(STAMP), ZoneOffset.UTC);

    private Path globalSkillsDir;
    private Path outside;
    private final List<String> agentNames = new ArrayList<>();
    private final List<Long> agentIds = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        Fixtures.deleteDatabase();
        globalSkillsDir = Files.createTempDirectory("jf406-registry-");
        outside = Files.createTempDirectory("jf406-outside-");
        SkillsPathSync.point(globalSkillsDir);
        SkillLoader.clearCache();
    }

    @AfterEach
    void teardown() throws IOException {
        SkillsPathSync.restore();
        SkillLoader.clearCache();
        SkillPromotionService.deleteRecursive(globalSkillsDir);
        SkillPromotionService.deleteRecursive(outside);
        for (var name : agentNames) SkillPromotionService.deleteRecursive(AgentService.workspacePath(name));
        for (var id : agentIds) KeptSkillFiles.deleteForAgent(id);
    }

    private Agent createAgent(String name) throws IOException {
        agentNames.add(name);
        var agent = AgentService.create(name, "openrouter", "gpt-4.1");
        agentIds.add(agent.id);
        // The in-memory database numbers agents from 1 again in every run, and the kept root under tmp/ outlives it.
        KeptSkillFiles.deleteForAgent(agent.id);
        return agent;
    }

    private void publish(String skill, String version) throws IOException {
        write(globalSkillsDir.resolve(skill).resolve("SKILL.md"),
                "---\nname: " + skill + "\ndescription: test skill\nversion: " + version + "\n---\n# " + skill + "\n");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Path skillsOf(Agent agent) {
        return AgentService.workspacePath(agent.name).resolve("skills");
    }

    /** The entries of a folder, by name and in order, never through a link. */
    private static List<String> entriesOf(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Everything under a folder, files and folders alike, relative and in order. */
    private static List<String> treeOf(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> !p.equals(dir)).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    /** The regular files of a folder, relative and in order. */
    private static List<String> filesIn(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    /** What the staging area holds for {@code skill}: a copy's folder is named for its skill. */
    private static List<String> stagedFor(String skill) throws IOException {
        return entriesOf(WorkspaceFiles.skillStagingRoot()).stream().filter(n -> n.startsWith(skill + "-")).toList();
    }

    private static void copyAtTheFrozenMillisecond(Agent agent, String skill) throws IOException {
        AppClock.callWith(FROZEN, () -> {
            SkillPromotionService.copyToAgentWorkspace(agent, skill);
            return null;
        });
    }

    @Test
    void aLinkPlantedUnderTheNameTheStagingCopyTookIsNeverFollowedAndTheAddLands() throws Exception {
        var agent = createAgent("jf406-staging-link");
        publish("alpha", "1.0.0");
        write(globalSkillsDir.resolve("alpha/tools/run.sh"), "echo alpha\n");
        Files.createDirectories(skillsOf(agent));
        var planted = Files.createSymbolicLink(skillsOf(agent).resolve("alpha.copying-" + STAMP), outside);

        copyAtTheFrozenMillisecond(agent, "alpha");

        assertEquals(List.of(), treeOf(outside), "nothing is written through the link");
        assertEquals(outside, Files.readSymbolicLink(planted), "the link is left as it was");
        var copy = skillsOf(agent).resolve("alpha");
        assertFalse(Files.isSymbolicLink(copy), "the copy is a folder of its own, never the planted link moved into place");
        assertEquals(List.of("SKILL.md", "tools/run.sh"), filesIn(copy));
        assertEquals(Files.readString(globalSkillsDir.resolve("alpha/SKILL.md")), Files.readString(copy.resolve("SKILL.md")));
        assertEquals(List.of("alpha", "alpha.copying-" + STAMP), entriesOf(skillsOf(agent)));
        assertEquals(List.of(), stagedFor("alpha"), "the staging area is empty once the copy is in place");
    }

    @Test
    void aLinkPlantedUnderTheNameTheBackupTookIsNeverFollowedAndTheUpdateCarriesTheOldFilesOver() throws Exception {
        var agent = createAgent("jf406-backup-link");
        publish("beta", "1.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, "beta");
        publish("beta", "2.0.0");
        write(skillsOf(agent).resolve("beta/credentials/key.txt"), "fake-jf406-key\n");
        write(outside.resolve("sentinel.txt"), "the operator's\n");
        var planted = Files.createSymbolicLink(skillsOf(agent).resolve("beta.replacing-" + STAMP), outside);

        copyAtTheFrozenMillisecond(agent, "beta");

        assertEquals(List.of("sentinel.txt"), treeOf(outside), "nothing is written through the link");
        assertEquals("the operator's\n", Files.readString(outside.resolve("sentinel.txt")));
        assertEquals(outside, Files.readSymbolicLink(planted), "the link is left as it was");
        var copy = skillsOf(agent).resolve("beta");
        assertEquals(List.of("SKILL.md", "credentials/key.txt"), filesIn(copy));
        assertEquals(Files.readString(globalSkillsDir.resolve("beta/SKILL.md")), Files.readString(copy.resolve("SKILL.md")));
        assertEquals("fake-jf406-key\n", Files.readString(copy.resolve("credentials/key.txt")),
                "JF-405's carry-over still puts the old copy's credentials file in the new copy");
        assertEquals(List.of("beta", "beta.replacing-" + STAMP), entriesOf(skillsOf(agent)));
        assertEquals(List.of(), stagedFor("beta"), "the staging area is empty once the copy is in place");
    }

    /** A racing command can only reach what its sandbox lets it write: the copy is built in none of it. */
    @Test
    void theNewCopyIsBuiltInAFolderOfItsOwnOutsideTheWorkspaceThatEverySandboxHides() throws Exception {
        var agent = createAgent("jf406-where");
        publish("gamma", "1.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, "gamma");
        publish("gamma", "2.0.0");
        write(skillsOf(agent).resolve("gamma/credentials/key.txt"), "fake-jf406-key\n");
        var duringTheCopy = new AtomicReference<List<String>>();

        // The carry-over logs once the new copy is built and before it moves, on the copying thread.
        EventLogger.captureMatchingForTest(event -> {
            if (agent.name.equals(event.agentId()) && event.message().startsWith("Carried ")) {
                try {
                    var seen = new ArrayList<String>();
                    for (var entry : entriesOf(skillsOf(agent))) seen.add("skills/" + entry);
                    for (var folder : stagedFor("gamma")) {
                        for (var file : treeOf(WorkspaceFiles.skillStagingRoot().resolve(folder))) {
                            seen.add("staging/" + file);
                        }
                    }
                    duringTheCopy.set(seen);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }, _ -> {
            try {
                SkillPromotionService.copyToAgentWorkspace(agent, "gamma");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertEquals(List.of("skills/gamma", "staging/new", "staging/new/SKILL.md", "staging/new/credentials",
                "staging/new/credentials/key.txt"), duringTheCopy.get());
        var staging = SandboxHiddenRoots.canonical(WorkspaceFiles.skillStagingRoot());
        assertTrue(SandboxHiddenRoots.current().contains(staging), staging + " in " + SandboxHiddenRoots.current());
        assertTrue(HarnessSandbox.customAgentHiddenRoots().contains(staging), "hidden from a custom agent's shell");
        assertTrue(HarnessSandbox.MainFence.current(WorkspaceFiles.workspaceRoot().toFile()).hidden().contains(staging),
                "hidden from the Main Agent's fenced shell, which writes every workspace");
        assertEquals(List.of("SKILL.md", "credentials/key.txt"), filesIn(skillsOf(agent).resolve("gamma")));
        assertEquals(List.of(), stagedFor("gamma"));
    }

    @Test
    void aFailedAddLeavesNothingInTheStagingAreaOrTheSkillsFolder() throws Exception {
        var agent = createAgent("jf406-add-fails");
        publish("delta", "1.0.0");
        var locked = globalSkillsDir.resolve("delta/locked.md");
        Files.writeString(locked, "cannot read me");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            var refused = Assertions.assertThrows(AccessDeniedException.class,
                    () -> SkillPromotionService.copyToAgentWorkspace(agent, "delta"));
            assertEquals(locked.toString(), refused.getFile());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
        }

        assertEquals(List.of(), entriesOf(skillsOf(agent)));
        assertEquals(List.of(), stagedFor("delta"));
    }

    @Test
    void aFailedUpdateLeavesNothingInTheStagingAreaAndTheOldCopyWhole() throws Exception {
        var agent = createAgent("jf406-update-fails");
        publish("nas", "1.0.0");
        SkillPromotionService.copyToAgentWorkspace(agent, "nas");
        publish("nas", "2.0.0");
        // The library ships a file where the old copy's credentials folder must go, so carrying it over fails.
        write(globalSkillsDir.resolve("nas/credentials"), "shipped");
        var old = skillsOf(agent).resolve("nas");
        var skillMd = Files.readString(old.resolve("SKILL.md"));
        write(old.resolve("credentials/nas.env"), "NAS_KEY=fake-jf406\n");

        Assertions.assertThrows(FileAlreadyExistsException.class,
                () -> SkillPromotionService.copyToAgentWorkspace(agent, "nas"));

        assertEquals(List.of("nas"), entriesOf(skillsOf(agent)));
        assertEquals(List.of("SKILL.md", "credentials/nas.env"), filesIn(old));
        assertEquals(skillMd, Files.readString(old.resolve("SKILL.md")), "the old copy is still in place");
        assertEquals(List.of(), KeptSkillFiles.list(agent, "nas"), "what the update kept goes with it");
        assertEquals(List.of(), stagedFor("nas"));
    }

    @Test
    void anEntryAlreadyAtTheStagingPathIsRefusedAndLeftAsItIs() throws Exception {
        var root = Files.createTempDirectory("jf406-swap-");
        try {
            var target = root.resolve("target");
            var staging = Files.createSymbolicLink(root.resolve("staging"), outside);

            var refused = Assertions.assertThrows(FileAlreadyExistsException.class,
                    () -> AtomicDirSwap.stageAndSwap(target, staging, root.resolve("backup"), false,
                            dir -> Files.writeString(dir.resolve("file.txt"), "staged")));

            assertEquals(staging.toString(), refused.getFile());
            assertEquals(List.of(), treeOf(outside), "nothing is written through the link");
            assertEquals(outside, Files.readSymbolicLink(staging), "the link is left as it was");
            assertEquals(List.of("staging"), entriesOf(root), "no target appears");
        } finally {
            SkillPromotionService.deleteRecursive(root);
        }
    }
}
