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
import tools.ShellExecTool.Confinement;
import utils.ToolErrorTemplates;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JCLAW-1153 and JF-165: {@code exec}'s OS sandbox. {@code main} and its copies follow the
 * {@code shell.sandbox} tri-state, whose shipped default leaves the launch unwrapped; every other
 * agent is always confined, refused where no sandbox can start, and on macOS cannot read the home
 * folder outside its workspace. {@code echo hi; …} still runs both statements, but the second one can
 * no longer land outside the workspace.
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
        deleteDir(HarnessSandbox.customAgentTempDir(AgentService.workspacePath(AGENT).toFile()));
    }

    /**
     * {@code main}, unsaved: it is provisioned by code and {@code Fixtures.deleteDatabase} has removed its row.
     * The id matches no row, so the allowlist is the global one.
     */
    private static Agent main() {
        var main = new Agent();
        main.id = Long.MAX_VALUE;
        main.name = Agent.MAIN_AGENT_NAME;
        return main;
    }

    private static Agent unsaved(String name, Agent parent) {
        var agent = new Agent();
        agent.name = name;
        agent.parentAgent = parent;
        return agent;
    }

    private JsonObject run(Agent who, String command) {
        var args = new JsonObject();
        args.addProperty("command", command);
        return JsonParser.parseString(tool.execute(args.toString(), who)).getAsJsonObject();
    }

    private static Path outsideProbe() {
        return Path.of(System.getProperty("user.home"), "jclaw-jf165-" + UUID.randomUUID() + ".txt");
    }

    /** On Linux, a host whose bwrap cannot create namespaces (this build host's) runs none of the real-run tests. */
    private static void assumeWorkingSandbox() {
        var availability = HarnessSandbox.availability();
        assumeTrue(availability.available(), "no working sandbox here: " + availability.reason());
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

        var result = run(main(), "echo hi");
        assertEquals("hi", result.get("output").getAsString(), result.toString());
        assertEquals(0, result.get("exitCode").getAsInt(), result.toString());
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

    /** uv, npm and pip keep caches under the home folder, which a confined run cannot write; its caches go to its workspace. */
    @Test
    @EnabledOnOs(OS.MAC)
    void aConfinedRunKeepsToolCachesInItsWorkspace() {
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        var cache = AgentService.workspacePath(AGENT).toAbsolutePath().normalize().resolve(".cache");
        var result = JsonParser.parseString(tool.execute("""
                {"command": "echo \\"$XDG_CACHE_HOME|$UV_CACHE_DIR|$npm_config_cache|$PIP_CACHE_DIR\\"; mkdir -p \\"$UV_CACHE_DIR\\" && echo cached > \\"$UV_CACHE_DIR/probe\\""}
                """, agent)).getAsJsonObject();

        assertEquals("%s|%s|%s|%s".formatted(cache, cache.resolve("uv"), cache.resolve("npm"), cache.resolve("pip")),
                result.get("output").getAsString(), result.toString());
        assertTrue(Files.exists(cache.resolve("uv").resolve("probe")),
                "a write to the uv cache must land in the workspace; got: " + result);

        // main's key-confined run redirects its caches to its own workspace the same way.
        var mainCache = AgentService.workspacePath(Agent.MAIN_AGENT_NAME).toAbsolutePath().normalize().resolve(".cache");
        var mainResult = JsonParser.parseString(tool.execute("""
                {"command": "echo \\"$XDG_CACHE_HOME|$UV_CACHE_DIR|$npm_config_cache|$PIP_CACHE_DIR\\""}
                """, main())).getAsJsonObject();
        assertEquals("%s|%s|%s|%s".formatted(mainCache, mainCache.resolve("uv"), mainCache.resolve("npm"),
                mainCache.resolve("pip")), mainResult.get("output").getAsString(), mainResult.toString());
    }

    /** A hidden home makes git's read of ~/.gitconfig fail fatally on macOS, so a custom agent has no global git config; main keeps its own. */
    @Test
    void aCustomAgentHasNoGlobalGitConfigAndMainKeepsItsOwn() {
        HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.PASSTHROUGH);
        var command = """
                {"command": "echo \\"$GIT_CONFIG_GLOBAL|$XDG_CONFIG_HOME\\""}
                """;
        var config = AgentService.workspacePath(AGENT).toAbsolutePath().normalize().resolve(".config");
        var custom = JsonParser.parseString(tool.execute(command, agent)).getAsJsonObject();
        assertEquals("/dev/null|" + config, custom.get("output").getAsString(), custom.toString());

        var host = System.getenv();
        var mainResult = JsonParser.parseString(tool.execute(command, main())).getAsJsonObject();
        assertEquals(host.getOrDefault("GIT_CONFIG_GLOBAL", "") + "|" + host.getOrDefault("XDG_CONFIG_HOME", ""),
                mainResult.get("output").getAsString(), mainResult.toString());
    }

    /** An unconfined run keeps the host's cache locations, so it shares the caches it always used. */
    @Test
    void anUnconfinedRunKeepsTheHostCacheLocations() {
        var result = JsonParser.parseString(tool.execute("""
                {"command": "echo \\"$XDG_CACHE_HOME|$UV_CACHE_DIR|$npm_config_cache|$PIP_CACHE_DIR\\""}
                """, main())).getAsJsonObject();

        var host = System.getenv();
        assertEquals("%s|%s|%s|%s".formatted(host.getOrDefault("XDG_CACHE_HOME", ""),
                        host.getOrDefault("UV_CACHE_DIR", ""), host.getOrDefault("npm_config_cache", ""),
                        host.getOrDefault("PIP_CACHE_DIR", "")),
                result.get("output").getAsString(), result.toString());
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

            // The Main Agent's own runs are fenced (JF-325); the override gives the one unconfined control run.
            String control;
            HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.PASSTHROUGH);
            try {
                control = tool.execute(command, main());
            } finally {
                HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.NONE);
            }
            assertTrue(control.contains(secret), "unconfined, exec must reach the sentinels or the test proves nothing; got: " + control);
            for (int i = 0; i < real.size(); i++) {
                assertTrue(control.contains("READABLE_" + i), "unconfined, exec must read " + real.get(i) + "; got: " + control);
            }

            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
            var confined = tool.execute(command, main());
            assertFalse(confined.contains(secret), "data/, certs/ and logs/ must be unreadable; got: " + confined);
            assertTrue(confined.contains("STATE_DENIED"), "got: " + confined);
            for (int i = 0; i < real.size(); i++) {
                assertTrue(confined.contains("DENIED_" + i), real.get(i) + " must be unreadable; got: " + confined);
            }
        } finally {
            for (var s : sentinels) Files.deleteIfExists(s);
        }
    }

    private static final List<String> KEY_VALUES = List.of("", "false", "true", "untrusted");

    /** Every agent whose chain does not end at main is confined whatever the key and the origin. */
    @Test
    void aCustomAgentAndItsCopiesAreAlwaysConfined() {
        var root = unsaved("custom-root", null);
        var child = unsaved("custom-root-sub-1", root);
        var grandchild = unsaved("custom-root-sub-1-sub-2", child);
        for (var value : KEY_VALUES) {
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, value);
            for (var trusted : List.of(true, false)) {
                for (var who : List.of(root, child, grandchild)) {
                    assertEquals(Confinement.CUSTOM_AGENT, ShellExecTool.confinement(who, trusted),
                            who.name + " under shell.sandbox='" + value + "', trusted=" + trusted);
                }
            }
        }
    }

    /** main and a copy of main follow the key exactly: unset and false never confine, untrusted only an untrusted origin. */
    @Test
    void mainAndItsCopiesFollowTheKey() {
        var main = main();
        var copy = unsaved("main-sub-abc", main);
        for (var who : List.of(main, copy)) {
            for (var trusted : List.of(true, false)) {
                for (var value : KEY_VALUES) {
                    ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, value);
                    var expected = switch (value) {
                        case "true" -> Confinement.KEY;
                        case "untrusted" -> trusted ? Confinement.FENCE : Confinement.KEY;
                        default -> Confinement.FENCE;
                    };
                    assertEquals(expected, ShellExecTool.confinement(who, trusted),
                            who.name + " under shell.sandbox='" + value + "', trusted=" + trusted);
                }
            }
        }
    }

    /** The parent walk stops after 32 hops: a chain that reaches main within them is main's, one longer is custom. */
    @Test
    void aParentChainLongerThanTheWalkIsConfined() {
        var reachesMain = main();
        for (int i = 0; i < 32; i++) reachesMain = unsaved("main-sub-" + i, reachesMain);
        assertEquals(Confinement.FENCE, ShellExecTool.confinement(reachesMain, true), "32 hops reach main");

        var tooLong = unsaved("main-sub-extra", reachesMain);
        assertEquals(Confinement.CUSTOM_AGENT, ShellExecTool.confinement(tooLong, true),
                "33 hops end at an agent with a parent");
    }

    /** No sandbox here: a custom agent's command is refused and nothing runs or stays behind; main runs. */
    @Test
    void withNoSandboxEveryAgentsCommandIsRefused() throws IOException {
        HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.unavailable("test: no sandbox"));
        var workspace = AgentService.workspacePath(AGENT);
        var args = new JsonObject();
        args.addProperty("command", "echo x > marker");

        var result = tool.execute(args.toString(), agent);

        assertEquals("Error: `echo x > marker` did not run: every agent runs shell commands inside the OS sandbox, the"
                        + " Main Agent's fenced from JClaw's own files, and this host cannot start one (test: no sandbox).\n\n"
                        + "What to check: JClaw refuses the command rather than run it unconfined.\n\n"
                        + "How to retry: Do not retry. Tell the operator that this host's sandbox is not working.",
                result);
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.shellSandboxUnavailable("echo x > marker",
                "test: no sandbox")), result);
        assertFalse(Files.exists(workspace.resolve("marker")), "the command must not have run");
        try (var left = Files.list(workspace.resolve(".exec"))) {
            assertEquals(List.of(), left.toList(), "no output file may stay behind");
        }

        // JF-325: the Main Agent's shell is fenced from JClaw's own files, and where no sandbox starts it fails closed too.
        var args2 = new JsonObject();
        args2.addProperty("command", "echo hi");
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.shellSandboxUnavailable("echo hi", "test: no sandbox")),
                tool.execute(args2.toString(), main()));
    }

    /** The background form is refused the same way, naming the command the model sent. */
    @Test
    void withNoSandboxABackgroundCommandIsRefusedToo() {
        HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.unavailable("test: no sandbox"));
        var result = tool.execute("""
                {"command": "echo hi", "background": true}
                """, agent);
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.shellSandboxUnavailable("echo hi",
                "test: no sandbox")), result);
    }

    /**
     * main keeps today's behaviour under its key whatever the custom-agent override says: where no sandbox
     * starts, its exec throws the same SandboxUnavailableException; where one does, the argv is the one the
     * real sandbox builds.
     */
    @Test
    void mainIgnoresTheCustomAgentOverride() {
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        var argv = List.of("/bin/sh", "-c", "echo hi");
        var root = AgentService.workspacePath(AGENT).toFile();
        if (!HarnessSandbox.availability().available()) {
            var unaided = assertThrows(HarnessSandbox.SandboxUnavailableException.class, () -> run(main(), "echo hi"));
            for (var override : List.of(HarnessSandbox.CustomAgentOverride.unavailable("test: no sandbox"),
                    HarnessSandbox.CustomAgentOverride.PASSTHROUGH)) {
                HarnessSandbox.setCustomAgentOverrideForTest(override);
                var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class, () -> run(main(), "echo hi"),
                        "override " + override);
                assertEquals(unaided.getMessage(), e.getMessage(), "override " + override);
            }
            return;
        }
        var real = HarnessSandbox.wrap(argv, root, List.of(), HarnessSandbox.SHELL_SANDBOX_KEY, true);
        for (var override : List.of(HarnessSandbox.CustomAgentOverride.unavailable("test: no sandbox"),
                HarnessSandbox.CustomAgentOverride.PASSTHROUGH)) {
            HarnessSandbox.setCustomAgentOverrideForTest(override);
            assertEquals(real, HarnessSandbox.wrap(argv, root, List.of(), HarnessSandbox.SHELL_SANDBOX_KEY, true),
                    "override " + override);
        }
    }

    /** With no override, a host whose sandbox cannot start refuses a custom agent's command with the probe's reason. */
    @Test
    void theRealReadinessProbeRefusesACustomAgent() {
        var availability = HarnessSandbox.availability();
        assumeFalse(availability.available(), "this host has a working sandbox");
        var args = new JsonObject();
        args.addProperty("command", "echo x > marker");

        var result = tool.execute(args.toString(), agent);

        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.shellSandboxUnavailable("echo x > marker",
                HarnessSandbox.availability().reason())), result);
        assertFalse(Files.exists(AgentService.workspacePath(AGENT).resolve("marker")), "the command must not have run");
    }

    /** With shell.sandbox unset, a custom agent's write outside its workspace never lands; one inside it does. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentWritesOnlyInsideItsWorkspace() throws IOException {
        assumeWorkingSandbox();
        assertWritesOnlyInside(agent, AgentService.workspacePath(AGENT));
    }

    /** A subagent_spawn copy of a custom agent is confined the same way, to its root's workspace. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCopyOfACustomAgentWritesOnlyInsideItsRootsWorkspace() throws IOException {
        assumeWorkingSandbox();
        var child = AgentService.create(AGENT + "-sub-1", "openrouter", "gpt-4.1", null, null, false, agent);
        assertWritesOnlyInside(child, AgentService.workspacePath(AGENT));
    }

    private void assertWritesOnlyInside(Agent who, Path workspace) throws IOException {
        var outside = outsideProbe();
        var kept = workspace.resolve("kept-" + UUID.randomUUID() + ".txt");
        try {
            var result = run(who, "echo escaped 2>/dev/null > '%s'; echo inside > '%s'; cat '%s'"
                    .formatted(outside, kept.getFileName(), kept.getFileName()));
            assertEquals("inside", result.get("output").getAsString(), result.toString());
            assertFalse(Files.exists(outside), "the write outside the workspace must not land: " + result);
            assertEquals("inside\n", Files.readString(kept));
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(kept);
        }
    }

    /** A custom agent's ls of JClaw's data/ and certs/ shows none of their files; main's unconfined ls does. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentCannotListJClawState() throws IOException {
        assumeWorkingSandbox();
        var appRoot = Play.applicationPath.toPath();
        var sentinels = new ArrayList<Path>();
        try {
            for (var dir : List.of("data", "certs")) {
                var sentinel = Files.createDirectories(appRoot.resolve(dir))
                        .resolve("jclaw-jf165-sentinel-" + UUID.randomUUID() + ".txt");
                Files.writeString(sentinel, "x");
                sentinels.add(sentinel);
            }
            var command = "ls '%s' '%s' 2>&1; true".formatted(appRoot.resolve("data"), appRoot.resolve("certs"));

            String control;
            HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.PASSTHROUGH);
            try {
                control = run(main(), command).get("output").getAsString();
            } finally {
                HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.NONE);
            }
            for (var s : sentinels) {
                assertTrue(control.contains(s.getFileName().toString()), "unconfined, ls must list " + s + ": " + control);
            }
            // JF-325: the Main Agent's own run is fenced from both folders.
            var fenced = run(main(), command).get("output").getAsString();
            for (var s : sentinels) assertFalse(fenced.contains(s.getFileName().toString()), "fenced, main listed " + s);
            var confined = run(agent, command).get("output").getAsString();
            for (var s : sentinels) {
                assertFalse(confined.contains(s.getFileName().toString()), s + " must be hidden: " + confined);
            }
        } finally {
            for (var s : sentinels) Files.deleteIfExists(s);
        }
    }

    /** A file under the home folder outside the workspace, deleted with its folder by the caller. */
    private static Path homeFile(String content) throws IOException {
        var dir = Files.createDirectories(Path.of(System.getProperty("user.home"), "jclaw-jf165-" + UUID.randomUUID()));
        return Files.writeString(dir.resolve("secret.txt"), content);
    }

    /**
     * macOS hides the home folder from a custom agent except its workspace and JClaw's PATH directories,
     * so the toolchain still runs: its workspace, ls, true, git and python3 work, a home file does not.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacACustomAgentCannotReadTheHomeFolderButItsToolsRun() throws IOException {
        ConfigService.set("shell.allowlist", "echo,cat,ls,true,git");
        var secret = homeFile("jf165-home-secret");
        try {
            assertEquals("HOME_DENIED", run(agent, "cat '%s' 2>/dev/null || echo HOME_DENIED".formatted(secret))
                    .get("output").getAsString());

            Files.writeString(AgentService.workspacePath(AGENT).resolve("ws.txt"), "ws-readable");
            assertEquals("ws-readable", run(agent, "cat ws.txt").get("output").getAsString());
            assertEquals(0, run(agent, "ls").get("exitCode").getAsInt());
            assertEquals(0, run(agent, "/usr/bin/true").get("exitCode").getAsInt());
            for (var probe : List.of("git --version")) {
                var unconfined = run(main(), probe);
                assumeTrue(unconfined.get("exitCode").getAsInt() == 0, probe + " does not run here: " + unconfined);
                var confined = run(agent, probe);
                assertEquals(0, confined.get("exitCode").getAsInt(), probe + ": " + confined);
            }
        } finally {
            deleteDir(secret.getParent());
        }
    }

    /** A folder the operator lists in shell.sandbox.homeReads is readable to a custom agent; one they do not list is not. */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacAListedHomeFolderIsReadableAndAnUnlistedOneIsNot() throws IOException {
        ConfigService.set("shell.allowlist", "echo,cat");
        var listed = homeFile("jf165-listed");
        var unlisted = homeFile("jf165-unlisted");
        try {
            ConfigService.set(HarnessSandbox.HOME_READS_KEY, listed.getParent().toString());
            assertEquals("jf165-listed", run(agent, "cat '%s' 2>/dev/null || echo HOME_DENIED".formatted(listed))
                    .get("output").getAsString());
            assertEquals("HOME_DENIED", run(agent, "cat '%s' 2>/dev/null || echo HOME_DENIED".formatted(unlisted))
                    .get("output").getAsString());
        } finally {
            deleteDir(listed.getParent());
            deleteDir(unlisted.getParent());
        }
    }

    /**
     * python3 is often a wrapper over an interpreter installed elsewhere under $HOME (uv, pyenv), outside the PATH
     * folders. Where it is, listing that folder in shell.sandbox.homeReads is what makes it run for a custom agent.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacAPythonInstalledUnderHomeRunsOnceItsFolderIsListed() {
        ConfigService.set("shell.allowlist", "python3");
        var unconfined = run(main(), "python3 -c 'import sys; print(sys.base_prefix)'");
        assumeTrue(unconfined.get("exitCode").getAsInt() == 0, "python3 does not run here: " + unconfined);
        var prefix = Path.of(unconfined.get("output").getAsString().strip());
        var home = Path.of(System.getProperty("user.home"));
        if (prefix.startsWith(home) && !prefix.equals(home)) {
            ConfigService.set(HarnessSandbox.HOME_READS_KEY, prefix.toString());
        }
        var confined = run(agent, "python3 --version");
        assertEquals(0, confined.get("exitCode").getAsInt(), "python3 under " + prefix + ": " + confined);
    }

    /** A PATH the model passes in exec's env reaches the command but reopens nothing: only JClaw's own PATH does. */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacAModelSuppliedPathOpensNothing() throws IOException {
        ConfigService.set("shell.allowlist", "echo,cat");
        var secret = homeFile("jf165-model-path-secret");
        try {
            var args = new JsonObject();
            args.addProperty("command", "echo \"$PATH\"; /bin/cat '%s' 2>/dev/null || echo HOME_DENIED".formatted(secret));
            var env = new JsonObject();
            env.addProperty("PATH", secret.getParent() + ":/usr/bin:/bin");
            args.add("env", env);

            var result = JsonParser.parseString(tool.execute(args.toString(), agent)).getAsJsonObject();

            assertEquals(secret.getParent() + ":/usr/bin:/bin\nHOME_DENIED", result.get("output").getAsString(),
                    result.toString());
        } finally {
            deleteDir(secret.getParent());
        }
    }

    /** main under shell.sandbox=true keeps today's profile, in which the home folder stays readable. */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacMainUnderTheKeyStillReadsTheHomeFolder() throws IOException {
        var secret = homeFile("jf165-home-readable");
        try {
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
            assertEquals("jf165-home-readable", run(main(), "cat '%s'".formatted(secret)).get("output").getAsString());
        } finally {
            deleteDir(secret.getParent());
        }
    }

    /**
     * Runs a custom agent's exec, confined with {@code shell.sandbox} unset and the network under {@code network},
     * connecting to {@code server} and writing in the workspace; returns its output.
     */
    private String connectFromTheSandbox(ServerSocket server, String network) {
        ConfigService.set("shell.allowlist", "nc,echo,cat");
        // Set right before the run: another class's table wipe can clear a key.
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

    // ===== JF-325: the Main Agent's fence =====

    private static HarnessSandbox.MainFence sampleFence() {
        return new HarnessSandbox.MainFence(
                List.of(Path.of("/srv/jclaw"), Path.of("/opt/play1"), Path.of("/home/op/.gradle")),
                Path.of("/srv/jclaw/workspace"),
                List.of(Path.of("/srv/jclaw/data"), Path.of("/srv/jclaw/certs"), Path.of("/srv/jclaw/logs")),
                List.of(Path.of("/srv/jclaw/data/backups")), 4242L);
    }

    @Test
    void theMainAgentsMacProfileKeepsEverythingButJclawsOwnFiles() {
        assertEquals("""
                (version 1)
                (allow default)
                (deny file-write* (subpath "/srv/jclaw"))
                (deny file-write* (subpath "/opt/play1"))
                (deny file-write* (subpath "/home/op/.gradle"))
                (allow file-write* (subpath "/srv/jclaw/workspace"))
                (deny file-read* file-write* (subpath "/srv/jclaw/data"))
                (deny file-read* file-write* (subpath "/srv/jclaw/certs"))
                (deny file-read* file-write* (subpath "/srv/jclaw/logs"))
                (allow file-read* (subpath "/srv/jclaw/data/backups"))
                (allow process-exec (literal "/bin/ps") (with no-sandbox))
                (allow process-exec (literal "/usr/bin/top") (with no-sandbox))
                (deny network-outbound file-write* file-link (regex #"/\\.java_pid4242$"))
                """, HarnessSandbox.mainFenceMacosProfile(sampleFence()));
    }

    @Test
    void theMainAgentsLinuxArgvMountsInTheOrderTheFenceNeeds() {
        var argv = HarnessSandbox.mainFenceLinuxArgv(List.of("/bin/sh", "-c", "true"), sampleFence(), false);
        assertEquals(List.of("bwrap", "--dev-bind", "/", "/",
                "--ro-bind-try", "/srv/jclaw", "/srv/jclaw",
                "--ro-bind-try", "/opt/play1", "/opt/play1",
                "--ro-bind-try", "/home/op/.gradle", "/home/op/.gradle",
                "--bind", "/srv/jclaw/workspace", "/srv/jclaw/workspace"),
                argv.subList(0, 16), "the whole tree first, the program files read-only, the workspaces writable");
        var rest = argv.subList(16, argv.size());
        var tmpfs = rest.indexOf("--ro-bind");
        assertTrue(tmpfs > 0, "a readable folder is bound back after the tmpfs over its parent: " + rest);
        assertEquals(List.of("--ro-bind", "/srv/jclaw/data/backups", "/srv/jclaw/data/backups", "/bin/sh", "-c", "true"),
                rest.subList(tmpfs, rest.size()));
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--dev-bind"),
                HarnessSandbox.mainFenceLinuxArgv(List.of("true"), sampleFence(), true).subList(0, 5),
                "as root, in a user namespace of its own");
    }

    /**
     * On this host, a real fenced run of the Main Agent's: JClaw's secret folders unreadable, its program files and
     * the Gradle cache read-only, the workspaces and everything outside JClaw writable, the backup folder readable.
     */
    @Test
    void theMainAgentsShellCannotReadJclawsSecretsOrChangeItsProgramFiles() throws IOException {
        assumeTrue(HarnessSandbox.availability().available(), "no sandbox on this host");
        ConfigService.set("shell.allowlist", "echo,head,touch,ls,cat,rm");
        var app = Play.applicationPath.toPath().toRealPath();
        var data = app.resolve("data");
        var backups = services.database.DatabaseService.backupsDir().toAbsolutePath();
        Files.createDirectories(backups);
        var tag = UUID.randomUUID().toString().substring(0, 8);
        var secretProbe = data.resolve("fence-" + tag + ".txt");
        var backupProbe = backups.resolve("fence-" + tag + ".zip");
        var workspaceRoot = services.WorkspaceFiles.workspaceRoot().toAbsolutePath();
        Files.createDirectories(workspaceRoot);
        var outside = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().resolve("fence-" + tag + ".txt");
        Files.writeString(secretProbe, "secret");
        Files.writeString(backupProbe, "backup");
        try {
            var refused = new ArrayList<String>();
            var allowed = new ArrayList<String>();
            for (var command : List.of("head -c 0 " + app.resolve("certs/.env"), "head -c 0 " + secretProbe,
                    "touch " + backups.resolve("new-" + tag + ".zip"), "touch " + app.resolve("fence-" + tag + ".txt"),
                    "touch " + Path.of(System.getProperty("user.home"), ".gradle", "fence-" + tag),
                    "touch " + Play.frameworkPath.toPath().resolve("fence-" + tag))) {
                if (run(main(), command).get("exitCode").getAsInt() == 0) refused.add(command);
            }
            for (var command : List.of("cat " + backupProbe, "head -c 1 " + app.resolve("conf/application.conf"),
                    "touch " + workspaceRoot.resolve("fence-" + tag + ".txt"), "touch " + outside)) {
                var result = run(main(), command);
                if (result.get("exitCode").getAsInt() != 0) allowed.add(command + " -> " + result.get("output"));
            }
            assertEquals(List.of(), refused, "these ran, and the fence should have stopped them");
            assertEquals(List.of(), allowed, "these were stopped, and the fence should have let them run");
            assertEquals("backup", run(main(), "cat " + backupProbe).get("output").getAsString().strip());
            assertFalse(Files.exists(app.resolve("fence-" + tag + ".txt")));
            assertFalse(Files.exists(backups.resolve("new-" + tag + ".zip")));
        } finally {
            Files.deleteIfExists(secretProbe);
            Files.deleteIfExists(backupProbe);
            Files.deleteIfExists(workspaceRoot.resolve("fence-" + tag + ".txt"));
            Files.deleteIfExists(outside);
        }
    }

    /** macOS runs no setuid program inside a sandbox, so the fence lets these two read-only viewers run outside it. */
    @Test
    @EnabledOnOs(OS.MAC)
    void theMainAgentsFencedShellStillRunsPsAndTop() {
        assumeTrue(HarnessSandbox.availability().available(), "no sandbox on this host");
        ConfigService.set("shell.allowlist", "ps,top");
        var ps = run(main(), "ps -p 1 -o pid=");
        assertEquals(0, ps.get("exitCode").getAsInt(), ps.toString());
        assertEquals("1", ps.get("output").getAsString().strip());
        var top = run(main(), "top -l 1 -n 0");
        assertEquals(0, top.get("exitCode").getAsInt(), top.toString());
    }

    /** JF-324: the files commands are told to trust stay readable to the fenced shell, and it cannot remove them. */
    @Test
    void theMainAgentsFencedShellCannotRemoveTheSecretsTrustFiles() {
        assumeTrue(HarnessSandbox.availability().available(), "no sandbox on this host");
        services.secrets.SecretProxy.caForTest();
        var bundle = services.secrets.SecretProxy.trustFolder().resolve("bundle.pem");
        ConfigService.set("shell.allowlist", "rm,head");
        run(main(), "rm -f " + bundle);
        assertTrue(Files.exists(bundle), "the fenced shell removed " + bundle);
        assertEquals(0, run(main(), "head -c 1 " + bundle).get("exitCode").getAsInt());
    }
}
