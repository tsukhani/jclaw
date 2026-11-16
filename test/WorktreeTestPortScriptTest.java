import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Runs jclaw.sh's {@code ensure_play_test_port} on a fresh worktree's {@code certs/.env}. The port comes
 * from the worktree's path and steps to the next free one in 9100-9899 past a port another checkout of the
 * repository holds, a port something listens on, and a sidecar's default port (9527-9533), where a test
 * server would stop the live instance from starting that sidecar. A fake {@code lsof} says which ports are
 * listening, so a port this machine happens to use cannot move the result.
 */
class WorktreeTestPortScriptTest extends UnitTest {

    private static final String SECRET = "PLAY_SECRET=a-test-secret\n";

    /** A bash function rather than a script on the PATH: a process per port made the 800-port case take minutes. */
    private static final String FAKE_LSOF = """
            lsof() {
                [[ $# -eq 3 && "$1" == -nP && "$2" == -iTCP:* && "$3" == -sTCP:LISTEN ]] || return 2
                [[ " $LISTENING " == *" ${2#-iTCP:} "* ]]
            }
            """;

    @TempDir Path tmp;

    private Path base;
    private int candidate;
    private List<Integer> listening = List.of();

    @BeforeEach
    void resolveTheTempDir() throws Exception {
        base = tmp.toRealPath();
    }

    @Test
    void aPathThatHashesOntoTheSidecarPortsStepsPastThem() throws Exception {
        var first = outsideGit(pathsHashingTo(9527, 1).getFirst());
        var last = outsideGit(pathsHashingTo(9533, 1).getFirst());

        assertEquals("PLAY_TEST_PORT=9534", ensurePort(first));
        assertEquals("PLAY_TEST_PORT=9534", ensurePort(last));
    }

    @Test
    void aPathThatHashesBesideTheSidecarPortsKeepsItsPort() throws Exception {
        var below = outsideGit(pathsHashingTo(9526, 1).getFirst());
        var above = outsideGit(pathsHashingTo(9534, 1).getFirst());

        assertEquals("PLAY_TEST_PORT=9526", ensurePort(below));
        assertEquals("PLAY_TEST_PORT=9534", ensurePort(above));
    }

    @Test
    void twoWorktreesWhosePathsHashToOnePortGetDifferentPorts() throws Exception {
        var worktrees = pathsHashingTo(9200, 2);
        repository(worktrees);

        assertEquals("PLAY_TEST_PORT=9200", ensurePort(worktrees.get(0)));
        assertEquals("PLAY_TEST_PORT=9201", ensurePort(worktrees.get(1)));
    }

    @Test
    void aPortTheMainCheckoutHoldsIsNotHandedOut() throws Exception {
        var worktree = pathsHashingTo(9200, 1).getFirst();
        var main = repository(List.of(worktree));
        Files.writeString(main.resolve("certs/.env"), SECRET + "PLAY_TEST_PORT=9200\n");

        assertEquals("PLAY_TEST_PORT=9201", ensurePort(worktree));
    }

    @Test
    void aListeningPortIsNotHandedOut() throws Exception {
        var worktree = outsideGit(pathsHashingTo(9200, 1).getFirst());
        listening(9200);

        assertEquals("PLAY_TEST_PORT=9201", ensurePort(worktree));
    }

    @Test
    void steppingGoesOnPastEveryTakenPortInARow() throws Exception {
        var paths = pathsHashingTo(9200, 2);
        var worktree = paths.get(0);
        var other = paths.get(1);
        var main = repository(paths);
        Files.writeString(main.resolve("certs/.env"), SECRET + "PLAY_TEST_PORT=9200\n");
        Files.writeString(other.resolve("certs/.env"), SECRET + "PLAY_TEST_PORT=9201\n");
        listening(9202);

        assertEquals("PLAY_TEST_PORT=9203", ensurePort(worktree));
    }

    @Test
    void aWorktreeHoldingTheNextPortLeavesThisOneItsOwn() throws Exception {
        var worktree = pathsHashingTo(9200, 1).getFirst();
        var other = pathsHashingTo(9300, 1).getFirst();
        repository(List.of(worktree, other));
        Files.writeString(other.resolve("certs/.env"), SECRET + "PLAY_TEST_PORT=9201\n");
        listening(9199);

        assertEquals("PLAY_TEST_PORT=9200", ensurePort(worktree));
    }

    @Test
    void steppingPastTheTopOfTheRangeWrapsToItsBottom() throws Exception {
        var worktree = outsideGit(pathsHashingTo(9899, 1).getFirst());
        listening(9899);

        assertEquals("PLAY_TEST_PORT=9100", ensurePort(worktree));
    }

