package services;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return setResult(new ProbeResult(false, 0,
                        "GET %s/models returned HTTP %d".formatted(baseUrl, resp.statusCode()),
                        false));
            }
            var json = JsonParser.parseString(resp.body()).getAsJsonObject();
            var data = json.has("data") ? json.getAsJsonArray("data") : null;
            int count = data == null ? 0 : data.size();
            return setResult(new ProbeResult(true, count, null, false));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            return setResult(new ProbeResult(false, 0,
                    "%s not reachable (Ollama not running)".formatted(baseUrl),
                    true));
        } catch (IOException e) {
            return setResult(new ProbeResult(false, 0,
                    "%s probe failed: %s".formatted(baseUrl, e.getMessage()),
                    false));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return setResult(new ProbeResult(false, 0,
                    "%s probe interrupted".formatted(baseUrl),
                    false));
        }
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
}
