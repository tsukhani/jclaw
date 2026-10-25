import agents.SkillFrontmatter;
import agents.SkillLoader;
import agents.SkillSpec;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;

/**
 * The Agent Skills layout JClaw writes, the earlier layout it still reads, and the port of
 * Anthropic's {@code quick_validate.py} that decides whether Claude would accept a skill.
 */
class SkillFrontmatterTest extends UnitTest {

    private static final String SPEC_LAYOUT = """
            ---
            name: wa-notifier
            description: >-
              Sends WhatsApp messages. Use when the operator asks to message someone.
            metadata:
              version: "1.4.2"
              author: main
              jclaw-icon: "💬"
              jclaw-tools: exec filesystem
              jclaw-commands: wacli wacli-setup
              jclaw-mcp-servers: jira-confluence
            ---

            # WA Notifier
            """;

    private static final String EARLIER_LAYOUT = """
            ---
            name: wa-notifier
            description: Sends WhatsApp messages. Use when the operator asks to message someone.
            version: 1.4.2
            author: main
            tools: [exec, filesystem]
            commands: [wacli, wacli-setup]
            mcp_servers: [jira-confluence]
            icon: 💬
            ---

            # WA Notifier
            """;

    // ==================== reading ====================

    @Test
    void aSkillInTheSpecLayoutLoadsEveryJclawField() {
        var info = SkillLoader.parseSkillContent(SPEC_LAYOUT, null);

        assertEquals("wa-notifier", info.name());
        assertEquals("Sends WhatsApp messages. Use when the operator asks to message someone.", info.description());
        assertEquals("1.4.2", info.version());
        assertEquals("main", info.author());
        assertEquals("💬", info.icon());
        assertEquals(List.of("exec", "filesystem"), info.tools());
        assertTrue(info.toolsDeclared());
        assertEquals(List.of("wacli", "wacli-setup"), info.commands());
        assertEquals(List.of("jira-confluence"), info.mcpServers());
    }

    @Test
    void aSkillInTheEarlierLayoutLoadsTheSameFields() {
        assertEquals(SkillLoader.parseSkillContent(SPEC_LAYOUT, null), SkillLoader.parseSkillContent(EARLIER_LAYOUT, null));
    }

    @Test
    void anEmptyToolListStillDeclaresTheTools() {
        var declared = SkillLoader.parseSkillContent(
                "---\nname: x\ndescription: d\nmetadata:\n  jclaw-tools: \"\"\n---\n", null);
        var undeclared = SkillLoader.parseSkillContent("---\nname: x\ndescription: d\n---\n", null);

        assertEquals(List.of(), declared.tools());
        assertTrue(declared.toolsDeclared(), "declared as none: the body is not scanned for tools");
        assertFalse(undeclared.toolsDeclared());
    }

    @Test
    void aMetadataKeyWinsOverTheEarlierLayoutsKeyOfTheSameMeaning() {
        var info = SkillLoader.parseSkillContent(
                "---\nname: x\ndescription: d\ncommands: [rm]\nmetadata:\n  jclaw-commands: ok\n---\n", null);

        assertEquals(List.of("ok"), info.commands());
    }

    @Test
    void frontmatterThatIsNotYamlStillLoadsThroughTheLineReader() {
        var md = "---\nname: stager\ndescription: Works in stages: a plan first\ntools: [filesystem]\n---\n";

        assertNull(SkillFrontmatter.of(md), "\": \" in a plain scalar is not YAML");
        var info = SkillLoader.parseSkillContent(md, null);
        assertEquals("Works in stages: a plan first", info.description());
        assertEquals(List.of("filesystem"), info.tools());
    }

    // ==================== rendering ====================

    @Test
    void renderingTheEarlierLayoutWritesTheSpecLayout() {
        var rendered = SkillFrontmatter.of(EARLIER_LAYOUT).render();

        assertEquals(SPEC_LAYOUT.substring(0, SPEC_LAYOUT.indexOf("---\n\n") + 4), rendered);
        assertEquals(List.of(), SkillSpec.problems(rendered + "\n# WA Notifier\n", "wa-notifier"));
    }

    @Test
    void everyValueReadsBackUnchangedByADefaultTypedParser() {
        var frontmatter = SkillFrontmatter.empty()
                .put("name", "tricky")
                .put("description", "Works in stages: a plan, then review #deep. Call it \"thorough\"")
                .put("license", "yes")
                .putMetadata("version", "1.0")
                .putMetadata("author", "O'Brien: \\ and \t tab")
                .putMetadata("jclaw-icon", "🎭");

        Map<String, Object> parsed = new Yaml().load(body(frontmatter.render()));

        assertEquals("Works in stages: a plan, then review #deep. Call it \"thorough\"", parsed.get("description"));
        assertEquals("yes", parsed.get("license"), "quoted, or YAML 1.1 would read true");
        assertEquals(Map.of("version", "1.0", "author", "O'Brien: \\ and \t tab", "jclaw-icon", "🎭"), parsed.get("metadata"));
    }

    @Test
    void aMultiLineDescriptionKeepsItsLineBreaks() {
        var md = SkillFrontmatter.empty().put("name", "x").put("description", "First line.\nSecond line.").render();

        assertEquals("First line.\nSecond line.", SkillFrontmatter.of(md).description());
    }

