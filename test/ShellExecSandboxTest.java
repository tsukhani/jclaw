import models.Agent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import tools.HarnessSandbox;
import tools.ShellExecTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JCLAW-1153: {@code exec}'s opt-in OS sandbox — the tri-state is its own key, the
 * shipped default leaves the launch unwrapped, an unavailable mechanism fails closed,
 * and (on macOS, with sandbox-exec present) a real confined run denies the write the
 * allowlist cannot: {@code echo hi; …} still runs both statements, but the second one
 * can no longer land outside the workspace.
 */
class ShellExecSandboxTest extends UnitTest {

    private static final String AGENT = "shell-sandbox-agent";

    private ShellExecTool tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        ShellSandboxSync.acquire();
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        tool = new ShellExecTool();
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
        ConfigService.set("shell.allowlist", "echo,cat,ls");
    }

    @AfterEach
    void teardown() {
        ShellSandboxSync.release();
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath(AGENT));
    }

    /** The shell key is a tri-state of its own — confining a coding harness is a
     *  separate decision from confining every shell command, so neither key may
     *  move the other's scope. */
    @Test
    void shellSandboxIsATriStateIndependentOfTheHarnessKey() {
        assertEquals(HarnessSandbox.Scope.OFF, HarnessSandbox.scope(HarnessSandbox.SHELL_SANDBOX_KEY),
                "an unset key is the shipped false default");

        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        assertEquals(HarnessSandbox.Scope.ALL, HarnessSandbox.scope(HarnessSandbox.SHELL_SANDBOX_KEY));
        assertEquals(HarnessSandbox.Scope.OFF, HarnessSandbox.scope(),
                "shell.sandbox must not confine acp runs");

        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "untrusted");
        assertEquals(HarnessSandbox.Scope.UNTRUSTED, HarnessSandbox.scope(HarnessSandbox.SHELL_SANDBOX_KEY));

        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        try {
            assertEquals(HarnessSandbox.Scope.UNTRUSTED,
                    HarnessSandbox.scope(HarnessSandbox.SHELL_SANDBOX_KEY),
                    "subagent.acp.sandbox must not confine shell runs");
        } finally {
            ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "");
        }
    }

    /** The shipped default is off: exec runs unwrapped, exactly as before JCLAW-1153. */
    @Test
    void offByDefaultRunsUnwrapped() {
        assertEquals(List.of("/bin/sh", "-c", "echo hi"),
                HarnessSandbox.wrap(List.of("/bin/sh", "-c", "echo hi"),
                        AgentService.workspacePath(AGENT).toFile(), List.of(),
                        HarnessSandbox.SHELL_SANDBOX_KEY, true),
                "an unset shell.sandbox must be a passthrough");

        var result = tool.execute("""
                {"command": "echo hi"}
                """, agent);
        assertTrue(result.contains("hi"), "got: " + result);
        assertTrue(result.contains("\"exitCode\":0"), "got: " + result);
    }

    /** Fail closed: with the flag on and no resolvable write root the launch throws
     *  rather than falling back to an unconfined process. This is the cross-platform
     *  half of the mechanism check — the macOS run below is the other half. */
    @Test
    void enabledButNoWriteRootFailsClosed() {
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                () -> HarnessSandbox.wrap(List.of("/bin/sh", "-c", "echo hi"), null, List.of(),
                        HarnessSandbox.SHELL_SANDBOX_KEY, true));
        assertTrue(e.getMessage().contains("session working directory"), e.getMessage());
    }

    /** {@code untrusted} confines only untrusted-origin runs: a trusted origin passes
     *  through even with the mode on, an untrusted one takes the confine path (proven
     *  cross-platform by the fail-closed guard the null write root trips). */
    @Test
    void untrustedModeConfinesOnlyUntrustedOrigin() {
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "untrusted");
        var argv = List.of("/bin/sh", "-c", "echo hi");
        assertEquals(argv, HarnessSandbox.wrap(argv, AgentService.workspacePath(AGENT).toFile(),
                List.of(), HarnessSandbox.SHELL_SANDBOX_KEY, true));
        assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                () -> HarnessSandbox.wrap(argv, null, List.of(),
                        HarnessSandbox.SHELL_SANDBOX_KEY, false));
    }

    /**
     * The acceptance criterion, run for real: {@code echo hi; echo … > <outside>} passes
     * the allowlist on its first token and still executes both statements, but with the
     * sandbox on the second one cannot land. "Outside" must be a genuinely-denied path —
     * NOT the temp tree, which the profile grants for TMPDIR — so target the home root.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void sandboxDeniesTheWriteTheAllowlistCannot() throws IOException {
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        var outside = Path.of(System.getProperty("user.home"), "jclaw-shell-sbx-escape-probe.txt");
        Files.deleteIfExists(outside);
        try {
            var result = tool.execute("""
                    {"command": "echo hi; echo escaped > %s; echo done"}
                    """.formatted(outside), agent);

            assertTrue(result.contains("hi"), "the allowed first statement still runs; got: " + result);
            assertTrue(result.contains("done"), "shell composition still runs; got: " + result);
            assertFalse(Files.exists(outside),
                    "the write outside the workspace must be denied by the sandbox");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** The confinement is a boundary, not a blanket denial: the workspace stays writable,
     *  so ordinary agent work (build output, scratch files) is unaffected by turning it on. */
    @Test
    @EnabledOnOs(OS.MAC)
    void sandboxStillAllowsWritesInsideTheWorkspace() {
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        var result = tool.execute("""
                {"command": "echo inside > kept.txt; cat kept.txt"}
                """, agent);
        assertTrue(result.contains("inside"), "workspace write must survive the sandbox; got: " + result);
        assertTrue(Files.exists(AgentService.workspacePath(AGENT).resolve("kept.txt")),
                "the file must exist on disk, not just echo back; got: " + result);
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) { /* best-effort */ }
            });
        } catch (IOException _) { /* best-effort */ }
    }
}
