import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's {@code ensure_play_test_port} on a fresh worktree's {@code certs/.env}. The port comes
 * from the worktree's path, and never lands on a sidecar's default port (9527-9533), where a test server
 * would stop the live instance from starting that sidecar.
 */
class WorktreeTestPortScriptTest extends UnitTest {

    @TempDir Path tmp;

    @Test
    void aPathThatHashesOntoTheSidecarPortsGetsThePortSevenAbove() throws Exception {
        var worktrees = worktreesHashingTo(List.of(9527, 9533));

        assertEquals("PLAY_TEST_PORT=9534", ensurePort(worktrees.get(9527)));
        assertEquals("PLAY_TEST_PORT=9540", ensurePort(worktrees.get(9533)));
    }

    @Test
    void aPathThatHashesBesideTheSidecarPortsKeepsItsPort() throws Exception {
        var worktrees = worktreesHashingTo(List.of(9526, 9534));

        assertEquals("PLAY_TEST_PORT=9526", ensurePort(worktrees.get(9526)));
        assertEquals("PLAY_TEST_PORT=9534", ensurePort(worktrees.get(9534)));
    }

    // --- helpers ---

    /** A worktree directory for each port, whose path {@code cksum} maps there (9100 + crc % 800), as jclaw.sh hashes it. */
    private Map<Integer, Path> worktreesHashingTo(List<Integer> ports) throws Exception {
        var found = new HashMap<Integer, Path>();
        var candidates = Files.createDirectories(tmp.resolve("candidates"));
        for (int batch = 0; found.size() < ports.size(); batch++) {
            assertTrue(batch < 20, () -> "no path found for every port in " + ports + ": " + found);
            var files = new ArrayList<String>();
            for (int i = 0; i < 2000; i++) {
                var name = "c" + batch + "-" + i;
                Files.writeString(candidates.resolve(name), tmp.resolve("wt-" + name).toString());
                files.add(name);
            }
            var command = new ArrayList<>(List.of("cksum"));
            command.addAll(files);
            for (var line : run(new ProcessBuilder(command).directory(candidates.toFile())).lines().toList()) {
                var fields = line.split(" ");
                int port = (int) (9100 + Long.parseLong(fields[0]) % 800);
                if (ports.contains(port)) found.putIfAbsent(port, tmp.resolve("wt-" + fields[2]));
            }
        }
        for (var worktree : found.values()) {
            Files.createDirectories(worktree.resolve("certs"));
            Files.writeString(worktree.resolve("certs/.env"), "PLAY_SECRET=a-test-secret\n");
        }
        return found;
    }

    /** Runs {@code ensure_play_test_port} with {@code SCRIPT_DIR} at the worktree; returns the line it added to certs/.env. */
    private String ensurePort(Path worktree) throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "jclaw.sh"));
        var m = Pattern.compile("(?ms)^ensure_play_test_port\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
        assertTrue(m.find(), "jclaw.sh no longer defines ensure_play_test_port");
        run(new ProcessBuilder("/bin/bash", "-c",
                "set -euo pipefail\nSCRIPT_DIR='" + worktree + "'\n" + m.group() + "ensure_play_test_port\n"));
        return Files.readAllLines(worktree.resolve("certs/.env")).getLast();
    }

    private static String run(ProcessBuilder pb) throws Exception {
        var p = pb.redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the command hung");
        assertEquals(0, p.exitValue(), out);
        return out;
    }
}
