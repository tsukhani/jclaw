package services;

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

    private static final ProbeCache<ProbeResult> CACHE = new ProbeCache<>(
            new ProbeResult(false, 0, "ollama-local probe has not run yet", false));

    /**
     * Issue a {@code GET <baseUrl>/models} with a short timeout and cache the
     * outcome. Counts entries in the OpenAI-compatible {@code data} array;
     * Ollama's /v1 endpoint returns the same shape.
     */
    public static ProbeResult probe(String baseUrl) {
        // JCLAW-186: probeModels now uses OkHttp. The previous HTTP_2 hint
        // is gone — OkHttp speaks HTTP/1.1 to plain-HTTP cleartext
        // endpoints by default (no h2c upgrade), which is what Ollama
        // serves on the loopback /v1 port.
        var r = LocalProviderProbeSupport.probeModels(baseUrl, "Ollama");
        return CACHE.set(new ProbeResult(r.available(), r.modelCount(), r.reason(), r.connectionRefused()));
    }

    public static ProbeResult lastResult() {
        return CACHE.get();
    }

    /**
     * Test seam: replace the cached probe result without invoking the network.
     * Lets tests exercise the unreachable code path on a host where Ollama is
     * actually running (and vice versa).
     */
    public static void setForTest(ProbeResult forced) {
        CACHE.setForTest(forced);
    }
}
