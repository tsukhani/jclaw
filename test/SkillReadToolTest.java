import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import models.Agent;
import models.AgentSkillConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import tools.SkillReadTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link SkillReadTool} (JF-146) against real skill folders in an agent's workspace: every row of the
 * spec's input matrix, the listing as the only source of readable names, and the guard that keeps the
 * read inside the workspace.
 */
class SkillReadToolTest extends UnitTest {

    private static final String BODY = """
            ---
            name: abundent-api
            description: Call the Abundent API
            ---
            # Abundent API\r
            \r
            Use `GET /v1/items` — café ✓ 😀 \r
            no newline at the end""";

    private static final String NOT_A_NAME = "Error: '%s' is not a skill name. Pass a name from <available_skills>, not a path.";
    private static final String NO_NAME = "Error: pass the name of a skill from <available_skills> as 'name'.";

    private final SkillReadTool tool = new SkillReadTool();
    private final List<Path> cleanup = new ArrayList<>();
    private Agent agent;

    @AfterEach
    void removeWorkspaces() {
        for (var dir : cleanup) deleteTree(dir);
        SkillLoader.clearCache();
    }

    @Test
    void aListedSkillComesBackByteForByte() throws IOException {
        newAgent("skill-read-listed");
        var md = writeSkill("abundent-api", BODY);

        var result = tool.execute(args("abundent-api"), agent);

        assertArrayEquals(Files.readAllBytes(md), result.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownNameIsRefusedWithTheReadableNames() throws IOException {
        newAgent("skill-read-unknown");
        writeSkill("abundent-api", BODY);
        writeSkill("alpha-skill", skillMd("alpha-skill", "alpha body"));

        assertEquals("Error: no skill named 'nope' is listed for you. Skills you can read: abundent-api, alpha-skill.",
                tool.execute(args("nope"), agent));
    }

    @Test
    void anExcludedSkillGetsTheSameRefusalAsAnUnknownName() throws IOException {
        newAgent("skill-read-excluded");
        writeSkill("abundent-api", BODY);
        writeSkill("hidden-skill", skillMd("hidden-skill", "HIDDEN_BODY"));
        var row = new AgentSkillConfig();
        row.agent = agent;
        row.skillName = "hidden-skill";
        row.enabled = false;
        row.save();
        SkillLoader.clearCache();

        assertEquals("Error: no skill named 'hidden-skill' is listed for you. Skills you can read: abundent-api.",
                tool.execute(args("hidden-skill"), agent));
    }

    @Test
    void aSkillLeftOutForAToolTheAgentLacksGetsTheSameRefusal() throws IOException {
        newAgent("skill-read-missing-tool");
        writeSkill("abundent-api", BODY);
        writeSkill("shell-skill", "---\nname: shell-skill\ndescription: Needs exec\ntools: [exec]\n---\nSHELL_BODY\n");
        var row = new models.AgentToolConfig();
        row.agent = agent;
        row.toolName = "exec";
        row.enabled = false;
        row.save();
        ToolRegistry.invalidateDisabledToolsCache(agent);
        SkillLoader.clearCache();

        assertEquals("Error: no skill named 'shell-skill' is listed for you. Skills you can read: abundent-api.",
                tool.execute(args("shell-skill"), agent));
    }

    @Test
    void withNoSkillsTheRefusalSaysThereAreNone() throws IOException {
        newAgent("skill-read-none");

        assertEquals("Error: no skill named 'abundent-api' is listed for you, and you have no skills to read.",
                tool.execute(args("abundent-api"), agent));
    }

    @Test
    void aPathShapedNameIsRefusedBeforeTheSkillScan() throws IOException {
        newAgent("skill-read-paths");

        for (var name : List.of("../x", "a/b", "a\\b", ".hidden", "..", "a..b")) {
            assertEquals(NOT_A_NAME.formatted(name), tool.execute(args(name), agent), name);
        }

        // A scan would have cached the empty list for this agent; the skill written after it, with the cache
        // left alone, is found only because none of the calls above listed the skills.
        Files.writeString(Files.createDirectories(skillsDir().resolve("abundent-api")).resolve("SKILL.md"), BODY);
        assertEquals(BODY, tool.execute(args("abundent-api"), agent));
    }

    @Test
    void aMissingOrBlankNameAsksForOne() throws IOException {
        newAgent("skill-read-blank");
        writeSkill("abundent-api", BODY);

        for (var argsJson : List.of("{}", "{\"name\":\" \"}", "{\"name\":\"\"}", "{\"name\":null}",
                "{\"name\":{\"a\":1}}")) {
            assertEquals(NO_NAME, tool.execute(argsJson, agent), argsJson);
        }
    }

    @Test
    void anArgumentThatIsNotAnObjectAsksForAName() throws IOException {
        newAgent("skill-read-not-object");
        writeSkill("abundent-api", BODY);

        for (var argsJson : List.of("[]", "\"abc\"", "[\"abundent-api\"]", "\"abundent-api\"")) {
            assertEquals(NO_NAME, tool.execute(argsJson, agent), argsJson);
        }
    }

    @Test
    void aNameIsMatchedWithoutItsSurroundingSpace() throws IOException {
        newAgent("skill-read-strip");
        writeSkill("abundent-api", BODY);

        assertEquals(BODY, tool.execute(args("  abundent-api "), agent));
    }

    @Test
    void atTheBoundTheWholeFileComesBackWithNoMarker() throws IOException {
        newAgent("skill-read-at-bound");
        var content = padded("big-skill", 40_000, "");
        writeSkill("big-skill", content);

        assertEquals(content, tool.execute(args("big-skill"), agent));
    }

    @Test
    void overTheBoundTheFirst40000CharactersComeBackWithTheMarker() throws IOException {
        newAgent("skill-read-over-bound");
        var content = padded("big-skill", 40_001, "");
        writeSkill("big-skill", content);

        assertEquals(content.substring(0, 40_000) + "\n[SKILL.md truncated: showed the first 40,000 of 40,001 characters]",
                tool.execute(args("big-skill"), agent));
    }

    @Test
    void aCutInsideASurrogatePairFallsBeforeThePair() throws IOException {
        newAgent("skill-read-surrogate");
        var content = padded("big-skill", 39_999, "😀tail");
        assertTrue(Character.isHighSurrogate(content.charAt(39_999)), "premise: the pair straddles character 40,000");
        writeSkill("big-skill", content);

        assertEquals(content.substring(0, 39_999) + "\n[SKILL.md truncated: showed the first 39,999 of 40,005 characters]",
                tool.execute(args("big-skill"), agent));
    }

    @Test
    void aSkillFolderLinkingOutsideTheWorkspaceIsRefused() throws IOException {
        newAgent("skill-read-symlink");
        var outside = Files.createTempDirectory("skill-read-outside");
        cleanup.add(outside);
        Files.writeString(outside.resolve("SKILL.md"), skillMd("outside-skill", "OUTSIDE_SECRET"));
        Files.createSymbolicLink(skillsDir().resolve("outside-skill"), outside);
        SkillLoader.clearCache();
        assertEquals(List.of("outside-skill"),
                SkillLoader.loadSkills(agent.name).stream().map(SkillLoader.SkillInfo::name).toList(),
                "premise: the linked folder is listed like any other skill");

        assertEquals("Error: skill 'outside-skill' cannot be read: Path 'skills/outside-skill/SKILL.md' escapes the workspace.",
                tool.execute(args("outside-skill"), agent));
    }

    @Test
    void aSkillMdLinkedToAnotherWorkspaceFileIsRefused() throws IOException {
        newAgent("skill-read-md-link");
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("AGENT.md"), "WORKSPACE_FILE_MARKER\n");
        var folder = Files.createDirectories(skillsDir().resolve("linked-skill"));
        Files.createSymbolicLink(folder.resolve("SKILL.md"), Path.of("../../AGENT.md"));
        SkillLoader.clearCache();
        assertEquals(List.of("linked-skill"),
                SkillLoader.loadSkills(agent.name).stream().map(SkillLoader.SkillInfo::name).toList(),
                "premise: the folder is listed by its name");

        var result = tool.execute(args("linked-skill"), agent);

        assertEquals("Error: skill 'linked-skill' cannot be read: its SKILL.md is a link to another file.", result);
        assertFalse(result.contains("WORKSPACE_FILE_MARKER"), result);
    }

    @Test
    void nothingElseInASkillFolderEverComesBack() throws IOException {
        newAgent("skill-read-siblings");
        writeSkill("abundent-api", BODY);
        var folder = skillsDir().resolve("abundent-api");
        Files.writeString(folder.resolve("reference.md"), "SIBLING_MARKER");
        Files.createDirectories(folder.resolve("tools"));
        Files.writeString(folder.resolve("tools/run.sh"), "#!/bin/sh\necho SCRIPT_MARKER\n");

        var names = List.of("abundent-api", "reference.md", "run.sh", "tools", "abundent-api/reference.md",
                "abundent-api/tools/run.sh", "../abundent-api/reference.md", "abundent-api\\reference.md");
        for (var name : names) {
            var result = tool.execute(args(name), agent);
            assertFalse(result.contains("SIBLING_MARKER"), name + " -> " + result);
            assertFalse(result.contains("SCRIPT_MARKER"), name + " -> " + result);
        }
        assertEquals(BODY, tool.execute(args("abundent-api"), agent));
    }

    @Test
    void itIsAReadOnlyCatalogStyleToolThatANewAgentHasToBeGranted() throws IOException {
        newAgent("skill-read-default");

        assertEquals("skill_read", tool.name());
        assertFalse(tool.parallelSafe());
        assertEquals(ToolRegistry.WORKSPACE_GROUP, tool.serializationGroup(),
                "a write to the SKILL.md earlier in the same message lands before the read");
        assertTrue(tool.verbatimResult(args("abundent-api")), "a cut SKILL.md would leave the agent guessing");
        assertTrue(tool.readsOutsideContent(args("abundent-api"), agent), "the same as readFile on the same file");
        assertTrue(ToolRegistry.loadDisabledTools(agent).contains("skill_read"), "off unless the operator enables it");
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private void newAgent(String name) throws IOException {
        agent = AgentService.create(name, "openrouter", "gpt-4.1");
        cleanup.add(AgentService.workspacePath(name));
        deleteTree(skillsDir());
        Files.createDirectories(skillsDir());
        SkillLoader.clearCache();
    }

    private Path skillsDir() {
        return AgentService.workspacePath(agent.name).resolve("skills");
    }

    private Path writeSkill(String folder, String content) throws IOException {
        var dir = Files.createDirectories(skillsDir().resolve(folder));
        var md = dir.resolve("SKILL.md");
        Files.writeString(md, content);
        SkillLoader.clearCache();
        return md;
    }

    private static String skillMd(String name, String body) {
        return "---\nname: " + name + "\ndescription: A skill for SkillReadToolTest\n---\n" + body + "\n";
    }

    /** A SKILL.md padded with {@code x} to {@code length} characters, then {@code tail}. */
    private static String padded(String name, int length, String tail) {
        var head = "---\nname: " + name + "\ndescription: A large skill\n---\n";
        return head + "x".repeat(length - head.length()) + tail;
    }

    private static String args(String name) {
        var o = new JsonObject();
        o.addProperty("name", name);
        return o.toString();
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            });
        } catch (IOException _) {
            // best-effort cleanup
        }
    }
}
