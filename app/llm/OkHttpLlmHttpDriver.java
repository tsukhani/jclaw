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
import java.util.function.Consumer;

/**
 * OkHttp 5.x-backed driver. Single-shot calls go through
 * {@link LlmOkHttpClient#SINGLE_SHOT}; streaming calls use
 * {@link LlmOkHttpClient#STREAMING} via {@link EventSources}.
 *
 * <p>OkHttp doesn't issue an {@code Upgrade: h2c} on plain HTTP, so it
 * has no LM-Studio quirk to dodge — provider name is irrelevant here, and
 * the entire {@link utils.HttpClients#forLlmProvider} routing collapses
 * to a single client. Phase 3 of the migration (JCLAW-187) deletes the
 * routing helper outright once this driver becomes the default.
 */
final class OkHttpLlmHttpDriver implements LlmHttpDriver {

    private static final MediaType JSON = MediaType.get("application/json");

    @Override
    public HttpReply send(URI uri, String authHeader, String jsonBody, Duration timeout)
            throws IOException, InterruptedException {
        var client = LlmOkHttpClient.singleShot(timeout);
        var req = new Request.Builder()
                .url(uri.toString())
                .header("Authorization", authHeader)
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        try (var resp = client.newCall(req).execute()) {
            var body = resp.body() != null ? resp.body().string() : "";
            var retryAfter = Optional.ofNullable(resp.header("Retry-After"))
                    .flatMap(OkHttpLlmHttpDriver::parseRetryAfter);
            return new HttpReply(resp.code(), body, retryAfter);
        }
    }

    @Override
    public void streamSse(URI uri, String authHeader, String jsonBody, Duration timeout,
                          Consumer<String> onEvent, Runnable onComplete, Consumer<Throwable> onError) {
        var req = new Request.Builder()
                .url(uri.toString())
                .header("Authorization", authHeader)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(jsonBody, JSON))
                .build();

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
                        try { body = resp.body() != null ? resp.body().string() : ""; }
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

        EventSources.createFactory(LlmOkHttpClient.STREAMING).newEventSource(req, listener);
        try {
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            onError.accept(ie);
        }
    }

    private static Optional<Long> parseRetryAfter(String value) {
        try { return Optional.of(Long.parseLong(value)); }
        catch (NumberFormatException _) { return Optional.empty(); }
    }
}