    @Test
    void whenEveryPortIsTakenItWritesNoneAndFails() throws Exception {
        var worktree = outsideGit(pathsHashingTo(9200, 1).getFirst());
        listening(IntStream.rangeClosed(9100, 9899).boxed().toArray(Integer[]::new));

        var result = runFunction(worktree);

        assertEquals(1, result.exit());
        assertEquals("    No free PLAY_TEST_PORT in 9100-9899: set one in " + worktree.resolve("certs/.env")
                + " by hand.\n", result.output());
        assertEquals(SECRET, Files.readString(worktree.resolve("certs/.env")));
    }

    @Test
    void anExistingPortIsLeftUntouchedEvenWhenTaken() throws Exception {
        var worktree = pathsHashingTo(9200, 1).getFirst();
        var main = repository(List.of(worktree));
        Files.writeString(main.resolve("certs/.env"), SECRET + "PLAY_TEST_PORT=9377\n");
        var env = SECRET + "\n# set by hand\nPLAY_TEST_PORT=9377\n";
        Files.writeString(worktree.resolve("certs/.env"), env);
        listening(9377);

        var result = runFunction(worktree);

        assertEquals(0, result.exit(), result.output());
        assertEquals("    PLAY_TEST_PORT already set to 9377 — leaving it untouched.\n", result.output());
        assertEquals(env, Files.readString(worktree.resolve("certs/.env")));
    }

    // --- helpers ---

    /** {@code count} paths under the temp dir that {@code cksum} maps to {@code port} (9100 + crc % 800), as jclaw.sh hashes a worktree's path. */
    private List<Path> pathsHashingTo(int port, int count) throws Exception {
        var found = new ArrayList<Path>();
        var candidates = Files.createDirectories(base.resolve("candidates"));
        for (int batch = 0; found.size() < count; batch++) {
            assertTrue(batch < 20, () -> "no " + count + " paths found for port " + port + ": " + found);
            var files = new ArrayList<String>();
            for (int i = 0; i < 2000; i++) {
                var name = "c" + candidate++;
                Files.writeString(candidates.resolve(name), base.resolve("wt-" + name).toString());
                files.add(name);
            }
            var command = new ArrayList<>(List.of("cksum"));
            command.addAll(files);
            for (var line : run(new ProcessBuilder(command).directory(candidates.toFile())).output().lines().toList()) {
                var fields = line.split(" ");
                if (found.size() < count && 9100 + Long.parseLong(fields[0]) % 800 == port) {
                    found.add(base.resolve("wt-" + fields[2]));
                }
            }
        }
        return found;
    }

    /** A directory outside any git repository, as a dist bundle is, with a certs/.env that holds only a secret. */
    private Path outsideGit(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("certs"));
        Files.writeString(dir.resolve("certs/.env"), SECRET);
        return dir;
    }

    /** A git repository whose main checkout has a linked worktree at each path; every checkout's certs/.env holds only a secret. */
    private Path repository(List<Path> worktrees) throws Exception {
        var main = Files.createDirectories(base.resolve("main"));
        git(main, "init", "-q");
        git(main, "-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
                "commit", "-q", "--allow-empty", "-m", "init");
        outsideGit(main);
        for (var worktree : worktrees) {
            git(main, "worktree", "add", "-q", "--detach", worktree.toString());
            outsideGit(worktree);
        }
        return main;
    }

    private void listening(Integer... ports) {
        listening = List.of(ports);
    }

    /** Runs {@code ensure_play_test_port} with {@code SCRIPT_DIR} at the worktree; returns the line it added to certs/.env. */
    private String ensurePort(Path worktree) throws Exception {
        var result = runFunction(worktree);
        assertEquals(0, result.exit(), result.output());
        return Files.readAllLines(worktree.resolve("certs/.env")).getLast();
    }

    private Result runFunction(Path worktree) throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "jclaw.sh"));
        var m = Pattern.compile("(?ms)^ensure_play_test_port\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
        assertTrue(m.find(), "jclaw.sh no longer defines ensure_play_test_port");
        var ports = listening.stream().map(String::valueOf).collect(Collectors.joining(" "));
        return exec(new ProcessBuilder("/bin/bash", "-c", "set -euo pipefail\nSCRIPT_DIR='" + worktree
                + "'\nLISTENING='" + ports + "'\n" + FAKE_LSOF + m.group() + "ensure_play_test_port\n"));
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("/usr/bin/git", "-C", dir.toString(), "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(args));
        run(new ProcessBuilder(command));
    }

    private static Result run(ProcessBuilder pb) throws Exception {
        var result = exec(pb);
        assertEquals(0, result.exit(), result.output());
        return result;
    }

    private static Result exec(ProcessBuilder pb) throws Exception {
        pb.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        var p = pb.redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the command hung");
        return new Result(p.exitValue(), out);
    }

    private record Result(int exit, String output) {}
}
