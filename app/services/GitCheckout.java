package services;

import org.jspecify.annotations.Nullable;
import play.Play;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The commit this install's working tree sits on, for the Settings panels.
 *
 * <p>Only a source checkout has one — a dist or bundle install ships without
 * {@code .git} — so every method here returns null on a packaged install and the
 * UI omits the field rather than showing a blank.
 */
public final class GitCheckout {

    /** git commands here are local and index-bound; a slower answer is a broken one. */
    private static final long TIMEOUT_SECONDS = 2;

    private GitCheckout() {}

    /**
     * Short commit id, suffixed {@code -dirty} when the working tree carries
     * uncommitted changes, or null when this install is not a git checkout.
     *
     * <p>Read live rather than cached at boot: in DEV mode Play recompiles per
     * request, so a boot-time value would drift out of step with the code actually
     * serving the page.
     */
    public static @Nullable String describe() {
        var head = run("rev-parse", "--short=8", "HEAD");
        if (head == null || head.isBlank()) return null;
        var status = run("status", "--porcelain");
        // A failed status read leaves the plain id rather than guessing "-dirty":
        // claiming modifications that may not exist is the worse of the two errors.
        return status != null && !status.isBlank() ? head + "-dirty" : head;
    }

    /**
     * Run {@code git -C <appPath> <args>} and return trimmed stdout, or null when git
     * is absent, the directory is not a repository, or the call outlives its timeout.
     */
    private static @Nullable String run(String... args) {
        var cmd = new ArrayList<>(List.of("git", "-C", Play.applicationPath.getAbsolutePath()));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd);
        // A dist install fails every one of these; without this the JVM's stderr
        // collects a "not a git repository" line per Settings page load.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            var p = pb.start();
            var out = new AtomicReference<>("");
            // readAllBytes blocks until EOF, which a wedged child never reaches — drain
            // off this thread so the bounded waitFor below still bounds us. Mirrors
            // ExecutableProbeSupport.probeCapturing, whose binary+one-arg shape cannot
            // express `git -C <path> …`.
            var drainer = Thread.ofVirtual().start(() -> {
                try (var in = p.getInputStream()) {
                    out.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException _) {
                    // Child died mid-read; the exit check below decides the outcome.
                }
            });
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                drainer.join(Duration.ofSeconds(1));
                return null;
            }
            drainer.join();
            var captured = out.get();
            return p.exitValue() == 0 && captured != null ? captured.trim() : null;
        } catch (IOException _) {
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
