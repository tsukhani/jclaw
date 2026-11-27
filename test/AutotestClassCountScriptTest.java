import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's {@code autotest_class_count}, which {@code ./jclaw.sh test}'s summary counts backend classes
 * with, under the script's own {@code set -euo pipefail}, against logs shaped as {@code playAutotest} writes them:
 * FirePhoque's one line per class, then Gradle's own lines.
 */
class AutotestClassCountScriptTest extends UnitTest {

    private static final String FAILED_RUN = """
            > Task :playAutotest
            ~ Starting Play in test mode...
            ~ Server is up and running
            ~ Starting FirePhoque...
            ~ 5 tests to run:
            ~
            ~ AbsoluteDatesPropertyTest...                     PASSED     3s
            ~ AcpChannelGateTest...                            PASSED     3s
            ~ PlaywrightToolTest...                            FAILED  !  2 min 41s
            ~ HungFunctionalTest...                            ERROR   ?  5 min 0s
            ~ ZonedClockTest...                                PASSED     1s
            ~ Some tests failed. See /tmp/jclaw/test-result for results

            > Task :playAutotest FAILED

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':playAutotest'.

            BUILD FAILED in 20m 3s
            """;

    private static final String PASSING_RUN = """
            > Task :playAutotest
            ~ AbsoluteDatesPropertyTest...                     PASSED     3s
            ~ AcpChannelGateTest...                            PASSED     3s
            ~ All tests passed

            BUILD SUCCESSFUL in 19m 58s
            """;

    @TempDir Path tmp;

    record Result(int exit, String out) {}

    @Test
    void aFailedRunCountsTheFailedClassAndNotGradlesBuildFailedLine() throws Exception {
        assertEquals(new Result(0, "[1]\n"), count("FAILED", FAILED_RUN));
    }

    @Test
    void aClassThatEndedInErrorCountsAsNotPassed() throws Exception {
        assertEquals(new Result(0, "[1]\n"), count("ERROR", FAILED_RUN));
        assertEquals(new Result(0, "[2]\n"), count("FAILED|ERROR", FAILED_RUN));
    }

    @Test
    void thePassedCountIsTheClassesThatPassed() throws Exception {
        assertEquals(new Result(0, "[3]\n"), count("PASSED", FAILED_RUN));
    }

    @Test
    void aPassingRunCountsNoFailedClass() throws Exception {
        assertEquals(new Result(0, "[0]\n"), count("FAILED|ERROR", PASSING_RUN));
        assertEquals(new Result(0, "[2]\n"), count("PASSED", PASSING_RUN));
    }

    @Test
    void anEmptyLogCountsZero() throws Exception {
        assertEquals(new Result(0, "[0]\n"), count("PASSED", ""));
    }

    @Test
    void aMissingLogPrintsNothingAndDoesNotEndTheScript() throws Exception {
        assertEquals(new Result(0, "[]\n"), run("PASSED", tmp.resolve("absent.log")));
    }

    // --- helpers ---

    private Result count(String status, String log) throws Exception {
        var file = tmp.resolve("test-backend.log");
        Files.writeString(file, log);
        return run(status, file);
    }

    private Result run(String status, Path log) throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "jclaw.sh"));
        var m = Pattern.compile("(?ms)^autotest_class_count\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
        assertTrue(m.find(), "jclaw.sh no longer defines autotest_class_count");
        var pb = new ProcessBuilder("/bin/bash", "-c", "set -euo pipefail\n" + m.group()
                + "n=$(autotest_class_count \"$STATUS\" \"$LOG\")\necho \"[$n]\"\n").redirectErrorStream(true);
        pb.environment().put("STATUS", status);
        pb.environment().put("LOG", log.toString());
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        return new Result(p.exitValue(), out);
    }
}
