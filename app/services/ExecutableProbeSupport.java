package services;

import play.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Shared mechanism for detecting whether a CLI binary is on PATH by running
 * {@code <binary> <versionArg>} with a bounded timeout and inspecting the exit
 * code. Mirrors {@link LocalProviderProbeSupport} (which does the equivalent for
 * the local-LLM {@code /models} probes): the per-prerequisite probe classes
 * ({@link services.transcription.FfmpegProbe}, {@link services.imagegen.FluxSidecarProbe})
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

    private ExecutableProbeSupport() {}

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
