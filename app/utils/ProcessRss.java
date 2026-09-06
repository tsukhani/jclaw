package utils;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resident set size of this process — the RAM the OS actually charges JClaw.
 *
 * <p>This is not any heap figure and must not be conflated with one: under ZGC the RSS
 * sits well above heap-used, so an operator shown heap-used alone concludes the process
 * is small while {@code ps} says otherwise. When it cannot be read the answer is null,
 * rendered as unavailable — never substituted with a heap number, because a confidently
 * wrong RAM figure is worse than an absent one.
 *
 * <p>No portable API exists ({@code OperatingSystemMXBean} exposes system and virtual
 * memory, not process RSS), so each platform is handled on its own terms.
 */
public final class ProcessRss {

    /**
     * Cached here rather than in the caller so no polling cadence can turn the macOS
     * branch into a process spawn per request — the panel refreshes every few seconds and
     * a future caller must not be able to reintroduce that rate by polling harder.
     */
    static final long TTL_MS = 30_000;

    private record Cached(@Nullable Long bytes, long readAtMs) {}

    private static final AtomicReference<Cached> CACHE = new AtomicReference<>();

    private ProcessRss() {}

    /** RSS in bytes, or null when this platform has no supported way to report it. */
    public static @Nullable Long bytes() {
        var now = System.currentTimeMillis();
        var cached = CACHE.get();
        if (cached != null && now - cached.readAtMs() < TTL_MS) return cached.bytes();
        // A failed read is cached too: on a platform where this never works, retrying per
        // request would spawn ps forever to learn the same thing.
        var fresh = read();
        CACHE.set(new Cached(fresh, now));
        return fresh;
    }

    /**
     * Drop the cached value so the next call re-reads. Test seam — public because Play
     * compiles {@code test/} into the default package, which cannot see package-private
     * members of {@code utils}.
     */
    public static void invalidateForTest() {
        CACHE.set(null);
    }

    private static @Nullable Long read() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return fromProcStatus();
        if (os.contains("mac")) return fromPs();
        return null;
    }

    /** {@code VmRSS:\t  123456 kB} — a plain file read, so no spawn on Linux. */
    private static @Nullable Long fromProcStatus() {
        try {
            for (var line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (!line.startsWith("VmRSS:")) continue;
                var parts = line.split("\\s+");
                // "VmRSS:", "<value>", "kB" — the unit is always kB in this file.
                if (parts.length < 2) return null;
                return Long.parseLong(parts[1]) * 1024;
            }
        } catch (IOException | NumberFormatException _) {
            return null;
        }
        return null;
    }

    /**
     * {@code ps -o rss= -p <pid>} prints kilobytes and exits immediately, so the output
     * cannot fill the pipe buffer — unlike the probe helpers, this needs no drainer
     * thread to keep the bounded wait honest.
     */
    private static @Nullable Long fromPs() {
        var pb = new ProcessBuilder("ps", "-o", "rss=", "-p",
                String.valueOf(ProcessHandle.current().pid()));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            var p = pb.start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return out.isEmpty() ? null : Long.parseLong(out) * 1024;
        } catch (IOException | NumberFormatException _) {
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
