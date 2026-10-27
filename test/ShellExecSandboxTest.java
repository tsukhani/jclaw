import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import play.Play;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import tools.HarnessSandbox;
import tools.ShellExecTool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JCLAW-1153: {@code exec}'s opt-in OS sandbox — the tri-state is its own key, the
 * shipped default leaves the launch unwrapped, an unavailable mechanism fails closed,
 * and (on macOS, with sandbox-exec present) a real confined run denies the write the
 * allowlist cannot: {@code echo hi; …} still runs both statements, but the second one
 * can no longer land outside the workspace.
 */
class ShellExecSandboxTest extends UnitTest {

    private static final String AGENT = "shell-sandbox-agent";
    private static final String NETWORK_KEY = HarnessSandbox.SHELL_NETWORK_KEY;

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
        ConfigService.set(NETWORK_KEY, "");
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

    /**
     * JCLAW-1452 AC1: a confined {@code exec} cannot read the app root's data/, certs/ or logs/ —
     * the real directories, not the temp tree. Sentinels of the test's own stand in for the H2
     * file and the log, which a test instance does not have; certs/.env and data/jclaw.mv.db are
     * checked too when present, for readability only, so their contents never reach the result.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void sandboxHidesJClawStateFromExec() throws IOException {
        var appRoot = Play.applicationPath.toPath();
        var secret = "jclaw-1452-secret-" + UUID.randomUUID();
        var sentinels = new ArrayList<Path>();
        try {
            for (var dir : List.of("data", "certs", "logs")) {
                var sentinel = Files.createDirectories(appRoot.resolve(dir))
                        .resolve("jclaw-1452-sentinel-" + UUID.randomUUID() + ".txt");
                Files.writeString(sentinel, secret);
                sentinels.add(sentinel);
            }
            var files = String.join(" ", sentinels.stream().map(s -> "'" + s + "'").toList());
            var reads = "cat %s 2>/dev/null || echo STATE_DENIED".formatted(files);
            var real = new ArrayList<Path>();
            for (var file : List.of(appRoot.resolve("certs/.env"), appRoot.resolve("data/jclaw.mv.db"))) {
                if (Files.isReadable(file)) real.add(file);
            }
            var probe = new StringBuilder(reads);
            for (int i = 0; i < real.size(); i++) {
                // One byte proves the read; on the live checkout the H2 file is over a gigabyte.
                probe.append("; head -c 1 '%s' >/dev/null 2>&1 && echo READABLE_%d || echo DENIED_%d".formatted(real.get(i), i, i));
            }
            var args = new JsonObject();
            args.addProperty("command", probe.toString());
            var command = args.toString();

            var control = tool.execute(command, agent);
            assertTrue(control.contains(secret), "unconfined, exec must reach the sentinels or the test proves nothing; got: " + control);
            for (int i = 0; i < real.size(); i++) {
                assertTrue(control.contains("READABLE_" + i), "unconfined, exec must read " + real.get(i) + "; got: " + control);
            }

            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
            var confined = tool.execute(command, agent);
            assertFalse(confined.contains(secret), "data/, certs/ and logs/ must be unreadable; got: " + confined);
            assertTrue(confined.contains("STATE_DENIED"), "got: " + confined);
            for (int i = 0; i < real.size(); i++) {
                assertTrue(confined.contains("DENIED_" + i), real.get(i) + " must be unreadable; got: " + confined);
            }
        } finally {
            for (var s : sentinels) Files.deleteIfExists(s);
        }
    }

    /** Runs exec, sandboxed under {@code network}, connecting to {@code server} and writing in the workspace; returns its output. */
    private String connectFromTheSandbox(ServerSocket server, String network) {
        ConfigService.set("shell.allowlist", "nc,echo,cat");
        // Set right before the run: another class's table wipe can clear a key.
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        ConfigService.set(NETWORK_KEY, network);
        var args = new JsonObject();
        args.addProperty("command", ("/usr/bin/nc -z -w 2 127.0.0.1 %d 2>/dev/null && echo NET_CONNECTED || echo NET_BLOCKED; "
                + "echo inside > kept.txt; cat kept.txt").formatted(server.getLocalPort()));
        var result = tool.execute(args.toString(), agent);
        return JsonParser.parseString(result).getAsJsonObject().get("output").getAsString();
    }

    /**
     * With the network denied, a confined exec cannot reach even a loopback server, so it cannot ship
     * the workspace out with curl or python; the workspace stays writable.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void networkDenyRefusesALoopbackConnectionAndKeepsTheWorkspaceWritable() throws IOException {
        try (var server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            assertEquals("NET_BLOCKED\ninside", connectFromTheSandbox(server, "deny"));
            server.setSoTimeout(500);
            assertThrows(SocketTimeoutException.class, server::accept, "no connection may reach the server");
            assertEquals("inside\n", Files.readString(AgentService.workspacePath(AGENT).resolve("kept.txt")));
        }
    }

    /** With the network allowed, the same confined run reaches the server, as before the key existed. */
    @Test
    @EnabledOnOs(OS.MAC)
    void networkAllowKeepsTheLoopbackConnection() throws IOException {
        try (var server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            assertEquals("NET_CONNECTED\ninside", connectFromTheSandbox(server, "allow"));
            server.setSoTimeout(5_000);
            try (var accepted = server.accept()) {
                assertEquals(InetAddress.getLoopbackAddress(), accepted.getInetAddress());
            }
        }
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
