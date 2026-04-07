package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class WebFetchTool implements ToolRegistry.Tool {

    private static final int MAX_CONTENT_LENGTH = 50_000;
    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String name() { return "web_fetch"; }

    @Override
    public String description() {
        return "Fetch the content of a URL and return it as text. Useful for reading web pages, APIs, or documents.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "The URL to fetch")
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var url = args.get("url").getAsString();

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JClaw/1.0")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            var response = utils.HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: HTTP %d fetching %s".formatted(response.statusCode(), url);
            }

            var contentType = response.headers().firstValue("Content-Type").orElse("text/plain");
            if (!contentType.contains("text") && !contentType.contains("json")
                    && !contentType.contains("xml") && !contentType.contains("html")) {
                return "Non-text content type: %s (size: %d bytes)".formatted(
                        contentType, response.body().length());
            }

            var body = response.body();
            if (body.length() > MAX_CONTENT_LENGTH) {
                return body.substring(0, MAX_CONTENT_LENGTH) + "\n\n[Truncated: content exceeds %d characters]"
                        .formatted(MAX_CONTENT_LENGTH);
            }
            return body;

        } catch (java.net.http.HttpTimeoutException _) {
            return "Error: Request timed out after %d seconds fetching %s".formatted(TIMEOUT_SECONDS, url);
        } catch (javax.net.ssl.SSLException _) {
            // Retry with lenient SSL for sites with misconfigured certificate chains
            return fetchWithLenientSsl(url);
        } catch (Exception e) {
            if (e.getCause() instanceof javax.net.ssl.SSLException) {
                return fetchWithLenientSsl(url);
            }
            return "Error fetching URL: %s".formatted(e.getMessage());
        }
    }

    private String fetchWithLenientSsl(String url) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "JClaw/1.0")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            var response = lenientClient().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: HTTP %d fetching %s".formatted(response.statusCode(), url);
            }

            var body = response.body();
            if (body.length() > MAX_CONTENT_LENGTH) {
                return body.substring(0, MAX_CONTENT_LENGTH) + "\n\n[Truncated: content exceeds %d characters]"
                        .formatted(MAX_CONTENT_LENGTH);
            }
            return body;
        } catch (Exception e) {
            return "Error fetching URL: %s".formatted(e.getMessage());
        }
    }

    private static volatile HttpClient lenientSslClient;

    private static HttpClient lenientClient() {
        if (lenientSslClient == null) {
            synchronized (WebFetchTool.class) {
                if (lenientSslClient == null) {
                    try {
                        var trustAll = new TrustManager[]{new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }};
                        var sslContext = SSLContext.getInstance("TLS");
                        sslContext.init(null, trustAll, new java.security.SecureRandom());
                        lenientSslClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .sslContext(sslContext)
                                .build();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create lenient SSL client", e);
                    }
                }
            }
        }
        return lenientSslClient;
    }
}
