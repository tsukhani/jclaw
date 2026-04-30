package utils;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Shared OkHttp clients for non-LLM outbound HTTP — channel webhooks
 * (Slack, WhatsApp, the non-SDK Telegram path), the openrouter
 * leaderboard fetch, the scanner production HTTP, and any future
 * non-LLM caller. JCLAW-188 added this as the drop-in replacement for
 * the deleted {@code utils.HttpClients}.
 *
 * <p>Single shared client for now ({@link #GENERAL}). The original
 * migration ticket suggested per-area tuning, but until any area shows
 * an actual perf reason, one client keeps the shared connection pool
 * and dispatcher thread pool to a minimum. Per-area splits become a
 * follow-up if/when the need is real.
 *
 * <p>The LLM-bound singleton lives separately in
 * {@link llm.LlmOkHttpClient} because LLM traffic has different timeout
 * needs (180s read for slow models, callTimeout 0 for SSE).
 */
public final class OkHttpClients {

    /**
     * Pool sized for ordinary webhook + scanner concurrency: 32 idle
     * slots, 5-minute keep-alive. Roughly half the {@code LlmOkHttpClient}
     * pool because non-LLM call sites typically fan out to fewer hosts.
     */
    public static final ConnectionPool POOL = new ConnectionPool(32, 5, TimeUnit.MINUTES);

    /**
     * General-purpose OkHttp client. Default timeouts (10s connect / 30s
     * read / 30s write / 60s callTimeout) match what the previous JDK
     * {@code HttpClients.GENERAL} effectively imposed via per-request
     * timeouts on its callers, without requiring each call site to
     * specify its own.
     *
     * <p>Each call site can still derive a tighter or looser per-call
     * timeout via {@link OkHttpClient.Builder#newBuilder()} — that's
     * cheap (no new pool) and idiomatic for one-off limits like a
     * 5-second leaderboard fetch.
     */
    public static final OkHttpClient GENERAL = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(60))
            .connectionPool(POOL)
            .build();

    private OkHttpClients() { }
}
