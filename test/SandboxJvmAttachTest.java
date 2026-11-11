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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JF-290: a custom agent's {@code exec} can neither attach to a JVM outside its sandbox nor signal one into
 * starting its attach listener, while {@code main}'s unconfined {@code exec} does both against the same JVM.
 * Every JVM attached to here is a throwaway the test starts and stops.
 */
class SandboxJvmAttachTest extends UnitTest {

    private static final String AGENT = "jvm-attach-agent";
    private static final Path JCMD = Path.of(System.getProperty("java.home"), "bin", "jcmd");
    /** The first line {@code VM.version} answers with: only a completed attach prints it. */
    private static final String VM_VERSION_LINE =
            System.getProperty("java.vm.name") + " version " + System.getProperty("java.vm.version");

    private static final String SLEEPER = """
            public class Jf290Sleeper {
                public static void main(String[] args) throws Exception {
                    var limit = new Thread(() -> {
                        try { Thread.sleep(120_000); } catch (InterruptedException e) { }
                        Runtime.getRuntime().halt(3);
                    });
                    limit.setDaemon(true);
                    limit.start();
                    System.out.println("ready " + ProcessHandle.current().pid() + " " + System.getProperty("java.io.tmpdir"));
                    while (System.in.read() >= 0) { }
                }
            }
            """;

    /** A throwaway JVM and the socket its attach listener binds once started. */
    private record Jvm(long pid, Path socket) {}

