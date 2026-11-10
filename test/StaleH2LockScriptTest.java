import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's {@code check_stale_h2_lock_or_exit} against lock files shaped as H2 writes
 * them (JF-267). H2 stores the lock as a Java properties file, so the port is escaped as
 * {@code host\:port}, and an {@code AUTO_SERVER} lock says {@code method=file} beside its
 * {@code server=} line.
 */
class StaleH2LockScriptTest extends UnitTest {
    private static final List<String> FUNCTIONS = List.of("probe_tcp_reachable", "check_stale_h2_lock_or_exit");

    @TempDir Path tmp;

    private record Run(int exit, String out) {}

    @Test
    void anAutoServerLockWhoseServerIsGoneIsRemovedAndTheStartGoesOn() throws Exception {
        // Port 1 on loopback has no listener, so the probe is refused at once.
        var lock = writeLock("method=file\nserver=127.0.0.1\\:1\n");

        var run = run();

        assertEquals(0, run.exit(), run.out());
        assertEquals("==> Removed stale H2 lock pointing at 127.0.0.1:1\n", run.out());
        assertFalse(Files.exists(lock), "the stale lock is removed");
    }

    @Test
    void aLockWhoseServerAnswersStopsTheStart() throws Exception {
        try (var holder = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = holder.getLocalPort();
            var lock = writeLock("method=file\nserver=127.0.0.1\\:" + port + "\n");

            var run = run();

            assertEquals(1, run.exit(), run.out());
            assertEquals("Error: another instance is already using this database at 127.0.0.1:" + port + ".\n"
                    + "       Stop it before starting again ('jclaw.sh stop' or 'docker compose down').\n"
                    + "       (If you're sure no other instance exists, remove " + lock + " manually.)\n", run.out());
            assertTrue(Files.exists(lock), "a live holder's lock stays");
        }
    }

    @Test
    void aSocketLockWithTheOldTcpPrefixIsProbedTheSameWay() throws Exception {
        var lock = writeLock("method=socket\nserver=tcp://127.0.0.1\\:1\n");

        var run = run();

        assertEquals(0, run.exit(), run.out());
        assertEquals("==> Removed stale H2 lock pointing at 127.0.0.1:1\n", run.out());
        assertFalse(Files.exists(lock));
    }

    @Test
    void aLockWithoutAServerLineIsLeftForH2ToJudge() throws Exception {
        var lock = writeLock("method=file\n");

        var run = run();

        assertEquals(0, run.exit(), run.out());
        assertEquals("", run.out());
        assertTrue(Files.exists(lock), "nothing to probe, so nothing is removed");
    }

    @Test
    void noLockFileIsANoOp() throws Exception {
        Files.createDirectories(tmp.resolve("data"));

        var run = run();

        assertEquals(0, run.exit(), run.out());
        assertEquals("", run.out());
    }

    private Path writeLock(String body) throws Exception {
        var lock = Files.createDirectories(tmp.resolve("data")).resolve("jclaw.lock.db");
        Files.writeString(lock, "#FileLock\n#Tue Nov 10 16:53:22 GMT+08:00 2026\nhostName=127.0.0.1\nid=abc\n" + body);
        return lock;
    }

    private Run run() throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\nSCRIPT_DIR='" + tmp + "'\n");
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append("check_stale_h2_lock_or_exit\n");
        // $0 is what the message names as the stop command; the script's own name keeps the assertion exact.
        var pb = new ProcessBuilder("bash", "-c", harness.toString(), "jclaw.sh").redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        return new Run(p.exitValue(), out);
    }
}
