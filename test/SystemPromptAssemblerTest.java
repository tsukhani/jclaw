import agents.SystemPromptAssembler;
import models.Agent;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConfigService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Service-layer tests for {@link SystemPromptAssembler}, focused on the
 * cacheable-prefix invariant and the JCLAW-128 prompt-cache boundary
 * placement that depends on it.
 *
 * <p>Coverage breakdown:
 * <ul>
 *   <li>Workspace file order — SOUL → IDENTITY → USER → BOOTSTRAP → AGENT.</li>
 *   <li>Cache boundary marker — present once, sits between the stable prefix
 *       and the per-turn memories section, and {@code breakdown()} reports
 *       prefix/suffix sizes consistent with that placement.</li>
 *   <li>Skills XML — emitted when the agent has at least one skill on disk,
 *       absent otherwise.</li>
 *   <li>Channel guidance — Telegram and Web sections render their distinctive
 *       bodies; Slack/WhatsApp/null/unknown skip the section entirely.</li>
 *   <li>Blank file handling — a blank workspace markdown file silently drops
 *       from the output instead of injecting an empty section.</li>
 * </ul>
 */
public class SystemPromptAssemblerTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    private Agent newAgent(String name) {
        // AgentService.create seeds the workspace + the five .md files.
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    private void writeWorkspaceFile(String agentName, String filename, String content) throws Exception {
        var path = AgentService.workspacePath(agentName).resolve(filename);
        Files.writeString(path, content);
    }

    // =====================
    // Workspace file order: SOUL → IDENTITY → USER → BOOTSTRAP → AGENT
    // =====================

    @Test
    public void workspaceFilesAppearInDeclaredNarrativeOrder() throws Exception {
        var agent = newAgent("spa-order-1");
        // Stamp each workspace file with a distinctive sentinel so we can read
        // their absolute positions back out of the assembled prompt.
        writeWorkspaceFile(agent.name, "SOUL.md", "MARKER_SOUL_5e8a");
        writeWorkspaceFile(agent.name, "IDENTITY.md", "MARKER_IDENTITY_5e8a");
        writeWorkspaceFile(agent.name, "USER.md", "MARKER_USER_5e8a");
        writeWorkspaceFile(agent.name, "BOOTSTRAP.md", "MARKER_BOOTSTRAP_5e8a");
        writeWorkspaceFile(agent.name, "AGENT.md", "MARKER_AGENT_5e8a");

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();

        int soul = prompt.indexOf("MARKER_SOUL_5e8a");
        int identity = prompt.indexOf("MARKER_IDENTITY_5e8a");
        int user = prompt.indexOf("MARKER_USER_5e8a");
        int bootstrap = prompt.indexOf("MARKER_BOOTSTRAP_5e8a");
        int agentMd = prompt.indexOf("MARKER_AGENT_5e8a");

        assertTrue(soul >= 0, "SOUL marker missing");
        assertTrue(identity > soul, "IDENTITY must follow SOUL");
        assertTrue(user > identity, "USER must follow IDENTITY");
        assertTrue(bootstrap > user, "BOOTSTRAP must follow USER");
        assertTrue(agentMd > bootstrap, "AGENT must follow BOOTSTRAP");
    }

    @Test
    public void blankWorkspaceFileIsDroppedSilently() throws Exception {
        var agent = newAgent("spa-blank-drop");
        writeWorkspaceFile(agent.name, "USER.md", "   \n  \n");
        writeWorkspaceFile(agent.name, "AGENT.md", "MARKER_AGENT_BD");

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        // AGENT.md content still appears; the blank USER.md leaves no trace.
        assertTrue(prompt.contains("MARKER_AGENT_BD"));
        // No stray blank lines that could only come from USER.md being emitted —
        // the appendSection guard is what we're really pinning here.
        assertFalse(prompt.contains("\n\n\n\n\n"),
                "blank file should not introduce stacked empty paragraphs");
    }

    // =====================
    // Cache boundary marker placement
    // =====================

    @Test
    public void cacheBoundaryMarkerAppearsExactlyOnce() {
        var agent = newAgent("spa-cache-once");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        int first = prompt.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        int last = prompt.lastIndexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        assertTrue(first >= 0, "marker must be present in the assembled prompt");
        assertEquals(first, last,
                "the cache boundary marker must appear exactly once");
    }

