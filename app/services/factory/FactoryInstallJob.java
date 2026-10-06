package services.factory;

import org.jspecify.annotations.Nullable;
import services.EventLogger;
import utils.AppClock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * {@code install-agent.sh} as a background job (JCLAW-1393): the request that starts it returns at
 * once, and the job's state and output so far are polled by id. It holds the harness permit for its
 * whole run, so an install and a harness start or stop never overlap. Jobs live in memory only.
 */
public final class FactoryInstallJob {

    /** install-agent.sh may build the sandbox image the first time. */
    public static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);

    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    static final int OUTPUT_CAP = 512 * 1024;
    private static final int KEEP = 5;
    private static final String CATEGORY = "factory";

    /**
     * @param exitCode null while running, or when the installer could not be run at all
     * @param truncated {@code output} is only the last {@value #OUTPUT_CAP} bytes
     */
    public record JobView(String id, String state, long elapsedMillis, @Nullable Integer exitCode,
                          boolean timedOut, boolean truncated, String output) {}

    private static final LinkedHashMap<String, Job> JOBS = new LinkedHashMap<>();

    private FactoryInstallJob() {}

    /** Start the installer, or return null while an install or a harness command holds the permit. */
    public static @Nullable JobView start() {
        if (!FactoryProcess.tryAcquireHarness()) return null;
        var job = new Job(UUID.randomUUID().toString(), AppClock.now());
        try {
            register(job);
            Thread.ofPlatform().daemon().name("factory-install").start(() -> run(job));
        } catch (RuntimeException e) {
            FactoryProcess.releaseHarness();
            job.finish(FAILED, null, false);
            throw e;
        }
        return job.view();
    }

    public static @Nullable JobView get(String id) {
        Job job;
        synchronized (JOBS) {
            job = JOBS.get(id);
        }
        return job != null ? job.view() : null;
    }

    /** The newest job's id (the running one, when one runs), or null before the first install. */
    public static @Nullable String latestId() {
        synchronized (JOBS) {
            String last = null;
            for (var id : JOBS.keySet()) last = id;
            return last;
        }
    }

    private static void register(Job job) {
        synchronized (JOBS) {
            JOBS.put(job.id, job);
            var it = JOBS.keySet().iterator();
            while (JOBS.size() > KEEP) {
                it.next();
                it.remove();
            }
        }
    }

    private static void run(Job job) {
        String state = FAILED;
        Integer exitCode = null;
        boolean timedOut = false;
        try {
            var res = FactoryProcess.runStreaming(List.of(FactoryHome.installer().toString()), INSTALL_TIMEOUT,
                    job::append);
            // A spawn failure never reaches the sink; its message is the result's whole output.
            if (job.isEmpty() && !res.output().isEmpty()) {
                var bytes = res.output().getBytes(StandardCharsets.UTF_8);
                job.append(bytes, 0, bytes.length);
            }
            state = res.ok() ? SUCCEEDED : FAILED;
            exitCode = res.exitCode();
            timedOut = res.timedOut();
        } catch (RuntimeException e) {
            var note = "\nThe installer could not run: " + e.getClass().getSimpleName() + "\n";
            var bytes = note.getBytes(StandardCharsets.UTF_8);
            job.append(bytes, 0, bytes.length);
        } finally {
            // Released before the state flips, so a poller that sees the end can start the next command.
            FactoryProcess.releaseHarness();
            FactoryStatus.clearCache();
            // In the finally so an Error cannot leave the job reporting running forever.
            job.finish(state, exitCode, timedOut);
        }
        if (SUCCEEDED.equals(state)) {
            EventLogger.info(CATEGORY, "Factory install finished: exit code 0");
        } else {
            String why;
            if (timedOut) why = "timed out";
            else if (exitCode != null) why = "failed with exit code " + exitCode;
            else why = "could not run";
            EventLogger.warn(CATEGORY, "Factory install " + why, new FactoryProcess.ExecResult(
                    exitCode != null ? exitCode : -1, job.view().output(), timedOut).tail());
        }
    }

    private static final class Job {
        final String id;
        final Instant startedAt;
        private @Nullable Instant finishedAt;
        private String state = RUNNING;
        private @Nullable Integer exitCode;
        private boolean timedOut;
        private byte[] buf = new byte[0];
        private int len;
        private boolean truncated;

        Job(String id, Instant startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }

        synchronized void append(byte[] b, int off, int n) {
            if (n >= OUTPUT_CAP) {
                buf = Arrays.copyOfRange(b, off + n - OUTPUT_CAP, off + n);
                len = OUTPUT_CAP;
                truncated = true;
                return;
            }
            var overflow = len + n - OUTPUT_CAP;
            if (overflow > 0) {
                System.arraycopy(buf, overflow, buf, 0, len - overflow);
                len -= overflow;
                truncated = true;
            }
            if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.min(OUTPUT_CAP, Math.max(len + n, buf.length * 2)));
            System.arraycopy(b, off, buf, len, n);
            len += n;
        }

        synchronized boolean isEmpty() {
            return len == 0;
        }

        synchronized void finish(String state, @Nullable Integer exitCode, boolean timedOut) {
            this.state = state;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.finishedAt = AppClock.now();
        }

        synchronized JobView view() {
            var end = finishedAt != null ? finishedAt : AppClock.now();
            var from = 0;
            // A cut tail may start inside a UTF-8 character: skip its continuation bytes.
            if (truncated) while (from < len && (buf[from] & 0xC0) == 0x80) from++;
            return new JobView(id, state, Duration.between(startedAt, end).toMillis(), exitCode, timedOut,
                    truncated, new String(buf, from, len - from, StandardCharsets.UTF_8));
        }
    }
}
