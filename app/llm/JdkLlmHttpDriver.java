package llm;

import utils.HttpClients;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * JDK {@link HttpClient}-backed driver. Routes through
 * {@link HttpClients#forLlmProvider(String)} so the LM-Studio HTTP/1.1 pin
 * stays in effect on this path. This is the default driver and the only
 * path live in production today; the OkHttp sibling is opt-in via
 * {@code play.llm.client=okhttp} for the JCLAW-185 evaluation phase.
 */
final class JdkLlmHttpDriver implements LlmHttpDriver {

    private final HttpClient client;

    JdkLlmHttpDriver(String providerName) {
        this.client = HttpClients.forLlmProvider(providerName);
    }

    @Override
    public HttpReply send(URI uri, String authHeader, String jsonBody, Duration timeout)
            throws IOException, InterruptedException {
        var req = buildRequest(uri, authHeader, jsonBody, timeout);
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        var retryAfter = resp.headers().firstValue("Retry-After")
                .flatMap(JdkLlmHttpDriver::parseRetryAfter);
        return new HttpReply(resp.statusCode(), resp.body(), retryAfter);
    }

    @Override
    public void streamSse(URI uri, String authHeader, String jsonBody, Duration timeout,
                          Consumer<String> onEvent, Runnable onComplete, Consumer<Throwable> onError) {
        try {
            var req = buildRequest(uri, authHeader, jsonBody, timeout);
            var resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                var body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                onError.accept(new LlmProvider.LlmException(
                        "HTTP %d: %s".formatted(resp.statusCode(), body)));
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        onEvent.accept(line.substring(6).strip());
                    }
                }
            }
            onComplete.run();
        } catch (Throwable t) {
            onError.accept(t);
        }
    }

    private static HttpRequest buildRequest(URI uri, String authHeader, String jsonBody, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private static Optional<Long> parseRetryAfter(String value) {
        try { return Optional.of(Long.parseLong(value)); }
        catch (NumberFormatException _) { return Optional.empty(); }
    }
}
