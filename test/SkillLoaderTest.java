import agents.SkillLoader;
import agents.SkillLoader.SkillInfo;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Branch coverage for {@link SkillLoader}'s pure parsing / classification /
 * formatting logic (JCLAW-707): frontmatter parsing, YAML scalar + list
 * extraction (inline vs block vs multiline, present vs absent, custom-key
 * default arm), the text-vs-binary classifier, the XML formatter's empty and
 * over-budget-compaction paths, and {@code parseSkillFile}'s directory-name
 * fallback + IOException arm. All the exercised methods are public and
 * filesystem/DB-free except the two disk cases, which use a temp dir.
 */
class SkillLoaderTest extends UnitTest {

    private static final Path LOC = Path.of("SKILL.md");

    // ─── parseSkillContent ───────────────────────────────────────────────────

    @Test
    void parseNullOrFrontmatterlessOrNamelessReturnsNull() {
        assertNull(SkillLoader.parseSkillContent(null, LOC), "null content");
        assertNull(SkillLoader.parseSkillContent("just body, no frontmatter", LOC), "no --- fences");
        assertNull(SkillLoader.parseSkillContent("---\ndescription: x\n---\nbody", LOC),
                "frontmatter without a name: key");
    }

    @Test
    void parseMinimalSkillDefaultsOptionalFields() {
        var info = SkillLoader.parseSkillContent("---\nname: only-name\n---\nbody", LOC);
        assertNotNull(info);
        assertEquals("only-name", info.name());
        assertEquals("", info.description(), "absent description → empty string");
        assertEquals("0.0.0", info.version(), "absent version → default semver");
        assertEquals("", info.author());
        assertEquals("", info.icon());
        assertTrue(info.tools().isEmpty());
        assertFalse(info.toolsDeclared(), "no tools: key → toolsDeclared false");
        assertTrue(info.mcpServers().isEmpty());
        assertTrue(info.commands().isEmpty());
    }

    @Test
    void parseFullFrontmatterPopulatesEveryField() {
        var content = """
                ---
                name: my-skill
                description: Does useful things
                version: 1.2.3
                author: alice
                icon: 🚀
                tools: [exec, filesystem]
                commands: [foo, bar]
                mcp_servers: [jira-confluence]
                ---
                body text
                """;
        var info = SkillLoader.parseSkillContent(content, LOC);
        assertNotNull(info);
        assertEquals("my-skill", info.name());
        assertEquals("Does useful things", info.description());
        assertEquals("1.2.3", info.version());
        assertEquals("alice", info.author());
        assertEquals("🚀", info.icon());
        assertEquals(List.of("exec", "filesystem"), info.tools());
        assertTrue(info.toolsDeclared());
        assertEquals(List.of("foo", "bar"), info.commands());
        assertEquals(List.of("jira-confluence"), info.mcpServers());
    }

    @Test
    void parseEmptyToolsListStillMarksDeclared() {
        var info = SkillLoader.parseSkillContent("---\nname: s\ntools: []\n---\n", LOC);
        assertNotNull(info);
        assertTrue(info.tools().isEmpty(), "empty inline list → no tools");
        assertTrue(info.toolsDeclared(), "presence of the tools: key alone marks it declared");
    }

    @Test
    void parseToolsBlockFormIsParsed() {
        var content = "---\nname: s\ntools:\n  - exec\n  - browser\n---\n";
        var info = SkillLoader.parseSkillContent(content, LOC);
        assertNotNull(info);
        assertEquals(List.of("exec", "browser"), info.tools());
        assertTrue(info.toolsDeclared());
    }

    // ─── extractYamlValue ────────────────────────────────────────────────────

    @Test
    void extractYamlValueScalarQuotingAndAbsence() {
        assertEquals("foo", SkillLoader.extractYamlValue("name: \"foo\"", "name"), "quotes stripped");
        assertEquals("bar", SkillLoader.extractYamlValue("name: bar", "name"), "unquoted scalar");
        assertNull(SkillLoader.extractYamlValue("name:\n", "name"), "empty value → null");
        assertNull(SkillLoader.extractYamlValue("other: x", "name"), "absent key → null");
        // "author" is not in the pre-compiled switch → default-branch pattern.
        assertEquals("bob", SkillLoader.extractYamlValue("author: bob", "author"));
    }

