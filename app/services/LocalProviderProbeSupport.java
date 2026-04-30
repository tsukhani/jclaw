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

final class LocalProviderProbeSupport {

    record Result(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    private LocalProviderProbeSupport() {}

    /**
     * Probe a local LLM provider's {@code /models} endpoint with a short
     * timeout. The {@code version} parameter is explicit so each probe
     * can pick the right HTTP version for its target server: LM Studio
     * needs {@link HttpClient.Version#HTTP_1_1} to dodge the
     * Express/Node h2c-upgrade hang; Ollama Local handles h2c gracefully
     * and uses {@link HttpClient.Version#HTTP_2}.
     */
    static Result probeModels(String baseUrl, String notRunningLabel, HttpClient.Version version) {
        try {
            var client = HttpClient.newBuilder()
                    .version(version)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new Result(false, 0,
                        "GET %s/models returned HTTP %d".formatted(baseUrl, resp.statusCode()),
                        false);
            }
            var json = JsonParser.parseString(resp.body()).getAsJsonObject();
            var data = json.has("data") ? json.getAsJsonArray("data") : null;
            return new Result(true, data == null ? 0 : data.size(), null, false);
        } catch (ConnectException | HttpConnectTimeoutException e) {
            return new Result(false, 0,
                    "%s not reachable (%s not running)".formatted(baseUrl, notRunningLabel),
                    true);
        } catch (IOException e) {
            return new Result(false, 0,
                    "%s probe failed: %s".formatted(baseUrl, e.getMessage()),
                    false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0,
                    "%s probe interrupted".formatted(baseUrl),
                    false);
        }
    }
}
