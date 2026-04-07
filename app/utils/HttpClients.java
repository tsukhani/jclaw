package utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient instances to avoid multiplied connection pools.
 */
public class HttpClients {

    /** For LLM API calls — longer timeout for streaming responses. */
    public static final HttpClient LLM = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** For channel webhooks, tools, and general HTTP requests. */
    public static final HttpClient GENERAL = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}
