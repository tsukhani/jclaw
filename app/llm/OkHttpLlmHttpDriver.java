package llm;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OkHttp 5.x-backed LLM transport. The only HTTP path for outbound LLM
 * traffic since JCLAW-187 deleted the JDK alternative; static utility
 * methods because the class has no per-instance state — clients are
 * sourced via {@link utils.HttpFactories#llmStreaming()} and
 * {@link utils.HttpFactories#llmSingleShot(Duration)}.
 *
 * <p>OkHttp does not issue an {@code Upgrade: h2c} on plain HTTP, so the
 * LM-Studio Express upgrade-event hang the previous JDK driver had to
 * dodge with the {@code forLlmProvider} routing rule is structurally
 * absent — JCLAW-187 deleted that helper as well.
 */
final class OkHttpLlmHttpDriver {

    private static final MediaType JSON = MediaType.get("application/json");

    /** Single-shot HTTP response. */
    record HttpReply(int statusCode, String body, Optional<Long> retryAfterSeconds) { }

    private OkHttpLlmHttpDriver() { }

    /**
     * Single-shot POST. Returns the full response. Throws on transport failure
     * (connection refused, timeout, malformed URL); HTTP errors are reflected
     * as non-200 status codes in the result, not thrown.
     */
    static HttpReply send(URI uri, String authHeader, String jsonBody, Duration timeout, String channel)
            throws IOException, InterruptedException {
        var builder = new Request.Builder()
                .url(uri.toString())
                .header("Authorization", authHeader)
                .post(RequestBody.create(jsonBody, JSON));
        if (channel != null) builder.tag(String.class, channel);
        var req = builder.build();
        var call = utils.HttpFactories.llmSingleShot().newCall(req);
        // Per-call timeout via Call.timeout() — no per-call client allocation.
        call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        try (var resp = call.execute()) {
            var body = resp.body().string();
            var retryAfter = Optional.ofNullable(resp.header("Retry-After"))
                    .flatMap(OkHttpLlmHttpDriver::parseRetryAfter);
            return new HttpReply(resp.code(), body, retryAfter);
        }
    }

    /**
     * Streaming POST consuming {@code text/event-stream}. Yields each SSE
     * event's {@code data:} field value (already-stripped) to {@code onEvent};
     * calls {@code onComplete} when the server closes the stream cleanly;
     * calls {@code onError} on transport failure or non-200 HTTP status.
     *
     * <p>The {@code [DONE]} sentinel is NOT filtered out here — the caller in
     * {@link LlmProvider#chatStream} skips it before parsing.
     */
    static void streamSse(URI uri, String authHeader, String jsonBody, Duration timeout,
                          Consumer<String> onEvent, Runnable onComplete, Consumer<Throwable> onError,
                          String channel) {
        var builder = new Request.Builder()
                .url(uri.toString())
                .header("Authorization", authHeader)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(jsonBody, JSON));
        if (channel != null) builder.tag(String.class, channel);
        var req = builder.build();

        var done = new CountDownLatch(1);
        var listener = new EventSourceListener() {
            @Override public void onEvent(EventSource es, String id, String type, String data) {
                onEvent.accept(data);
            }
            @Override public void onClosed(EventSource es) {
                try { onComplete.run(); }
                finally { done.countDown(); }
            }
            @Override public void onFailure(EventSource es, Throwable t, Response resp) {
                try {
                    if (resp != null && resp.code() != 200) {
                        var body = "";
                        try { body = resp.body().string(); }
                        catch (IOException _) { /* body already consumed or absent */ }
                        onError.accept(new LlmProvider.LlmException(
                                "HTTP %d: %s".formatted(resp.code(), body)));
                    } else {
                        onError.accept(t != null ? t
                                : new LlmProvider.LlmException("SSE failed without cause"));
                    }
                } finally {
                    done.countDown();
                }
            }
        };

        var eventSource = EventSources.createFactory(utils.HttpFactories.llmStreaming())
                .newEventSource(req, listener);
        try {
            done.await();
        } catch (InterruptedException ie) {
            // Tell OkHttp to abort the in-flight call so the underlying
            // socket connection isn't held open until readTimeout fires.
            // Without this, a cancelled chat (user clicks Stop, route
            // navigation, etc.) would leak its connection for up to 180s.
            eventSource.cancel();
            Thread.currentThread().interrupt();
            onError.accept(ie);
        }
    }

    private static Optional<Long> parseRetryAfter(String value) {
        try { return Optional.of(Long.parseLong(value)); }
        catch (NumberFormatException _) { return Optional.empty(); }
    }
}
