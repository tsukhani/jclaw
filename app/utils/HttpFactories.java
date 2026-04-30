package utils;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Single source of truth for outbound HTTP-client provisioning. Callers
 * declare <em>intent</em> ({@link #llmStreaming()}, {@link #llmSingleShot},
 * {@link #general()}) and receive an appropriately-tuned
 * {@link OkHttpClient}; they never see the underlying connection pools,
 * dispatcher, or timeout policy. That makes this class the GRASP
 * Information Expert on which client matches each purpose.
 *
 * <p>Three tiers, two pools, two dispatchers:
 *
 * <ul>
 *   <li>{@link #llmStreaming()} — SSE-friendly: {@code callTimeout(0)} so
 *       streams can run as long as the server keeps sending data; per-frame
 *       180s {@code readTimeout} bounds individual stalls. Uses
 *       {@link #LLM_DISPATCHER}, which runs on a virtual-thread executor
 *       so concurrent SSE reads unmount their carriers cleanly during
 *       blocking socket reads (the architectural insight that closed the
 *       Phase-1 perf gap to the JDK {@code HttpClient}).</li>
 *
 *   <li>{@link #llmSingleShot(Duration)} — single-shot LLM calls (chat,
 *       embeddings, discovery, boot probes) with caller-specified
 *       {@code callTimeout}. Same shared pool + VT dispatcher as streaming
 *       so connection reuse is unified across the LLM stack.</li>
 *
 *   <li>{@link #general()} / {@link #general(Duration)} — non-LLM HTTP:
 *       channel webhooks, the openrouter leaderboard fetch, scanner
 *       production HTTP, web search. Conventional cached-thread-pool
 *       dispatcher (no VT — request volume here is well below the point
 *       where platform-thread scheduling overhead matters), smaller
 *       32-slot connection pool, default 60s {@code callTimeout}.
 *       The {@code (Duration)} overload returns a derived client with the
 *       caller's {@code callTimeout} and {@code readTimeout} both set —
 *       cheap because the underlying pool and dispatcher are shared by
 *       reference via {@link OkHttpClient.Builder#newBuilder()}.</li>
 * </ul>
 *
 * <p>No SSRF guard here — provider URLs and channel webhook URLs are
 * trusted operator config. The user-controlled URL path that
 * {@link tools.WebFetchTool} exposes wraps its own {@link OkHttpClient}
 * via {@link SsrfGuard#buildGuardedClient} with a per-request DNS
 * allow-list; that lives separately because its constraint differs.
 *
 * <p>This class replaces the prior {@code LlmOkHttpClient} +
 * {@code OkHttpClients} static-field holders, which exposed mutable
 * shared resources as public constants — anti-OO, hard to substitute in
 * tests, low cohesion across two near-identical files. The factory-method
 * shape gives a single Information Expert, declared intent at every call
 * site, and a single place to evolve tuning.
 */
public final class HttpFactories {

    private static final ConnectionPool LLM_POOL = new ConnectionPool(64, 5, TimeUnit.MINUTES);
    private static final ConnectionPool GEN_POOL = new ConnectionPool(32, 5, TimeUnit.MINUTES);

    /**
     * Virtual-thread executor for the LLM dispatcher. Per-task VTs let
     * OkHttp's blocking {@code BufferedSource.read()} loop unmount its
     * carrier whenever the socket has nothing to deliver — the same
     * threading efficiency the JDK {@code HttpClient} gets natively via
     * async I/O, achieved here via cooperative VT scheduling.
     */
    private static final ExecutorService LLM_VT_EXEC =
            Executors.newVirtualThreadPerTaskExecutor();

    private static final Dispatcher LLM_DISPATCHER;
    static {
        LLM_DISPATCHER = new Dispatcher(LLM_VT_EXEC);
        LLM_DISPATCHER.setMaxRequests(128);
        LLM_DISPATCHER.setMaxRequestsPerHost(64);
    }

    private static final OkHttpClient LLM_STREAMING_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ZERO)
            .connectionPool(LLM_POOL)
            .dispatcher(LLM_DISPATCHER)
            .build();

    private static final OkHttpClient LLM_SINGLE_SHOT_BASE = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(180))
            .connectionPool(LLM_POOL)
            .dispatcher(LLM_DISPATCHER)
            .build();

    private static final OkHttpClient GENERAL_BASE = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(60))
            .connectionPool(GEN_POOL)
            .build();

    private HttpFactories() { }

    /**
     * Streaming LLM SSE client. Always returns the same shared instance —
     * its {@code callTimeout(0)} means individual streams set their own
     * pacing via per-frame {@code readTimeout}, so there's no per-call
     * timeout to derive.
     */
    public static OkHttpClient llmStreaming() {
        return LLM_STREAMING_CLIENT;
    }

    /**
     * Single-shot LLM client honoring the caller's {@code callTimeout}.
     * Returns the shared base client when the requested timeout matches
     * the 180s default (the typical case for non-streaming chat); otherwise
     * derives a per-call client via {@link OkHttpClient.Builder#newBuilder()}
     * so the underlying connection pool and dispatcher are shared by
     * reference.
     *
     * <p>Sets {@code readTimeout} to the same duration as {@code callTimeout}
     * — for short-lived single-shot calls there's no per-frame slowness to
     * accommodate, so a single bound is the right semantics.
     */
    public static OkHttpClient llmSingleShot(Duration callTimeout) {
        if (callTimeout.equals(Duration.ofSeconds(180))) return LLM_SINGLE_SHOT_BASE;
        return LLM_SINGLE_SHOT_BASE.newBuilder()
                .callTimeout(callTimeout)
                .readTimeout(callTimeout)
                .build();
    }

    /**
     * General-purpose non-LLM client at default tunings (60s callTimeout,
     * 30s readTimeout). The same shared instance for every caller; per-call
     * timeout overrides go through {@link #general(Duration)}.
     */
    public static OkHttpClient general() {
        return GENERAL_BASE;
    }

    /**
     * General-purpose client with a caller-specified {@code callTimeout}.
     * {@code readTimeout} is set to the same duration so callers don't
     * need to think about both axes — for non-LLM HTTP the two bounds are
     * almost always correlated. Cheap to derive (shares the underlying
     * pool by reference); a fresh client per call is acceptable when the
     * call site really needs a non-default timeout.
     */
    public static OkHttpClient general(Duration callTimeout) {
        return GENERAL_BASE.newBuilder()
                .callTimeout(callTimeout)
                .readTimeout(callTimeout)
                .build();
    }
}
