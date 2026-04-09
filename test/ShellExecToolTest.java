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
        agent = AgentService.create("shell-test-agent", "openrouter", "gpt-4.1", false, null);
        // Seed allowlist
        ConfigService.set("shell.allowlist", "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("shell-test-agent"));
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
        var resolved = tool.resolveWorkdir(args, workspace, false);
        assertEquals(workspace, resolved);
    }

    @Test
    public void relativeSubdirectory() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "skills"}
                """).getAsJsonObject();
        var resolved = tool.resolveWorkdir(args, workspace, false);
        assertEquals(workspace.resolve("skills"), resolved);
    }

    @Test
    public void pathTraversalBlocked() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "../../etc"}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> tool.resolveWorkdir(args, workspace, false));
    }

    @Test
    public void absolutePathBlockedByDefault() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "/tmp"}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> tool.resolveWorkdir(args, workspace, false));
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