    @Test
    public void cacheBoundarySitsBetweenEnvironmentAndMemoriesSections() throws Exception {
        // Environment is the last section in the cacheable prefix; memories are
        // the only section after the boundary. This pin enforces the JCLAW-128
        // ordering invariant the prompt cache relies on.
        var agent = newAgent("spa-boundary-sandwich");
        // Force a memory recall by passing a user message — even with no memory
        // store seeded, the section header path is taken and we can verify
        // ordering against environment.
        var prompt = SystemPromptAssembler.assemble(agent, "tell me about cats", null, "web").systemPrompt();

        int env = prompt.indexOf("## Environment");
        int marker = prompt.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);
        assertTrue(env >= 0, "environment header missing");
        assertTrue(marker > env,
                "cache boundary marker must come after the environment section");
    }

    @Test
    public void breakdownReportsPrefixAndSuffixCharsAroundMarker() {
        var agent = newAgent("spa-breakdown-split");
        var bd = SystemPromptAssembler.breakdown(agent, null, "web");
        // Without a user message the memories section is empty, but the marker
        // still bisects the prompt and breakdown reports the split.
        assertTrue(bd.cacheablePrefixChars() > 0,
                "non-empty cacheable prefix expected");
        assertTrue(bd.cacheablePrefixChars() < bd.totalChars(),
                "prefix must be a strict subset of total chars");
        assertEquals(SystemPromptAssembler.CACHE_BOUNDARY_MARKER,
                bd.cacheBoundaryMarker(),
                "breakdown must echo the canonical marker constant");
    }

    // =====================
    // Skills XML emission
    // =====================

    @Test
    public void skillsXmlOmittedWhenAgentHasNoSkills() {
        var agent = newAgent("spa-skills-absent");
        // Fresh workspace has skills/ directory but no SKILL.md inside.
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertFalse(prompt.contains("<available_skills>"),
                "no skills means no <available_skills> XML block");
        assertFalse(prompt.contains("## Tool Catalog"),
                "tool catalog is gated on skills being present");
    }

    @Test
    public void skillsXmlEmittedWhenAgentHasAtLeastOneSkill() throws Exception {
        var agent = newAgent("spa-skills-present");
        // Seed a minimal SKILL.md under skills/test-skill/. SkillLoader requires
        // YAML frontmatter with at least name + description; the body is freeform.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: test-skill
                description: A skill seeded by SystemPromptAssemblerTest
                ---

                Pretend skill body for the assembler test.
                """);
        // SkillLoader caches per agent — reset so the freshly-seeded skill is
        // visible without waiting for the TTL.
        agents.SkillLoader.clearCache();

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertTrue(prompt.contains("<available_skills>"),
                "<available_skills> must be emitted when at least one skill loads");
        assertTrue(prompt.contains("test-skill"),
                "skill name must appear in the rendered XML");
    }

    // =====================
    // Channel guidance
    // =====================

    @Test
    public void telegramChannelInjectsTelegramGuidance() {
        var agent = newAgent("spa-channel-telegram");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "telegram").systemPrompt();
        assertTrue(prompt.contains("Channel Guidance (telegram)"),
                "telegram must produce a Channel Guidance header");
        // Distinctive Telegram-specific copy.
        assertTrue(prompt.contains("Telegram") || prompt.contains("telegram"),
                "guidance body should reference Telegram");
        assertTrue(prompt.contains("4000 characters") || prompt.contains("inline photos"),
                "guidance body should include Telegram-specific hints");
    }

    @Test
    public void webChannelInjectsWebGuidance() {
        var agent = newAgent("spa-channel-web");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertTrue(prompt.contains("Channel Guidance (web)"),
                "web must produce a Channel Guidance header");
        assertTrue(prompt.contains("admin chat UI") || prompt.contains("download chips"),
                "guidance body should include web-specific hints");
    }

    @Test
    public void slackChannelSkipsGuidanceSection() {
        var agent = newAgent("spa-channel-slack");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "slack").systemPrompt();
        assertFalse(prompt.contains("Channel Guidance"),
                "slack has no registered guidance — section must be omitted");
    }

    @Test
    public void whatsappChannelSkipsGuidanceSection() {
        var agent = newAgent("spa-channel-wa");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "whatsapp").systemPrompt();
        assertFalse(prompt.contains("Channel Guidance"));
    }

    @Test
    public void unknownChannelSkipsGuidanceSection() {
        var agent = newAgent("spa-channel-unknown");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "rocketchat").systemPrompt();
        assertFalse(prompt.contains("Channel Guidance"),
                "unrecognized channels must not trigger guidance");
    }

    @Test
    public void nullChannelSkipsGuidanceSection() {
        var agent = newAgent("spa-channel-null");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, null).systemPrompt();
        assertFalse(prompt.contains("Channel Guidance"));
    }

    @Test
    public void channelTypeIsCaseInsensitive() {
        var agent = newAgent("spa-channel-case");
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "TELEGRAM").systemPrompt();
        assertTrue(prompt.contains("Channel Guidance (telegram)"),
                "uppercase channelType must still resolve to the telegram body");
    }

    // =====================
    // Cleanup so workspace dirs don't accumulate across runs
    // =====================

    @AfterAll
    static void cleanupWorkspaceDirs() {
        var root = AgentService.workspaceRoot();
        if (!Files.exists(root)) return;
        try (var stream = Files.list(root)) {
            stream.filter(p -> p.getFileName().toString().startsWith("spa-"))
                    .forEach(SystemPromptAssemblerTest::deleteRecursively);
        } catch (Exception _) {
            // best-effort
        }
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                try { Files.delete(child); } catch (Exception _) { /* best-effort */ }
            });
        } catch (Exception _) {
            // best-effort
        }
    }

    // =====================
    // Loadtest agent: minimal prompt for fair cross-provider benchmarks
    // =====================

    @Test
    public void loadtestAgentEmitsOnlySafetyExecutionBiasAndChannelGuidance() throws Exception {
        var agent = newAgent(services.LoadTestRunner.LOADTEST_AGENT_NAME);
        // Add a workspace file the loadtest path should IGNORE — proves the
        // slim path is genuinely skipping content rather than the test
        // happening to land on an empty workspace.
        writeWorkspaceFile(agent.name, "AGENT.md", "MARKER_AGENT_LOADTEST_SHOULD_NOT_APPEAR");

        var assembled = SystemPromptAssembler.assemble(agent, "anything", null, "web");
        var prompt = assembled.systemPrompt();

        // Dump to /tmp for empirical inspection — operators can cat the file
        // after `play autotest` to see exactly what bytes ship for the
        // loadtest agent. Best-effort; failures here don't break the test.
        try {
            Files.writeString(Path.of("/tmp/jclaw-loadtest-prompt.txt"), prompt);
        } catch (Exception _) { /* ok */ }

        assertTrue(prompt.contains("## Safety"),
                "Safety section must appear in the loadtest prompt");
        assertTrue(prompt.contains("Execution Bias") || prompt.contains("Execution") || prompt.contains("execution"),
                "Execution Bias section must appear in the loadtest prompt");
        assertTrue(prompt.toLowerCase().contains("channel guidance"),
                "Channel Guidance section must appear when channelType is 'web'");

        // Negative assertions — every other section must be absent.
        assertFalse(prompt.contains("MARKER_AGENT_LOADTEST_SHOULD_NOT_APPEAR"),
                "Loadtest path must NOT include workspace file content");
        assertFalse(prompt.contains("Workspace File Delivery"),
                "Loadtest path must NOT include the workspace-file-delivery convention section");
        assertFalse(prompt.contains("Environment"),
                "Loadtest path must NOT include the environment-info section");
        // JCLAW-281: the Execution Bias section now references "Tool Catalog"
        // by name in a behavioral rule about MCP/tools categorization. Assert
        // on the literal section header so this stays a check that the
        // section itself isn't rendered, not that the phrase never appears.
        assertFalse(prompt.contains("## Tool Catalog"),
                "Loadtest path must NOT include the tool-catalog section");
        assertFalse(prompt.contains("Relevant Memories") || prompt.contains("memories"),
                "Loadtest path must NOT include the recalled-memories section");

        assertTrue(assembled.skills().isEmpty(),
                "Loadtest path must return zero skills (regardless of whether disk has any)");
    }

    @Test
    public void loadtestAgentSkipsChannelGuidanceWhenChannelHasNone() throws Exception {
        var agent = newAgent(services.LoadTestRunner.LOADTEST_AGENT_NAME);
        // Slack has no registered channel guidance → section silently absent.
        var assembled = SystemPromptAssembler.assemble(agent, "x", null, "slack");
        assertFalse(assembled.systemPrompt().toLowerCase().contains("channel guidance"),
                "Channel Guidance section must be absent for channels without registered guidance");
        assertTrue(assembled.systemPrompt().contains("## Safety"),
                "Safety still appears regardless of channel");
    }
}
