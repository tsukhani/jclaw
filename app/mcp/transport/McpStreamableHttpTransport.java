package mcp.transport;

import mcp.jsonrpc.JsonRpc;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import play.Logger;
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);
    /** Per MCP spec (2025-06-18) §2.5: clients must echo this header on every
     *  request after the server assigns it on the initialize response. */
    private static final String MCP_SESSION_ID = "Mcp-Session-Id";

    private final String name;
    private final URI endpoint;
    private final Map<String, String> headers;

    private Consumer<JsonRpc.Message> onMessage;
    private Consumer<Throwable> onError;
    private volatile boolean closed;

    private final ConcurrentHashMap<Long, okhttp3.Call> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong callSeq = new AtomicLong();
    /** Session id assigned by the server on the initialize response (if any).
     *  Atomic so the SSE-reader thread that observes it from one response
     *  publishes to subsequent {@link #send} calls without a lock. Null until
     *  the first response carries it; null forever if the server doesn't
     *  participate in session tracking (legacy non-streamable HTTP servers). */
    private final AtomicReference<String> sessionId = new AtomicReference<>();

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
                .header(HttpKeys.ACCEPT, "application/json, text/event-stream")
                .post(body);
        for (var entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        // Echo the session id once the server has assigned one — required by
        // streamable-HTTP servers (e.g. context7) that reject subsequent
        // requests with HTTP 400 "No valid session ID provided" without it.
        // Spec is case-insensitive on the header name; we send the canonical
        // form to match the spec example.
        var sid = sessionId.get();
        if (sid != null) builder.header(MCP_SESSION_ID, sid);
        var call = HttpFactories.llmStreaming().newCall(builder.build());
        var token = callSeq.incrementAndGet();
        inFlight.put(token, call);
        Thread.ofVirtual().name("mcp-http-" + name + "-" + token).start(() -> {
            try (var resp = call.execute()) {
                handleResponse(resp);
            } catch (IOException | RuntimeException e) {
                // RuntimeException too: an unexpected parse/dereference failure
                // on this reader VT would otherwise vanish silently — route it
                // to onError so the connection manager can react.
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
            try { call.cancel(); } catch (RuntimeException _) { /* best effort */ }
        }
        inFlight.clear();
    }

    private void handleResponse(Response resp) throws IOException {
        // Capture the session id assigned by the server (if present). Both
        // canonical (Mcp-Session-Id) and lowercase forms are seen in the wild
        // since header names are case-insensitive; OkHttp normalizes lookup
        // so the canonical name covers both. First non-null wins — subsequent
        // responses may omit the header and shouldn't clobber.
        var sidFromHeader = resp.header(MCP_SESSION_ID);
        if (sidFromHeader != null && !sidFromHeader.isBlank()) {
            sessionId.compareAndSet(null, sidFromHeader);
        }
        if (!resp.isSuccessful()) {
            String snippet = "";
            try { snippet = resp.peekBody(512).string(); }
            catch (IOException _) { /* body unreadable */ }
            throw new IOException("MCP HTTP " + resp.code() + ": " + snippet);
        }
        if (resp.code() == 202) return;  // notification accepted, no body

        var rb = resp.body();
        if (rb == null) return;  // success status with no body — nothing to deliver

        var contentType = resp.header(HttpKeys.CONTENT_TYPE, "");
        if (contentType.contains("text/event-stream")) {
            consumeSseStream(rb.source());
            return;
        }
        var bodyStr = rb.string();
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
        String line;
        // Loop on readUtf8Line alone — it returns null on EOF, which is the
        // clean teardown signal. Calling source.exhausted() here would block
        // until the next byte (or EOF) arrives, defeating the close() path's
        // call.cancel(): cancellation surfaces as an IOException out of the
        // blocked read, which is what tears the loop down.
        while ((line = source.readUtf8Line()) != null) {
            if (line.isEmpty()) {
                flushSseEvent(data);
            } else if (line.startsWith("data:")) {
                appendSseDataLine(data, line);
            }
            // event:, id:, retry:, comment lines (':...') — all ignored for MCP
        }
        // EOF without trailing blank line: dispatch any buffered event.
        if (!data.isEmpty()) dispatchSseEvent(data.toString());
    }

    private void flushSseEvent(StringBuilder data) {
        if (!data.isEmpty()) {
            dispatchSseEvent(data.toString());
            data.setLength(0);
        }
    }

    private static void appendSseDataLine(StringBuilder data, String line) {
        var value = line.substring(5);
        if (value.startsWith(" ")) value = value.substring(1);
        if (!data.isEmpty()) data.append('\n');
        data.append(value);
    }

    private void dispatchSseEvent(String payload) {
        try {
            onMessage.accept(JsonRpc.decode(payload));
        } catch (RuntimeException e) {
            Logger.warn("[mcp:%s] failed to parse SSE event: %s", name, e.getMessage());
        }
    }
}
