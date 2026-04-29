package utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient instances to avoid multiplied connection pools.
 *
 * <p>Both clients are pinned to HTTP/1.1. Java's default
 * {@link HttpClient.Version#HTTP_2} attempts an {@code Upgrade: h2c}
 * negotiation on plain HTTP requests, and some local LLM servers — notably
 * LM Studio's bundled llama.cpp HTTP server — parse the upgrade header but
 * then hang the response until the request times out. The same client is
 * reused for HTTPS calls to cloud providers (ollama-cloud, openrouter,
 * etc.); TLS+HTTP/1.1 is universally supported and we don't lose anything
 * meaningful for streaming chat workloads, which don't benefit from HTTP/2
 * multiplexing. Pinning here covers the chat path
 * ({@link llm.LlmProvider#executeWithRetry}) and the discovery path
 * ({@link services.ModelDiscoveryService}); the boot-time probes have
 * their own short-timeout builders and pin HTTP/1.1 inline for the same
 * reason.
 */
public class HttpClients {

    /** For LLM API calls — longer timeout for streaming responses. */
    public static final HttpClient LLM = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** For channel webhooks, tools, and general HTTP requests. */
    public static final HttpClient GENERAL = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}
