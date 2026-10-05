import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.ConfigService;
import services.factory.FactoryHome;
import services.factory.FactoryProcess;
import services.factory.FactoryStatus;
import utils.AppClock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The Software Factory status service (JCLAW-1391) against a temporary factory home and a fake
 * command runner: no Docker, no launchd, never the real {@code ~/.jclaw-factory}.
 */
class FactoryStatusTest extends UnitTest {

    private static final String MAC = "Mac OS X";
    private static final String KEY = "JCLAW-7";

    @TempDir
    Path home;

    private final ApiFactoryControllerTest.FakeRunner runner = new ApiFactoryControllerTest.FakeRunner();

    @BeforeEach
    void setup() throws Exception {
        FactoryRunnerSync.acquire();
        FactoryHome.setHomeForTest(home);
        FactoryProcess.setRunnerForTest(runner);
        FactoryStatus.clearCache();
        Files.createDirectories(home.resolve("logs"));
    }

    @AfterEach
    void clearSeams() {
        FactoryHome.setHomeForTest(null);
        FactoryProcess.setRunnerForTest(null);
        FactoryRunnerSync.release();
    }

    private static FactoryProcess.ExecResult ok(String output) {
        return new FactoryProcess.ExecResult(0, output, false);
    }

    private static final FactoryProcess.ExecResult TIMED_OUT = new FactoryProcess.ExecResult(-1, "", true);
    private static final FactoryProcess.ExecResult NOT_STARTED = new FactoryProcess.ExecResult(-1, "No such file", false);

    private static String launchdList(String pidLine) {
        return "{\n\t\"Label\" = \"com.jclaw.factory\";\n\t\"LastExitStatus\" = 0;\n" + pidLine + "};\n";
    }

    private void board(String stories) throws Exception {
        Files.writeString(home.resolve("board.json"), """
                {"schema": 1, "updatedAt": "2026-10-05T09:12:44.120Z",
                 "harness": {"pid": 4242, "startedAt": "2026-10-05T08:00:01.002Z", "main": null},
                 "settings": {"FACTORY_MAX_PARALLEL": 2, "FACTORY_CPUS": 6, "FACTORY_POLL_SECONDS": 120,
                              "FACTORY_MODEL": "claude-opus-5-5"},
                 "stories": [%s]}
                """.formatted(stories));
    }

    private void boardListing(String... logs) throws Exception {
        var quoted = String.join(", ", java.util.Arrays.stream(logs).map(l -> "\"" + l.replace("\\", "\\\\") + "\"").toList());
        board("""
                {"key": "JCLAW-7", "summary": "s", "source": "jira", "autoMerge": false, "state": "review",
                 "since": "2026-10-05T08:31:40.007Z", "logs": [%s]},
                {"key": "JCLAW-8", "summary": "t", "source": "jira", "autoMerge": true, "state": "running",
                 "phase": "gate-1", "phaseStartedAt": "2026-10-05T09:10:02.311Z", "since": "2026-10-05T08:31:40.007Z",
                 "logs": ["JCLAW-8-implement.log"]}
                """.formatted(quoted));
    }

    // --- probes ---

    @Test
    void onMacItReportsTheHarnessGatewayAndRunningSandboxes() {
        runner.replies.put("launchctl list com.jclaw.factory", ok(launchdList("\t\"PID\" = 4242;\n")));
        runner.replies.put("docker ps", ok(String.join("\n",
                "jclaw-factory-gateway\trunning\tUp 3 hours",
                "sandcastle-a\trunning\tUp 12 minutes",
                "sandcastle-b\trunning\tUp About a minute",
                "sandcastle-c\texited\tExited (0) 2 hours ago",
                "postgres\trunning\tUp 2 days",
                "WARNING: a line docker printed on stderr")));
        var clone = "/Users/op/.jclaw-factory/jclaw";
        runner.replies.put("docker inspect", ok("/sandcastle-a\t" + clone + "/.sandcastle/worktrees/agent-JCLAW-7\t"
                + clone + "/.git\n/sandcastle-b\t/tmp/plan\n"));

        var s = FactoryStatus.read(MAC);

        assertTrue(s.supported());
        assertNull(s.reason());
        assertEquals(new FactoryStatus.HarnessView("running", 4242L), s.harness());
        assertEquals("running", s.gateway().state());
        assertEquals(List.of(new FactoryStatus.SandboxView("sandcastle-a", KEY, "12 minutes"),
                new FactoryStatus.SandboxView("sandcastle-b", null, "About a minute")), s.sandboxes());
        assertTrue(runner.calls.contains(List.of("docker", "inspect", "--format",
                "{{.Name}}{{range .Mounts}}\t{{.Source}}{{end}}", "sandcastle-a", "sandcastle-b")), runner.calls::toString);
    }

