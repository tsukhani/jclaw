import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.factory.FactoryProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    void outputSurvivesAnOrphanHoldingThePipeOpen() {
        // The subshell exits at once, so its sleep is reparented out of descendants() and keeps stdout past the join.
        var res = FactoryProcess.run(List.of("sh", "-c", "echo started; (sleep 5 &); sleep 30"), Duration.ofMillis(500));
        assertTrue(res.timedOut(), res::toString);
        assertTrue(res.output().contains("started"), res::toString);
    }

    @Test
    void aTimeoutKillsTheChildrenToo() throws Exception {
        var res = FactoryProcess.run(List.of("sh", "-c", "sleep 30 & echo $!; wait"), Duration.ofMillis(500));
        assertTrue(res.timedOut(), res::toString);
        var child = ProcessHandle.of(Long.parseLong(res.output().trim()));
        if (child.isPresent()) child.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(child.map(ProcessHandle::isAlive).orElse(false), res::toString);
    }

    @Test
    void multibyteCharactersSurviveTheChunkBoundary() {
        // tr writes one ASCII byte then "é" in even-sized blocks, so every block ends inside a character.
        var res = FactoryProcess.run(List.of("sh", "-c",
                "{ printf a; yes \"$(printf '\\303\\251')\" | head -n 10000; } | tr -d '\\n'"), Duration.ofSeconds(10));
        assertEquals(0, res.exitCode(), res::toString);
        assertEquals("a" + "\u00e9".repeat(10_000), res.output());
    }

    @Test
    void aMissingExecutableIsMinusOneWithoutATimeout() {
        var res = FactoryProcess.run(List.of("/nonexistent/jclaw-factory-no-such-binary"), Duration.ofSeconds(5));
        assertEquals(-1, res.exitCode(), res::toString);
        assertFalse(res.timedOut());
    }

    @Test
    void aHarnessCommandWhileAnotherRunsIsRefused(@TempDir Path dir) throws Exception {
        var started = dir.resolve("started");
        var go = dir.resolve("go");
        var script = "touch '" + started + "'; while [ ! -f '" + go + "' ]; do sleep 0.05; done; echo done";
        var first = new CompletableFuture<FactoryProcess.@Nullable ExecResult>();
        var starter = Thread.ofPlatform().start(() ->
                first.complete(FactoryProcess.runHarnessCommand(List.of("sh", "-c", script), Duration.ofSeconds(30))));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(started), "the first command never started");
            assertNull(FactoryProcess.runHarnessCommand(List.of("true"), Duration.ofSeconds(5)),
                    "a second harness command ran beside the first");
        } finally {
            Files.writeString(go, "");
            starter.join(30_000);
        }
        var res = first.get(5, TimeUnit.SECONDS);
        assertNotNull(res, "the first command was refused");
        assertTrue(res.ok(), res::toString);
        assertNotNull(FactoryProcess.runHarnessCommand(List.of("true"), Duration.ofSeconds(5)), "the lock was not released");
    }
}
