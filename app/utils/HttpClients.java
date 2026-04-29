package utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient instances to avoid multiplied connection pools.
 *
 * <p>Three flavors:
 *
 * <ul>
 *   <li>{@link #LLM} — HTTP/2-default, used for chat completions and
 *       model discovery against HTTPS LLM providers (ollama-cloud,
 *       openrouter, OpenAI-compatible cloud endpoints, etc.). ALPN
 *       negotiates HTTP/2 cleanly over TLS, no h2c-upgrade involved.
 *   <li>{@link #LLM_HTTP_1_1} — HTTP/1.1-pinned, used for chat
 *       completions and model discovery against plain-HTTP loopback
 *       LLM endpoints (LM Studio, ollama-local). LM Studio's bundled
 *       llama.cpp HTTP server parses Java's {@code Upgrade: h2c}
 *       header but never completes the upgrade, hanging the request
 *       for the full timeout. Pinning HTTP/1.1 skips the upgrade.
 *   <li>{@link #GENERAL} — HTTP/2-default, used for channel webhooks
 *       (Slack, Telegram, WhatsApp) and other non-LLM HTTP traffic.
 *       These targets are always HTTPS in practice; no h2c risk.
 * </ul>
 *
 * <p>Route LLM calls via {@link #forLlmBaseUrl(String)} — it picks
 * {@link #LLM_HTTP_1_1} when the baseUrl is a loopback address that
 * tends to host a quirky local server, and falls through to
 * {@link #LLM} for everything else (including non-loopback plain HTTP,
 * which can be overridden at the call site if a specific operator
 * deployment needs HTTP/1.1 for a LAN-hosted local model).
 *
 * <p>The boot-time probes ({@link services.LmStudioProbe},
 * {@link services.OllamaLocalProbe}) keep their own short-timeout
 * builders and pin HTTP/1.1 inline — they're always loopback-targeted
 * by definition, so no routing decision is needed.
 */
public class HttpClients {

    /** HTTP/2-capable client for chat + discovery against HTTPS LLM providers. */
    public static final HttpClient LLM = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** HTTP/1.1-pinned client for chat + discovery against plain-HTTP loopback LLM endpoints. */
    public static final HttpClient LLM_HTTP_1_1 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** For channel webhooks, tools, and general HTTPS requests. */
    public static final HttpClient GENERAL = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Pick the right HttpClient for an LLM-bound call to a given baseUrl.
     * Loopback URLs ({@code localhost}, {@code 127.x.x.x}, {@code [::1]},
     * {@code 0.0.0.0}) get the HTTP/1.1 client; everything else falls
     * through to the HTTP/2-capable default.
     */
    public static HttpClient forLlmBaseUrl(String baseUrl) {
        return isLoopbackBaseUrl(baseUrl) ? LLM_HTTP_1_1 : LLM;
    }

    static boolean isLoopbackBaseUrl(String baseUrl) {
        if (baseUrl == null) return false;
        var lower = baseUrl.toLowerCase();
        return lower.startsWith("http://localhost")
                || lower.startsWith("http://127.")
                || lower.startsWith("http://[::1]")
                || lower.startsWith("http://0.0.0.0");
    }
}
