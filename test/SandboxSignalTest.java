import agents.ToolContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JF-303: a signal from a custom agent's {@code exec} reaches only the processes of that same run. A process
 * outside its sandbox, and one an earlier run of its own left in the background, refuse it; {@code exec}
 * stops the second for the agent from outside the sandbox. {@code main}'s {@code exec} sends the same
 * signals as the control. Every process signaled here is a throwaway the test starts: a signal aimed at a
 * whole process group is sent only inside a session the test starts for it, never in the test JVM's own.
 */
class SandboxSignalTest extends UnitTest {

    private static final String AGENT = "signal-agent";
    private static final List<String> SIGNALS = List.of("0", "HUP", "INT", "QUIT", "USR1", "TERM", "STOP", "KILL");
    private static final Path PERL = Path.of("/usr/bin/perl");

    /** What a launcher in a process group of its own printed, how it ended, and whether the sleep it started is still running. */
    private record GroupOutcome(String output, int exit, int sleepersLeft) {}

    private ShellExecTool tool;
    private Agent agent;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setup() {
        ShellSandboxSync.acquire();
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        tool = new ShellExecTool();
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
        ConfigService.set("shell.allowlist", "kill,sleep,echo");
    }

    @AfterEach
    void teardown() {
        try {
            started.forEach(Process::destroyForcibly);
            sleepers("sleep 4801", "sleep 4802", "sleep 4803", "sleep 4804").forEach(ProcessHandle::destroyForcibly);
        } finally {
            ShellSandboxSync.release();
        }
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath(AGENT));
        deleteDir(HarnessSandbox.customAgentTempDir(AgentService.workspacePath(AGENT).toFile()));
    }

    /** {@code main}, unsaved: {@code Fixtures.deleteDatabase} removed its row. */
    private static Agent main() {
        var main = new Agent();
        main.id = Long.MAX_VALUE;
        main.name = Agent.MAIN_AGENT_NAME;
        return main;
    }

    private JsonObject run(Agent who, String command) {
        var args = new JsonObject();
        args.addProperty("command", command);
        return JsonParser.parseString(exec(who, args)).getAsJsonObject();
    }

    private String exec(Agent who, JsonObject args) {
        return ToolContext.withScope(null, null, () -> tool.execute(args.toString(), who));
    }

    private static List<String> lines(JsonObject result) {
        return result.get("output").getAsString().lines().toList();
    }

    private static void assumeWorkingSandbox() {
        var availability = HarnessSandbox.availability();
        assumeTrue(availability.available(), "no working sandbox here: " + availability.reason());
    }

    private Process startSleep() throws IOException {
        var process = new ProcessBuilder("/bin/sleep", "120").start();
        started.add(process);
        return process;
    }

    /** One {@code kill} per signal at {@code pid}, each followed by a line naming the signal and kill's exit status. */
    private static String everySignalAt(long pid) {
        var command = new StringBuilder();
        for (var signal : SIGNALS) {
            command.append("kill -%s %d 2>/dev/null; echo %s=$?; ".formatted(signal, pid, signal));
        }
        return command.toString();
    }

    /** No signal from a custom agent's run reaches a process outside its sandbox, and main's unconfined exec ends that same process. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentCannotSignalAProcessOutsideItsSandbox() throws Exception {
        assumeWorkingSandbox();
        var outside = startSleep();

        var refused = run(agent, everySignalAt(outside.pid()));

        assertEquals(SIGNALS.stream().map(signal -> signal + "=1").toList(), lines(refused), refused.toString());
        assertTrue(outside.isAlive(), "no signal may have reached the process outside the sandbox");

        var why = run(agent, "kill %d".formatted(outside.pid()));
        assertEquals(1, why.get("exitCode").getAsInt(), why.toString());
        if (OS.MAC.isCurrentOs()) {
            assertEquals("/bin/sh: line 1: kill: (%d) - Operation not permitted".formatted(outside.pid()),
                    why.get("output").getAsString());
        }
        assertTrue(outside.isAlive(), why.toString());

        var control = run(main(), "kill %d; echo term=$?".formatted(outside.pid()));
        assertEquals(List.of("term=0"), lines(control), "unconfined, the same kill must land or the test proves nothing");
        assertTrue(outside.waitFor(10, TimeUnit.SECONDS), control.toString());
        assertEquals(143, outside.exitValue());
    }

    /** Inside one run a signal is still delivered: a command stops the job it started, and a subshell its own shell. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentStillSignalsTheProcessesOfItsOwnRun() {
        assumeWorkingSandbox();

        var job = run(agent, "sleep 4803 & kill $!; wait $!; echo status=$?");

        assertEquals(0, job.get("exitCode").getAsInt(), job.toString());
        assertTrue(lines(job).contains("status=143"), "the job took the TERM its own run sent: " + job);
        assertEquals(List.of(), sleepers("sleep 4803"));

        var shell = run(agent, "echo before; (kill -TERM $$); echo after=$?");

        assertEquals(143, shell.get("exitCode").getAsInt(), "the run's shell took the TERM its subshell sent: " + shell);
        assertEquals("before", shell.get("output").getAsString(), shell.toString());
    }

    /**
     * Each run is a sandbox of its own, so a later run's kill does not reach what an earlier run of the same
     * agent left in the background. exec stops it for the agent from outside the sandbox instead.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aLaterRunCannotSignalWhatAnEarlierRunLeftAndExecStopsItForTheAgent() throws Exception {
        assumeWorkingSandbox();
        var background = new JsonObject();
        background.addProperty("command", "sleep 4801");
        background.addProperty("background", true);
        var left = JsonParser.parseString(exec(agent, background)).getAsJsonObject();
        long pid = awaitSleeper("sleep 4801").pid();
        assertTrue(left.get("output").getAsString().matches("(?s).*; pids? [0-9, ]*\\b" + pid + "\\b.*"),
                "the result names the pid a stop takes: " + left);

        var later = run(agent, everySignalAt(pid));

        assertEquals(SIGNALS.stream().map(signal -> signal + "=1").toList(), lines(later), later.toString());
        assertEquals(1, sleepers("sleep 4801").size(), "the earlier run's process is still running");

        var stop = new JsonObject();
        stop.addProperty("stop", pid);
        assertEquals("Stopped background process %d and every process it started.".formatted(pid), exec(agent, stop));
        awaitGone("sleep 4801");
    }

    /**
     * A signal a run aims at its own process group reaches only that run. JClaw starts the run, so the run is
     * born into JClaw's process group, and a process group is not bounded by a PID namespace: the run needs a
     * group of its own. A launcher stands in for JClaw here, in a session the test starts for it, with a sleep
     * beside the run as JClaw's other process; unconfined, the same command ends both.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aSignalToTheRunsOwnProcessGroupReachesNothingOutsideTheRun() throws Exception {
        assumeWorkingSandbox();
        assumeTrue(Files.isExecutable(PERL), "no perl here to start a session of the test's own");
        var workspace = AgentService.workspacePath(AGENT);
        Files.createDirectories(workspace);
        for (var signal : List.of("TERM", "KILL")) {
            int killedBy = 128 + (signal.equals("TERM") ? 15 : 9);
            var shell = List.of("/bin/sh", "-c", "kill -%s 0".formatted(signal));
            var confined = HarnessSandbox.wrapCustomAgentShell(shell, workspace.toFile());

            var inside = inAProcessGroupOfItsOwn(confined.argv(), confined.env(), workspace);

            assertEquals(new GroupOutcome("run=%d\n".formatted(killedBy), 0, 1), inside,
                    "only the run's own shell took " + signal + ": the launcher went on and its sleep still runs");

            var control = inAProcessGroupOfItsOwn(shell, Map.of(), workspace);

            assertEquals(new GroupOutcome("", killedBy, 0), control,
                    "unconfined, " + signal + " must end the launcher and its sleep or the test proves nothing");
        }
    }

    /**
     * Starts {@code run} from a launcher that leads a session and a process group of its own, beside a sleep the
     * launcher starts first. The launcher prints the run's exit status and exits; it starts nothing unless it
     * leads its group, so a signal to the group can only reach processes started here.
     */
    private GroupOutcome inAProcessGroupOfItsOwn(List<String> run, Map<String, String> env, Path workdir) throws Exception {
        var argv = new ArrayList<>(List.of(PERL.toString(), "-MPOSIX", "-e",
                "(POSIX::setsid() > 0 && getpgrp() == $$) or exit 97; exec @ARGV; exit 98",
                "/bin/sh", "-c", "/bin/sleep 4804 >/dev/null 2>&1 &\n\"$@\"\necho \"run=$?\"", "launcher"));
        argv.addAll(run);
        // To a file, not a pipe: a process that outlived the launcher would hold a pipe open and the read would not end.
        var printed = Files.createTempFile("jf303-group", ".out");
        try {
            // The launcher's shell reports a command a signal ended on stderr, with its pid.
            var builder = new ProcessBuilder(argv).directory(workdir.toFile())
                    .redirectOutput(printed.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(env);
            var launcher = builder.start();
            started.add(launcher);
            assertTrue(launcher.waitFor(30, TimeUnit.SECONDS), "the launcher ended");
            if (launcher.exitValue() != 0) {
                // A signal that ended the launcher reached its sleep in the same call; the sleep is gone a moment later.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!sleepers("sleep 4804").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
            }
            int left = sleepers("sleep 4804").size();
            sleepers("sleep 4804").forEach(ProcessHandle::destroyForcibly);
            awaitGone("sleep 4804");
            return new GroupOutcome(Files.readString(printed), launcher.exitValue(), left);
        } finally {
            Files.deleteIfExists(printed);
        }
    }

    /** main's exec is as before: unconfined and confined under shell.sandbox alike, its signal reaches a process outside. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void mainsExecStillSignalsAProcessOutsideItWithAndWithoutTheKey() throws Exception {
        var first = startSleep();
        var unconfined = run(main(), "kill %d; echo term=$?".formatted(first.pid()));
        assertEquals(List.of("term=0"), lines(unconfined));
        assertTrue(first.waitFor(10, TimeUnit.SECONDS), unconfined.toString());
        assertEquals(143, first.exitValue());

        assumeWorkingSandbox();
        var second = startSleep();
        ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
        assertEquals(ShellExecTool.Confinement.KEY, ShellExecTool.confinement(main(), true));
        var confined = run(main(), "kill %d; echo term=$?".formatted(second.pid()));
        assertEquals(List.of("term=0"), lines(confined));
        assertTrue(second.waitFor(10, TimeUnit.SECONDS), confined.toString());
        assertEquals(143, second.exitValue());
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
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