    @Test
    void aStoppedGatewayReportsItsStateAndAMissingOneIsAbsent() {
        runner.replies.put("docker ps", ok("jclaw-factory-gateway\texited\tExited (0) 1 minute ago\n"));
        assertEquals("exited", FactoryStatus.read(MAC).gateway().state());
        runner.replies.put("docker ps", ok(""));
        var s = FactoryStatus.read(MAC);
        assertEquals("absent", s.gateway().state());
        assertEquals(List.of(), s.sandboxes());
        assertFalse(runner.ranPrefix("docker inspect"), "no sandbox, no inspect");
    }

    @Test
    void theHarnessIsStoppedWhenLaunchdHasNoPidOrNoAgentAndUnknownWhenLaunchctlFails() {
        runner.replies.put("launchctl", ok(launchdList("")));
        assertEquals(new FactoryStatus.HarnessView("stopped", null), FactoryStatus.read(MAC).harness());
        runner.replies.put("launchctl", new FactoryProcess.ExecResult(113, "Could not find service", false));
        assertEquals(new FactoryStatus.HarnessView("stopped", null), FactoryStatus.read(MAC).harness());
        runner.replies.put("launchctl", TIMED_OUT);
        assertEquals(new FactoryStatus.HarnessView("unknown", null), FactoryStatus.read(MAC).harness());
        runner.replies.put("launchctl", NOT_STARTED);
        assertEquals(new FactoryStatus.HarnessView("unknown", null), FactoryStatus.read(MAC).harness());
    }