    private ShellExecTool tool;
    private Agent agent;
    private Path scratch;
    private final List<Process> started = new ArrayList<>();
    private final List<Path> sockets = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        ShellSandboxSync.acquire();
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        tool = new ShellExecTool();
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
        ConfigService.set("shell.allowlist", "jcmd,kill,sleep,mv,ln,rm,echo,cat");
        scratch = Files.createTempDirectory("jclaw-jf290-");
    }

    @AfterEach
    void teardown() throws Exception {
        try {
            for (var process : started) {
                process.getOutputStream().close();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
            for (var socket : sockets) Files.deleteIfExists(socket);
            deleteDir(scratch);
        } finally {
            ShellSandboxSync.release();
        }
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath(AGENT));
    }

    /** {@code main}, unsaved: {@code Fixtures.deleteDatabase} removed its row, and it runs {@code exec} unconfined. */
    private static Agent main() {
        var main = new Agent();
        main.id = Long.MAX_VALUE;
        main.name = Agent.MAIN_AGENT_NAME;
        return main;
    }

    private JsonObject run(Agent who, String command) {
        var args = new JsonObject();
        args.addProperty("command", command);
        return JsonParser.parseString(tool.execute(args.toString(), who)).getAsJsonObject();
    }

    private static List<String> lines(JsonObject result) {
        return result.get("output").getAsString().lines().toList();
    }

    private static int exit(JsonObject result) {
        return result.get("exitCode").getAsInt();
    }

    private static void assumeWorkingSandbox() {
        var availability = HarnessSandbox.availability();
        assumeTrue(availability.available(), "no working sandbox here: " + availability.reason());
    }

    /** Skipped where no sandbox starts, or where this JDK's jcmd cannot run inside one, as a JDK under the hidden home folder cannot. */
    private void assumeAnAttachCanBeTried() {
        assumeWorkingSandbox();
        assumeTrue(Files.isExecutable(JCMD) && JCMD.toString().indexOf(' ') < 0, "this JDK has no usable jcmd: " + JCMD);
        var help = run(agent, JCMD + " -h");
        assumeTrue(exit(help) == 0, "jcmd does not run inside the sandbox here: " + help);
    }

    private Jvm startJvm() throws Exception {
        var source = Files.writeString(scratch.resolve("Jf290Sleeper.java"), SLEEPER);
        var log = scratch.resolve("jvm-" + UUID.randomUUID() + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", source.toString())
                .directory(scratch.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        started.add(process);
        for (int i = 0; i < 600 && process.isAlive(); i++) {
            var ready = Files.readAllLines(log).stream().filter(l -> l.startsWith("ready ")).findFirst();
            if (ready.isPresent()) {
                var parts = ready.get().split(" ", 3);
                var socket = Path.of(parts[2]).resolve(".java_pid" + parts[1]);
                sockets.add(socket);
                return new Jvm(Long.parseLong(parts[1]), socket);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the throwaway JVM did not start: " + Files.readString(log));
    }

    private Process startSleep() throws IOException {
        var process = new ProcessBuilder("/bin/sleep", "120").start();
        started.add(process);
        return process;
    }

    private static String attachTo(Jvm jvm) {
        return "%s %d VM.version".formatted(JCMD, jvm.pid());
    }

    /**
     * A JVM nobody has attached to yet: the sandboxed attempt is refused and leaves its attach listener
     * unstarted, and main's unconfined exec then attaches to that same JVM.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentCannotSignalAJvmOutsideItsSandboxIntoAttaching() throws Exception {
        assumeAnAttachCanBeTried();
        var jvm = startJvm();

        var confined = run(agent, attachTo(jvm));
        assertEquals(1, exit(confined), confined.toString());
        assertFalse(lines(confined).contains(VM_VERSION_LINE), "the JVM must not have answered: " + confined);
        if (OS.MAC.isCurrentOs()) {
            assertTrue(lines(confined).contains("java.io.IOException: Operation not permitted"), confined.toString());
        }
        assertFalse(Files.exists(jvm.socket()), "the JVM must not have started its attach listener: " + jvm.socket());

        var control = run(main(), attachTo(jvm));
        assertEquals(0, exit(control), "unconfined, the same attempt must attach or the test proves nothing: " + control);
        assertTrue(lines(control).contains(VM_VERSION_LINE), control.toString());
        assertTrue(Files.exists(jvm.socket()), "the listener's socket is where the test looked for it: " + jvm.socket());
    }

    /** A JVM whose attach listener is already up, as after an operator's own jcmd: its socket takes no connection from the sandbox. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentCannotAttachToAJvmThatIsAlreadyListening() throws Exception {
        assumeAnAttachCanBeTried();
        var jvm = startJvm();

        var control = run(main(), attachTo(jvm));
        assertEquals(0, exit(control), "unconfined, the attempt must attach or the test proves nothing: " + control);
        assertTrue(lines(control).contains(VM_VERSION_LINE), control.toString());
        assertTrue(Files.exists(jvm.socket()), "the JVM is listening: " + jvm.socket());

        var confined = run(agent, attachTo(jvm));
        assertEquals(1, exit(confined), confined.toString());
        assertFalse(lines(confined).contains(VM_VERSION_LINE), "the JVM must not have answered: " + confined);
        if (OS.MAC.isCurrentOs()) {
            assertTrue(lines(confined).contains("java.io.IOException: Operation not permitted"), confined.toString());
        }
    }

    /**
     * The macOS rule goes by the socket's name, so the sandbox may not give it another: no rename, no hard
     * link, no unlink. Unconfined, the link and the rename both work on this volume.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacACustomAgentCannotRenameLinkOrRemoveAListeningJvmsSocket() throws Exception {
        assumeAnAttachCanBeTried();
        var jvm = startJvm();
        assertEquals(0, exit(run(main(), attachTo(jvm))));
        var socket = jvm.socket();
        var alias = socket.resolveSibling("jclaw-jf290-alias-" + UUID.randomUUID());
        Files.createLink(alias, socket);
        Files.delete(alias);
        Files.move(socket, alias);
        Files.move(alias, socket);

        var result = run(agent, "mv '%s' moved.sock; echo mv=$?; ln '%s' linked.sock; echo ln=$?; rm -f '%s'; echo rm=$?"
                .formatted(socket, socket, socket));

        assertTrue(lines(result).containsAll(List.of("mv=1", "ln=1", "rm=1")), result.toString());
        var workspace = AgentService.workspacePath(AGENT);
        assertFalse(Files.exists(workspace.resolve("moved.sock")), result.toString());
        assertFalse(Files.exists(workspace.resolve("linked.sock")), result.toString());
        assertTrue(Files.exists(socket), result.toString());
        assertEquals(0, exit(run(main(), attachTo(jvm))), "the socket still answers an attach from outside the sandbox");
    }

    /**
     * Only SIGQUIT is held inside the sandbox on macOS. A process outside refuses it and still takes SIGTERM,
     * which is how a later run stops what an earlier one left in the background; the run's own child takes it.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacACustomAgentSendsSigquitOnlyInsideItsOwnSandbox() throws Exception {
        assumeWorkingSandbox();
        var outside = startSleep();

        var quit = run(agent, "kill -QUIT %d; echo quit=$?".formatted(outside.pid()));
        assertEquals("quit=1", lines(quit).getLast(), quit.toString());
        assertTrue(outside.isAlive(), "SIGQUIT must not have reached the process outside the sandbox");

        var term = run(agent, "kill %d; echo term=$?".formatted(outside.pid()));
        assertEquals(List.of("term=0"), lines(term));
        assertTrue(outside.waitFor(10, TimeUnit.SECONDS), "SIGTERM must still reach a process outside the sandbox");
        assertEquals(143, outside.exitValue());

        var own = run(agent, "sleep 30 & kill -QUIT $!; wait $!; echo own=$?");
        assertTrue(lines(own).contains("own=131"), "the run's own child must still take SIGQUIT: " + own);

        var control = startSleep();
        var unconfined = run(main(), "kill -QUIT %d; echo quit=$?".formatted(control.pid()));
        assertEquals(List.of("quit=0"), lines(unconfined));
        assertTrue(control.waitFor(10, TimeUnit.SECONDS), "unconfined, SIGQUIT is delivered");
        assertEquals(131, control.exitValue());
    }

    /** What a custom agent is for still passes: an allowlisted command reads and writes in its workspace. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentStillRunsAnAllowlistedCommandInItsWorkspace() throws IOException {
        assumeWorkingSandbox();

        var result = run(agent, "echo kept > note.txt; cat note.txt");

        assertEquals(0, exit(result), result.toString());
        assertEquals("kept", result.get("output").getAsString());
        assertEquals("kept\n", Files.readString(AgentService.workspacePath(AGENT).resolve("note.txt")));
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
