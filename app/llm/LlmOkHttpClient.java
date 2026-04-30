package llm;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp client tuned for LLM provider traffic — short connect timeout, long
 * read timeout for slow models, no overall call timeout for SSE so streams
 * can run as long as the server keeps sending data. No SSRF guard here:
 * provider URLs are trusted operator config, unlike WebFetch's user-controlled
 * URLs that {@link utils.SsrfGuard} defends.
 *
 * <p>Two static instances:
 * <ul>
 *   <li>{@link #STREAMING}: {@code callTimeout(0)} so SSE survives long
 *       reasoning gaps; per-frame 180s {@code readTimeout} still bounds
 *       individual stalls.</li>
 *   <li>{@link #SINGLE_SHOT}: 180s {@code callTimeout} matching the JDK
 *       client's per-request timeout; used by non-streaming chat and
 *       embeddings.</li>
 * </ul>
 *
 * <p><b>Connection-pool sizing.</b> OkHttp's default {@link ConnectionPool}
 * keeps 5 idle connections per address. With 10+ concurrent SSE streams,
 * that means each iteration boundary churns through TCP setup as
 * connections beyond the 5 idle slots get closed and new ones opened.
 * We override to 64 idle slots so concurrent LLM traffic re-uses
 * connections cleanly across iterations, matching the JDK
 * {@link java.net.http.HttpClient}'s effectively-unbounded reuse model.
 *
 * <p><b>Protocols.</b> We rely on OkHttp's default protocol list — for HTTPS
 * that's {@code [H2, HTTP_1_1]} (ALPN-negotiated, picks HTTP/2 against any
 * modern provider so 10 concurrent streams multiplex on a single TCP
 * connection) and for cleartext that's {@code [HTTP_1_1]} only (no h2c
 * probe, so no LM-Studio-style upgrade-event hang risk). The earlier
 * cloud-vs-local benchmark caught a 2.6x regression on cloud caused by
 * a hand-pinned HTTP/1.1 list that disabled multiplexing over TLS;
 * removing the pin fixed cloud throughput without affecting the
 * cleartext loopback case.
 *
 * <p><b>Virtual-thread dispatcher.</b> OkHttp's default {@link Dispatcher}
 * runs calls on a {@code newCachedThreadPool()} of platform threads; with
 * its synchronous {@code BufferedSource.read()} loop, every concurrent SSE
 * stream pins a platform thread for the stream's full duration. The JDK
 * {@link java.net.http.HttpClient} we benchmark against uses
 * {@code AsynchronousSocketChannel} natively and lets virtual threads unmount
 * during blocking reads — which is why JDK at c=10 stays close to capacity
 * while OkHttp on the default executor pays platform-thread scheduling
 * overhead (~20% on local, ~50% on cloud). Pointing the Dispatcher at
 * {@link Executors#newVirtualThreadPerTaskExecutor()} gives OkHttp the same
 * unmount-during-blocking-I/O behavior: each call's read loop runs on a
 * virtual thread that yields its carrier to other work whenever the socket
 * has nothing to deliver. This is the fix that closes the residual gap.
 */
public final class LlmOkHttpClient {

    /**
     * Shared connection pool across both clients. 64 idle slots covers the
     * concurrency the loadtest exercises with margin to spare; 5-minute
     * keep-alive matches OkHttp's default and is well within Ollama's
     * idle-disconnect window.
     */
    public static final ConnectionPool POOL = new ConnectionPool(64, 5, TimeUnit.MINUTES);

    /**
     * Executor that hands every dispatched OkHttp call its own virtual
     * thread — so the synchronous {@code BufferedSource.read()} loop inside
     * each call unmounts its carrier whenever the socket has nothing to
     * deliver. Static-final ensures one pool for the lifetime of the JVM.
     */
    private static final ExecutorService VTHREAD_EXEC =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Shared dispatcher across both clients. Uses {@link #VTHREAD_EXEC} so
     * concurrent SSE streams ride virtual threads rather than the cached-
     * thread-pool platform threads OkHttp's default Dispatcher creates.
     * {@link Dispatcher#setMaxRequestsPerHost} is raised to 64 so the
     * dispatcher does not impose its own per-host cap on top of whatever
     * the connection pool and the underlying provider negotiate.
     */
    public static final Dispatcher DISPATCHER;
    static {
        DISPATCHER = new Dispatcher(VTHREAD_EXEC);
        DISPATCHER.setMaxRequests(128);
        DISPATCHER.setMaxRequestsPerHost(64);
    }

    public static final OkHttpClient STREAMING = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ZERO)
            .connectionPool(POOL)
            .dispatcher(DISPATCHER)
            .build();

    public static final OkHttpClient SINGLE_SHOT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(180))
            .connectionPool(POOL)
            .dispatcher(DISPATCHER)
            .build();

    /**
     * Single-shot client honoring the caller's timeout. Returns {@link #SINGLE_SHOT}
     * directly when the requested timeout matches the default 180s; otherwise
     * derives a per-call client (cheap — shares the base pool, dispatcher, and
     * connection pool via {@link OkHttpClient.Builder#newBuilder}).
     */
    public static OkHttpClient singleShot(Duration timeout) {
        if (timeout.equals(Duration.ofSeconds(180))) return SINGLE_SHOT;
        return SINGLE_SHOT.newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    private LlmOkHttpClient() { }
}
