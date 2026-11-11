import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import models.Agent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.BackgroundProcesses;
import services.ConfigService;
import tools.ShellExecTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * JF-303: {@code exec}'s {@code stop} ends a background process the calling agent's own commands left
 * running, from outside any sandbox, and no other process: not another agent's, not one JClaw never started
 * for it. Runs unconfined, so it holds on a host with no working sandbox; {@link SandboxSignalTest} covers
 * the same stop against a process inside one.
 */
class ExecStopBackgroundTest extends UnitTest {

    private static final String AGENT = "stop-agent";
    private static final String OTHER = "stop-other-agent";

    private static final String NOT_RUNNING = "Error: No background process %d of this agent's is running.\n\n"
            + "What to check: exec stops only a process this agent's own commands left running, by the pid their"
            + " result named. ";
    private static final String NONE_LEFT = "None is running now, so it may have exited already.";

    private ShellExecTool tool;
    private Agent agent;
    private Agent other;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setup() {
        // shell.sandbox is process-global and read on every exec.
        ShellSandboxSync.acquireUnconfined();
        Fixtures.deleteDatabase();
        cleanup();
        tool = new ShellExecTool();
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
        other = AgentService.create(OTHER, "openrouter", "gpt-4.1");
        ConfigService.set("shell.allowlist", "sleep,echo,sh");
    }

    @AfterEach
    void teardown() {
        try {
            started.forEach(Process::destroyForcibly);
            sleepers("sleep 4806", "sleep 4807", "sleep 4808", "sleep 4809").forEach(ProcessHandle::destroyForcibly);
        } finally {
            ShellSandboxSync.release();
        }
    }

    @AfterAll
    static void cleanup() {
        deleteDir(AgentService.workspacePath(AGENT));
        deleteDir(AgentService.workspacePath(OTHER));
    }

    private String exec(Agent who, String argsJson) {
        return ToolContext.withScope(null, null, () -> tool.execute(argsJson, who));
    }

    private String stop(Agent who, long pid) {
        return exec(who, "{\"stop\": %d, \"why\": \"done with it\"}".formatted(pid));
    }

    private void startInBackground(Agent who, String command) {
        var args = new JsonObject();
        args.addProperty("command", command);
        args.addProperty("background", true);
        var result = exec(who, args.toString());
        assertTrue(result.contains("keeps writing its output to"), "the command was left running: " + result);
    }

    @Test
    void stopEndsTheAgentsOwnBackgroundProcessWithEveryProcessItStarted() throws Exception {
        startInBackground(agent, "sh -c 'sleep 4806; echo done'");
        awaitSleeper("sleep 4806");
        long shell = BackgroundProcesses.list(List.of(agent)).stream()
                .filter(e -> e.command().endsWith("sleep 4806; echo done")).findFirst().orElseThrow().pid();

        var stopped = stop(agent, shell);

        assertEquals("Stopped background process %d and every process it started.".formatted(shell), stopped);
        awaitGone("sleep 4806");
        assertEquals(Optional.empty(), tool.postConditionFailure(ToolRegistry.ToolResult.text(stopped)),
                "a stop that worked is no failed command");
        assertEquals(NOT_RUNNING.formatted(shell) + NONE_LEFT, stop(agent, shell), "a second stop finds nothing");
    }

    @Test
    void anAgentCannotStopAnotherAgentsBackgroundProcess() throws Exception {
        startInBackground(other, "sleep 4807");
        long pid = awaitSleeper("sleep 4807").pid();

        assertEquals(NOT_RUNNING.formatted(pid) + NONE_LEFT, stop(agent, pid));
        assertEquals(1, sleepers("sleep 4807").size(), "a refused stop stops nothing");

        assertEquals("Stopped background process %d and every process it started.".formatted(pid), stop(other, pid));
        awaitGone("sleep 4807");
    }

    /** A process JClaw never started for the agent is refused like one that does not exist, and the refusal names what the agent can stop. */
    @Test
    void aRefusedStopNamesTheProcessesTheAgentCanStop() throws Exception {
        startInBackground(agent, "sleep 4808");
        awaitSleeper("sleep 4808");
        var outside = new ProcessBuilder("/bin/sleep", "120").start();
        started.add(outside);
        var own = BackgroundProcesses.list(List.of(agent)).stream()
                .map(e -> e.label(80)).collect(Collectors.joining(", "));
        assertTrue(own.contains("sleep 4808)"), own);
        var listed = "Running now: " + own + ".\n\nHow to retry: Re-send with 'stop' set to one of those pids.";

        for (long pid : List.of(outside.pid(), ProcessHandle.current().pid(), 1L, 0L, -1L)) {
            assertEquals(NOT_RUNNING.formatted(pid) + listed, stop(agent, pid));
        }

        assertTrue(outside.isAlive(), "a process JClaw did not start for the agent is never stopped");
        assertEquals(1, sleepers("sleep 4808").size(), "a refused stop stops nothing");
    }

    /** A running process is named by its pid and its command line, on one line and cut where it runs long. */
    @Test
    void aRunningProcessIsLabelledWithItsPidAndItsCommandOnOneLine() {
        assertEquals("pid 12 (sleep 300)", entry(12, "sleep 300").label(80));
        assertEquals("pid 12 (sh -c sleep 1; echo done)", entry(12, " sh -c\n  sleep 1;\techo done\n").label(80));
        assertEquals("pid 12 (abcde)", entry(12, "abcde").label(5));
        assertEquals("pid 12 (abcde…)", entry(12, "abcdef").label(5));
    }

