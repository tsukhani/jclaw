package services;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot health check for a local Ollama instance reachable at
 * {@code provider.ollama-local.baseUrl}. Mirrors the shape of {@link OcrHealthProbe}:
 * a cached {@link ProbeResult} accessible via {@link #lastResult()}, a
 * {@link #setForTest(ProbeResult)} seam, and {@link #probe(String)} for the
 * boot job to drive.
 *
 * <p>{@link ProbeResult#connectionRefused()} distinguishes the typical "Ollama
 * not installed" failure mode from genuine errors (HTTP 5xx, malformed
 * /v1/models payload, response timeout). {@link jobs.OllamaLocalProbeJob}
 * uses that flag to log connection-refused at DEBUG (silent under default
 * INFO logging) so a fresh install with no Ollama present doesn't surface a
 * spurious WARN line on every JVM start.
 */
public class OllamaLocalProbe {

    public record ProbeResult(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    private static final ProbeResult UNRUN = new ProbeResult(false, 0,
            "ollama-local probe has not run yet", false);

    private static final AtomicReference<ProbeResult> result = new AtomicReference<>(UNRUN);

    /**
     * Issue a {@code GET <baseUrl>/models} with a short timeout and cache the
     * outcome. Counts entries in the OpenAI-compatible {@code data} array;
     * Ollama's /v1 endpoint returns the same shape.
     */
    public static ProbeResult probe(String baseUrl) {
        // Ollama's server handles h2c gracefully (ignores the upgrade and
        // responds HTTP/1.1). HTTP/2 default is fine here — it'll fall back
        // transparently and we get HTTP/2 free if Ollama ever ships it.
        return setResult(fromShared(LocalProviderProbeSupport.probeModels(
                baseUrl, "Ollama", java.net.http.HttpClient.Version.HTTP_2)));
    }

    public static ProbeResult lastResult() {
        return result.get();
    }

    /**
     * Test seam: replace the cached probe result without invoking the network.
     * Lets tests exercise the unreachable code path on a host where Ollama is
     * actually running (and vice versa).
     */
    public static void setForTest(ProbeResult forced) {
        result.set(forced == null ? UNRUN : forced);
    }

    private static ProbeResult setResult(ProbeResult r) {
        result.set(r);
        return r;
    }

    private static ProbeResult fromShared(LocalProviderProbeSupport.Result r) {
        return new ProbeResult(r.available(), r.modelCount(), r.reason(), r.connectionRefused());
    }
}
