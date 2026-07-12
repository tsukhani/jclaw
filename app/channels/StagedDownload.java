package channels;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import utils.HttpFactories;
import utils.SsrfGuard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Shared inbound-media staging for the channel file downloaders (JCLAW-719):
 * {@link SlackFileDownloader}, {@link TelegramFileDownloader}, and
 * {@link WhatsAppMediaDownloader}. Each previously carried its own copy of the same
 * download policy — the 20 MiB size cap, the SSRF-hardened OkHttp client, the stream
 * a-200-body-into-a-staged-temp-file transfer, and the best-effort cleanup — so a
 * tightening of the SSRF stance had to be made in three places or it drifted.
 *
 * <p>This type single-sources that policy: {@link #MAX_FILE_BYTES}, the
 * {@link #newSsrfClient() SSRF client recipe}, {@link #DOWNLOAD_TIMEOUT}, the
 * {@link #fetch byte transfer with cap}, and {@link #bestEffortDelete cleanup} each
 * exist exactly once. A caller supplies only the channel-specific bits — URL
 * construction, scheme/host validation, and auth headers — building the
 * {@link Request} and handing it (with its own swappable client field) to
 * {@link #fetch}.
 *
 * <p>The per-channel {@code DOWNLOAD_CLIENT} field survives as a test seam (each
 * downloader's tests reflectively swap in a socket-free interceptor client);
 * {@link #fetch} takes the client as a parameter so those swaps are honored, while
 * the recipe that builds the production client lives here alone.
 */
final class StagedDownload {

    private StagedDownload() {}

    /** Inbound media size cap shared by every channel downloader: 20 MiB. */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** Wall-clock bound on a single staged byte transfer. */
    static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Build an SSRF-hardened download client: derived from {@link HttpFactories#general()}
     * so the shared connection pool, dispatcher, timeouts, and protocol-logging event
     * listener carry over, then layered with {@link SsrfGuard#SAFE_DNS} (every hostname
     * resolution is gated — any unsafe resolved address throws before a socket opens)
     * and redirect-following disabled so a 302 can't bounce the GET past that gate to a
     * blocked target. Callers hold the result in a package-private, non-final field so
     * tests can swap a socket-free interceptor client in (mirrors {@code WebFetchTool.CLIENT}).
     */
    static OkHttpClient newSsrfClient() {
        return HttpFactories.general().newBuilder()
                .dns(SsrfGuard.SAFE_DNS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    /** Outcome of streaming one prepared GET into a staged file. */
    sealed interface Result permits Ok, TooBig, Redirect, HttpError, Failed {}

    /** 200 body streamed and within the cap. {@code contentType} is the empty string
     *  when the response carried none. */
    record Ok(String contentType, long sizeBytes) implements Result {}

    /** The staged body exceeded {@link #MAX_FILE_BYTES}; the partial file was deleted. */
    record TooBig(long actualBytes) implements Result {}

    /** A 3xx was returned (redirects are not auto-followed); {@code location} may be null. */
    record Redirect(int code, String location) implements Result {}

    /** A non-200, non-3xx status. */
    record HttpError(int code) implements Result {}

    /** An IO/transport error (the exception message); the partial file was deleted. */
    record Failed(String message) implements Result {}

    /**
     * Execute {@code request} on {@code client} (the caller's swappable SSRF client)
     * and stream a 200 response body into {@code stagedPath} with the
     * {@link #MAX_FILE_BYTES} cap. Redirects are surfaced, not followed, so a caller
     * that needs a manual hop can act on the {@link Redirect#location()}. On any
     * failure — oversize or IO error — the partial staging file is best-effort
     * deleted; the caller owns cleanup for the non-200 / redirect cases (nothing is
     * written for those).
     */
    static Result fetch(OkHttpClient client, Request request, Path stagedPath) {
        try {
            var call = client.newCall(request);
            call.timeout().timeout(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try (var resp = call.execute()) {
                int code = resp.code();
                if (code >= 300 && code < 400) {
                    return new Redirect(code, resp.header("Location"));
                }
                if (code != 200) {
                    return new HttpError(code);
                }
                // OkHttp 5's Response.body is non-null (an empty body for bodyless
                // responses), so read the content-type and stream directly.
                var ct = resp.body().contentType();
                String contentType = ct != null ? ct.toString() : "";
                try (InputStream in = resp.body().byteStream()) {
                    Files.copy(in, stagedPath, StandardCopyOption.REPLACE_EXISTING);
                }
                long size = Files.size(stagedPath);
                if (size > MAX_FILE_BYTES) {
                    bestEffortDelete(stagedPath);
                    return new TooBig(size);
                }
                return new Ok(contentType, size);
            }
        } catch (Exception e) {
            bestEffortDelete(stagedPath);
            return new Failed(e.getMessage());
        }
    }

    /** Best-effort delete of a partial/rejected staging file — swallows IO errors. */
    static void bestEffortDelete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException _) { /* best-effort */ }
    }
}
