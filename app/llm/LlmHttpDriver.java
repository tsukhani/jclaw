package llm;

import play.Play;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Transport seam for outbound LLM HTTP calls. Two implementations live behind
 * the {@code play.llm.client} flag: {@link JdkLlmHttpDriver} (default,
 * {@code jdk}) wraps the JDK {@link java.net.http.HttpClient} via
 * {@link utils.HttpClients#forLlmProvider(String)}; {@link OkHttpLlmHttpDriver}
 * ({@code okhttp}) wraps OkHttp 5.x with {@code okhttp-sse} for streaming.
 *
 * <p>The flag is read once per request at the entry of
 * {@link LlmProvider#chat}, {@link LlmProvider#chatStream}, and
 * {@link LlmProvider#embeddings}; the chosen driver is captured for the
 * duration of that request, so an operator who flips the flag mid-session
 * affects only the next request, never an in-flight one (JCLAW-185 AC4).
 *
 * <p>The driver owns transport only — request serialization and SSE chunk
 * parsing live in {@link LlmProvider}, so both drivers feed the exact same
 * stream of {@code data:} payloads upward. That's what makes the AC1
 * byte-identical assertion possible against a single MockWebServer3 fixture.
 */
interface LlmHttpDriver {

    /**
     * Single-shot POST. Returns the full response. Throws on transport failure
     * (connection refused, timeout, malformed URL); HTTP errors are reflected
     * as non-200 status codes in the result, not thrown.
     */
    HttpReply send(URI uri, String authHeader, String jsonBody, Duration timeout)
            throws IOException, InterruptedException;

    /**
     * Streaming POST consuming {@code text/event-stream}. Yields each SSE
     * event's {@code data:} field value (already-stripped) to {@code onEvent};
     * calls {@code onComplete} when the server closes the stream cleanly;
     * calls {@code onError} on transport failure or non-200 HTTP status.
     *
     * <p>The {@code [DONE]} sentinel is NOT filtered out here — both drivers
     * emit it as a regular event payload so callers can decide what to do
     * with it. (The current call site in {@link LlmProvider#chatStream}
     * just ignores it; the server closes the stream right after.)
     */
    void streamSse(URI uri, String authHeader, String jsonBody, Duration timeout,
                   Consumer<String> onEvent,
                   Runnable onComplete,
                   Consumer<Throwable> onError);

    /**
     * Pick a driver based on {@code play.llm.client} (default {@code jdk}).
     * Per-call: capture the returned driver into a local final and use it
     * for the whole request lifetime — that is the per-request commit that
     * AC4 relies on.
     */
    static LlmHttpDriver pick(String providerName) {
        var mode = Play.configuration != null
                ? Play.configuration.getProperty("play.llm.client", "jdk")
                : "jdk";
        return switch (mode) {
            case "jdk"    -> new JdkLlmHttpDriver(providerName);
            case "okhttp" -> new OkHttpLlmHttpDriver();
            default -> throw new IllegalArgumentException(
                    "play.llm.client must be 'jdk' or 'okhttp', got: " + mode);
        };
    }

    /** Single-shot HTTP response. */
    record HttpReply(int statusCode, String body, Optional<Long> retryAfterSeconds) { }
}
