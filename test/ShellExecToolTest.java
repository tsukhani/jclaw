import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import services.ConfigService;
import tools.ShellExecTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ShellExecToolTest extends UnitTest {

    private ShellExecTool tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        tool = new ShellExecTool();
        agent = AgentService.create("shell-test-agent", "openrouter", "gpt-4.1", null);
        // Seed allowlist
        ConfigService.set("shell.allowlist", "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("shell-test-agent"));
        deleteDir(AgentService.workspacePath("main"));
    }

    // ==================== Allowlist Validation ====================

    @Test
    public void allowedCommandPasses() {
        var error = tool.validateAllowlist("echo hello");
        assertNull(error);
    }

    @Test
    public void blockedCommandRejected() {
        var error = tool.validateAllowlist("rm -rf /");
        assertNotNull(error);
        assertTrue(error.contains("not in the allowed commands list"));
        assertTrue(error.contains("rm"));
    }

    @Test
    public void pipeChainValidatesFirstCommandOnly() {
        var error = tool.validateAllowlist("git log | head -20");
        assertNull(error);
    }

    @Test
    public void emptyCommandRejected() {
        var error = tool.validateAllowlist("   ");
        assertNotNull(error);
    }

    @Test
    public void commandWithLeadingWhitespace() {
        var error = tool.validateAllowlist("  echo hello");
        assertNull(error);
    }

    @Test
    public void singleWordCommand() {
        var error = tool.validateAllowlist("ls");
        assertNull(error);
    }

    // ==================== Working Directory Resolution ====================

    @Test
    public void defaultWorkspaceDir() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("{}").getAsJsonObject();
        var resolved = tool.resolveWorkdir(args, workspace, false, agent.name);
        assertEquals(workspace, resolved);
    }

    @Test
    public void relativeSubdirectory() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "skills"}
                """).getAsJsonObject();
        var resolved = tool.resolveWorkdir(args, workspace, false, agent.name);
        assertEquals(workspace.resolve("skills"), resolved);
    }

    @Test
    public void pathTraversalBlocked() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "../../etc"}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> tool.resolveWorkdir(args, workspace, false, agent.name));
    }

    @Test
    public void absolutePathBlockedByDefault() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "/tmp"}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> tool.resolveWorkdir(args, workspace, false, agent.name));
    }

    @Test
    public void shellWorkdirSymlinkEscapeBlocked() throws Exception {
        // A symlink inside the workspace pointing to an outside directory used
        // to pass the textual containment check (the symlink's lexical path
        // stayed inside the workspace) and ProcessBuilder would happily follow
        // it. The canonical (toRealPath) layer in acquireContained rejects it.
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        var outside = Files.createTempDirectory("jclaw-shell-symlink-");
        var link = workspace.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
            var args = com.google.gson.JsonParser.parseString("""
                    {"workdir": "escape"}
                    """).getAsJsonObject();
            assertThrows(IllegalArgumentException.class,
                    () -> tool.resolveWorkdir(args, workspace, false, agent.name));
        } finally {
            Files.deleteIfExists(link);
            Files.walk(outside).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    // ==================== Environment Filtering ====================

    @Test
    public void sensitiveVarsByNamePattern() {
        assertTrue(ShellExecTool.isSensitiveEnvVar("OPENAI_API_KEY"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("my_secret_value"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("DB_PASSWORD"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("AUTH_TOKEN"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("AWS_ACCESS_KEY_ID"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("ANTHROPIC_API_KEY"));
    }

    @Test
    public void nonSensitiveVarsPass() {
        assertFalse(ShellExecTool.isSensitiveEnvVar("PATH"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("HOME"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("LANG"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("SHELL"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("USER"));
    }

    @Test
    public void customSensitiveVarsBlocked() {
        var args = com.google.gson.JsonParser.parseString("""
                {"env": {"OPENAI_API_KEY": "injected", "MY_VAR": "hello"}}
                """).getAsJsonObject();
        var env = tool.buildEnvironment(args);
        assertFalse(env.containsKey("OPENAI_API_KEY"));
        assertEquals("hello", env.get("MY_VAR"));
    }

    // ==================== End-to-End Execution ====================

    @Test
    public void basicEchoCommand() {
        var result = tool.execute("""
                {"command": "echo hello"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":0"));
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("\"timedOut\":false"));
    }

    @Test
    public void nonZeroExitCode() {
        var result = tool.execute("""
                {"command": "exit 42"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":42"));
        assertTrue(result.contains("\"timedOut\":false"));
    }

    @Test
    public void blockedCommandReturnsError() {
        var result = tool.execute("""
                {"command": "rm -rf /"}
                """, agent);
        assertTrue(result.contains("not in the allowed commands list"));
    }

    @Test
    public void emptyCommandReturnsError() {
        var result = tool.execute("""
                {"command": "  "}
                """, agent);
        assertTrue(result.contains("Error"));
    }

    @Test
    public void timeoutKillsProcess() {
        var result = tool.execute("""
                {"command": "sleep 30", "timeout": 1}
                """, agent);
        assertTrue(result.contains("\"timedOut\":true"));
        assertTrue(result.contains("\"exitCode\":-1"));
        assertTrue(result.contains("timeout after 1 seconds"));
    }

    @Test
    public void pwdReturnsWorkspace() {
        var result = tool.execute("""
                {"command": "pwd"}
                """, agent);
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize().toString();
        assertTrue(result.contains(workspace));
    }

    @Test
    public void customEnvVarPassed() {
        var result = tool.execute("""
                {"command": "printenv MY_VAR", "env": {"MY_VAR": "test_value"}}
                """, agent);
        assertTrue(result.contains("test_value"));
        assertTrue(result.contains("\"exitCode\":0"));
    }

    @Test
    public void pipelineExecution() {
        var result = tool.execute("""
                {"command": "echo 'line1\\nline2\\nline3' | wc -l"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":0"));
    }

    // ==================== Main-Agent Privilege Escapes ====================

    @Test
    public void mainAgentBypassesAllowlistWhenConfigured() {
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1", null);
        ConfigService.set("agent.main.shell.bypassAllowlist", "true");

        // whoami is NOT in the allowlist; with bypass on, it must slip past the allowlist check
        var result = tool.execute("""
                {"command": "whoami"}
                """, mainAgent);
        assertFalse(result.contains("not in the allowed commands list"),
                "Main agent with bypassAllowlist=true must not hit the allowlist error");
    }

    @Test
    public void nonMainAgentIgnoresBypassAllowlistConfigRow() {
        // An orphaned/out-of-band Config row for a non-main agent must have no effect:
        // the identity check must short-circuit before the Config is consulted.
        ConfigService.set("agent.shell-test-agent.shell.bypassAllowlist", "true");

        var result = tool.execute("""
                {"command": "whoami"}
                """, agent);
        assertTrue(result.contains("not in the allowed commands list"),
                "Non-main agent must be rejected by the allowlist regardless of any Config row");
    }

    @Test
    public void mainAgentUsesAbsoluteWorkdirWhenConfigured() {
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1", null);
        ConfigService.set("agent.main.shell.allowGlobalPaths", "true");

        var result = tool.execute("""
                {"command": "pwd", "workdir": "/tmp"}
                """, mainAgent);
        assertTrue(result.contains("\"exitCode\":0"));
        // macOS symlinks /tmp to /private/tmp; Linux returns /tmp directly
        assertTrue(result.contains("/tmp"));
    }

    @Test
    public void nonMainAgentIgnoresAllowGlobalPathsConfigRow() {
        ConfigService.set("agent.shell-test-agent.shell.allowGlobalPaths", "true");

        var result = tool.execute("""
                {"command": "pwd", "workdir": "/tmp"}
                """, agent);
        assertTrue(result.contains("must be within the agent workspace"),
                "Non-main agent must stay sandboxed regardless of any Config row");
    }

    @Test
    public void mainAgentWithoutPrivilegeConfigStillRejected() {
        // Main agent without the bypass config set: must still hit the allowlist
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1", null);

        var result = tool.execute("""
                {"command": "whoami"}
                """, mainAgent);
        assertTrue(result.contains("not in the allowed commands list"),
                "Main agent must only bypass when the Config row is actually set to true");
    }

    // ==================== Helpers ====================

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
