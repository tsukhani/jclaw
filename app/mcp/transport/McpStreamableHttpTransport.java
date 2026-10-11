package mcp.transport;

import com.google.errorprone.annotations.MustBeClosed;
import mcp.McpException;
import mcp.jsonrpc.JsonRpc;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.jspecify.annotations.Nullable;
import play.Logger;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
 * enqueued; the response is read on OkHttp's dispatcher executor (a virtual
 * thread per {@link HttpFactories#llmStreaming}) and delivered via
 * {@code onMessage}. Concurrent {@code send}s are independent HTTP calls,
 * pooled and dispatched by that executor, so the dispatcher's per-host
 * request caps apply and provide backpressure.
 *
 * <p><b>Failures.</b> A non-2xx answer to a request fails that request alone through
 * {@code onRequestFailed} (JCLAW-1442): under 2026-07-28 a 400 or 404 carrying a JSON-RPC
 * error is an ordinary answer to one request. Two cases stay connection-level and go to
 * {@code onError}: a 400 or 404 on a POST that carried {@code Mcp-Session-Id}, either of which
 * can mean the session is gone, and a call that got no HTTP response.
 *
 * <p><b>Out of scope.</b> The optional GET-SSE channel for receiving
 * server-initiated events between requests is not implemented; for the
 * tools-only slice JCLAW-31 covers, notifications can only ride on the
 * SSE response of an in-flight request.
 */
public final class McpStreamableHttpTransport implements McpTransport {

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);
    /** Per MCP spec (2025-06-18) §2.5: clients must echo this header on every
     *  request after the server assigns it on the initialize response. */
    private static final String MCP_SESSION_ID = "Mcp-Session-Id";
    /** Enough for any JSON-RPC error object; a larger error body is not one we parse. */
    private static final long ERROR_BODY_LIMIT = 64 * 1024L;
    private static final String LISTEN = "subscriptions/listen";

    private final String name;
    private final URI endpoint;
    private final Map<String, String> headers;

    private Consumer<JsonRpc.Message> onMessage = _ -> {};
    private BiConsumer<Object, McpException> onRequestFailed = (_, _) -> {};
    private Consumer<Throwable> onError = _ -> {};
    private volatile boolean closed;
    /** Started with a request table, so a stream that ends unanswered can fail its request alone. */
    private volatile boolean tracksRequests;

    private final ConcurrentHashMap<Long, Call> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong callSeq = new AtomicLong();
    private final ConcurrentHashMap<Object, Call> requestCalls = new ConcurrentHashMap<>();
    /** Session id assigned by the server on the initialize response (if any).
     *  Atomic so the SSE-reader thread that observes it from one response
     *  publishes to subsequent {@link #send} calls without a lock. Null until
     *  the first response carries it; null forever if the server doesn't
     *  participate in session tracking (legacy non-streamable HTTP servers). */
    private final AtomicReference<String> sessionId = new AtomicReference<>();

    @MustBeClosed
    public McpStreamableHttpTransport(String name, URI endpoint, Map<String, String> headers) {
        if (endpoint == null) throw new IllegalArgumentException("endpoint required");
        this.name = name;
        this.endpoint = endpoint;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** With no request table to fail into, a per-request failure goes to {@code onError}. */
    @Override
    public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
        start(onMessage, (_, e) -> onError.accept(e), onError);
        tracksRequests = false;
    }

    @Override
    public void start(Consumer<JsonRpc.Message> onMessage,
                      BiConsumer<Object, McpException> onRequestFailed,
                      Consumer<Throwable> onError) {
        this.onMessage = onMessage;
        this.onRequestFailed = onRequestFailed;
        this.onError = onError;
        this.tracksRequests = true;
    }

    @Override
    public void send(JsonRpc.Message msg) throws IOException {
        send(msg, Map.of());
    }

    @Override
    public void send(JsonRpc.Message msg, Map<String, String> requestHeaders) throws IOException {
        if (closed) throw new IOException("transport closed");
        // JCLAW-778: the endpoint is agent-settable config. Reject a metadata /
        // link-local literal before opening the connection; hostname rebinding is
        // screened at connect by PROVIDER_SAFE_DNS on the guarded client below.
        try {
            SsrfGuard.assertProviderUrlSafe(endpoint.toString());
        } catch (SecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
        var body = RequestBody.create(JsonRpc.encode(msg), JSON);
        var builder = new Request.Builder()
                .url(endpoint.toString())
                .header(HttpKeys.ACCEPT, "application/json, text/event-stream")
                .post(body);
        for (var entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        for (var entry : requestHeaders.entrySet()) builder.header(entry.getKey(), entry.getValue());
        // Echo the session id once the server has assigned one — required by
        // streamable-HTTP servers (e.g. context7) that reject subsequent
        // requests with HTTP 400 "No valid session ID provided" without it.
        // Spec is case-insensitive on the header name; we send the canonical
        // form to match the spec example.
        var sid = sessionId.get();
        if (sid != null) builder.header(MCP_SESSION_ID, sid);
        var requestId = msg instanceof JsonRpc.Request r ? r.id() : null;
        var isInitialize = msg instanceof JsonRpc.Request r && "initialize".equals(r.method());
        // A dropped or idle listen stream fails that subscription, never the connection.
        var isListen = msg instanceof JsonRpc.Request r && LISTEN.equals(r.method());
        var carriedSession = sid != null;
        var call = (isListen ? listenClient() : HttpFactories.llmStreamingGuarded()).newCall(builder.build());
        var token = callSeq.incrementAndGet();
        inFlight.put(token, call);
        if (requestId != null) requestCalls.put(requestId, call);
        // Enqueue through OkHttp's Dispatcher rather than firing a per-send
        // virtual thread + call.execute(): the dispatcher owns the virtual-thread
        // executor (per HttpFactories#llmStreaming) and enforces the operator's
        // per-host request caps, giving sends real backpressure instead of an
        // unbounded fan-out of concurrent connections.
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                try {
                    // A cancelled call is one request given up on, not a failed connection.
                    if (!closed && !call.isCanceled()) ioFailure(e, requestId, isListen);
                } finally {
                    forget(token, requestId, call);
                }
            }

            @Override
            public void onResponse(Call call, Response resp) {
                try (resp) {
                    handleResponse(resp, requestId, isInitialize, carriedSession);
                } catch (IOException e) {
                    if (!closed && !call.isCanceled()) ioFailure(e, requestId, isListen);
                } catch (RuntimeException e) {
                    // RuntimeException too: an unexpected parse/dereference failure
                    // while reading the response would otherwise vanish silently —
                    // route it to onError so the connection manager can react.
                    if (!closed && !call.isCanceled()) onError.accept(e);
                } finally {
                    forget(token, requestId, call);
                }
            }
        });
    }

    /**
     * A listen stream is silent until something changes, and a server need not send keep-alives, so
     * the 180 s read timeout would cut it every three minutes; {@link #close()} or the server ends it.
     */
    private static OkHttpClient listenClient() {
        return HttpFactories.llmStreamingGuarded().newBuilder().readTimeout(Duration.ZERO).build();
    }

    private void ioFailure(IOException e, @Nullable Object requestId, boolean isListen) {
        if (isListen && requestId != null) {
            onRequestFailed.accept(requestId, new McpException(LISTEN + " stream failed: " + e.getMessage(), e));
        } else {
            onError.accept(e);
        }
    }

    private void forget(long token, @Nullable Object requestId, Call call) {
        inFlight.remove(token);
        if (requestId != null) requestCalls.remove(requestId, call);
    }

    @Override
    public boolean carriesHeaders() {
        return true;
    }

    /** Cancelling the call closes its response stream, which is how Streamable HTTP cancels a request. */
    @Override
    public void cancel(Object requestId, String reason) {
        var call = requestCalls.remove(requestId);
        if (call != null) call.cancel();
    }

    /** HTTP calls sent and not yet finished, for a test to see that a cancelled one went. */
    public int inFlightCalls() {
        return inFlight.size();
    }

    @Override
    public void close() {
        closed = true;
        for (var call : inFlight.values()) {
            try { call.cancel(); } catch (RuntimeException _) { /* best effort */ }
        }
        inFlight.clear();
        requestCalls.clear();
    }

    private void handleResponse(Response resp, @Nullable Object requestId, boolean isInitialize,
                                boolean carriedSession) throws IOException {
        // Capture the session id assigned by the server (if present). Both
        // canonical (Mcp-Session-Id) and lowercase forms are seen in the wild
        // since header names are case-insensitive; OkHttp normalizes lookup
        // so the canonical name covers both. First non-null wins — subsequent
        // responses may omit the header and shouldn't clobber. Only initialize
        // assigns one: 2026-07-28 has no sessions, so a modern connection never
        // stores or sends one (JCLAW-1443).
        var sidFromHeader = resp.header(MCP_SESSION_ID);
        if (isInitialize && sidFromHeader != null && !sidFromHeader.isBlank()) {
            sessionId.compareAndSet(null, sidFromHeader);
        }
        if (!resp.isSuccessful()) {
            // JCLAW-778: status only — the upstream body is attacker-influenced
            // (agent-settable endpoint) and must not be reflected into lastError.
            // Either can mean the session is gone: 404 for one the server ended, 400 from a server
            // that treats an unknown id like a missing one. Reconnecting drops the stale id.
            if (carriedSession && (resp.code() == 404 || resp.code() == 400)) {
                throw new IOException("MCP HTTP " + resp.code());
            }
            if (requestId != null) {
                if (!closed) onRequestFailed.accept(requestId, requestFailure(resp));
            } else {
                Logger.warn("[mcp:%s] HTTP %d answering a message that expects no reply", name, resp.code());
            }
            return;
        }
        if (resp.code() == 202) return;  // notification accepted, no body

        // OkHttp 5 Response.body() is non-null; an empty body yields "" (handled below).
        var rb = resp.body();
        var contentType = resp.header(HttpKeys.CONTENT_TYPE, "");
        if (contentType.contains("text/event-stream")) {
            consumeSseStream(rb.source());
            if (requestId != null && tracksRequests && !closed) {
                // Ignored by the client when the stream already carried the response.
                onRequestFailed.accept(requestId, new McpException("stream ended without a response"));
            }
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
     * The failure a non-2xx answer hands its request: the JSON-RPC error code when the body is
     * one, plus any recognized versions in {@code data.supported}. Never the body's message text.
     */
    private static McpException requestFailure(Response resp) {
        JsonRpc.Message decoded;
        try {
            decoded = JsonRpc.decode(resp.peekBody(ERROR_BODY_LIMIT).string());
        } catch (IOException | RuntimeException _) {
            // Not a JSON-RPC body; the exception text may quote it, so it goes nowhere.
            return McpException.fromHttp(resp.code(), null, null);
        }
        if (decoded instanceof JsonRpc.Response r && r.error() != null) {
            return McpException.fromHttp(resp.code(), r.error().code(), r.error().data());
        }
        return McpException.fromHttp(resp.code(), null, null);
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
            // event:, id:, retry: are ignored for MCP; comment lines (':...') are keep-alives
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
