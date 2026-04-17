import org.junit.jupiter.api.*;
import play.test.*;
import agents.SkillLoader;
import models.Agent;
import services.AgentService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Covers JCLAW-71's skill-icon behaviour: frontmatter parsing, backward
 * compatibility on SkillInfo constructors, and default-icon substitution
 * in the formatted skill entry the system prompt injects.
 */
public class SkillIconTest extends UnitTest {

    @Test
    public void parsesIconFromFrontmatter() {
        var content = """
                ---
                name: sky-blue
                description: Explain why the sky is blue
                icon: 🧠
                ---
                Body text.
                """;
        var info = SkillLoader.parseSkillContent(content, Paths.get("sky-blue/SKILL.md"));
        assertNotNull(info);
        assertEquals("🧠", info.icon());
    }

    @Test
    public void missingIconLeavesFieldEmpty() {
        var content = """
                ---
                name: no-icon-skill
                description: A skill without an icon key
                ---
                Body.
                """;
        var info = SkillLoader.parseSkillContent(content, Paths.get("no-icon-skill/SKILL.md"));
        assertNotNull(info);
        assertEquals("", info.icon(), "no icon: key → empty string, never null");
    }

    @Test
    public void defaultIconAppearsWhenSkillHasNone() {
        var info = new SkillLoader.SkillInfo("plain", "no icon", Paths.get("plain/SKILL.md"));
        var xml = SkillLoader.formatSkillsXml(java.util.List.of(info));
        assertTrue(xml.contains("<icon>" + SkillLoader.DEFAULT_SKILL_ICON + "</icon>"),
                "default 🎯 icon substituted into skill XML when frontmatter omits it");
    }

    @Test
    public void explicitIconOverridesDefault() {
        var info = new SkillLoader.SkillInfo(
                "sky-blue", "Explain why the sky is blue", Paths.get("sky-blue/SKILL.md"),
                java.util.List.of(), false, "0.0.0",
                java.util.List.of(), "", "🧠");
        var xml = SkillLoader.formatSkillsXml(java.util.List.of(info));
        assertTrue(xml.contains("<icon>🧠</icon>"));
        assertFalse(xml.contains(SkillLoader.DEFAULT_SKILL_ICON));
    }

    @Test
    public void sixArgConstructorStillWorks_iconDefaultsEmpty() {
        // Backward compat: call sites that haven't been updated should compile
        // and produce a SkillInfo with icon() == "".
        var info = new SkillLoader.SkillInfo("old", "legacy caller", Paths.get("old/SKILL.md"),
                java.util.List.of(), false, "0.0.0");
        assertEquals("", info.icon());
    }

    @Test
    public void eightArgConstructorStillWorks_iconDefaultsEmpty() {
        // Added for JCLAW-71 — lets pre-icon call sites compile unchanged.
        var info = new SkillLoader.SkillInfo("old2", "legacy 8-arg", Paths.get("old2/SKILL.md"),
                java.util.List.of(), false, "0.0.0",
                java.util.List.of(), "author-a");
        assertEquals("", info.icon());
        assertEquals("author-a", info.author());
    }

    @Test
    public void defaultSkillIconIsTarget() {
        // Locking in the specific emoji so a rename is an explicit code change.
        assertEquals("🎯", SkillLoader.DEFAULT_SKILL_ICON);
    }

    @Test
    public void iconSurvivesWorkspaceRelativization() throws Exception {
        // Regression for JCLAW-71's first field-drop bug: the loader's
        // post-parse relativization used to rebuild SkillInfo via a 6-arg
        // ctor, silently losing commands / author / icon. This test hits the
        // full loadSkills() path to verify icon round-trips to SkillInfo.icon().
        Fixtures.deleteDatabase();
        var agentName = "icon-test-agent";
        var workspace = AgentService.workspacePath(agentName);
        try {
            var agent = AgentService.create(agentName, "openrouter", "gpt-4.1");
            var skillDir = workspace.resolve("skills/iconized");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: iconized
                    description: A skill with an explicit icon
                    icon: 🛠️
                    ---
                    Body.
                    """);

            SkillLoader.clearCache();
            var skills = SkillLoader.loadSkills(agentName);
            var iconized = skills.stream().filter(s -> "iconized".equals(s.name())).findFirst();
            assertTrue(iconized.isPresent(), "skill was loaded");
            assertEquals("🛠️", iconized.get().icon(),
                    "icon frontmatter must survive the workspace-relativization step");
        } finally {
            deleteDir(workspace);
        }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) {}
            });
        } catch (Exception _) {}
    }
}
