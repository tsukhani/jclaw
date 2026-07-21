package services;

import play.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mechanism for detecting whether a CLI binary is on PATH by running
 * {@code <binary> <versionArg>} with a bounded timeout and inspecting the exit
 * code. Mirrors {@link LocalProviderProbeSupport} (which does the equivalent for
 * the local-LLM {@code /models} probes): the per-prerequisite probe classes
 * ({@link services.transcription.FfmpegProbe}, {@link services.UvProbe})
 * keep their own typed result record and cached facade, delegating only this
 * identical detection logic here.
 */
public final class ExecutableProbeSupport {

    /**
     * {@code -version}/{@code --version} prints and exits within milliseconds;
     * bound the wait anyway so a hung binary can't stall startup forever.
     */
    private static final long PROBE_TIMEOUT_SECONDS = 5;

    public record Result(boolean available, String reason) {}

    /**
     * Availability classification plus the command's combined stdout/stderr, for
     * callers that need the printed output (e.g. a version line) rather than just
     * a boolean. See {@link #probeCapturing}.
     */
    public record CapturedResult(boolean available, String reason, String output) {}

    private ExecutableProbeSupport() {}

    /**
     * Like {@link #probeOnPath} but captures the command's combined stdout/stderr
     * so a caller can surface the printed version line
     * ({@link services.OcrHealthProbe}). The output is drained on a separate
     * virtual thread: {@link java.io.InputStream#readAllBytes()} blocks until EOF,
     * which a binary that accepts the invocation but never exits never reaches —
     * so an inline read would defeat the bounded {@link Process#waitFor(long, TimeUnit)}
     * and hang the caller (here, the synchronous boot probe). On timeout the child
     * is {@link Process#destroyForcibly()}'d and a non-available result returned.
     *
     * @param binary       the executable to look up on PATH (e.g. {@code "tesseract"})
     * @param versionArg   the version flag (e.g. {@code "--version"})
     * @param notFoundHint trailing hint appended to the "not found on PATH" reason
     */
    public static CapturedResult probeCapturing(String binary, String versionArg, String notFoundHint) {
        var pb = new ProcessBuilder(binary, versionArg);
        pb.redirectErrorStream(true);
        try {
            var p = pb.start();
            var captured = new AtomicReference<>("");
            var drainer = Thread.ofVirtual().start(() -> {
                try (var in = p.getInputStream()) {
                    captured.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException _) {
                    // Process died mid-read (e.g. destroyForcibly on timeout) — keep
                    // whatever was captured; classification comes from waitFor below.
                }
            });
            boolean exited = p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                drainer.join(Duration.ofSeconds(1));
                return new CapturedResult(false,
                        "%s %s did not exit within %ds".formatted(binary, versionArg, PROBE_TIMEOUT_SECONDS),
                        captured.get());
            }
            drainer.join();
            int code = p.exitValue();
            if (code != 0) {
                return new CapturedResult(false,
                        "%s %s exited %d".formatted(binary, versionArg, code), captured.get());
            }
            Logger.info("ExecutableProbeSupport: %s available on PATH".formatted(binary));
            return new CapturedResult(true, "available", captured.get());
        } catch (IOException e) {
            return new CapturedResult(false,
                    "%s not found on PATH (%s)%s".formatted(binary, e.getMessage(), notFoundHint), "");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new CapturedResult(false, "interrupted while probing " + binary, "");
        }
    }

    /**
     * Run {@code <binary> <versionArg>} and classify the outcome.
     *
     * @param binary       the executable to look up on PATH (e.g. {@code "ffmpeg"})
     * @param versionArg   the version flag (e.g. {@code "-version"})
     * @param logLabel     prefix for the success log line (e.g. {@code "FfmpegProbe"})
     * @param notFoundHint trailing hint appended to the "not found on PATH" reason
     *                     when the binary is absent (empty string for none)
     */
    public static Result probeOnPath(String binary, String versionArg, String logLabel, String notFoundHint) {
        try {
            var pb = new ProcessBuilder(binary, versionArg);
            pb.redirectErrorStream(true);
            var p = pb.start();
            boolean exited = p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return new Result(false, "%s %s did not exit within %ds".formatted(binary, versionArg, PROBE_TIMEOUT_SECONDS));
            }
            int code = p.exitValue();
            if (code != 0) {
                return new Result(false, "%s %s exited %d".formatted(binary, versionArg, code));
            }
            Logger.info("%s: %s available on PATH".formatted(logLabel, binary));
            return new Result(true, "available");
        } catch (IOException e) {
            return new Result(false, "%s not found on PATH (%s)%s".formatted(binary, e.getMessage(), notFoundHint));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted while probing " + binary);
        }
    }
}
