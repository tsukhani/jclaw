package mcp;

import com.google.gson.JsonObject;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import play.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * MCP client state machine over an {@link McpTransport} (JCLAW-31).
 *
 * <p>Owns the JSON-RPC ID space, the in-flight request map, the tool-list
 * cache, and the protocol lifecycle. The transport delivers raw {@link
 * JsonRpc.Message}s; this class correlates responses, dispatches notifications,
 * and replies to server-initiated requests.
 *
 * <p><b>Threading.</b> All public methods are safe to call from any virtual
 * thread. The transport's read loop drives {@link #onInbound(JsonRpc.Message)}
 * on its own thread; that handler does no blocking work — it completes futures
 * and dispatches notifications via {@code listChangedHandler}.
 *
 * <p><b>Server-initiated requests.</b> The MCP spec lets servers ask the
 * client to do things (sampling, elicitation, roots/list). JClaw declares
 * none of these capabilities during {@code initialize}, so a well-behaved
 * server won't issue them. If one slips through anyway we reply with
 * JSON-RPC error -32601 (Method not found) — the spec-conformant fallback.
 */
public class McpClient implements AutoCloseable {

    public enum State { DISCONNECTED, INITIALIZING, READY, CLOSING }

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String CLIENT_NAME = "jclaw";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private final McpTransport transport;
    private final String clientVersion;
    private final Duration requestTimeout;

    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Object, CompletableFuture<JsonRpc.Response>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);

    private volatile List<McpToolDef> cachedTools = List.of();
    private volatile String lastError;
    private volatile Consumer<List<McpToolDef>> onToolsChanged = (tools) -> {};

    public McpClient(String name, McpTransport transport, String clientVersion) {
        this(name, transport, clientVersion, DEFAULT_REQUEST_TIMEOUT);
    }

    public McpClient(String name, McpTransport transport, String clientVersion, Duration requestTimeout) {
        this.name = name;
        this.transport = transport;
        this.clientVersion = clientVersion;
        this.requestTimeout = requestTimeout;
    }

    public String name() { return name; }

    public State state() { return state.get(); }

    public String lastError() { return lastError; }

    public List<McpToolDef> tools() { return cachedTools; }

    /** Set a callback fired when the server reports {@code tools/list_changed}. */
    public void onToolsChanged(Consumer<List<McpToolDef>> handler) {
        this.onToolsChanged = handler != null ? handler : (tools) -> {};
    }

    /**
     * Connect and initialize. Performs the {@code initialize} handshake,
     * sends {@code notifications/initialized}, then fetches the initial
     * tool list. On success leaves the client in {@link State#READY}.
     */
    public void connect() throws IOException, McpException {
        if (!state.compareAndSet(State.DISCONNECTED, State.INITIALIZING)) {
            throw new McpException("connect() called from state " + state.get());
        }
        try {
            transport.start(this::onInbound, this::onTransportError);
            doInitialize();
            sendNotification("notifications/initialized", null);
            cachedTools = fetchTools();
            state.set(State.READY);
        } catch (RuntimeException | IOException e) {
            lastError = e.getMessage();
            state.set(State.DISCONNECTED);
            try { transport.close(); } catch (RuntimeException ignored) { /* best effort */ }
            throw e;
        }
    }

    /**
     * Invoke a tool by name with arguments. Blocks up to {@code requestTimeout}
     * for the server's response. Throws {@link McpException} on JSON-RPC error
     * (server returned error) or timeout. Returns the parsed result envelope —
     * note that {@link CallToolResult#isError()} is the spec's separate
     * "tool ran but reported failure" channel.
     */
    public CallToolResult callTool(String toolName, JsonObject arguments) throws IOException, McpException {
        if (state.get() != State.READY) {
            throw new McpException("callTool requires READY state, was " + state.get());
        }
        var params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments != null ? arguments : new JsonObject());
        var resp = sendRequest("tools/call", params);
        if (resp.isError()) {
            throw new McpException(resp.error().code(), resp.error().message());
        }
        if (resp.result() == null || !resp.result().isJsonObject()) {
            throw new McpException("tools/call returned non-object result");
        }
        return CallToolResult.fromResultObject(resp.result().getAsJsonObject());
    }

    @Override
    public void close() {
        if (state.getAndSet(State.CLOSING) == State.CLOSING) return;
        for (var future : pending.values()) {
            future.completeExceptionally(new McpException("Client closing"));
        }
        pending.clear();
        try { transport.close(); } catch (RuntimeException ignored) { /* best effort */ }
        state.set(State.DISCONNECTED);
    }

    // ==================== protocol ====================

    private void doInitialize() throws IOException, McpException {
        var params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        var info = new JsonObject();
        info.addProperty("name", CLIENT_NAME);
        info.addProperty("version", clientVersion);
        params.add("clientInfo", info);
        var resp = sendRequest("initialize", params);
        if (resp.isError()) {
            throw new McpException(resp.error().code(),
                    "initialize failed: " + resp.error().message());
        }
    }

    private List<McpToolDef> fetchTools() throws IOException, McpException {
        var resp = sendRequest("tools/list", new JsonObject());
        if (resp.isError()) {
            throw new McpException(resp.error().code(),
                    "tools/list failed: " + resp.error().message());
        }
        var result = resp.result();
        if (result == null || !result.isJsonObject()) return List.of();
        var arr = result.getAsJsonObject().getAsJsonArray("tools");
        if (arr == null) return List.of();
        var out = new ArrayList<McpToolDef>(arr.size());
        for (var el : arr) {
            if (el.isJsonObject()) out.add(McpToolDef.fromJson(el.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    private JsonRpc.Response sendRequest(String method, Object params) throws IOException, McpException {
        var id = nextId.getAndIncrement();
        var req = new JsonRpc.Request(id, method, params);
        var future = new CompletableFuture<JsonRpc.Response>();
        pending.put(id, future);
        try {
            transport.send(req);
        } catch (IOException | RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("Request " + method + " timed out after " + requestTimeout, e);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for " + method, e);
        } catch (ExecutionException e) {
            pending.remove(id);
            var cause = e.getCause();
            if (cause instanceof McpException me) throw me;
            if (cause instanceof IOException ioe) throw ioe;
            throw new McpException(method + " failed", cause);
        }
    }

    private void sendNotification(String method, Object params) throws IOException {
        transport.send(new JsonRpc.Notification(method, params));
    }

    // ==================== inbound dispatch ====================

    void onInbound(JsonRpc.Message msg) {
        switch (msg) {
            case JsonRpc.Response r -> {
                var future = pending.remove(r.id());
                if (future != null) {
                    future.complete(r);
                } else {
                    Logger.warn("[mcp:%s] response for unknown id %s", name, r.id());
                }
            }
            case JsonRpc.Notification n -> handleNotification(n);
            case JsonRpc.Request r -> handleServerRequest(r);
        }
    }

    private void handleNotification(JsonRpc.Notification n) {
        if ("notifications/tools/list_changed".equals(n.method())) {
            // Refresh out-of-band; never block the read thread on a network round-trip.
            Thread.startVirtualThread(() -> {
                try {
                    var refreshed = fetchTools();
                    cachedTools = refreshed;
                    onToolsChanged.accept(refreshed);
                } catch (Exception e) {
                    Logger.warn("[mcp:%s] tools/list refresh failed: %s", name, e.getMessage());
                }
            });
        }
        // Other notifications (logging/message, progress, cancelled) are out of scope for JCLAW-31.
    }

    private void handleServerRequest(JsonRpc.Request r) {
        // We declared no client capabilities; reply method-not-found per JSON-RPC spec.
        try {
            transport.send(new JsonRpc.Response(r.id(), null,
                    new JsonRpc.Error(-32601, "Method not found: " + r.method())));
        } catch (IOException e) {
            Logger.warn("[mcp:%s] failed to reply to server request %s: %s",
                    name, r.method(), e.getMessage());
        }
    }

    private void onTransportError(Throwable t) {
        lastError = t.getMessage();
        for (var future : pending.values()) {
            future.completeExceptionally(t);
        }
        pending.clear();
        if (state.get() == State.READY || state.get() == State.INITIALIZING) {
            state.set(State.DISCONNECTED);
        }
    }

    // visible for test
    Map<Object, CompletableFuture<JsonRpc.Response>> pendingForTest() {
        return pending;
    }
}
