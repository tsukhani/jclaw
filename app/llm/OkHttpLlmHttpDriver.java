package llm;

import llm.LlmFailureClassifier.CallSite;
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
 * sourced via {@link utils.HttpFactories#llmStreamingGuarded()} and
 * {@link utils.HttpFactories#llmSingleShotGuarded()}.
 *
 * <p>The guarded tiers, not the plain ones (JCLAW-1229). A provider base URL
 * used to be screened once at discovery and never again, so a host that
 * resolved somewhere respectable then and to 169.254.169.254 afterwards
 * reached the metadata endpoint on the chat path. The relaxed provider guard
 * permits loopback and RFC-1918, so self-hosted Ollama and LM Studio are
 * unaffected — see {@code SsrfGuard.isBlockedForProvider}.
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
        var call = OtelRuntime.traced(HttpFactories.llmSingleShotGuarded()).newCall(req);
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
     * writes to H2 and the JDK closes a file database's channel on interrupt. Canceling surfaces
     * as {@code onFailure}, so the await below unwinds through the ordinary path.
     */
    @SuppressWarnings("java:S107") // the SSE callback surface, the cancel handle, and the call's identity
    static void streamSse(URI uri, String authHeader, String jsonBody, CallSite site,
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
                        onError.accept(classify(resp.code(), body, authHeader, site));
                    } else {
                        onError.accept(t != null ? t
                                : new LlmProvider.LlmException("SSE failed without cause"));
                    }
                } finally {
                    done.countDown();
                }
            }
        };

        var eventSource = EventSources.createFactory(OtelRuntime.traced(HttpFactories.llmStreamingGuarded()))
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
     * Streaming POST consuming newline-delimited JSON (JCLAW-1158): Ollama's native
     * {@code /api/chat} answers one JSON object per line rather than SSE frames. Every non-blank
     * line reaches {@code onLine}; {@code onComplete} runs when the body ends cleanly;
     * {@code onError} on transport failure or a non-200 status. Same client and the same cancel
     * contract as {@link #streamSse}: {@code publishCancel} gets the call's abort, which closes
     * the socket and unwinds the blocked read below as an {@link IOException}.
     */
    @SuppressWarnings("java:S107") // the streaming callback surface, the cancel handle, and the call's identity
    static void streamNdjson(URI uri, String authHeader, String jsonBody, CallSite site,
                             Consumer<String> onLine, Runnable onComplete, Consumer<Throwable> onError,
                             Consumer<Runnable> publishCancel, @Nullable String channel) {
        var builder = new Request.Builder()
                .url(uri.toString())
                .header(HttpKeys.AUTHORIZATION, authHeader)
                .post(RequestBody.create(jsonBody, JSON));
        if (channel != null) builder.tag(String.class, channel);
        var call = OtelRuntime.traced(HttpFactories.llmStreamingGuarded()).newCall(builder.build());
        publishCancel.accept(call::cancel);
        try (var resp = call.execute()) {
            if (resp.code() != 200) {
                onError.accept(classify(resp.code(), resp.body().string(), authHeader, site));
                return;
            }
            var source = resp.body().source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (!line.isBlank()) onLine.accept(line);
            }
            onComplete.run();
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
        }
    }

    /**
     * Classify a non-200 SSE status the way {@code LlmProvider.attemptRequest} classifies a
     * single-shot one (JCLAW-1166), so a caller can tell a provider fault from a request of
     * ours, and attach the remedy it needs (JCLAW-1134). The message keeps the body text:
     * {@code ToolCapabilityMemo} matches on it.
     *
     * <p>The remedy is read off the raw body and the message off the sanitized one — redaction
     * and the length cap can land mid-code, and only the sanitized copy may be surfaced.
     */
    private static LlmProvider.LlmException classify(int status, String body, String authHeader,
                                                     CallSite site) {
        var failure = site.classifying(status, body);
        var sanitized = sanitizeErrorBody(body, authHeader);
        var message = "HTTP %d: %s".formatted(status, sanitized);
        if (status == 429) return new LlmProvider.LlmException.RateLimited(message, null, failure);
        if (status >= 500) return new LlmProvider.LlmException.ServerError(message, null, failure);
        if (status >= 400) return new LlmProvider.LlmException.ClientError(message, failure, status, sanitized);
        return new LlmProvider.LlmException(message, null, failure);
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
