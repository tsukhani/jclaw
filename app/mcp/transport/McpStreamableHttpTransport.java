package mcp.transport;

import mcp.jsonrpc.JsonRpc;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import play.Logger;
import utils.HttpFactories;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP Streamable HTTP transport (JCLAW-31).
 *
 * <p>Per the MCP 2025-06-18 spec the client POSTs each JSON-RPC message to a
 * single endpoint with {@code Accept: application/json, text/event-stream}.
 * The server responds with one of:
 *
 * <ul>
 *   <li>{@code Content-Type: application/json} — a single JSON-RPC reply
 *       (immediate request/response).</li>
 *   <li>{@code Content-Type: text/event-stream} — an SSE stream where each
 *       event's {@code data:} field carries one JSON-RPC message; the server
 *       closes the stream after delivering the response.</li>
 *   <li>{@code 202 Accepted} with no body — the message was a notification
 *       and no reply is expected.</li>
 * </ul>
 *
 * <p>This satisfies the AC's "HTTP, and SSE transports" — the SSE path is the
 * streaming sub-mode of HTTP, chosen by the server per request via Content-Type.
 *
 * <p><b>Threading.</b> {@link #send} returns as soon as the POST request is
 * dispatched; the response is read on a fresh virtual thread and delivered
 * via {@code onMessage}. Concurrent {@code send}s are independent HTTP calls,
 * pooled and dispatched by OkHttp's virtual-thread executor (per
 * {@link HttpFactories#llmStreaming}).
 *
 * <p><b>Out of scope.</b> The optional GET-SSE channel for receiving
 * server-initiated events between requests is not implemented; for the
 * tools-only slice JCLAW-31 covers, notifications can only ride on the
 * SSE response of an in-flight request. Adding GET-SSE is additive when a
 * future story needs it.
 */
public final class McpStreamableHttpTransport implements McpTransport {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String name;
    private final URI endpoint;
    private final Map<String, String> headers;

    private Consumer<JsonRpc.Message> onMessage;
    private Consumer<Throwable> onError;
    private volatile boolean closed;

    private final ConcurrentHashMap<Long, okhttp3.Call> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong callSeq = new AtomicLong();

    public McpStreamableHttpTransport(String name, URI endpoint, Map<String, String> headers) {
        if (endpoint == null) throw new IllegalArgumentException("endpoint required");
        this.name = name;
        this.endpoint = endpoint;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
        this.onMessage = onMessage;
        this.onError = onError;
    }

    @Override
    public void send(JsonRpc.Message msg) throws IOException {
        if (closed) throw new IOException("transport closed");
        var body = RequestBody.create(JsonRpc.encode(msg), JSON);
        var builder = new Request.Builder()
                .url(endpoint.toString())
                .header("Accept", "application/json, text/event-stream")
                .post(body);
        for (var entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        var call = HttpFactories.llmStreaming().newCall(builder.build());
        var token = callSeq.incrementAndGet();
        inFlight.put(token, call);
        Thread.ofVirtual().name("mcp-http-" + name + "-" + token).start(() -> {
            try (var resp = call.execute()) {
                handleResponse(resp);
            } catch (IOException e) {
                if (!closed) onError.accept(e);
            } finally {
                inFlight.remove(token);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        for (var call : inFlight.values()) {
            try { call.cancel(); } catch (RuntimeException ignored) { /* best effort */ }
        }
        inFlight.clear();
    }

    private void handleResponse(Response resp) throws IOException {
        if (!resp.isSuccessful()) {
            String snippet = "";
            try { snippet = resp.peekBody(512).string(); }
            catch (IOException ignored) { /* body unreadable */ }
            throw new IOException("MCP HTTP " + resp.code() + ": " + snippet);
        }
        if (resp.code() == 202) return;  // notification accepted, no body

        var contentType = resp.header("Content-Type", "");
        if (contentType.contains("text/event-stream")) {
            consumeSseStream(resp.body().source());
            return;
        }
        var bodyStr = resp.body().string();
        if (bodyStr.isBlank()) return;
        try {
            onMessage.accept(JsonRpc.decode(bodyStr));
        } catch (RuntimeException e) {
            Logger.warn("[mcp:%s] failed to parse HTTP body: %s", name, e.getMessage());
        }
    }

    /**
     * Minimal SSE parser: each event is a sequence of {@code field: value}
     * lines terminated by an empty line. We care only about the {@code data:}
     * field (concatenated with {@code \n} when it spans multiple lines per
     * the SSE spec). All other fields are ignored.
     */
    private void consumeSseStream(BufferedSource source) throws IOException {
        var data = new StringBuilder();
        while (!closed && !source.exhausted()) {
            var line = source.readUtf8Line();
            if (line == null) break;  // server closed
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    dispatchSseEvent(data.toString());
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                var value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(value);
            }
            // event:, id:, retry:, comment lines (':...') — all ignored for MCP
        }
        // EOF without trailing blank line: dispatch any buffered event.
        if (data.length() > 0) dispatchSseEvent(data.toString());
    }

    private void dispatchSseEvent(String payload) {
        try {
            onMessage.accept(JsonRpc.decode(payload));
        } catch (RuntimeException e) {
            Logger.warn("[mcp:%s] failed to parse SSE event: %s", name, e.getMessage());
        }
    }
}
