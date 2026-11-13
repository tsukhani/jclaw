import agents.SkillLoader;
import agents.ToolRegistry;
import jobs.DefaultConfigJob;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.EventLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JF-379: the bundled task-creator skill keeps skill-creator's conventions, and every boot installs the
 * registry's copy for main, the agent that creates most tasks.
 */
class TaskCreatorSkillTest extends UnitTest {

    private static final Path BUNDLED = Play.applicationPath.toPath().resolve("skills").resolve(DefaultConfigJob.TASK_CREATOR);
    private static final Pattern REFERENCE = Pattern.compile("skills/task-creator/(references/[A-Za-z0-9_.-]+)");

    private final List<Path> created = new ArrayList<>();

    @AfterEach
    void removeCopies() throws IOException {
        for (var dir : created) deleteTree(dir);
    }

    // ==================== skill-creator's conventions ====================

    @Test
    void theFrontmatterFollowsSkillCreatorsLayout() {
        var info = SkillLoader.parseSkillFile(BUNDLED.resolve("SKILL.md"));

        assertNotNull(info, "SKILL.md must parse");
        assertEquals("task-creator", info.name());
        assertEquals("1.0.0", info.version());
        assertEquals("Tarun Sukhani", info.author());
        assertEquals("⏰", info.icon());
        assertTrue(info.toolsDeclared());
        assertEquals(List.of("task_manager", "jclaw_api", "filesystem"), info.tools());
        assertEquals(List.of(), info.commands());
    }

    @Test
    void theSkillMdStaysUnderTheContextCap() throws IOException {
        var length = Files.readString(BUNDLED.resolve("SKILL.md")).length();
        assertTrue(length < 20_000, "SKILL.md is " + length + " characters; JClaw keeps only the first 20,000");
    }

    @Test
    void theDescriptionFitsTheAgentSkillsLimits() {
        var description = SkillLoader.parseSkillFile(BUNDLED.resolve("SKILL.md")).description();
        assertTrue(description.length() <= 1024, "description is " + description.length() + " characters");
        assertFalse(description.contains("<") || description.contains(">"), "description holds an angle bracket");
    }

    @Test
    void everyDeclaredToolIsARegisteredToolTheBodyNames() throws Exception {
        var body = Files.readString(BUNDLED.resolve("SKILL.md"));
        var declared = SkillLoader.parseSkillFile(BUNDLED.resolve("SKILL.md")).tools();
        var unregistered = new ArrayList<String>();
        ToolRegistrySync.withCanonicalTools(() -> {
            for (var tool : declared) if (ToolRegistry.lookupTool(tool) == null) unregistered.add(tool);
        });
        assertEquals(List.of(), unregistered);
        assertEquals(List.of(), declared.stream().filter(t -> !body.contains("`" + t + "`")).toList(),
                "skill-creator: the declared list equals the tools the body references");
    }

    @Test
    void theReferencesTheSkillNamesAreExactlyTheOnesItShips() throws IOException {
        var body = Files.readString(BUNDLED.resolve("SKILL.md"));
        var named = new TreeSet<String>();
        var m = REFERENCE.matcher(body);
        while (m.find()) named.add(m.group(1));
        Set<String> shipped;
        try (var files = Files.list(BUNDLED.resolve("references"))) {
            shipped = files.map(p -> "references/" + p.getFileName()).collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(shipped, named);
    }

    @Test
    void theSkillFolderHoldsOnlySkillMdAndItsReferences() throws IOException {
        try (var entries = Files.list(BUNDLED)) {
            assertEquals(Set.of("SKILL.md", "references"),
                    entries.map(p -> p.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    // ==================== seeding for main ====================

    @Test
    void seedingInstallsTheRegistrysCopyForMain() throws IOException {
        var registryCopy = SkillLoader.globalSkillsPath().resolve(DefaultConfigJob.TASK_CREATOR);
        assertFalse(Files.exists(registryCopy), "the test registry must not ship task-creator already");
        copyTree(BUNDLED, registryCopy);
        created.add(registryCopy);
        var main = mainAgent();
        var installed = AgentService.workspacePath(main.name).resolve("skills").resolve(DefaultConfigJob.TASK_CREATOR);
        created.add(installed);

        var events = EventLogger.captureForTest(() -> DefaultConfigJob.seedSkillForMain(
                DefaultConfigJob.TASK_CREATOR, "task-creator skill installed for main agent (task design guidance seeded)"));

        assertEquals(Files.readString(BUNDLED.resolve("SKILL.md")), Files.readString(installed.resolve("SKILL.md")));
        assertEquals(Files.readString(BUNDLED.resolve("references/examples.md")),
                Files.readString(installed.resolve("references/examples.md")));
        assertEquals(List.of("INFO agent task-creator skill installed for main agent (task design guidance seeded)"),
                events.stream().filter(e -> e.category().equals("agent"))
                        .map(e -> e.level() + " " + e.category() + " " + e.message()).toList());
    }

    @Test
    void aSkillTheRegistryDoesNotShipInstallsNothing() {
        var main = mainAgent();
        var name = "jf379-absent-skill";
        var installed = AgentService.workspacePath(main.name).resolve("skills").resolve(name);

        var events = EventLogger.captureForTest(() -> DefaultConfigJob.seedSkillForMain(name, "installed"));

        assertFalse(Files.exists(installed));
        assertEquals(List.of(), events.stream().filter(e -> e.category().equals("agent")).toList());
    }

    // ==================== helpers ====================

    private static Agent mainAgent() {
        var main = Agent.findByName("main");
        return main != null ? main : AgentService.create("main", "openrouter", "gpt-4.1");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (var source : walk.toList()) {
                var target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
