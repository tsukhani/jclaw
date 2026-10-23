import jobs.PeriodicJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.EventLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JF-23: a periodic job that ticks between SIGTERM and Play stopping its jobs, when H2's exit hook
 * has already closed the database. Each job runs through Play's own scheduler, as an @Every tick does.
 */
class PeriodicJobTest extends UnitTest {

    /** A job whose view of the JVM the test sets, since a test cannot stop its own JVM. */
    public static class ScriptedJob extends PeriodicJob {
        volatile boolean stopping;
        volatile boolean shutdownStartsDuringTheTick;
        volatile RuntimeException failure;
        final AtomicInteger runs = new AtomicInteger();

        @Override
        protected boolean stopping() {
            return stopping;
        }

        @Override
        public void doJob() {
            runs.incrementAndGet();
            if (shutdownStartsDuringTheTick) stopping = true;
            if (failure != null) throw failure;
        }
    }

    private static List<EventLogger.Captured> tick(ScriptedJob job) throws Exception {
        return EventLogger.captureMatchingForTest(e -> e.message().startsWith("ScriptedJob"), _ -> {
            try {
                job.now().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aTickWhileTheJvmRunsRunsTheJob() throws Exception {
        var job = new ScriptedJob();

        assertEquals(List.of(), tick(job));
        assertEquals(1, job.runs.get());
    }

    @Test
    void aTickOnceTheShutdownHasBegunDoesNotRunTheJob() throws Exception {
        var job = new ScriptedJob();
        job.stopping = true;

        assertEquals(List.of(), tick(job));
        assertEquals(0, job.runs.get());
    }

    @Test
    void aFailureWhileTheJvmRunsIsReportedAsBefore() {
        var job = new ScriptedJob();
        job.failure = new IllegalArgumentException("a real fault");

        var thrown = assertThrows(IllegalStateException.class, () -> tick(job));
        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        assertSame(job.failure, root);
    }

    @Test
    void aTickTheShutdownCutShortIsOneInfoLine() throws Exception {
        var job = new ScriptedJob();
        job.shutdownStartsDuringTheTick = true;
        job.failure = new IllegalStateException("Database is already closed");

        var events = tick(job);

        assertEquals(1, job.runs.get());
        assertEquals(List.of(new EventLogger.Captured("INFO", "shutdown", null, null,
                "ScriptedJob: tick cut short by the shutdown: java.lang.IllegalStateException: Database is already closed",
                null)), events);
    }

    @Test
    void theJvmReportsItsShutdownOnlyOnceItHasBegun(@TempDir Path dir) throws Exception {
        var probe = dir.resolve("Probe.java");
        Files.writeString(probe, """
                public class Probe {
                    public static void main(String[] args) {
                        System.out.println("running " + utils.JvmShutdown.inProgress());
                        Runtime.getRuntime().addShutdownHook(new Thread(
                                () -> System.out.println("stopping " + utils.JvmShutdown.inProgress())));
                    }
                }
                """);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", Path.of("build/classes/java/main").toAbsolutePath().toString(),
                probe.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe JVM exited");
        assertEquals("running false\nstopping true\n",
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, process.exitValue());
    }
}
