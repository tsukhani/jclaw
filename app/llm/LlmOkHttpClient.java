package llm;

import okhttp3.OkHttpClient;

import java.time.Duration;

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
 * <p>Both clients share the same default {@code ConnectionPool} and
 * {@code Dispatcher} (via OkHttp's defaults — neither is overridden) so
 * connections are reused across single-shot and streaming requests.
 */
final class LlmOkHttpClient {

    static final OkHttpClient STREAMING = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ZERO)
            .build();

    static final OkHttpClient SINGLE_SHOT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(180))
            .build();

    /**
     * Single-shot client honoring the caller's timeout. Returns {@link #SINGLE_SHOT}
     * directly when the requested timeout matches the default 180s; otherwise
     * derives a per-call client (cheap — shares the base pool / dispatcher).
     */
    static OkHttpClient singleShot(Duration timeout) {
        if (timeout.equals(Duration.ofSeconds(180))) return SINGLE_SHOT;
        return SINGLE_SHOT.newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    private LlmOkHttpClient() { }
}
