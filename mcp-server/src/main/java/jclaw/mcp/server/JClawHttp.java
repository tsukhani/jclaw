package jclaw.mcp.server;

import okhttp3.OkHttpClient;

import java.time.Duration;

/**
 * Shared OkHttp client + immutable config for the outbound JClaw API.
 *
 * <p>One client per process so the connection pool is warm by the time
 * the first {@code tools/call} arrives — bearer auth + the OpenAPI
 * fetch already exercised it. {@link ToolInvoker} re-uses the same
 * client for every operation.
 *
 * <p>Timeouts mirror what {@code app/utils/HttpFactories#general()}
 * uses on the main app side: 5s connect, 30s read. JClaw endpoints
 * that take longer than 30s (e.g. some discover-models flows) are
 * deliberately excluded by being either SSE-marked or operator-
 * blocklisted in the README.
 */
public final class JClawHttp {

    private final Config config;
    private final OkHttpClient client;

    public JClawHttp(Config config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Visible for {@link ToolInvokerTest} — assemble a custom client
     *  for the mock-web-server-backed tests without going through the
     *  full {@link Config} parsing path. */
    JClawHttp(Config config, OkHttpClient client) {
        this.config = config;
        this.client = client;
    }

    public Config config() { return config; }
    public OkHttpClient client() { return client; }
}
