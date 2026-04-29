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
 * One-shot health check for an LM Studio instance reachable at
 * {@code provider.lm-studio.baseUrl}. Mirrors {@link OllamaLocalProbe} —
 * LM Studio exposes the same OpenAI-compatible {@code /v1/models} shape on
 * {@code http://localhost:1234/v1} by default. {@link jobs.LmStudioProbeJob}
 * uses {@link ProbeResult#connectionRefused()} to demote "LM Studio not
 * running" failures to DEBUG so a fresh install without LM Studio doesn't
 * print a spurious WARN on every JVM start.
 */
public class LmStudioProbe {

    public record ProbeResult(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    private static final ProbeResult UNRUN = new ProbeResult(false, 0,
            "lm-studio probe has not run yet", false);

    private static final AtomicReference<ProbeResult> result = new AtomicReference<>(UNRUN);

    public static ProbeResult probe(String baseUrl) {
        try {
            // Force HTTP/1.1 — see utils.HttpClients for the rationale.
            // LM Studio's bundled HTTP server hangs on Java's default
            // HTTP/2 h2c upgrade attempt, so the probe must opt out.
            var client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
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
                    "%s not reachable (LM Studio not running)".formatted(baseUrl),
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

    public static void setForTest(ProbeResult forced) {
        result.set(forced == null ? UNRUN : forced);
    }

    private static ProbeResult setResult(ProbeResult r) {
        result.set(r);
        return r;
    }
}
