import models.Agent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import tools.ShellExecTool;
import tools.TerminalImageRenderer;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ShellExecToolTest extends UnitTest {

    private ShellExecTool tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        // JCLAW-1153: shell.sandbox is process-global and read on every execute(),
        // so these runs must not overlap ShellExecSandboxTest turning it on.
        ShellSandboxSync.acquire();
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        tool = new ShellExecTool();
        agent = AgentService.create("shell-test-agent", "openrouter", "gpt-4.1");
        // Seed allowlist
        ConfigService.set("shell.allowlist", "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
    }

    @AfterEach
    void releaseSandboxFlag() {
        ShellSandboxSync.release();
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("shell-test-agent"));
        deleteDir(AgentService.workspacePath("main"));
    }

    // ==================== Allowlist Validation ====================

    /**
     * Allowlist-accepting shapes: bare allowed cmd, pipe chain (first cmd
     * checked), leading-whitespace tolerance, single-word command.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "allowedCommandPasses               ; echo hello",
            "pipeChainValidatesFirstCommandOnly ; git log | head -20",
            "commandWithLeadingWhitespace       ; '  echo hello'",
            "singleWordCommand                  ; ls"
    })
    void validateAllowlistAcceptsAllowedShape(String label, String cmd) {
        var error = tool.validateAllowlist(cmd);
        assertNull(error);
    }

    @Test
    void blockedCommandRejected() {
        var error = tool.validateAllowlist("rm -rf /");
        assertNotNull(error);
        assertTrue(error.whatBroke().contains("not in the allowed commands list"));
        assertTrue(error.whatBroke().contains("rm"));
    }

    @Test
    void emptyCommandRejected() {
        var error = tool.validateAllowlist("   ");
        assertNotNull(error);
    }

    @Test
    void relativePathMatchesByBasename() {
        // Skill-provided tools are commonly invoked by their workspace-relative
        // path. Users list the binary *name* in the allowlist; the validator
        // must treat "./skills/.../wacli" as equivalent to "wacli" for matching.
        ConfigService.set("shell.allowlist", "echo,wacli");
        var error = tool.validateAllowlist("./skills/whatsapp-wacli-mac/tools/wacli --status");
        assertNull(error, "relative-path invocation should match the basename entry");
    }

    @Test
    void absolutePathMatchesByBasename() {
        ConfigService.set("shell.allowlist", "grep");
        var error = tool.validateAllowlist("/usr/bin/grep foo bar");
        assertNull(error, "absolute-path invocation should match the basename entry");
    }

    @Test
    void explicitPathInAllowlistStillMatches() {
        // Operators who want strict path-scoped allowlists (list an exact relative
        // path) keep working — exact-token match is checked alongside basename.
        ConfigService.set("shell.allowlist", "./skills/foo/bar");
        var error = tool.validateAllowlist("./skills/foo/bar --go");
        assertNull(error, "exact relative-path allowlist entry must still match");
    }

    @Test
    void basenameMismatchStillRejected() {
        ConfigService.set("shell.allowlist", "wacli");
        var error = tool.validateAllowlist("./skills/foo/rm -rf /");
        assertNotNull(error, "a binary whose basename is not in the allowlist must be rejected");
        assertTrue(error.whatBroke().contains("not in the allowed commands list"));
    }

    // ==================== Working Directory Resolution ====================

    @Test
    void defaultWorkspaceDir() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("{}").getAsJsonObject();
        var resolved = tool.resolveWorkdir(args, workspace, false, agent.name);
        assertEquals(workspace, resolved);
    }

    @Test
    void relativeSubdirectory() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "skills"}
                """).getAsJsonObject();
        var resolved = tool.resolveWorkdir(args, workspace, false, agent.name);
        assertEquals(workspace.resolve("skills"), resolved);
    }

    @Test
    void pathTraversalBlocked() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "../../etc"}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> tool.resolveWorkdir(args, workspace, false, agent.name));
    }

    @Test
    void absolutePathBlockedByDefault() {
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        var args = com.google.gson.JsonParser.parseString("""
                {"workdir": "/tmp"}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> tool.resolveWorkdir(args, workspace, false, agent.name));
    }

    @Test
    void shellWorkdirSymlinkEscapeBlocked() throws Exception {
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
                    .forEach(p -> { try { Files.delete(p); } catch (Exception _) {} });
        }
    }

    // ==================== Environment Filtering ====================

    @Test
    void sensitiveVarsByNamePattern() {
        assertTrue(ShellExecTool.isSensitiveEnvVar("OPENAI_API_KEY"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("my_secret_value"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("DB_PASSWORD"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("AUTH_TOKEN"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("AWS_ACCESS_KEY_ID"));
        assertTrue(ShellExecTool.isSensitiveEnvVar("ANTHROPIC_API_KEY"));
    }

    @Test
    void nonSensitiveVarsPass() {
        assertFalse(ShellExecTool.isSensitiveEnvVar("PATH"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("HOME"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("LANG"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("SHELL"));
        assertFalse(ShellExecTool.isSensitiveEnvVar("USER"));
    }

    @Test
    void customSensitiveVarsBlocked() {
        var args = com.google.gson.JsonParser.parseString("""
                {"env": {"OPENAI_API_KEY": "injected", "MY_VAR": "hello"}}
                """).getAsJsonObject();
        var env = tool.buildEnvironment(args);
        assertFalse(env.containsKey("OPENAI_API_KEY"));
        assertEquals("hello", env.get("MY_VAR"));
    }

    @Test
    void hostEnvSensitiveVarsFilteredBeforeReachingChild() {
        // buildEnvironment starts from System.getenv(), which in real
        // deployments carries provider secrets (AWS_*, ANTHROPIC_*, ...).
        // Those must be stripped before the map is handed to ProcessBuilder —
        // otherwise a shell command could dump them with `env`.
        var args = com.google.gson.JsonParser.parseString("{}").getAsJsonObject();
        var env = tool.buildEnvironment(args);
        for (var key : env.keySet()) {
            assertFalse(ShellExecTool.isSensitiveEnvVar(key),
                    "host env key " + key + " leaked through the filter");
        }
    }

    // ==================== End-to-End Execution ====================

    @Test
    void basicEchoCommand() {
        var result = tool.execute("""
                {"command": "echo hello"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":0"));
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("\"timedOut\":false"));
        assertTrue(result.contains("\"stopped\":false"), result);
    }

    @Test
    void nonZeroExitCode() {
        var result = tool.execute("""
                {"command": "exit 42"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":42"));
        assertTrue(result.contains("\"timedOut\":false"));
    }

    @Test
    void blockedCommandReturnsError() {
        var result = tool.execute("""
                {"command": "rm -rf /"}
                """, agent);
        assertTrue(result.contains("not in the allowed commands list"));
    }

    @Test
    void schemaMarksWhyRequired() {
        var params = tool.parameters();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) params.get("required");
        assertTrue(required.contains("command"), "command stays required");
        assertTrue(required.contains("why"),
                "why must be required so the model always states intent (operator audit trail)");
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) params.get("properties");
        assertTrue(props.containsKey("why"), "why must be declared in the schema properties");
    }

    @Test
    void execRunsWithWhySupplied() {
        // why rides alongside command; execution is unchanged and still succeeds.
        var result = tool.execute("""
                {"command": "echo hi", "why": "smoke-test the echo path"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":0"));
        assertTrue(result.contains("hi"));
    }

    @Test
    void emptyCommandReturnsError() {
        var result = tool.execute("""
                {"command": "  "}
                """, agent);
        assertTrue(result.contains("Error"));
    }

    /** JCLAW-1132 AC: a failed command names itself, its status, and what to check. */
    @Test
    void aNonZeroExitCarriesTheCommandTheStatusAndARemedy() {
        var result = tool.execute("""
                {"command": "sh -c 'exit 3'", "why": "provoke a non-zero exit"}
                """, agent);

        var envelope = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        assertEquals(3, envelope.get("exitCode").getAsInt(), "the envelope contract is unchanged");
        assertTrue(envelope.has("output"), "the command's own output is still there");
        var error = envelope.getAsJsonObject("error");
        assertNotNull(error, "a failed command must carry its template: " + result);
        assertEquals("shell_exit_nonzero", error.get("code").getAsString());
        assertTrue(error.get("whatBroke").getAsString().contains("exit 3"),
                "the command itself: " + error);
        assertTrue(error.get("whatBroke").getAsString().contains("status 3"),
                "the exit status: " + error);
        assertFalse(error.get("whatToCheck").getAsString().isBlank());
    }

    @Test
    void aSuccessfulCommandCarriesNoErrorMember() {
        var result = tool.execute("""
                {"command": "echo hi", "why": "smoke"}
                """, agent);

        assertFalse(com.google.gson.JsonParser.parseString(result).getAsJsonObject().has("error"),
                "a success must look exactly as it did before this story: " + result);
    }

    /** A refusal before the process starts renders the template rather than the envelope. */
    @Test
    void aBlockedCommandStatesWhatToCheckAndHowToRetry() {
        var result = tool.execute("""
                {"command": "rm -rf /", "why": "provoke the allowlist"}
                """, agent);

        assertTrue(result.startsWith("Error: Command 'rm' is not in the allowed commands list"),
                "got: " + result);
        assertTrue(result.contains("What to check: "), "got: " + result);
        assertTrue(result.contains("How to retry: "), "got: " + result);
    }

    @Test
    void timeoutKillsProcess() {
        var result = tool.execute("""
                {"command": "sleep 30", "timeout": 1}
                """, agent);
        assertTrue(result.contains("\"timedOut\":true,\"stopped\":false"), result);
        assertTrue(result.contains("\"exitCode\":-1"));
        assertTrue(result.contains("timeout after 1 seconds"));
    }

    @Test
    void stoppingTheTurnKillsTheProcessTreeAndReportsStopped() throws Exception {
        var turn = new java.util.concurrent.atomic.AtomicBoolean(false);
        var result = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> result.complete(agents.ToolContext.withScope(null, null, null, turn,
                () -> tool.execute("""
                        {"command": "sleep 4711 & sleep 4712; wait", "timeout": 120}
                        """, agent))));

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (sleepers("sleep 4711", "sleep 4712").size() < 2 && System.nanoTime() < deadline) Thread.sleep(50);
        assertEquals(2, sleepers("sleep 4711", "sleep 4712").size(), "both forked sleeps started");

        agents.TurnCancellation.cancel(turn);
        var text = result.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(text.contains("\"stopped\":true"), text);
        assertTrue(text.contains("\"exitCode\":-1"), text);
        assertTrue(text.contains("[Process killed: turn stopped]"), text);
        assertTrue(text.contains("was stopped because its turn was stopped"), text);

        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!sleepers("sleep 4711", "sleep 4712").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(sleepers("sleep 4711", "sleep 4712").isEmpty(), "no process of the stopped command survives");
    }

    @Test
    void aTimeoutKillsTheForkedDescendantsToo() throws Exception {
        var result = tool.execute("""
                {"command": "sleep 4713 & sleep 4714; wait", "timeout": 1}
                """, agent);
        assertTrue(result.contains("\"timedOut\":true"), result);

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!sleepers("sleep 4713", "sleep 4714").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(sleepers("sleep 4713", "sleep 4714").isEmpty(), "no process of the timed-out command survives");
    }

    @Test
    void aSilentProcessLeftInTheBackgroundEndsTheCallButKeepsRunning() throws Exception {
        // The background sleep inherits the command's output and never writes, so a pipe's EOF never came.
        var result = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> result.complete(tool.execute("""
                {"command": "sleep 4715 & echo started", "timeout": 120}
                """, agent)));
        String text = null;
        try {
            text = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(text.contains("\"exitCode\":0"), text);
            assertTrue(text.contains("started"), text);
            assertTrue(Files.exists(backgroundOutput(text)), text);
            assertTrue(text.contains("\"timedOut\":false"), text);
            assertEquals(1, sleepers("sleep 4715").size(), "the background process is left running");
            assertTrue(text.contains("; pid " + sleepers("sleep 4715").getFirst().pid()), "the result names its pid: " + text);
        } finally {
            sleepers("sleep 4715").forEach(ProcessHandle::destroy);
            if (text != null) Files.deleteIfExists(backgroundOutput(text));
        }
    }

    @Test
    void aProcessLeftInTheBackgroundKeepsWritingAfterTheCallReturns() throws Exception {
        // On a pipe, the JDK closed the read end after the shell exited and the next write killed the writer.
        var text = tool.execute("""
                {"command": "sleep 1 && for i in $(seq 1 20000); do echo line $i; done && sleep 4718 & echo started"}
                """, agent);
        try {
            assertTrue(text.contains("started"), text);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (sleepers("sleep 4718").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(1, sleepers("sleep 4718").size(), "the background job wrote 20,000 lines and carried on");
            var output = backgroundOutput(text);
            assertTrue(Files.readString(output).contains("line 20000"), "its output is in " + output);
        } finally {
            sleepers("sleep 4718").forEach(ProcessHandle::destroy);
            if (text.contains("keeps writing its output to")) Files.deleteIfExists(backgroundOutput(text));
        }
    }

    @Test
    void aScriptThatBackgroundsAJobItselfIsSeenToLeaveItRunning() throws Exception {
        // No & in the command itself: only the descriptors the job inherited show it is still running.
        var script = AgentService.workspacePath("shell-test-agent").resolve("start-server.sh");
        Files.writeString(script, "(sleep 1; echo server-log-line; sleep 4723) &\necho launched\n");
        var text = tool.execute("""
                {"command": "sh %s"}
                """.formatted(script), agent);
        try {
            assertTrue(text.contains("launched"), text);
            var output = backgroundOutput(text);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (sleepers("sleep 4723").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(1, sleepers("sleep 4723").size(), "the job is still running");
            assertTrue(Files.readString(output).contains("server-log-line"), "its output is in " + output);
        } finally {
            sleepers("sleep 4723").forEach(ProcessHandle::destroy);
            if (text.contains("keeps writing its output to")) Files.deleteIfExists(backgroundOutput(text));
            Files.deleteIfExists(script);
        }
    }

    @Test
    void aBackgroundCommandReturnsAtOnceWithItsFileAndPid() throws Exception {
        long t0 = System.nanoTime();
        var text = tool.execute("""
                {"command": "echo starting; sleep 4719", "background": true}
                """, agent);
        try {
            assertTrue(System.nanoTime() - t0 < java.util.concurrent.TimeUnit.SECONDS.toNanos(5),
                    "the call returned without waiting for the command");
            assertTrue(text.contains("\"background\":true"), text);
            assertTrue(text.contains("starting"), "its first second of output is in the result: " + text);
            assertTrue(Files.exists(backgroundOutput(text)), text);
            assertEquals(1, sleepers("sleep 4719").size(), "the command keeps running");
            long pid = sleepers("sleep 4719").getFirst().pid();
            assertTrue(text.matches("(?s).*; pids? [0-9, ]*\\b" + pid + "\\b.*"), "the result names its pid: " + text);
        } finally {
            sleepers("sleep 4719").forEach(ProcessHandle::destroy);
            if (text.contains("keeps writing its output to")) Files.deleteIfExists(backgroundOutput(text));
        }
    }

    @Test
    void aBackgroundCommandThatEndsAtOnceShowsItsOutput() throws IOException {
        var before = outputFiles();
        var text = tool.execute("""
                {"command": "echo boom; exit 3", "background": true}
                """, agent);
        assertTrue(text.contains("boom"), "a port already in use would show the same way: " + text);
        assertTrue(text.contains("exited within a second"), text);
        assertFalse(text.contains("keeps writing its output to"), text);
        assertEquals(before, outputFiles(), "nothing is kept for a command that is already gone");
    }

    @Test
    void anAmpersandInsideQuotesLeavesNothingRunningOrBehind() throws IOException {
        var before = outputFiles();
        var text = tool.execute("""
                {"command": "echo 'a=1&b=2' \\"c&d\\""}
                """, agent);
        assertTrue(text.contains("a=1&b=2 c&d"), text);
        assertFalse(text.contains("keeps writing its output to"), "a quoted & backgrounds nothing: " + text);
        assertEquals(before, outputFiles());
    }

    @Test
    void aRunningCommandCountsAsActivityForTheRunAndTheTaskFireAroundIt() throws Exception {
        long runId = -java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        long taskRunId = 991_575_000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
        services.SubagentRegistry.register(runId, new java.util.concurrent.CompletableFuture<Void>());
        services.TaskRunRegistry.register(taskRunId);
        try {
            // Both clocks are otherwise touched only at checkpoints, so each would read the whole 3 s.
            services.SubagentRegistry.callAsRun(runId, () -> agents.ToolContext.withScope(null, taskRunId,
                    () -> tool.execute("{\"command\": \"sleep 3\"}", agent)));
            long twoSeconds = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            assertTrue(services.SubagentRegistry.nanosSinceActivity(runId) < twoSeconds,
                    "the subagent run saw the running command as activity");
            assertTrue(services.TaskRunRegistry.nanosSinceActivity(taskRunId) < twoSeconds,
                    "so did the task fire");
        } finally {
            services.SubagentRegistry.unregister(runId);
            services.TaskRunRegistry.unregister(taskRunId);
        }
    }

    @Test
    void theSchemaStatesTheTimeoutDefaultsAndTheBackgroundSwitch() {
        var text = tool.parameters().toString();
        assertTrue(text.contains("default: 120, max: 600"), text);
        assertTrue(text.contains("background"), text);
    }

    @Test
    void anOrdinaryCommandLeavesNoOutputFileBehind() throws IOException {
        var before = outputFiles();
        var result = tool.execute("""
                {"command": "echo hi"}
                """, agent);
        assertTrue(result.contains("hi"), result);
        assertEquals(before, outputFiles());
    }

    @Test
    void anOutputFolderReplacedByALinkIsRefusedAndNothingBehindItIsTouched() throws Exception {
        var outside = Files.createTempDirectory("exec-outside");
        var old = Files.writeString(outside.resolve("old.log"), "keep me");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofDays(3))));
        var dir = AgentService.workspacePath("shell-test-agent").resolve(".exec");
        deleteDir(dir);
        Files.createDirectories(dir.getParent());
        Files.createSymbolicLink(dir, outside);
        try {
            var result = tool.execute("""
                    {"command": "echo hi"}
                    """, agent);
            assertFalse(result.contains("\"exitCode\":0"), "the call is refused; got: " + result);
            assertTrue(result.contains(".exec"), "the refusal names the folder; got: " + result);
            services.BackgroundProcesses.sweep(java.util.List.of(agent));
            assertEquals("keep me", Files.readString(old), "a file behind the link is never deleted");
            try (var files = Files.list(outside)) {
                assertEquals(1, files.count(), "nothing is created behind the link");
            }
        } finally {
            Files.deleteIfExists(dir);
            deleteDir(outside);
        }
    }

    private static java.util.Set<Path> outputFiles() throws IOException {
        var dir = AgentService.workspacePath("shell-test-agent").resolve(".exec");
        if (!Files.isDirectory(dir)) return java.util.Set.of();
        try (var files = Files.list(dir)) {
            return files.collect(java.util.stream.Collectors.toSet());
        }
    }

    private static Path backgroundOutput(String result) {
        var m = java.util.regex.Pattern.compile("keeps writing its output to (\\S+?\\.log)").matcher(result);
        assertTrue(m.find(), "the result names where background output goes: " + result);
        return Path.of(m.group(1));
    }

    private static java.util.List<ProcessHandle> sleepers(String... commands) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(h -> h.info().commandLine()
                        .map(c -> java.util.Arrays.stream(commands).anyMatch(c::endsWith)).orElse(false))
                .toList();
    }

    @Test
    void pwdReturnsWorkspace() {
        var result = tool.execute("""
                {"command": "pwd"}
                """, agent);
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize().toString();
        assertTrue(result.contains(workspace));
    }

    @Test
    void customEnvVarPassed() {
        var result = tool.execute("""
                {"command": "printenv MY_VAR", "env": {"MY_VAR": "test_value"}}
                """, agent);
        assertTrue(result.contains("test_value"));
        assertTrue(result.contains("\"exitCode\":0"));
    }

    @Test
    void pipelineExecution() {
        var result = tool.execute("""
                {"command": "echo 'line1\\nline2\\nline3' | wc -l"}
                """, agent);
        assertTrue(result.contains("\"exitCode\":0"));
    }

    // ==================== Main-Agent Privilege Escapes ====================

    @Test
    void mainAgentBypassesAllowlistWhenConfigured() {
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1");
        ConfigService.set("agent.main.shell.bypassAllowlist", "true");

        // whoami is NOT in the allowlist; with bypass on, it must slip past the allowlist check
        var result = tool.execute("""
                {"command": "whoami"}
                """, mainAgent);
        assertFalse(result.contains("not in the allowed commands list"),
                "Main agent with bypassAllowlist=true must not hit the allowlist error");
    }

    @Test
    void nonMainAgentIgnoresBypassAllowlistConfigRow() {
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
    void mainAgentUsesAbsoluteWorkdirWhenConfigured() {
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1");
        ConfigService.set("agent.main.shell.allowGlobalPaths", "true");

        var result = tool.execute("""
                {"command": "pwd", "workdir": "/tmp"}
                """, mainAgent);
        assertTrue(result.contains("\"exitCode\":0"));
        // macOS symlinks /tmp to /private/tmp; Linux returns /tmp directly
        assertTrue(result.contains("/tmp"));
    }

    @Test
    void nonMainAgentIgnoresAllowGlobalPathsConfigRow() {
        ConfigService.set("agent.shell-test-agent.shell.allowGlobalPaths", "true");

        var result = tool.execute("""
                {"command": "pwd", "workdir": "/tmp"}
                """, agent);
        assertTrue(result.contains("must be within the agent workspace"),
                "Non-main agent must stay sandboxed regardless of any Config row");
    }

    @Test
    void mainAgentWithoutPrivilegeConfigStillRejected() {
        // Main agent without the bypass config set: must still hit the allowlist
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1");

        var result = tool.execute("""
                {"command": "whoami"}
                """, mainAgent);
        assertTrue(result.contains("not in the allowed commands list"),
                "Main agent must only bypass when the Config row is actually set to true");
    }

    // ==================== Shell-composition posture (JCLAW-146) ====================
    //
    // These tests pin the documented posture: the allowlist validates only the
    // first token, and the command is executed via /bin/sh -c so shell
    // composition/metacharacters ARE allowed by design. See the class-level
    // JavaDoc on ShellExecTool for the rationale. If any of these tests fail,
    // somebody has attempted to harden the allowlist into per-token gating —
    // that's a substantive security-posture change that needs a design review,
    // not a silent upgrade.

    @Test
    void commandCompositionRunsBothCommands() {
        // `echo hi; echo world` — first-token is `echo` (allowed), shell
        // composition runs both statements.
        var result = tool.execute("""
                {"command": "echo hi; echo world"}
                """, agent);
        assertTrue(result.contains("hi"), "first echo statement missing; got: " + result);
        assertTrue(result.contains("world"), "second echo statement missing; got: " + result);
        assertTrue(result.contains("\"exitCode\":0"));
    }

    @Test
    void commandSubstitutionIsExecuted() {
        // `echo $(echo nested)` — first-token allowlist check passes,
        // subshell evaluates and produces "nested".
        var result = tool.execute("""
                {"command": "echo $(echo nested)"}
                """, agent);
        assertTrue(result.contains("nested"),
                "command substitution did not evaluate; got: " + result);
    }

    @Test
    void shellRedirectionIsAllowed() {
        // Redirect to a file within the workspace. Allowlist sees `echo`
        // only — the `>` is handled by /bin/sh.
        var result = tool.execute("""
                {"command": "echo captured > out.txt && cat out.txt"}
                """, agent);
        assertTrue(result.contains("captured"),
                "redirect-then-cat did not work; got: " + result);
    }

    // ==================== UTF-8 + truncation ====================

    @Test
    void utf8MultiByteCharsRoundTripCorrectly() {
        // InputStreamReader with UTF-8 handles partial multi-byte sequences
        // across read() boundaries internally. Output non-ASCII chars and
        // verify they survive the read loop without corruption.
        var result = tool.execute("""
                {"command": "echo 'café — 中文 — 😀'"}
                """, agent);
        assertTrue(result.contains("café"), "é mangled; got: " + result);
        assertTrue(result.contains("中文"), "Han chars mangled; got: " + result);
        assertTrue(result.contains("😀"), "emoji mangled; got: " + result);
    }

    @Test
    void outputTruncationMarksTruncatedTrue() {
        // Keep the default allowlist and shell.maxOutputBytes cap, but force
        // a smaller cap for this test so the output overflows without
        // burning test time on a large process.
        ConfigService.set("shell.maxOutputBytes", "100");
        try {
            ConfigService.clearCache();
            // printf is in the allowlist implicitly via the test setup? It's
            // not — but `echo` is, and we can produce a large echo with a
            // shell loop that the first-token check still accepts.
            var result = tool.execute("""
                    {"command": "echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                    """, agent);
            assertTrue(result.contains("\"truncated\":true"),
                    "truncated flag not set; got: " + result);
            assertTrue(result.contains("Output truncated"),
                    "truncation marker not appended; got: " + result);
        } finally {
            ConfigService.delete("shell.maxOutputBytes");
            ConfigService.clearCache();
        }
    }

    // ==================== Watchdog race ====================

    @Test
    void fastExitDoesNotMarkTimedOut() {
        // Regression guard: process that exits well within the timeout must
        // leave timedOut=false (watchdog sees waitFor(timeout) return true and
        // never sets the flag). If this ever flips to true, the watchdog race
        // described in JCLAW-146 has re-emerged.
        var result = tool.execute("""
                {"command": "echo fast", "timeout": 10}
                """, agent);
        assertTrue(result.contains("\"timedOut\":false"),
                "fast exit must NOT be marked timed-out; got: " + result);
        assertTrue(result.contains("\"exitCode\":0"),
                "normal exit code must be preserved; got: " + result);
    }

    // ==================== Terminal-image early return ====================

    @Test
    void terminalImageTriggersEarlyReturn() {
        // 6 consecutive lines of U+2588 (full block, 12 chars each) — above
        // the 5-line/70% threshold in hasTerminalImage — triggers the
        // early-return branch that renders the block art as a PNG and marks
        // exitCode=-1 with a background-process note.
        var line = "████████████";
        var cmd = "printf '%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n' "
                + "'" + line + "' '" + line + "' '" + line + "' "
                + "'" + line + "' '" + line + "' '" + line + "'";
        // printf is not in the default seeded allowlist for this test class;
        // extend it so the allowlist check passes.
        ConfigService.set("shell.allowlist",
                "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep,printf");
        ConfigService.clearCache();
        try {
            var result = tool.execute(
                    "{\"command\":\"" + cmd.replace("\\", "\\\\") + "\"}", agent);
            // Early-return branch marks exitCode=-1 + timedOut=false + the
            // "Process still running in background" marker.
            assertTrue(result.contains("\"exitCode\":-1"),
                    "early-return should report exitCode=-1; got: " + result);
            assertTrue(result.contains("still running in background"),
                    "early-return marker missing; got: " + result);
            assertTrue(result.contains("\"timedOut\":false"),
                    "early-return must not mark timedOut; got: " + result);
        } finally {
            ConfigService.set("shell.allowlist",
                    "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
            ConfigService.clearCache();
        }
    }

    @Test
    void multiChunkPlainOutputDoesNotFalseTriggerImage() {
        // JCLAW-404 parity: ordinary output large enough to span several 4096-char
        // read() chunks (no block art) must complete normally — exitCode 0,
        // timedOut false, and NO early-return "still running in background" marker.
        // The incremental scanner must reach the same not-detected verdict the old
        // full-buffer rescan did, without re-scanning the whole buffer each chunk.
        ConfigService.set("shell.allowlist",
                "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep,seq,yes,printf");
        ConfigService.clearCache();
        try {
            // ~30 KB of plain numeric lines, comfortably over several read chunks.
            var result = tool.execute("""
                    {"command": "seq 1 4000"}
                    """, agent);
            assertTrue(result.contains("\"exitCode\":0"),
                    "plain multi-chunk output must exit normally; got: " + result);
            assertTrue(result.contains("\"timedOut\":false"),
                    "plain multi-chunk output must not time out; got: " + result);
            assertFalse(result.contains("still running in background"),
                    "plain output must NOT trip the terminal-image early return; got: " + result);
        } finally {
            ConfigService.set("shell.allowlist",
                    "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
            ConfigService.clearCache();
        }
    }

    @Test
    void terminalImageAfterPlainPrefixStillDetected() {
        // JCLAW-404 parity: a block-art image preceded by a chunk of ordinary
        // output (so the block-art lines accumulate across read() boundaries and
        // a non-block prefix resets the run first) must still trip the early
        // return. Exercises the incremental scanner's cross-chunk carry-over and
        // its run-reset on non-block-art lines.
        var line = "████████████";
        // A non-block-art prefix line, then 6 block-art lines.
        var cmd = "printf 'hello world prefix line\\n%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n' "
                + "'" + line + "' '" + line + "' '" + line + "' "
                + "'" + line + "' '" + line + "' '" + line + "'";
        ConfigService.set("shell.allowlist",
                "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep,printf");
        ConfigService.clearCache();
        try {
            var result = tool.execute(
                    "{\"command\":\"" + cmd.replace("\\", "\\\\") + "\"}", agent);
            assertTrue(result.contains("\"exitCode\":-1"),
                    "image after a plain prefix should still report exitCode=-1; got: " + result);
            assertTrue(result.contains("still running in background"),
                    "image after a plain prefix should still trip the early return; got: " + result);
        } finally {
            ConfigService.set("shell.allowlist",
                    "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
            ConfigService.clearCache();
        }
    }

    @Test
    void fourBlockArtLinesDoNotTriggerImage() {
        // JCLAW-404 parity: the threshold is 5 consecutive block-art lines.
        // Four such lines must NOT trip the early return — the incremental
        // scanner's run count must agree with the old <5 verdict.
        var line = "████████████";
        var cmd = "printf '%s\\n%s\\n%s\\n%s\\n' "
                + "'" + line + "' '" + line + "' '" + line + "' '" + line + "'";
        ConfigService.set("shell.allowlist",
                "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep,printf");
        ConfigService.clearCache();
        try {
            var result = tool.execute(
                    "{\"command\":\"" + cmd.replace("\\", "\\\\") + "\"}", agent);
            assertTrue(result.contains("\"exitCode\":0"),
                    "four block-art lines must exit normally (below threshold); got: " + result);
            assertFalse(result.contains("still running in background"),
                    "four block-art lines must NOT trip the early return; got: " + result);
        } finally {
            ConfigService.set("shell.allowlist",
                    "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
            ConfigService.clearCache();
        }
    }

    @Test
    void whitespacePaddedOutputDoesNotTriggerImage() {
        // JCLAW-1097: a progress bar clears its line with a run of spaces, and space
        // counted as a block glyph — so six cleared lines read as a picture and the
        // early return handed back a blank PNG instead of the command's real output.
        // Space still counts toward the 70% fill (it is the white pixel of half-block
        // encoding), but a line carrying no actual glyph is a blank line, not art.
        var cmd = "printf '%80s\\n%80s\\n%80s\\n%80s\\n%80s\\n%80s\\n' '' '' '' '' '' ''";
        ConfigService.set("shell.allowlist",
                "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep,printf");
        ConfigService.clearCache();
        try {
            var result = tool.execute(
                    "{\"command\":\"" + cmd.replace("\\", "\\\\") + "\"}", agent);
            assertTrue(result.contains("\"exitCode\":0"),
                    "whitespace-padded output must exit normally; got: " + result);
            assertFalse(result.contains("still running in background"),
                    "whitespace-padded output must NOT trip the early return; got: " + result);
        } finally {
            ConfigService.set("shell.allowlist",
                    "echo,ls,cat,git,head,sleep,pwd,printenv,exit,sh,wc,grep");
            ConfigService.clearCache();
        }
    }

    @Test
    void blockArtNeedsAGlyphNotJustFill() {
        assertFalse(TerminalImageRenderer.isBlockArtLine(" ".repeat(80)),
                "an all-space line is a blank line, not art (JCLAW-1097)");
        assertFalse(TerminalImageRenderer.isBlockArtLine("Downloading" + " ".repeat(80)),
                "a line cleared by trailing padding is still not art");
        assertTrue(TerminalImageRenderer.isBlockArtLine("  \u2588\u2588\u2580  \u2584\u2588  \u2588\u2580\u2588  "),
                "a half-block QR row is art even though most of it is white");
        assertFalse(TerminalImageRenderer.isBlockArtLine("Downloading chapter 12 of 40..."),
                "prose is not art");
    }

    @Test
    void renderedBlockArtCarriesAQuietZone() throws IOException {
        // The glyph requirement drops a QR code's blank border rows, so the quiet zone
        // has to be drawn rather than inherited — without it the code will not scan.
        var line = "\u2588".repeat(12);
        var md = TerminalImageRenderer.replaceTerminalImagesInOutput((line + "\n").repeat(6), agent);
        assertTrue(md.startsWith("![QR Code](/api/agents/"), "expected an image link; got: " + md);

        var name = md.substring(md.lastIndexOf('/') + 1, md.length() - 1);
        var png = ImageIO.read(AgentService.workspacePath(agent.name).resolve(name).toFile());
        assertTrue(png.getWidth() > line.length() * 8, "no horizontal margin: " + png.getWidth());
        assertTrue(png.getHeight() > 6 * 16, "no vertical margin: " + png.getHeight());
        // The art is solid black, so a white corner pixel can only be the quiet zone.
        assertEquals(Color.WHITE.getRGB(), png.getRGB(0, 0), "corner pixel is not white");
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
