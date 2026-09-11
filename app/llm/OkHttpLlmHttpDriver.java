package llm;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jspecify.annotations.Nullable;
import services.telemetry.OtelRuntime;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

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

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);

    /** Single-shot HTTP response. */
    record HttpReply(int statusCode, String body, Optional<Long> retryAfterSeconds) { }

    private OkHttpLlmHttpDriver() { }

    /**
     * Single-shot POST. Returns the full response. Throws on transport failure
     * (connection refused, timeout, malformed URL); HTTP errors are reflected
     * as non-200 status codes in the result, not thrown.
     */
    static HttpReply send(URI uri, String authHeader, String jsonBody, Duration timeout,
                          @Nullable String channel) throws IOException {
        var builder = new Request.Builder()
                .url(uri.toString())
                .header(HttpKeys.AUTHORIZATION, authHeader)
                .post(RequestBody.create(jsonBody, JSON));
        if (channel != null) builder.tag(String.class, channel);
        var req = builder.build();
        var call = OtelRuntime.traced(HttpFactories.llmSingleShot()).newCall(req);
        // Per-call timeout via Call.timeout() — no per-call client allocation.
        call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        try (var resp = call.execute()) {
            // OkHttp 5 Response.body() is non-null (an empty body yields ""), so no null guard.
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
     *
     * <p>Per-call timeout is intentionally absent: the streaming client uses
     * {@code callTimeout(0)} + a 180s {@code readTimeout} (see {@link utils.HttpFactories#llmStreaming()})
     * which is what every caller wants — bound individual stalls, not the
     * overall stream. The {@link okhttp3.Call#timeout()} per-call override
     * doesn't compose with EventSource the way it does with single-shot
     * {@code execute()}.
     *
     * <p>{@code publishCancel} receives the abort for the in-flight call as soon as there is one
     * (JCLAW-1183). It is what lets a watcher on another thread release this one, which is
     * otherwise reachable only by interrupting it — barred here, because this thread's retry path
     * writes to H2 and the JDK closes a file database's channel on interrupt. Cancelling surfaces
     * as {@code onFailure}, so the await below unwinds through the ordinary path.
     */
    @SuppressWarnings("java:S107") // the SSE callback surface plus the cancel handle it publishes
    static void streamSse(URI uri, String authHeader, String jsonBody,
                          Consumer<String> onEvent, Runnable onComplete, Consumer<Throwable> onError,
                          Consumer<Runnable> publishCancel, @Nullable String channel) {
        var builder = new Request.Builder()
                .url(uri.toString())
                .header(HttpKeys.AUTHORIZATION, authHeader)
                .header(HttpKeys.ACCEPT, "text/event-stream")
                .post(RequestBody.create(jsonBody, JSON));
        if (channel != null) builder.tag(String.class, channel);
        var req = builder.build();

        var done = new CountDownLatch(1);
        var listener = new EventSourceListener() {
            @Override public void onEvent(EventSource es, @Nullable String id,
                                          @Nullable String type, String data) {
                onEvent.accept(data);
            }
            @Override public void onClosed(EventSource es) {
                try { onComplete.run(); }
                finally { done.countDown(); }
            }
            @Override public void onFailure(EventSource es, @Nullable Throwable t,
                                            @Nullable Response resp) {
                try {
                    if (resp != null && resp.code() != 200) {
                        var body = "";
                        try { body = resp.body().string(); }
                        catch (IOException _) { /* body already consumed or absent */ }
                        onError.accept(classify(resp.code(), sanitizeErrorBody(body, authHeader)));
                    } else {
                        onError.accept(t != null ? t
                                : new LlmProvider.LlmException("SSE failed without cause"));
                    }
                } finally {
                    done.countDown();
                }
            }
        };

        var eventSource = EventSources.createFactory(OtelRuntime.traced(HttpFactories.llmStreaming()))
                .newEventSource(req, listener);
        publishCancel.accept(eventSource::cancel);
        try {
            done.await();
        } catch (InterruptedException ie) {
            // Tell OkHttp to abort the in-flight call so the underlying
            // socket connection isn't held open until readTimeout fires.
            // Without this, a canceled chat (user clicks Stop, route
            // navigation, etc.) would leak its connection for up to 180s.
            eventSource.cancel();
            Thread.currentThread().interrupt();
            onError.accept(ie);
        }
    }

    /**
     * Classify a non-200 SSE status the way {@code LlmProvider.attemptRequest} classifies a
     * single-shot one (JCLAW-1166), so a caller can tell a provider fault from a request of
     * ours. The message is unchanged: {@code ToolCapabilityMemo} matches on its text.
     */
    private static LlmProvider.LlmException classify(int status, String body) {
        var message = "HTTP %d: %s".formatted(status, body);
        if (status == 429) return new LlmProvider.LlmException.RateLimited(message);
        if (status >= 500) return new LlmProvider.LlmException.ServerError(message);
        if (status >= 400) return new LlmProvider.LlmException.ClientError(message);
        return new LlmProvider.LlmException(message);
    }

    private static Optional<Long> parseRetryAfter(String value) {
        try { return Optional.of(Long.parseLong(value)); }
        catch (NumberFormatException _) { return Optional.empty(); }
    }

    /**
     * JCLAW-730: scrub the bearer key and cap the length of a non-200 SSE error
     * body before it enters an {@link LlmProvider.LlmException} message (which
     * flows into event logs / the UI). {@code authHeader} is {@code "Bearer
     * <key>"}; a provider that echoes the request could otherwise surface the raw
     * key, and an oversized body would flood the logs.
     */
    private static String sanitizeErrorBody(String body, String authHeader) {
        String key = null;
        if (authHeader != null && authHeader.startsWith(HttpKeys.BEARER_PREFIX)) {
            var extracted = authHeader.substring(HttpKeys.BEARER_PREFIX.length());
            if (!extracted.isEmpty()) key = extracted;
        }
        return Strings.redactAndTruncate(body, key);
    }
}
