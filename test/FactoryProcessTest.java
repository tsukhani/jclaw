import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.factory.FactoryProcess;

import java.time.Duration;
import java.util.List;

/** {@link FactoryProcess}'s real process path, with no runner installed (JCLAW-1392). */
class FactoryProcessTest extends UnitTest {

    @BeforeEach
    void lock() {
        FactoryRunnerSync.acquire();
        FactoryProcess.runnerForTest = null;
    }

    @AfterEach
    void unlock() {
        FactoryRunnerSync.release();
    }

    @Test
    void capturesExitCodeAndBothStreams() {
        var res = FactoryProcess.run(List.of("sh", "-c", "echo out; echo err >&2; exit 3"), Duration.ofSeconds(10));
        assertEquals(3, res.exitCode(), res::toString);
        assertFalse(res.timedOut());
        assertTrue(res.output().contains("out"), res::toString);
        assertTrue(res.output().contains("err"), res::toString);
    }

    @Test
    void aTimeoutKillsTheProcessAndKeepsWhatItPrinted() {
        long start = System.nanoTime();
        var res = FactoryProcess.run(List.of("sh", "-c", "echo started; sleep 30"), Duration.ofMillis(500));
        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertTrue(res.timedOut(), res::toString);
        assertEquals(-1, res.exitCode());
        assertTrue(res.output().contains("started"), res::toString);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, () -> "took " + elapsed);
    }

    @Test
    void aMissingExecutableIsMinusOneWithoutATimeout() {
        var res = FactoryProcess.run(List.of("/nonexistent/jclaw-factory-no-such-binary"), Duration.ofSeconds(5));
        assertEquals(-1, res.exitCode(), res::toString);
        assertFalse(res.timedOut());
    }
}