    private static BackgroundProcesses.Entry entry(long pid, String command) {
        return new BackgroundProcesses.Entry(pid, 1L, AGENT, "/w/.exec/out.log", null, command, null, List.of());
    }

    @Test
    void stopTakesAWholeNumberAndNoCommand() throws Exception {
        startInBackground(agent, "sleep 4809");
        long pid = awaitSleeper("sleep 4809").pid();
        var notANumber = "Error: 'stop' must be the pid of a background process, as a whole number.\n\n"
                + "What to check: The 'stop' argument was %s.\n\n"
                + "How to retry: Re-send with 'stop' set to the pid an earlier result named, and no 'command'.";

        assertEquals(notANumber.formatted("\"abc\""), exec(agent, "{\"stop\": \"abc\"}"));
        assertEquals(notANumber.formatted(pid + ".5"), exec(agent, "{\"stop\": %d.5}".formatted(pid)));
        assertEquals(notANumber.formatted("[" + pid + "]"), exec(agent, "{\"stop\": [%d]}".formatted(pid)));
        assertEquals("Error: 'stop' and 'command' cannot be sent in one call.\n\n"
                        + "What to check: A call either runs a command or stops a background process. Nothing was run"
                        + " and nothing was stopped.\n\n"
                        + "How to retry: Send the stop on its own, then the command in a second call.",
                exec(agent, "{\"stop\": %d, \"command\": \"echo hi\"}".formatted(pid)));
        assertEquals(1, sleepers("sleep 4809").size(), "no refused call stopped it");

        var ignored = exec(agent, "{\"stop\": null, \"command\": \"echo hi\"}");
        assertTrue(ignored.contains("\"exitCode\":0") && ignored.contains("\"output\":\"hi\""), "a null stop is no stop: " + ignored);
        assertEquals("Stopped background process %d and every process it started.".formatted(pid),
                exec(agent, "{\"stop\": \"%d\", \"command\": null}".formatted(pid)),
                "a pid sent as a string is still that pid, and a null command is no command");
        awaitGone("sleep 4809");
    }

    /** A call that names neither a command nor a process to stop is refused as it always was. */
    @Test
    void aCallWithNeitherACommandNorAStopIsRefused() {
        var expected = "Error: command is required and must not be empty.\n\n"
                + "What to check: The 'command' argument was absent, null or whitespace only.\n\n"
                + "How to retry: Re-send the call with the shell command line in 'command'.";
        assertEquals(expected, exec(agent, "{}"));
        assertEquals(expected, exec(agent, "{\"why\": \"nothing\", \"stop\": null}"));
        assertEquals(expected, exec(agent, "{\"command\": null}"));
        assertEquals(expected, exec(agent, "{\"command\": \"  \"}"));
    }

    /** The schema is one for every agent: stop is a whole number, and why alone is required, since a stop sends no command. */
    @Test
    void theSchemaOffersStopAndRequiresOnlyWhy() {
        var params = tool.parameters();
        assertEquals(List.of("why"), params.get("required"));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Map<String, Object>>) params.get("properties");
        assertEquals(Map.of("type", "integer", "description",
                        "Instead of a command: the pid of a background process an earlier call of yours left running,"
                                + " as its result named it. Stops that process and every process it started."),
                props.get("stop"));
        assertEquals("Shell command to execute; required unless 'stop' is given", props.get("command").get("description"));
    }

    /** main and its copies read the description they always had; any other agent is told to stop with exec, since its kill reaches nothing an earlier command left. */
    @Test
    void onlyACustomAgentsDescriptionSendsItToStop() {
        var head = "Execute a shell command on the host system. Commands are validated against an allowlist of"
                + " permitted binaries. Working directory defaults to the agent workspace. Returns exit code, output,"
                + " and execution metadata. A command still in the foreground when its timeout passes is killed, so"
                + " start a server or a long build with \"background\": true rather than appending &: the call returns"
                + " at once, and the command keeps writing its output to a file the result names with its pid, which"
                + " you can read later. ";
        var tail = "Bind a server you start to 127.0.0.1 unless the user asks for another address.";
        var forMain = head + "The operator, or you through jclaw_api (POST /api/processes/{pid}/stop), can stop it. " + tail;
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        var copy = new Agent();
        copy.name = "main-copy";
        copy.parentAgent = main;
        var child = new Agent();
        child.name = "stop-agent-child";
        child.parentAgent = agent;

        assertEquals(forMain, tool.description());
        assertEquals(forMain, tool.description(null));
        assertEquals(forMain, tool.description(main));
        assertEquals(forMain, tool.description(copy));
        var forCustom = head + "To stop it, call exec with \"stop\" set to that pid and no command: each of your commands"
                + " runs in a sandbox of its own, so kill and pkill in a later command cannot reach it. The operator can"
                + " stop it too. " + tail;
        assertEquals(forCustom, tool.description(agent));
        assertEquals(forCustom, tool.description(child));
    }

    private static ProcessHandle awaitSleeper(String command) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var found = sleepers(command);
            if (!found.isEmpty()) return found.getFirst();
            Thread.sleep(50);
        }
        throw new AssertionError(command + " never started");
    }

    private static void awaitGone(String command) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sleepers(command).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        assertEquals(List.of(), sleepers(command), command + " was stopped");
    }

    private static List<ProcessHandle> sleepers(String... commands) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(h -> h.info().commandLine()
                        .map(c -> Arrays.stream(commands).anyMatch(c::endsWith)).orElse(false))
                .toList();
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
