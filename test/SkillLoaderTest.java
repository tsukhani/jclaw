import agents.SkillLoader;
import agents.SkillLoader.SkillAccess;
import agents.SkillLoader.SkillInfo;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
        // "maintainer" is not in the pre-compiled switch → default-branch pattern.
        assertEquals("bob", SkillLoader.extractYamlValue("maintainer: bob", "maintainer"));
    }

    @Test
    void extractYamlValueAuthorAndIconParseIdenticallyWhenPrecompiled() {
        assertEquals("bob", SkillLoader.extractYamlValue("author: bob", "author"), "unquoted scalar");
        assertEquals("alice", SkillLoader.extractYamlValue("author: 'alice'", "author"), "quotes stripped");
        assertNull(SkillLoader.extractYamlValue("name: x", "author"), "absent key → null");
        assertEquals("🚀", SkillLoader.extractYamlValue("icon: \"🚀\"", "icon"), "quotes stripped");
        assertNull(SkillLoader.extractYamlValue("name: x", "icon"), "absent key → null");
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

    @Test
    void isTextFileFoldsUppercaseViaLocaleRoot() {
        // JCLAW-808: classification uses toLowerCase(Locale.ROOT), so the
        // I-bearing names that a Turkish/Azeri default locale would fold to
        // dotless-'ı' (and thus misclassify as binary) stay text on every JVM.
        // We assert the contract on the exact bug-target names rather than
        // flipping Locale.getDefault() — that process-global static is read by
        // concurrently-running tests in play1's TestEngine, so mutating it here
        // would race them (see project-play1-testengine-concurrent-tests).
        assertTrue(SkillLoader.isTextFile("LICENSE"), "uppercase LICENSE is text");
        assertTrue(SkillLoader.isTextFile("DOCKERFILE"), "uppercase DOCKERFILE is text");
        assertTrue(SkillLoader.isTextFile("config.INI"), "uppercase .INI extension is text");
    }

    // ─── formatSkillsXml ─────────────────────────────────────────────────────

    @Test
    void formatSkillsXmlEmptyReturnsEmptyString() {
        assertEquals("", SkillLoader.formatSkillsXml(List.of(), true));
    }

    @Test
    void formatSkillsXmlWrapsSkillWithDefaultIcon() {
        var xml = SkillLoader.formatSkillsXml(List.of(new SkillInfo("alpha", "does alpha", LOC)), true);
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
        var xml = SkillLoader.formatSkillsXml(List.of(new SkillInfo("bulky", huge, LOC)), true);
        assertTrue(xml.contains("<name>bulky</name>"), "the skill still appears");
        assertFalse(xml.contains("<description>"), "compact entries omit the description");
    }

    @Test
    void formatSkillsXmlLeavesTheLocationOutWhenAsked() {
        var skill = new SkillInfo("alpha", "does alpha", Path.of("skills/alpha/SKILL.md"));
        assertEquals("""
                <available_skills>
                  <skill>
                    <name>alpha</name>
                    <icon>🎯</icon>
                    <description>does alpha</description>
                    <location>skills/alpha/SKILL.md</location>
                  </skill>
                </available_skills>""", SkillLoader.formatSkillsXml(List.of(skill), true));
        assertEquals("""
                <available_skills>
                  <skill>
                    <name>alpha</name>
                    <icon>🎯</icon>
                    <description>does alpha</description>
                  </skill>
                </available_skills>""", SkillLoader.formatSkillsXml(List.of(skill), false));
    }

    @Test
    void compactEntriesKeepTheLocationWhenAsked() {
        var xml = SkillLoader.formatSkillsXml(List.of(new SkillInfo("bulky", "d".repeat(31_000), LOC)), true);
        assertEquals("<available_skills>\n  <skill>\n    <name>bulky</name>\n    <icon>🎯</icon>\n"
                + "    <location>SKILL.md</location>\n  </skill>\n</available_skills>", xml);
    }

    @Test
    void compactEntriesLeaveTheLocationOutWhenAsked() {
        var xml = SkillLoader.formatSkillsXml(List.of(new SkillInfo("bulky", "d".repeat(31_000), LOC)), false);
        assertEquals("<available_skills>\n  <skill>\n    <name>bulky</name>\n    <icon>🎯</icon>\n  </skill>\n</available_skills>",
                xml);
    }

    // ─── skill access (JF-146) ───────────────────────────────────────────────

    @Test
    void theAccessIsTheFirstReadingToolTheTurnHas() {
        assertEquals(SkillAccess.FILESYSTEM, SkillAccess.of(Set.of()));
        assertEquals(SkillAccess.FILESYSTEM, SkillAccess.of(Set.of("skill_read")));
        assertEquals(SkillAccess.SKILL_READ, SkillAccess.of(Set.of("filesystem")));
        assertEquals(SkillAccess.NONE, SkillAccess.of(Set.of("filesystem", "skill_read")));
    }

    @Test
    void onlyFilesystemAccessShowsLocations() {
        assertTrue(SkillAccess.FILESYSTEM.showsLocation());
        assertFalse(SkillAccess.SKILL_READ.showsLocation());
        assertFalse(SkillAccess.NONE.showsLocation());
    }

    @Test
    void withFilesystemTheInstructionsAreUnchanged() {
        assertEquals("""
                ## Skills (mandatory)
                Before replying: scan <available_skills> <description> entries.
                - If exactly one skill clearly applies: read its SKILL.md at <location> with the filesystem tool's readFile action, then follow it.
                - If multiple could apply: choose the most specific one, then read/follow it.
                - If platform-specific variants exist (e.g., mac vs linux): select the variant matching the current platform from the Environment section.
                - If the request involves multiple skills: select the skill matching the primary action (the first or most important verb), not secondary follow-up actions.
                - Match by intent, not exact wording. A skill applies when the goal falls within the skill's domain, even if the description includes details the request did not mention. Example: a request to "recommend a restaurant" matches a skill described as "restaurants in City X" — the skill's specifics are implementation details, not prerequisites.
                - If none clearly apply: do not read any SKILL.md.
                - **Skill authoring is always routed through skill-creator.** If you're asked to create, update, modify, edit, rename, refactor, fix, or change a skill (anything under `skills/<name>/`), the applicable skill is `skill-creator` — regardless of whether it's mentioned by name. Read `skill-creator`'s SKILL.md first and follow its workflow. Never edit files under `skills/<name>/` directly via the filesystem tool without going through skill-creator.
                - **When you're asked what skills you have:** render a markdown table with header `| Skill | Description |`, one row per skill, where the Skill cell is `<icon> **<name>**` (icon from `<available_skills>` `<icon>` element) and the Description cell is the skill's `<description>`.
                - **When you're asked what tools you have:** present them grouped by the `###` category headings in the Tool Catalog (System / Files / Web / Utilities), each category followed by its own `| Tool | Purpose |` markdown table. Do not mention tools that are not in the Tool Catalog — those are internal and should stay hidden from your answer.
                Constraints: never read more than one skill up front; only read after selecting.
                """, SkillLoader.skillMatchingInstructions(SkillAccess.FILESYSTEM));
    }

    @Test
    void withSkillReadTheInstructionsNameItAndNeverFilesystem() {
        assertEquals("""
                ## Skills (mandatory)
                Before replying: scan <available_skills> <description> entries.
                - If exactly one skill clearly applies: read its SKILL.md by calling `skill_read` with the skill's <name>, then follow it.
                - `skill_read` returns only SKILL.md. When a skill points to other files, say you cannot open them rather than guess what they hold.
                - If multiple could apply: choose the most specific one, then read/follow it.
                - If platform-specific variants exist (e.g., mac vs linux): select the variant matching the current platform from the Environment section.
                - If the request involves multiple skills: select the skill matching the primary action (the first or most important verb), not secondary follow-up actions.
                - Match by intent, not exact wording. A skill applies when the goal falls within the skill's domain, even if the description includes details the request did not mention. Example: a request to "recommend a restaurant" matches a skill described as "restaurants in City X" — the skill's specifics are implementation details, not prerequisites.
                - If none clearly apply: do not read any SKILL.md.
                - **When you're asked what skills you have:** render a markdown table with header `| Skill | Description |`, one row per skill, where the Skill cell is `<icon> **<name>**` (icon from `<available_skills>` `<icon>` element) and the Description cell is the skill's `<description>`.
                - **When you're asked what tools you have:** present them grouped by the `###` category headings in the Tool Catalog (System / Files / Web / Utilities), each category followed by its own `| Tool | Purpose |` markdown table. Do not mention tools that are not in the Tool Catalog — those are internal and should stay hidden from your answer.
                Constraints: never read more than one skill up front; only read after selecting.
                """, SkillLoader.skillMatchingInstructions(SkillAccess.SKILL_READ));
    }

    @Test
    void withNeitherToolTheInstructionsSayToWorkFromTheDescriptions() {
        assertEquals("""
                ## Skills (mandatory)
                Before replying: scan <available_skills> <description> entries.
                You have no tool that opens a skill's SKILL.md, so work from each skill's <description> alone. When a description does not say enough to do the task, say so rather than guess.
                - If exactly one skill clearly applies: follow its <description>.
                - If multiple could apply: choose the most specific one.
                - If platform-specific variants exist (e.g., mac vs linux): select the variant matching the current platform from the Environment section.
                - If the request involves multiple skills: select the skill matching the primary action (the first or most important verb), not secondary follow-up actions.
                - Match by intent, not exact wording. A skill applies when the goal falls within the skill's domain, even if the description includes details the request did not mention. Example: a request to "recommend a restaurant" matches a skill described as "restaurants in City X" — the skill's specifics are implementation details, not prerequisites.
                - **When you're asked what skills you have:** render a markdown table with header `| Skill | Description |`, one row per skill, where the Skill cell is `<icon> **<name>**` (icon from `<available_skills>` `<icon>` element) and the Description cell is the skill's `<description>`.
                - **When you're asked what tools you have:** present them grouped by the `###` category headings in the Tool Catalog (System / Files / Web / Utilities), each category followed by its own `| Tool | Purpose |` markdown table. Do not mention tools that are not in the Tool Catalog — those are internal and should stay hidden from your answer.
                """, SkillLoader.skillMatchingInstructions(SkillAccess.NONE));
    }

    // ─── parseSkillFile (disk) ───────────────────────────────────────────────

    // ─── block-scalar frontmatter (JCLAW-995) ────────────────────────────────

    @Test
    void blockScalarValueParsesInsteadOfYieldingTheIndicator() {
        assertEquals("bob smith", SkillLoader.extractYamlValue("author: |\n  bob smith\n", "author"),
                "`|` header must fall through to the block-scalar pattern");
        assertEquals("bob smith", SkillLoader.extractYamlValue("author: >-\n  bob smith\n", "author"),
                "a chomping indicator (`>-`) is part of the header, not the value");
    }

    @Test
    void foldedDescriptionAcrossLinesJoinsIntoOneValue() {
        var info = SkillLoader.parseSkillContent(
                "---\nname: deck\ndescription: >-\n  Create decks whose document\n  is plain JSON.\n---\n# body",
                LOC);
        assertNotNull(info);
        assertEquals("Create decks whose document is plain JSON.", info.description(),
                "a folded description must reach the model, not \">-\"");
    }

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