    @Test
    void dockerThatTimesOutFailsOrIsMissingIsUnsupportedWithAReasonAndUnknownState() {
        runner.replies.put("docker ps", TIMED_OUT);
        var timedOut = FactoryStatus.read(MAC);
        assertFalse(timedOut.supported());
        assertTrue(timedOut.reason().contains("did not answer"), timedOut.reason());
        assertEquals("unknown", timedOut.gateway().state());
        assertNull(timedOut.sandboxes());

        runner.replies.put("docker ps", new FactoryProcess.ExecResult(1,
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock.\n", false));
        var down = FactoryStatus.read(MAC);
        assertFalse(down.supported());
        assertTrue(down.reason().contains("Cannot connect to the Docker daemon"), down.reason());

        runner.replies.put("docker ps", NOT_STARTED);
        assertTrue(FactoryStatus.read(MAC).reason().contains("not installed"));
    }

    @Test
    void anInspectThatTimesOutLeavesTheStoryUnknown() {
        runner.replies.put("docker ps", ok("sandcastle-a\trunning\tUp 1 second\n"));
        runner.replies.put("docker inspect", TIMED_OUT);
        assertEquals(List.of(new FactoryStatus.SandboxView("sandcastle-a", null, "1 second")),
                FactoryStatus.read(MAC).sandboxes());
    }

    @Test
    void offMacOsItIsUnsupportedAndProbesNothing() {
        var s = FactoryStatus.read("Linux");
        assertFalse(s.supported());
        assertTrue(s.reason().contains("only on macOS"), s.reason());
        assertEquals("stopped", s.harness().state());
        assertTrue(runner.calls.isEmpty(), runner.calls::toString);
    }

    @Test
    void probesAreReusedWithinTheTtlAndAskedAgainAfterItOrAClear() {
        var t0 = Instant.parse("2026-10-05T09:00:00Z");
        assertEquals(1, dockerPsCountAfterStatusAt(t0));
        assertEquals(1, dockerPsCountAfterStatusAt(t0.plusMillis(4999)), "within the TTL");
        assertEquals(2, dockerPsCountAfterStatusAt(t0.plusSeconds(5)), "at the TTL");
        assertEquals(2, dockerPsCountAfterStatusAt(t0.plusSeconds(6)));
        FactoryStatus.clearCache();
        assertEquals(3, dockerPsCountAfterStatusAt(t0.plusSeconds(6)), "after a clear");
    }

    private long dockerPsCountAfterStatusAt(Instant at) {
        AppClock.runWith(Clock.fixed(at, ZoneOffset.UTC), () -> FactoryStatus.current(MAC));
        return runner.calls.stream().filter(c -> String.join(" ", c).startsWith("docker ps")).count();
    }

    @Test
    void installedNeedsTheHomeAndTheCheckoutsRunSh() {
        assertTrue(FactoryStatus.read("Linux").installed(), "the checkout carries .sandcastle/run.sh");
        FactoryHome.setHomeForTest(home.resolve("missing"));
        assertFalse(FactoryStatus.read("Linux").installed());
    }

    // --- board ---

    @Test
    void theBoardIsParsedWithItsStoriesAndSettings() throws Exception {
        boardListing("JCLAW-7-implement.log");
        var s = FactoryStatus.read("Linux");
        assertNull(s.boardReason());
        var board = s.board();
        assertEquals(1, board.schema());
        assertEquals(4242L, board.harness().pid());
        assertEquals(new FactoryStatus.BoardSettings(2, 6, 120, "claude-opus-5-5"), board.settings());
        assertEquals(2, board.stories().size());
        var running = board.stories().get(1);
        assertEquals("running", running.state());
        assertEquals("gate-1", running.phase());
        assertTrue(running.autoMerge());
        assertEquals(List.of("JCLAW-7-implement.log"), board.stories().get(0).logs());
    }

    @Test
    void anAbsentUnreadableOrForeignBoardIsNullWithAReason() throws Exception {
        assertNull(FactoryStatus.read("Linux").board());
        assertTrue(FactoryStatus.read("Linux").boardReason().contains("no board.json"));

        Files.writeString(home.resolve("board.json"), "{not json");
        assertNull(FactoryStatus.read("Linux").board());
        assertTrue(FactoryStatus.read("Linux").boardReason().contains("not valid JSON"));

        Files.writeString(home.resolve("board.json"), "{\"schema\": 2, \"stories\": []}");
        assertTrue(FactoryStatus.read("Linux").boardReason().contains("schema 2"));

        Files.writeString(home.resolve("board.json"), "{\"schema\": 1}");
        assertTrue(FactoryStatus.read("Linux").boardReason().contains("without a key or logs"));

        Files.writeString(home.resolve("board.json"), "{\"schema\": 1, \"stories\": [{\"key\": \"JCLAW-1\"}]}");
        assertTrue(FactoryStatus.read("Linux").boardReason().contains("without a key or logs"));
    }

    // --- logs ---

    @Test
    void aListedLogIsServedWhole() throws Exception {
        boardListing("JCLAW-7-implement.log");
        Files.writeString(home.resolve("logs/JCLAW-7-implement.log"), "line one\nline two\n");
        var view = FactoryStatus.log(KEY, "JCLAW-7-implement.log");
        assertEquals("line one\nline two\n", view.text());
        assertEquals(18, view.size());
        assertFalse(view.truncated());
        assertEquals(Files.getLastModifiedTime(home.resolve("logs/JCLAW-7-implement.log")).toInstant(), view.modifiedAt());
    }

    @Test
    void aLogOfExactlyTheCapIsWholeAndOneByteMoreIsItsTail() throws Exception {
        boardListing("JCLAW-7-a.log", "JCLAW-7-b.log");
        var cap = FactoryStatus.LOG_TAIL_BYTES;
        Files.write(home.resolve("logs/JCLAW-7-a.log"), ("x".repeat(cap - 1) + "y").getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("logs/JCLAW-7-b.log"), ("z" + "x".repeat(cap - 1) + "y").getBytes(StandardCharsets.UTF_8));

        var exact = FactoryStatus.log(KEY, "JCLAW-7-a.log");
        assertFalse(exact.truncated());
        assertEquals(cap, exact.text().length());

        var over = FactoryStatus.log(KEY, "JCLAW-7-b.log");
        assertTrue(over.truncated());
        assertEquals(cap + 1L, over.size());
        assertEquals(cap, over.text().length());
        assertFalse(over.text().contains("z"));
        assertTrue(over.text().endsWith("y"));
    }

    @Test
    void aTailCutInsideAUtf8CharacterDropsItsFragment() throws Exception {
        boardListing("JCLAW-7-a.log");
        var cap = FactoryStatus.LOG_TAIL_BYTES;
        // "é" is two bytes; the cut lands on its second byte.
        Files.write(home.resolve("logs/JCLAW-7-a.log"), ("é" + "x".repeat(cap - 1)).getBytes(StandardCharsets.UTF_8));
        var view = FactoryStatus.log(KEY, "JCLAW-7-a.log");
        assertTrue(view.truncated());
        assertEquals("x".repeat(cap - 1), view.text());
    }

    @Test
    void aNameTheStoryDoesNotListIsRefused() throws Exception {
        boardListing("JCLAW-7-implement.log");
        Files.writeString(home.resolve("logs/JCLAW-7-implement.log"), "x");
        Files.writeString(home.resolve("logs/JCLAW-7-other.log"), "x");
        Files.writeString(home.resolve("logs/JCLAW-8-implement.log"), "x");
        assertNull(FactoryStatus.log(KEY, "JCLAW-7-other.log"), "on disk, not on the board");
        assertNull(FactoryStatus.log(KEY, "JCLAW-8-implement.log"), "another story's log");
        assertNull(FactoryStatus.log("JCLAW-9", "JCLAW-7-implement.log"), "a story the board does not hold");
        assertNotNull(FactoryStatus.log("JCLAW-8", "JCLAW-8-implement.log"), "its own story still reads it");
    }

    @Test
    void aListedNameThatLeavesTheLogsDirectoryIsRefused() throws Exception {
        Files.writeString(home.resolve(".env"), "ANTHROPIC_API_KEY=sk-test");
        Files.writeString(home.resolve("jira.env"), "JIRA_PERSONAL_TOKEN=t");
        Files.writeString(home.resolve("logs/.env"), "x");
        Files.writeString(home.resolve("logs/settings.env"), "x");
        Files.writeString(home.resolve("logs/..."), "x");
        Files.createDirectories(home.resolve("logs/JCLAW-7-dir"));
        Files.writeString(home.resolve("logs/JCLAW-7-dir/a.log"), "x");
        Files.createSymbolicLink(home.resolve("logs/JCLAW-7-link.log"), home.resolve(".env"));
        var refused = List.of("../.env", "../jira.env", "../board.json", ".env", "settings.env", "jira.env",
                "github.env", ".", "..", "...", "JCLAW-7-dir/a.log", "JCLAW-7-dir\\a.log", "JCLAW-7-dir",
                "JCLAW-7-link.log", "/etc/passwd", "");
        boardListing(refused.toArray(String[]::new));
        for (var name : refused) assertNull(FactoryStatus.log(KEY, name), name);
    }

    @Test
    void aListedLogMissingOnDiskOrWithNoBoardIsRefused() throws Exception {
        assertNull(FactoryStatus.log(KEY, "JCLAW-7-implement.log"), "no board");
        boardListing("JCLAW-7-implement.log");
        assertNull(FactoryStatus.log(KEY, "JCLAW-7-implement.log"), "not on disk");
    }

    // --- home ---

    @Test
    void theHomeIsTheFactoryHomeConfigKeyWithTildeExpanded() {
        FactoryHome.setHomeForTest(null);
        try {
            ConfigService.set(FactoryHome.HOME_KEY, home.toString());
            assertEquals(home, FactoryHome.home());
            ConfigService.set(FactoryHome.HOME_KEY, "~/factory-elsewhere");
            assertEquals(Path.of(System.getProperty("user.home"), "factory-elsewhere"), FactoryHome.home());
        } finally {
            ConfigService.delete(FactoryHome.HOME_KEY);
        }
        if (System.getenv("FACTORY_HOME") == null) {
            assertEquals(Path.of(System.getProperty("user.home"), ".jclaw-factory"), FactoryHome.home());
        }
    }
}
