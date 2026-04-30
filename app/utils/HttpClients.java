package utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient instances to avoid multiplied connection pools.
 *
 * <p>Two flavors:
 *
 * <ul>
 *   <li>{@link #LLM} — HTTP/2-capable JDK client. Legacy holdover from
 *       before JCLAW-187 unified LLM traffic on
 *       {@link llm.OkHttpLlmHttpDriver}; kept until phase 4 (JCLAW-188)
 *       deletes this class outright.
 *   <li>{@link #GENERAL} — HTTP/2-capable default, used for channel
 *       webhooks (Slack, Telegram, WhatsApp), the OpenRouter leaderboard
 *       fetch, and other non-LLM HTTP traffic. These targets are always
 *       HTTPS in practice; no h2c risk.
 * </ul>
 *
 * <p>The LM-Studio HTTP/1.1 pin and the {@code forLlmProvider} routing
 * helper that gated it were deleted in JCLAW-187 — the new OkHttp LLM
 * driver does not issue an {@code Upgrade: h2c} on plain HTTP, so the
 * Express upgrade-event hang those mechanisms dodged is structurally
 * absent on every LLM call site.
 */
public class HttpClients {

    /** HTTP/2-capable JDK client. Currently unused on the LLM path; phase 4 deletes this. */
    public static final HttpClient LLM = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** For channel webhooks, tools, and general HTTPS requests. */
    public static final HttpClient GENERAL = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}
