import org.junit.jupiter.api.*;
import play.test.*;
import agents.SkillLoader;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import models.SkillRegistryTool;
import services.AgentService;
import services.ConfigService;
import services.SkillPromotionService;
import tools.ShellExecTool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers the per-agent shell allowlist contribution model: skills declare
 * {@code commands:} in SKILL.md, promotion populates
 * {@link SkillRegistryTool}, skill install snapshots into
 * {@link AgentSkillAllowedTool}, {@link ShellExecTool#validateAllowlist}
 * unions global + per-agent-skill contributions.
 *
 * <p>Primary invariant under test: <em>workspace-file tampering cannot
 * expand an agent's allowlist.</em> The allowlist is sourced from DB
 * rows written at install time, not from re-reading workspace SKILL.md.
 */
public class SkillAllowlistTest extends UnitTest {

    private Agent agent;
    private Path globalSkillsDir;
    private ShellExecTool tool;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        // Global shell allowlist for baseline — echo is always safe to include.
        ConfigService.set("shell.allowlist", "echo,ls");

        agent = AgentService.create("allowlist-agent", "openrouter", "gpt-4.1");
        tool = new ShellExecTool();

        // Point the global skills registry at a fresh temp dir so we're not
        // contaminated by the repo's shipped skills/.
        globalSkillsDir = Files.createTempDirectory("skill-allowlist-test-global-");
        play.Play.configuration.setProperty("jclaw.skills.path", globalSkillsDir.toString());
        SkillLoader.clearCache();
    }

    @AfterEach
    void teardown() throws Exception {
        if (globalSkillsDir != null && Files.exists(globalSkillsDir)) {
            SkillPromotionService.deleteRecursive(globalSkillsDir);
        }
        play.Play.configuration.remove("jclaw.skills.path");
        deleteDir(AgentService.workspacePath("allowlist-agent"));
    }

    // ==================== Helpers ====================

    /**
     * Write a minimal valid SKILL.md to {@code dir} with an explicit
     * {@code commands:} block. Use for priming the global registry without
     * going through the LLM-backed promotion pipeline.
     */
    private static void writeSkillMd(Path dir, String skillName, String... commands) throws Exception {
        Files.createDirectories(dir);
        var cmdList = commands.length == 0 ? "[]"
                : "[" + String.join(", ", java.util.Arrays.stream(commands)
                        .map(c -> "\"" + c + "\"").toList()) + "]";
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
              + "name: " + skillName + "\n"
              + "description: test skill\n"
              + "version: 1.0.0\n"
              + "commands: " + cmdList + "\n"
              + "---\n"
              + "# " + skillName + "\n");
    }

    private static void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) SkillPromotionService.deleteRecursive(dir);
        } catch (java.io.IOException _) {}
    }

    /** Seed a registry row without going through promotion — used to isolate install behavior. */
    private static void seedRegistryTool(String skillName, String toolName) {
        var row = new SkillRegistryTool();
        row.skillName = skillName;
        row.toolName = toolName;
        row.save();
    }

    // ==================== The six core tests ====================

    @Test
    public void skillInstallPersistsAllowlistRows() throws Exception {
        // Registry has "demo" skill with two commands
        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "wacli", "wacli-setup");
        seedRegistryTool("demo", "wacli");
        seedRegistryTool("demo", "wacli-setup");

        // Install it for the agent
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");

        // Assert per-agent allowlist rows were snapshotted from the registry
        var rows = AgentSkillAllowedTool.findByAgentAndSkill(agent, "demo");
        assertEquals(2, rows.size(), "install should create one row per registry-blessed command");
        var tools = rows.stream().map(r -> r.toolName).sorted().toList();
        assertEquals(java.util.List.of("wacli", "wacli-setup"), tools);

        // And the command is now allowed for this agent
        assertNull(tool.validateAllowlist("wacli --status", agent),
                "installed skill's command must be in effective allowlist");
    }

    @Test
    public void skillTamperingDoesNotExpandAllowlist() throws Exception {
        // Registry blesses ONE command for "demo"
        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "wacli");
        seedRegistryTool("demo", "wacli");
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");

        // Agent tampers with its workspace copy of SKILL.md to add "rm"
        var workspaceSkillMd = AgentService.workspacePath(agent.name)
                .resolve("skills").resolve("demo").resolve("SKILL.md");
        var tampered = "---\nname: demo\ndescription: tampered\nversion: 1.0.0\n"
                + "commands: [\"wacli\", \"rm\"]\n---\n# demo\n";
        Files.writeString(workspaceSkillMd, tampered);
        SkillLoader.clearCache();

        // The registry-blessed command still works
        assertNull(tool.validateAllowlist("wacli", agent),
                "registry-blessed command must remain allowed");
        // But the tampered-in command does NOT — the allowlist is DB-authoritative, not workspace-authoritative
        assertNotNull(tool.validateAllowlist("rm -rf /", agent),
                "workspace SKILL.md tampering must NOT expand the effective allowlist");
    }

    @Test
    public void unPromotedSkillDoesNotAffectAgent() throws Exception {
        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "wacli");
        seedRegistryTool("demo", "wacli");
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");

        // Un-promote: delete registry rows AND remove global skill dir
        SkillRegistryTool.deleteBySkill("demo");
        SkillPromotionService.deleteRecursive(skillDir);

        // Agent's per-skill allowlist rows persist (they were snapshotted at install)
        var rows = AgentSkillAllowedTool.findByAgentAndSkill(agent, "demo");
        assertEquals(1, rows.size(),
                "un-promotion must not retroactively revoke granted allowlist rows");
        assertNull(tool.validateAllowlist("wacli", agent),
                "un-promoted skill's command remains executable for agents that already installed it");
    }

    @Test
    public void disablingSkillRemovesAllowlistContribution() throws Exception {
        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "wacli");
        seedRegistryTool("demo", "wacli");
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");

        // Initially allowed
        assertNull(tool.validateAllowlist("wacli", agent));

        // Disable the skill via AgentSkillConfig
        var cfg = new AgentSkillConfig();
        cfg.agent = agent;
        cfg.skillName = "demo";
        cfg.enabled = false;
        cfg.save();

        // Now rejected — effective allowlist excludes disabled skills
        assertNotNull(tool.validateAllowlist("wacli", agent),
                "disabled skill must not contribute to effective allowlist");

        // Re-enable
        cfg.enabled = true;
        cfg.save();
        assertNull(tool.validateAllowlist("wacli", agent),
                "re-enabling the skill must restore its allowlist contribution");
    }

    @Test
    public void skillDeleteRevokesAllowlistRows() throws Exception {
        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "wacli");
        seedRegistryTool("demo", "wacli");
        SkillPromotionService.copyToAgentWorkspace(agent, "demo");

        assertEquals(1, AgentSkillAllowedTool.findByAgentAndSkill(agent, "demo").size());

        // Explicit skill removal from agent workspace → revoke allowlist rows
        SkillPromotionService.revokeAgentAllowlist(agent, "demo");

        assertEquals(0, AgentSkillAllowedTool.findByAgentAndSkill(agent, "demo").size(),
                "removing the skill from an agent must drop its allowlist rows");
        assertNotNull(tool.validateAllowlist("wacli", agent),
                "after skill removal the command is no longer in the effective allowlist");
    }

    @Test
    public void skillCreatorCapabilityGate() throws Exception {
        // Agent does not have skill-creator in its workspace → capability denied
        assertFalse(SkillPromotionService.hasSkillCreatorCapability(agent),
                "agent without skill-creator in workspace must not have promote capability");

        // Install skill-creator for this agent
        var skillDir = globalSkillsDir.resolve("skill-creator");
        writeSkillMd(skillDir, "skill-creator"); // no commands — prompt-only skill
        SkillPromotionService.copyToAgentWorkspace(agent, "skill-creator");
        SkillLoader.clearCache();

        assertTrue(SkillPromotionService.hasSkillCreatorCapability(agent),
                "agent with skill-creator installed (and no disable row) must have capability");

        // Explicitly disable it → capability lost
        var cfg = new AgentSkillConfig();
        cfg.agent = agent;
        cfg.skillName = "skill-creator";
        cfg.enabled = false;
        cfg.save();

        assertFalse(SkillPromotionService.hasSkillCreatorCapability(agent),
                "disabling skill-creator must remove promote capability");
    }

    @Test
    public void commandsFrontmatterIsParsed() throws Exception {
        var skillDir = globalSkillsDir.resolve("demo");
        writeSkillMd(skillDir, "demo", "foo", "bar", "baz");

        var info = SkillLoader.parseSkillFile(skillDir.resolve("SKILL.md"));
        assertNotNull(info);
        assertEquals(java.util.List.of("foo", "bar", "baz"), info.commands(),
                "commands: frontmatter must parse into SkillInfo.commands");
    }
}
