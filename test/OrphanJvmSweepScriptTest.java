import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's {@code kill_orphan_jvms}, the sweep at the end of every prod stop, against real processes whose
 * command lines take the shapes the play1 fork gives its JVMs. Each test names a checkout path no real JVM carries,
 * so the sweep can reach only the processes the test started.
 */
class OrphanJvmSweepScriptTest extends UnitTest {

    private static final String EXITS_ON_SIGTERM = "trap 'exit 0' TERM; while :; do sleep 0.1; done";
    private static final String IGNORES_SIGTERM = "trap '' TERM; while :; do sleep 0.1; done";

    private final String checkout = "/nonexistent/jf620-" + UUID.randomUUID();
    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void stopWhatTheTestStarted() {
        started.forEach(Process::destroyForcibly);
    }

    @Test
    void aProdServerOfThisCheckoutIsTerminated() throws Exception {
        var server = start(EXITS_ON_SIGTERM, "-Dplay.id=prod", "-Dapplication.path=" + checkout,
                "play.server.Server", "--http.port=9000");

        assertEquals(terminating(server), sweep());
        assertTrue(server.waitFor(5, TimeUnit.SECONDS), "the server outlived the sweep");
    }

    @Test
    void theTestRunnerAndThePlayToolJvmsOfThisCheckoutAreSpared() throws Exception {
        var firephoque = start(EXITS_ON_SIGTERM, "-Dapplication.url=http://localhost:9460",
                "-Dapplication.path=" + checkout, "-DheadlessBrowser=true", "play.modules.testrunner.FirePhoque");
        var evolutions = start(EXITS_ON_SIGTERM, "-Dapplication.path=" + checkout, "play.db.Evolutions");

        assertEquals("", sweep());
        assertTrue(firephoque.isAlive(), "the sweep ended FirePhoque");
        assertTrue(evolutions.isAlive(), "the sweep ended playEvolutions");
    }

    @Test
    void theTestServerAndAnotherCheckoutsServerAreSpared() throws Exception {
        var testServer = start(EXITS_ON_SIGTERM, "-Dplay.id=test", "-Dapplication.path=" + checkout,
                "play.server.Server", "--http.port=9460");
        var otherCheckout = start(EXITS_ON_SIGTERM, "-Dapplication.path=" + checkout + "-old", "play.server.Server");

        assertEquals("", sweep());
        assertTrue(testServer.isAlive(), "the sweep ended the test server");
        assertTrue(otherCheckout.isAlive(), "the sweep ended another checkout's server");
    }

    @Test
    void aDevServerThatIgnoresSigtermIsKilled() throws Exception {
        var server = start(IGNORES_SIGTERM, "-Dapplication.path=" + checkout, "play.server.Server");

        assertEquals(terminating(server) + "         pid " + server.pid()
                + " ignored SIGTERM — escalating to kill -9.\n", sweep());
        assertTrue(server.waitFor(5, TimeUnit.SECONDS), "the server outlived kill -9");
    }

    // --- helpers ---

    /** A process whose command line reads {@code /bin/bash -c <loop> jvm <words…>}, as ps shows a JVM's. */
    private Process start(String loop, String... words) throws Exception {
        var command = new ArrayList<>(List.of("/bin/bash", "-c", loop, "jvm"));
        command.addAll(List.of(words));
        var p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        started.add(p);
        return p;
    }

    private static String terminating(Process p) {
        return "Warning: orphan JClaw JVM (pid " + p.pid() + ") found by process signature — terminating.\n";
    }

    private String sweep() throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "jclaw.sh"));
        var m = Pattern.compile("(?ms)^kill_orphan_jvms\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
        assertTrue(m.find(), "jclaw.sh no longer defines kill_orphan_jvms");
        var pb = new ProcessBuilder("/bin/bash", "-c", "set -euo pipefail\n" + m.group() + "kill_orphan_jvms\n")
                .redirectErrorStream(true);
        pb.environment().put("SCRIPT_DIR", checkout);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the sweep hung");
        assertEquals(0, p.exitValue(), out);
        return out;
    }
}