    // ─── extractYamlList ─────────────────────────────────────────────────────

    @Test
    void extractYamlListInlineBlockEmptyAndMissing() {
        assertEquals(List.of("a", "b", "c"), SkillLoader.extractYamlList("tools: [a, b, c]", "tools"));
        assertEquals(List.of("a", "b"),
                SkillLoader.extractYamlList("tools:\n  - a\n  - b\n", "tools"));
        assertTrue(SkillLoader.extractYamlList("tools: []", "tools").isEmpty(), "empty inline → empty");
        assertTrue(SkillLoader.extractYamlList("name: x", "tools").isEmpty(), "absent key → empty");
    }

    @Test
    void extractYamlListCustomKeyStripsItemQuotes() {
        // "deps" isn't a known key → default-branch pattern; quotes are stripped.
        assertEquals(List.of("x", "y"), SkillLoader.extractYamlList("deps: [\"x\", 'y']", "deps"));
    }

    // ─── isTextFile ──────────────────────────────────────────────────────────

    @Test
    void isTextFileByExtension() {
        assertTrue(SkillLoader.isTextFile("README.md"));
        assertTrue(SkillLoader.isTextFile("config.yaml"));
        assertTrue(SkillLoader.isTextFile("SRC/Main.JAVA"), "case-insensitive extension");
        assertFalse(SkillLoader.isTextFile("photo.png"), "binary extension");
    }

    @Test
    void isTextFileKnownExtensionlessNamesAndPathBasename() {
        assertTrue(SkillLoader.isTextFile("Makefile"));
        assertTrue(SkillLoader.isTextFile("Dockerfile"));
        assertTrue(SkillLoader.isTextFile("path/to/LICENSE"), "basename of a path is matched");
        assertFalse(SkillLoader.isTextFile("dir/unknownfile"), "unknown extensionless → binary");
    }

    // ─── formatSkillsXml ─────────────────────────────────────────────────────

    @Test
    void formatSkillsXmlEmptyReturnsEmptyString() {
        assertEquals("", SkillLoader.formatSkillsXml(List.of()));
    }

    @Test
    void formatSkillsXmlWrapsSkillWithDefaultIcon() {
        var xml = SkillLoader.formatSkillsXml(List.of(new SkillInfo("alpha", "does alpha", LOC)));
        assertTrue(xml.startsWith("<available_skills>"), xml);
        assertTrue(xml.contains("<name>alpha</name>"), xml);
        assertTrue(xml.contains("<description>does alpha</description>"), xml);
        assertTrue(xml.contains("🎯"), "empty icon falls back to the default emoji");
        assertTrue(xml.endsWith("</available_skills>"), xml);
    }

    @Test
    void formatSkillsXmlFallsBackToCompactWhenOverBudget() {
        // A description larger than MAX_SKILLS_CHARS (30k) forces the full entry
        // over budget → the formatter rebuilds with compact (description-less)
        // entries.
        var huge = "d".repeat(31_000);
        var xml = SkillLoader.formatSkillsXml(List.of(new SkillInfo("bulky", huge, LOC)));
        assertTrue(xml.contains("<name>bulky</name>"), "the skill still appears");
        assertFalse(xml.contains("<description>"), "compact entries omit the description");
    }

    // ─── parseSkillFile (disk) ───────────────────────────────────────────────

    @Test
    void parseSkillFileNonexistentReturnsNull() {
        assertNull(SkillLoader.parseSkillFile(Path.of("/no/such/dir/SKILL.md")),
                "unreadable path → IOException arm → null");
    }

    @Test
    void parseSkillFileFallsBackToDirectoryName() throws Exception {
        var dir = Files.createTempDirectory("skillloadertest");
        var skillDir = Files.createDirectory(dir.resolve("myskill"));
        var md = skillDir.resolve("SKILL.md");
        Files.writeString(md, "no frontmatter here, just prose");
        try {
            var info = SkillLoader.parseSkillFile(md);
            assertNotNull(info);
            assertEquals("myskill", info.name(), "no parseable name → directory name fallback");
            assertEquals("", info.description());
        } finally {
            Files.deleteIfExists(md);
            Files.deleteIfExists(skillDir);
            Files.deleteIfExists(dir);
        }
    }
}
