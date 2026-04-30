package utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient instances to avoid multiplied connection pools.
 *
 * <p>Three flavors:
 *
 * <ul>
 *   <li>{@link #LLM} — HTTP/2-capable default. Used for chat completions
 *       and model discovery against any LLM provider whose server handles
 *       Java's default h2c upgrade gracefully (cloud providers over HTTPS,
 *       Ollama Local over plain HTTP, anything ALPN-negotiated). For HTTPS
 *       endpoints ALPN selects HTTP/2 cleanly; for plain HTTP endpoints
 *       the server either accepts the upgrade or ignores it, and the
 *       client falls back to HTTP/1.1 transparently.
 *   <li>{@link #LLM_HTTP_1_1} — HTTP/1.1-pinned. Used for LM Studio
 *       specifically: LM Studio's local API is Express running on Node,
 *       and Node's {@code http.Server} fires an {@code 'upgrade'} event
 *       on requests carrying {@code Upgrade: h2c}. Express doesn't
 *       attach an upgrade handler, so the request never reaches the
 *       request pipeline and the connection sits idle until the client
 *       times out. Pinning HTTP/1.1 skips the upgrade entirely.
 *   <li>{@link #GENERAL} — HTTP/2-capable default, used for channel
 *       webhooks (Slack, Telegram, WhatsApp) and other non-LLM HTTP
 *       traffic. These targets are always HTTPS in practice; no h2c risk.
 * </ul>
 *
 * <p>Route LLM calls via {@link #forLlmProvider(String)} — it picks
 * {@link #LLM_HTTP_1_1} when the provider name contains the substring
 * {@code "lm-studio"} (case-insensitive) and falls through to
 * {@link #LLM} otherwise. The provider name is the discriminator because
 * the h2c-hang is server-implementation-specific (Express + Node stdlib
 * upgrade-pipeline quirk), and the server is determined by which provider
 * you're talking to, not by whether the URL is loopback. Operators who
 * later add another Express-backed local provider can opt into the
 * HTTP/1.1 path by giving its provider name an "lm-studio" substring or
 * by extending {@link #isLmStudio(String)} here.
 *
 * <p>The boot-time probes ({@link services.LmStudioProbe},
 * {@link services.OllamaLocalProbe}) construct their own short-timeout
 * builders via {@link services.LocalProviderProbeSupport} and pass an
 * explicit {@link HttpClient.Version} — LM Studio uses HTTP/1.1, Ollama
 * Local uses HTTP/2 default.
 */
public class HttpClients {

    /** HTTP/2-capable client for LLM providers that handle h2c gracefully (everyone except LM Studio). */
    public static final HttpClient LLM = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** HTTP/1.1-pinned client for LM Studio specifically. */
    public static final HttpClient LLM_HTTP_1_1 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** For channel webhooks, tools, and general HTTPS requests. */
    public static final HttpClient GENERAL = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Pick the right HttpClient for an LLM-bound call by provider name.
     * {@code lm-studio} (and any name containing that substring,
     * case-insensitive) routes to {@link #LLM_HTTP_1_1}; everything else
     * routes to the HTTP/2-capable {@link #LLM}.
     */
    public static HttpClient forLlmProvider(String providerName) {
        return isLmStudio(providerName) ? LLM_HTTP_1_1 : LLM;
    }

    static boolean isLmStudio(String providerName) {
        if (providerName == null) return false;
        return providerName.toLowerCase().contains("lm-studio");
    }
}
