package services.factory;

import org.jspecify.annotations.Nullable;
import play.Play;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one process spawner behind the Software Factory controls (JCLAW-1392): an argument list,
 * never a shell string, run in the checkout under a timeout.
 */
public final class FactoryProcess {

    static final int TAIL_LINES = 40;
    static final int TAIL_CHARS = 4000;
    private static final long DRAIN_JOIN_MILLIS = 2000;
    private static final ReentrantLock HARNESS = new ReentrantLock();

    /** Runs a command and returns its result. Injectable for tests. */
    @FunctionalInterface
    public interface Runner {
        ExecResult run(List<String> command, File workDir, Duration timeout);
    }

    /**
     * @param exitCode the process's exit code; {@code -1} on a timeout or when it could not start
     * @param output   stdout and stderr together
     */
    public record ExecResult(int exitCode, String output, boolean timedOut) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** The last {@value #TAIL_LINES} lines of {@link #output}, at most {@value #TAIL_CHARS} characters. */
        public String tail() {
            var lines = output.stripTrailing().split("\n", -1);
            var from = Math.max(0, lines.length - TAIL_LINES);
            var joined = String.join("\n", Arrays.asList(lines).subList(from, lines.length));
            return joined.length() > TAIL_CHARS ? joined.substring(joined.length() - TAIL_CHARS) : joined;
        }
    }

    private static volatile @Nullable Runner runnerOverride;

    private FactoryProcess() {}

    /** Test-only: send every {@link #run} to {@code runner} instead of spawning (or clear with {@code null}). */
    public static void setRunnerForTest(@Nullable Runner runner) {
        runnerOverride = runner;
    }

    /**
     * Run a harness command, or return null while another is still running: two would race
     * launchd's bootout and bootstrap of the harness agent.
     */
    public static @Nullable ExecResult runHarnessCommand(List<String> command, Duration timeout) {
        if (!HARNESS.tryLock()) return null;
        try {
            return run(command, timeout);
        } finally {
            HARNESS.unlock();
        }
    }

    /** Run {@code command} with the checkout ({@code Play.applicationPath}) as its working directory. */
    public static ExecResult run(List<String> command, Duration timeout) {
        var runner = runnerOverride;
        var dir = Play.applicationPath;
        return runner != null ? runner.run(command, dir, timeout) : execProcess(command, dir, timeout);
    }

    private static ExecResult execProcess(List<String> command, File workDir, Duration timeout) {
        Process proc;
        try {
            proc = new ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new ExecResult(-1, e.getMessage() != null ? e.getMessage() : "exec failed", false);
        }
        // Bytes, decoded once at the end, so a chunk boundary cannot split a UTF-8 character.
        var out = new ByteArrayOutputStream();
        var drainer = Thread.ofVirtual().start(() -> drain(proc.getInputStream(), out));
        boolean finished;
        try {
            finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            closeQuietly(proc.getInputStream());
            joinQuietly(drainer);
            return new ExecResult(-1, out.toString(StandardCharsets.UTF_8), true);
        }
        joinQuietly(drainer);
        return new ExecResult(proc.exitValue(), out.toString(StandardCharsets.UTF_8), false);
    }

    private static void drain(InputStream in, ByteArrayOutputStream sink) {
        // Chunked, so what was read survives a close on timeout or a child holding the pipe open.
        var buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
        } catch (IOException _) {
            // partial output is enough for the response tail
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException _) {
            // best effort
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(DRAIN_JOIN_MILLIS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
