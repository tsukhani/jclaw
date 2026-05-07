package utils;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Single source of truth for outbound HTTP-client provisioning. Callers
 * declare <em>intent</em> ({@link #llmStreaming()}, {@link #llmSingleShot()},
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
 *   <li>{@link #llmSingleShot()} — single-shot LLM calls (chat, embeddings,
 *       discovery, boot probes). 180s default {@code callTimeout}; callers
 *       override per-call via {@link okhttp3.Call#timeout()
 *       Call.timeout()}, which doesn't allocate a new client. Same shared
 *       pool + VT dispatcher as streaming so connection reuse is unified
 *       across the LLM stack.</li>
 *
 *   <li>{@link #general()} — non-LLM HTTP: channel webhooks, the
 *       openrouter leaderboard fetch, scanner production HTTP, web search.
 *       Default 60s {@code callTimeout}; callers override per-call via
 *       {@link okhttp3.Call#timeout() Call.timeout()}. Smaller 32-slot
 *       connection pool. Uses {@link #GEN_DISPATCHER} with the same
 *       {@code 128/64} caps as the LLM tier — overrides OkHttp's default
 *       {@code 64/5} so concurrent webhook bursts to the same host don't
 *       silently queue at the dispatcher (mirrors the Phase-1 fix the LLM
 *       tier needed; preemptive here since current general workloads stay
 *       well under {@code 5} per host but future batch flows might not).
 *       Conventional cached-thread-pool executor on the dispatcher (no VT —
 *       general request volume doesn't justify VT scheduling overhead).</li>
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
 *
 * <p><b>Per-call timeouts.</b> Use {@link okhttp3.Call#timeout()
 * Call.timeout()} on the returned call, not a derived client. That sets
 * the per-call deadline directly on the {@code RealCall} without
 * allocating a new {@code OkHttpClient}. Example:
 * <pre>{@code
 * var call = HttpFactories.general().newCall(request);
 * call.timeout().timeout(15, TimeUnit.SECONDS);
 * try (var resp = call.execute()) { ... }
 * }</pre>
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
        // Bootstrap defaults; DefaultConfigJob seeds the host-tuned values
        // post-JPA-init and calls applyDispatcherConfig() to swap them in.
        // Hardcoded here so any HTTP call that happens before the seed job
        // runs (none today, but defence-in-depth) gets a sane cap.
        LLM_DISPATCHER.setMaxRequests(128);
        LLM_DISPATCHER.setMaxRequestsPerHost(64);
        play.Logger.info("HttpFactories LLM_DISPATCHER bootstrap: maxRequests=%d maxRequestsPerHost=%d",
                LLM_DISPATCHER.getMaxRequests(), LLM_DISPATCHER.getMaxRequestsPerHost());
    }

    /**
     * Apply (or re-apply) {@code provider.llm.dispatcher.maxRequests} and
     * {@code provider.llm.dispatcher.maxRequestsPerHost} from
     * {@link services.ConfigService} to the live LLM dispatcher. Called
     * once at startup by DefaultConfigJob after the host-tuned defaults
     * are seeded, and again from {@link services.ConfigService#setWithSideEffects}
     * whenever an operator changes either key via Settings — so the new
     * values take effect immediately without restart.
     */
    public static void applyDispatcherConfig() {
        int perHost = services.ConfigService.getInt(
                "provider.llm.dispatcher.maxRequestsPerHost", 64);
        int max = services.ConfigService.getInt(
                "provider.llm.dispatcher.maxRequests", 128);
        LLM_DISPATCHER.setMaxRequestsPerHost(perHost);
        LLM_DISPATCHER.setMaxRequests(max);
        play.Logger.info("HttpFactories LLM_DISPATCHER applied: maxRequests=%d maxRequestsPerHost=%d",
                max, perHost);
    }

    /** Current dispatcher max-requests-per-host (live value, not Config). */
    public static int llmDispatcherMaxRequestsPerHost() {
        return LLM_DISPATCHER.getMaxRequestsPerHost();
    }

    /** Current dispatcher max-requests total (live value, not Config). */
    public static int llmDispatcherMaxRequests() {
        return LLM_DISPATCHER.getMaxRequests();
    }

    /**
     * Push transient caps into the live dispatcher WITHOUT touching Config.
     * Used by the loadtest controller to bump the cap above the static
     * default for a single test run; restored by the same controller in
     * a finally block. Bypasses {@link #applyDispatcherConfig} so the
     * persisted values stay untouched.
     */
    public static void setLlmDispatcherCapTransient(int maxRequestsPerHost, int maxRequests) {
        LLM_DISPATCHER.setMaxRequestsPerHost(maxRequestsPerHost);
        LLM_DISPATCHER.setMaxRequests(maxRequests);
    }

    private static final Dispatcher GEN_DISPATCHER;
    static {
        GEN_DISPATCHER = new Dispatcher();   // OkHttp's default cached-thread-pool executor
        GEN_DISPATCHER.setMaxRequests(128);
        GEN_DISPATCHER.setMaxRequestsPerHost(64);
    }

    /**
     * Logs the negotiated wire protocol (e.g. {@code h2}, {@code http/1.1})
     * the first time we see each {@code host:port}. Hooks {@link
     * EventListener#connectionAcquired}, which fires once per call when it
     * binds to a connection — never per frame, so SSE streams pay zero
     * per-byte cost. The seen-hosts set is a lock-free
     * {@link ConcurrentHashMap}-backed set; the hot path on subsequent
     * calls is one {@code add()} returning {@code false}.
     */
    private static final Set<String> SEEN_HOSTS = ConcurrentHashMap.newKeySet();
    private static final EventListener PROTOCOL_LOGGER = new EventListener() {
        @Override
        public void connectionAcquired(Call call, Connection connection) {
            var url = call.request().url();
            String key = url.host() + ":" + url.port();
            if (SEEN_HOSTS.add(key)) {
                play.Logger.info("OkHttp negotiated %s with %s",
                        connection.protocol(), key);
            }
        }
    };

    private static final OkHttpClient LLM_STREAMING_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ZERO)
            .connectionPool(LLM_POOL)
            .dispatcher(LLM_DISPATCHER)
            .eventListenerFactory(LlmCallEventListener.factory())
            .build();

    private static final OkHttpClient LLM_SINGLE_SHOT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(180))
            .connectionPool(LLM_POOL)
            .dispatcher(LLM_DISPATCHER)
            .eventListenerFactory(LlmCallEventListener.factory())
            .build();

    private static final OkHttpClient GENERAL_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(60))
            .connectionPool(GEN_POOL)
            .dispatcher(GEN_DISPATCHER)
            .eventListener(PROTOCOL_LOGGER)
            .build();

    private HttpFactories() { }

    /**
     * Streaming LLM SSE client. {@code callTimeout(0)} so individual streams
     * are bounded only by per-frame {@code readTimeout}, not by an overall
     * call deadline.
     */
    public static OkHttpClient llmStreaming() {
        return LLM_STREAMING_CLIENT;
    }

    /**
     * Single-shot LLM client. 180s default {@code callTimeout}; callers
     * override per-call via {@link okhttp3.Call#timeout() Call.timeout()}.
     * Always returns the same shared instance — no per-call client allocation.
     */
    public static OkHttpClient llmSingleShot() {
        return LLM_SINGLE_SHOT_CLIENT;
    }

    /**
     * General-purpose non-LLM client. 60s default {@code callTimeout};
     * callers override per-call via {@link okhttp3.Call#timeout()
     * Call.timeout()}. Always returns the same shared instance — no
     * per-call client allocation.
     */
    public static OkHttpClient general() {
        return GENERAL_CLIENT;
    }
}
