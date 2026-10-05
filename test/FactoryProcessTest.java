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
        FactoryProcess.setRunnerForTest(null);
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

    @Test
    void inTestModeTheFactoryCommandsAreRefusedWithoutARunner() {
        for (var command : List.of(List.of("docker", "ps"), List.of("/checkout/.sandcastle/install-agent.sh", "--remove"))) {
            var e = assertThrows(IllegalStateException.class, () -> FactoryProcess.run(command, Duration.ofSeconds(5)),
                    command::toString);
            assertTrue(e.getMessage().contains("refused in test mode"), e.getMessage());
        }
        assertEquals(0, FactoryProcess.run(List.of("true"), Duration.ofSeconds(5)).exitCode());
    }

    @Test
    void runStreamingDeliversOutputBeforeTheProcessExits(@TempDir Path dir) throws Exception {
        var go = dir.resolve("go");
        var seen = new java.io.ByteArrayOutputStream();
        var firstChunk = new CompletableFuture<String>();
        var script = "echo first; while [ ! -f '" + go + "' ]; do sleep 0.05; done; echo second";
        var run = new CompletableFuture<FactoryProcess.ExecResult>();
        var starter = Thread.ofPlatform().start(() -> run.complete(FactoryProcess.runStreaming(
                List.of("sh", "-c", script), Duration.ofSeconds(30), (b, off, n) -> {
                    synchronized (seen) {
                        seen.write(b, off, n);
                        firstChunk.complete(seen.toString(java.nio.charset.StandardCharsets.UTF_8));
                    }
                })));
        try {
            assertTrue(firstChunk.get(10, TimeUnit.SECONDS).contains("first"));
            assertFalse(run.isDone(), "the process finished before its first chunk arrived");
        } finally {
            Files.writeString(go, "");
            starter.join(30_000);
        }
        var res = run.get(5, TimeUnit.SECONDS);
        assertTrue(res.ok(), res::toString);
        assertEquals("first\nsecond\n", res.output());
        synchronized (seen) {
            assertEquals("first\nsecond\n", seen.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void aThreeArgumentRunnerStillFeedsTheSink() {
        FactoryProcess.setRunnerForTest((List<String> c, java.io.File d, Duration t) ->
                new FactoryProcess.ExecResult(0, "canned\n", false));
        try {
            var seen = new StringBuilder();
            var res = FactoryProcess.runStreaming(List.of("anything"), Duration.ofSeconds(5),
                    (b, off, n) -> seen.append(new String(b, off, n, java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("canned\n", res.output());
            assertEquals("canned\n", seen.toString());
        } finally {
            FactoryProcess.setRunnerForTest(null);
        }
    }

    @Test
    void inTestModeStreamingTheInstallerIsRefusedWithoutARunner() {
        var e = assertThrows(IllegalStateException.class, () -> FactoryProcess.runStreaming(
                List.of("/checkout/.sandcastle/install-agent.sh"), Duration.ofSeconds(5), (b, off, n) -> { }));
        assertTrue(e.getMessage().contains("refused in test mode"), e.getMessage());
    }

    @Test
    void theHarnessPermitRefusesASecondHolderUntilReleased() throws Exception {
        // Release only a permit this test holds: a spare release would leave the global semaphore at 2.
        var held = FactoryProcess.tryAcquireHarness();
        assertTrue(held);
        try {
            assertFalse(FactoryProcess.tryAcquireHarness());
            assertNull(FactoryProcess.runHarnessCommand(List.of("true"), Duration.ofSeconds(5)));
            // Released from another thread, as an install job does.
            var other = Thread.ofPlatform().start(FactoryProcess::releaseHarness);
            other.join(5_000);
            held = false;
            held = FactoryProcess.tryAcquireHarness();
            assertTrue(held, "the permit was not freed by the other thread");
        } finally {
            if (held) FactoryProcess.releaseHarness();
        }
        assertNotNull(FactoryProcess.runHarnessCommand(List.of("true"), Duration.ofSeconds(5)));
    }
}