    @Test
    void anImportedSkillKeepsEveryKeyItArrivedWith() {
        var upstream = """
                ---
                name: pdf
                description: Extract text from PDFs. Use when handling PDFs.
                license: Complete terms in LICENSE.txt
                compatibility: Requires Python 3.14+ and uv
                allowed-tools: Bash(git:*) Read
                metadata:
                  author: example-org
                  version: "1.0"
                  openclaw: {"requires": {"bins": ["qpdf"]}}
                ---
                """;
        var frontmatter = SkillFrontmatter.of(upstream).setTools(List.of("exec", "filesystem"));

        Map<String, Object> before = new Yaml().load(body(upstream));
        Map<String, Object> after = new Yaml().load(body(frontmatter.render()));

        assertEquals(List.of("name", "description", "license", "compatibility", "allowed-tools", "metadata"),
                List.copyOf(after.keySet()));
        for (var key : List.of("name", "description", "license", "compatibility", "allowed-tools")) {
            assertEquals(before.get(key), after.get(key), key);
        }
        assertEquals(Map.of("author", "example-org", "version", "1.0",
                        "openclaw", Map.of("requires", Map.of("bins", List.of("qpdf"))),
                        "jclaw-tools", "exec filesystem"),
                after.get("metadata"));
    }

    // ==================== word lists ====================

    @Test
    void aListEntryThatIsNotOneWordIsRefused() {
        var frontmatter = SkillFrontmatter.empty();

        var e = assertThrows(IllegalArgumentException.class, () -> frontmatter.setCommands(List.of("wacli", "x bash")));
        assertEquals("jclaw-commands entry is not a single word: \"x bash\"", e.getMessage());
    }

    @Test
    void anEarlierLayoutEntryThatIsNotOneWordIsDroppedRatherThanSplit() {
        var info = SkillLoader.parseSkillContent(
                "---\nname: x\ndescription: d\ncommands: [\"x bash\", wacli]\n---\n", null);

        assertEquals(List.of("wacli"), info.commands());
    }

    // skill-creator has new skills declare "no binaries" explicitly, so a reviewer can tell it from a missing declaration.
    @Test
    void anEmptyListStillDeclaresTheKey() {
        var md = SkillFrontmatter.empty().put("name", "x").put("description", "d")
                .setTools(List.of()).setCommands(List.of()).render();
        var converted = SkillFrontmatter.of("---\nname: x\ndescription: d\ntools: []\ncommands: []\n---\n").render();

        assertEquals("---\nname: x\ndescription: >-\n  d\nmetadata:\n  jclaw-tools: \"\"\n  jclaw-commands: \"\"\n---\n", md);
        assertEquals(md, converted);
    }

    // ==================== Anthropic's validator ====================

    @Test
    void theEarlierLayoutFailsAnthropicsValidatorOnItsTopLevelKeys() {
        assertEquals(List.of("Unexpected key(s) in SKILL.md frontmatter: author, commands, icon, mcp_servers, tools, version. "
                        + "Allowed properties are: allowed-tools, compatibility, description, license, metadata, name"),
                SkillSpec.problems(EARLIER_LAYOUT, "wa-notifier"));
    }

    @Test
    void theValidatorChecksTheNameAsTheSpecDefinesIt() {
        assertEquals(List.of("Name 'Wa' should be kebab-case (lowercase letters, digits, and hyphens only)"),
                SkillSpec.problems("---\nname: Wa\ndescription: d\n---\n", null));
        assertEquals(List.of("Name 'wa--x' cannot start/end with hyphen or contain consecutive hyphens"),
                SkillSpec.problems("---\nname: wa--x\ndescription: d\n---\n", null));
        assertEquals(List.of("Name must be a string, got bool"),
                SkillSpec.problems("---\nname: yes\ndescription: d\n---\n", null));
        assertEquals(List.of("Name 'wa' must match the skill's directory 'wa-notifier'"),
                SkillSpec.problems("---\nname: wa\ndescription: d\n---\n", "wa-notifier"));
        assertEquals(List.of(), SkillSpec.problems("---\nname: " + "a".repeat(64) + "\ndescription: d\n---\n", null));
        assertEquals(List.of("Name is too long (65 characters). Maximum is 64 characters."),
                SkillSpec.problems("---\nname: " + "a".repeat(65) + "\ndescription: d\n---\n", null));
    }

    @Test
    void theValidatorChecksTheDescriptionAndCompatibilityLengths() {
        assertEquals(List.of(), SkillSpec.problems("---\nname: x\ndescription: " + "d".repeat(1024)
                + "\ncompatibility: " + "c".repeat(500) + "\n---\n", null));
        assertEquals(List.of("Description is too long (1025 characters). Maximum is 1024 characters.",
                        "Compatibility is too long (501 characters). Maximum is 500 characters."),
                SkillSpec.problems("---\nname: x\ndescription: " + "d".repeat(1025)
                        + "\ncompatibility: " + "c".repeat(501) + "\n---\n", null));
        assertEquals(List.of("Description cannot contain angle brackets (< or >)"),
                SkillSpec.problems("---\nname: x\ndescription: writes to public/apps/<slug>/\n---\n", null));
    }

    @Test
    void theValidatorHoldsMetadataToStringValues() {
        assertEquals(List.of("Metadata value 'version' must be a string, got float"),
                SkillSpec.problems("---\nname: x\ndescription: d\nmetadata:\n  version: 1.0\n---\n", null));
        assertEquals(List.of("Missing 'name' in frontmatter", "Missing 'description' in frontmatter"),
                SkillSpec.problems("---\nlicense: MIT\n---\n", null));
        assertEquals(List.of("No YAML frontmatter found"), SkillSpec.problems("# no frontmatter", null));
    }

    private static String body(String fenced) {
        return fenced.substring(4, fenced.indexOf("\n---", 4));
    }
}
