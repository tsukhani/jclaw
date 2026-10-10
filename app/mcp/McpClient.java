package mcp;

import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import org.jspecify.annotations.Nullable;
import play.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p><b>Protocol eras (JCLAW-1443).</b> A client speaks one of two eras, settled in
 * {@link #connect()} by its {@link Opening}: the {@code initialize} handshake, pinned to
 * {@link #PROTOCOL_VERSION}, or {@link #MODERN_PROTOCOL_VERSION}, where every request carries
 * its version, capabilities and client info in {@code _meta} and there is no handshake.
 * Reaching a server again over the handshake on a fresh connection is the caller's to do:
 * this class signals it with {@link McpException.NeedsHandshake}.
 *
 * <p><b>Server-initiated requests.</b> The MCP spec lets servers ask the
 * client to do things (sampling, elicitation, roots/list). JClaw declares
 * none of these capabilities during {@code initialize}, so a well-behaved
 * server won't issue them. If one slips through anyway we reply with
 * JSON-RPC error -32601 (Method not found) — the spec-conformant fallback.
 */
public class McpClient implements AutoCloseable {

    public enum State { DISCONNECTED, INITIALIZING, READY, CLOSING }

    /** How {@link #connect()} opens the conversation. */
    public enum Opening {
        /** {@code server/discover} at 2026-07-28 first; its answer decides the era. */
        PROBE,
        /** {@code initialize} first, the probe skipped because it last ran out its budget; a -32022 refusal means modern. */
        INITIALIZE_OR_MODERN,
        /** {@code initialize} only, as before 2026-07-28; any refusal fails the connect. */
        INITIALIZE
    }

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    /** Every published MCP revision; a server-supplied version list is filtered to these. */
    public static final List<String> KNOWN_PROTOCOL_VERSIONS =
            List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25", "2026-07-28");
    public static final int HEADER_MISMATCH = -32020;
    public static final int MISSING_CLIENT_CAPABILITY = -32021;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;
    private static final String CLIENT_NAME = "jclaw";
    private static final String META = "_meta";
    private static final String META_PREFIX = "io.modelcontextprotocol/";
    private static final String DISCOVER = "server/discover";
    private static final String TOOLS_CALL = "tools/call";
    private static final String TOOLS_LIST = "tools/list";
    private static final String INITIALIZE = "initialize";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private final McpTransport transport;
    private final String clientVersion;
    /** Budget for the connect handshake: the probe, initialize and the first tools/list. */
    private final Duration handshakeTimeout;
    /** Budget for every request after the handshake, tool calls included. */
    private final Duration requestTimeout;
    private final Opening opening;

    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Object, CompletableFuture<JsonRpc.Response>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);

    // Reassigned with List.copyOf(...) / List.of(); the held list is immutable
    // so volatile-on-reference is sufficient publication.
    @SuppressWarnings("java:S3077")
    private volatile List<McpToolDef> cachedTools = List.of();
    private volatile @Nullable String lastError;
    // Reassigned with a stateless Consumer; volatile-on-reference is sufficient.
    @SuppressWarnings("java:S3077")
    private volatile Consumer<List<McpToolDef>> onToolsChanged = _ -> {};
    private volatile boolean modern;
    /** The version this connection speaks, once settled: sent as {@code MCP-Protocol-Version}. */
    private volatile @Nullable String protocolVersion;
    private volatile boolean offersModern;
    private volatile boolean probeTimedOut;

    @MustBeClosed
    public McpClient(String name, McpTransport transport, String clientVersion) {
        this(name, transport, clientVersion, DEFAULT_REQUEST_TIMEOUT);
    }

    /** One budget for the handshake and every request alike. */
    @MustBeClosed
    public McpClient(String name, McpTransport transport, String clientVersion, Duration requestTimeout) {
        this(name, transport, clientVersion, requestTimeout, requestTimeout);
    }

    /** Opens with {@link Opening#INITIALIZE}. */
    @MustBeClosed
    public McpClient(String name, McpTransport transport, String clientVersion,
                     Duration handshakeTimeout, Duration requestTimeout) {
        this(name, transport, clientVersion, handshakeTimeout, requestTimeout, Opening.INITIALIZE);
    }

    /**
     * Takes ownership of {@code transport}: {@link #close()} closes it.
     *
     * <p>JCLAW-1191: the two budgets are separate because a cold server legitimately takes
     * minutes to answer its first request, but a tool call that takes minutes is a hung server —
     * and a client that kept the handshake budget for every request made the per-server breaker
     * wait three of them before it could fail fast.
     */
    @MustBeClosed
    public McpClient(String name, McpTransport transport, String clientVersion,
                     Duration handshakeTimeout, Duration requestTimeout, Opening opening) {
        this.name = name;
        this.transport = transport;
        this.clientVersion = clientVersion;
        this.handshakeTimeout = handshakeTimeout;
        this.requestTimeout = requestTimeout;
        this.opening = opening;
    }

    public String name() { return name; }

    public State state() { return Objects.requireNonNull(state.get()); }

    public @Nullable String lastError() { return lastError; }

    public List<McpToolDef> tools() { return cachedTools; }

    /** True once the connection speaks 2026-07-28. */
    public boolean isModern() { return modern; }

    /** The protocol version the connection settled on, or null before {@link #connect()} succeeds. */
    public @Nullable String protocolVersion() { return protocolVersion; }

    /** Whether the server said it offers 2026-07-28, in a DiscoverResult or a -32022 version list. */
    public boolean offersModern() { return offersModern; }

    /** The probe ran out the handshake budget unanswered, and the connection went on with {@code initialize}. */
    public boolean probeTimedOut() { return probeTimedOut; }

    /** Set a callback fired when the server reports {@code tools/list_changed}. */
    public void onToolsChanged(Consumer<List<McpToolDef>> handler) {
        this.onToolsChanged = handler != null ? handler : _ -> {};
    }

    /**
     * Connect, settle the protocol era as {@link Opening} directs, and fetch the initial tool
     * list. On success leaves the client in {@link State#READY}.
     *
     * @throws McpException.NeedsHandshake when the server must be reached over the handshake
     *                                     on a fresh connection; this one is closed
     */
    public void connect() throws IOException, McpException {
        if (!state.compareAndSet(State.DISCONNECTED, State.INITIALIZING)) {
            throw new McpException("connect() called from state " + state.get());
        }
        try {
            transport.start(this::onInbound, this::onRequestFailed, this::onTransportError);
            switch (opening) {
                case PROBE -> openWithProbe();
                case INITIALIZE_OR_MODERN -> openWithInitialize(true);
                case INITIALIZE -> openWithInitialize(false);
            }
            state.set(State.READY);
        } catch (RuntimeException | IOException e) {
            lastError = e.getMessage();
            state.set(State.DISCONNECTED);
            try { transport.close(); } catch (RuntimeException _) { /* best effort */ }
            throw e;
        }
    }

    /**
     * Invoke a tool by name with arguments. Blocks up to {@code requestTimeout}
     * for the server's response. Throws {@link McpException} on JSON-RPC error
     * (server returned error) or timeout. Returns the parsed result envelope —
     * note that {@link CallToolResult#isError()} is the spec's separate
     * "tool ran but reported failure" channel.
     *
     * @throws McpException.NeedsHandshake when a modern server refused the call with -32020, or
     *                                     with -32022 and no 2026-07-28 left to offer
     */
    public CallToolResult callTool(String toolName, JsonObject arguments) throws IOException, McpException {
        if (state.get() != State.READY) {
            throw new McpException("callTool requires READY state, was " + state.get());
        }
        var params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments != null ? arguments : new JsonObject());
        JsonRpc.Response resp;
        try {
            resp = sendRequest(TOOLS_CALL, params, requestTimeout);
        } catch (McpException e) {
            throw callFailure(e);
        }
        if (resp.isError()) {
            var err = resp.resolvedError();
            throw callFailure(McpException.fromRpcError(err.code(), err.message(), err.data()));
        }
        if (resp.result() == null || !resp.result().isJsonObject()) {
            throw new McpException("tools/call returned non-object result");
        }
        var result = resp.result().getAsJsonObject();
        requireComplete(TOOLS_CALL, result);
        return CallToolResult.fromResultObject(result);
    }

    @Override
    public void close() {
        if (state.getAndSet(State.CLOSING) == State.CLOSING) return;
        for (var future : pending.values()) {
            future.completeExceptionally(new McpException("Client closing"));
        }
        pending.clear();
        try { transport.close(); } catch (RuntimeException _) { /* best effort */ }
        state.set(State.DISCONNECTED);
    }

    // ==================== opening ====================

    private void openWithProbe() throws IOException, McpException {
        var id = nextId.getAndIncrement();
        var discover = dispatch(id, DISCOVER, new JsonObject(), true);
        JsonRpc.Response resp;
        try {
            resp = discover.get(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            // A server answers the probe as soon as it is up; silence means it ignores the method.
            probeTimedOut = true;
            openAfterSilentProbe(id, discover);
            return;
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for " + DISCOVER, e);
        } catch (ExecutionException e) {
            pending.remove(id);
            if (e.getCause() instanceof McpException me) {
                onProbeRefused(me);
                return;
            }
            if (e.getCause() instanceof IOException ioe) throw ioe;
            throw new McpException(DISCOVER + " failed", e.getCause());
        }
        if (resp.isError()) {
            var err = resp.resolvedError();
            onProbeRefused(McpException.fromRpcError(err.code(), err.message(), err.data()));
        } else if (discoverResult(resp) instanceof JsonObject result) {
            onDiscovered(result);
        } else {
            openWithInitialize(false);
        }
    }

    /** Never keyed to one code or to message text: every handshake-era server refuses the probe its own way. */
    private void onProbeRefused(McpException refusal) throws IOException, McpException {
        if (Objects.equals(refusal.code(), UNSUPPORTED_PROTOCOL_VERSION)) {
            offersModern = refusal.supportedVersions().contains(MODERN_PROTOCOL_VERSION);
            if (offersModern) {
                becomeModern();
                return;
            }
            throw new McpException.NeedsHandshake(
                    "the server refused 2026-07-28 and offers " + describeVersions(refusal.supportedVersions()),
                    false, UNSUPPORTED_PROTOCOL_VERSION, refusal.supportedVersions());
        }
        openWithInitialize(false);
    }

    private void onDiscovered(JsonObject result) throws IOException, McpException {
        var versions = knownVersions(result.get("supportedVersions"));
        offersModern = versions.contains(MODERN_PROTOCOL_VERSION);
        if (!offersModern) {
            throw new McpException.NeedsHandshake(
                    "the server answered discovery offering " + describeVersions(versions), false, null, versions);
        }
        becomeModern();
    }

    /**
     * The probe went unanswered, so {@code initialize} follows on the same connection. A
     * DiscoverResult fixes a stdio process's era, so a late one — or initialize refused with
     * -32022 because of it — means modern rather than a failed connect.
     */
    private void openAfterSilentProbe(long discoverId, CompletableFuture<JsonRpc.Response> discover)
            throws IOException, McpException {
        var initId = nextId.getAndIncrement();
        var init = dispatch(initId, INITIALIZE, initializeParams(), false);
        try {
            awaitEither(discover, init);
            if (lateDiscoverResult(discover) instanceof JsonObject result) {
                pending.remove(initId);
                probeTimedOut = false;
                onDiscovered(result);
                return;
            }
            finishInitialize(initId, init, true);
        } finally {
            pending.remove(discoverId);
        }
    }

    private void awaitEither(CompletableFuture<JsonRpc.Response> a, CompletableFuture<JsonRpc.Response> b)
            throws McpException {
        try {
            CompletableFuture.anyOf(a, b).get(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException _) {
            // Read below from each future; the initialize await reports its own failure.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for " + INITIALIZE, e);
        }
    }

    private static @Nullable JsonObject lateDiscoverResult(CompletableFuture<JsonRpc.Response> discover) {
        if (!discover.isDone() || discover.isCompletedExceptionally()) return null;
        var resp = discover.getNow(null);
        return resp == null || resp.isError() ? null : discoverResult(resp);
    }

    private void openWithInitialize(boolean modernOnRefusal) throws IOException, McpException {
        var id = nextId.getAndIncrement();
        finishInitialize(id, dispatch(id, INITIALIZE, initializeParams(), false), modernOnRefusal);
    }

    /** With {@code modernOnRefusal}, a -32022 refusal, over HTTP or stdio alike, means modern. */
    private void finishInitialize(long id, CompletableFuture<JsonRpc.Response> init, boolean modernOnRefusal)
            throws IOException, McpException {
        JsonRpc.Response resp;
        try {
            resp = await(id, INITIALIZE, init, handshakeTimeout);
            if (resp.isError()) {
                var err = resp.resolvedError();
                throw McpException.fromRpcError(err.code(), "initialize failed: " + err.message(), err.data());
            }
        } catch (McpException e) {
            if (modernOnRefusal && Objects.equals(e.code(), UNSUPPORTED_PROTOCOL_VERSION)) {
                becomeModern();
                return;
            }
            throw e;
        }
        var result = resp.result();
        var version = result != null && result.isJsonObject() ? result.getAsJsonObject().get("protocolVersion") : null;
        protocolVersion = version != null && version.isJsonPrimitive()
                && KNOWN_PROTOCOL_VERSIONS.contains(version.getAsString())
                && !MODERN_PROTOCOL_VERSION.equals(version.getAsString()) ? version.getAsString() : PROTOCOL_VERSION;
        sendNotification("notifications/initialized", null);
        cachedTools = fetchTools(handshakeTimeout);
    }

    /**
     * Switch to 2026-07-28 and fetch the first tool list over it. On a connect, a failure there is
     * the server failing the new protocol after offering it, which the caller answers with the
     * handshake on a fresh connection.
     */
    private void becomeModern() throws IOException, McpException {
        modern = true;
        protocolVersion = MODERN_PROTOCOL_VERSION;
        offersModern = true;
        try {
            cachedTools = fetchToolsStrict(handshakeTimeout);
        } catch (McpException e) {
            throw new McpException.NeedsHandshake(
                    "the first tools/list over 2026-07-28 failed: " + e.getMessage(), true, e.code(), List.of());
        }
    }

    private JsonObject initializeParams() {
        var params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        params.add("clientInfo", clientInfo());
        return params;
    }

    private JsonObject clientInfo() {
        var info = new JsonObject();
        info.addProperty("name", CLIENT_NAME);
        info.addProperty("version", clientVersion);
        return info;
    }

    private static @Nullable JsonObject discoverResult(JsonRpc.Response resp) {
        var result = resp.result();
        if (result == null || !result.isJsonObject()) return null;
        var obj = result.getAsJsonObject();
        var versions = obj.get("supportedVersions");
        return versions != null && versions.isJsonArray() ? obj : null;
    }

    private static List<String> knownVersions(@Nullable JsonElement versions) {
        if (versions == null || !versions.isJsonArray()) return List.of();
        var out = new ArrayList<String>();
        for (var el : versions.getAsJsonArray()) {
            if (el.isJsonPrimitive() && KNOWN_PROTOCOL_VERSIONS.contains(el.getAsString())) out.add(el.getAsString());
        }
        return out;
    }

    static String describeVersions(List<String> versions) {
        return versions.isEmpty() ? "no version JClaw knows" : String.join(", ", versions);
    }

    // ==================== protocol ====================

    private List<McpToolDef> fetchTools(Duration timeout) throws IOException, McpException {
        var resp = sendRequest(TOOLS_LIST, new JsonObject(), timeout);
        if (resp.isError()) {
            var err = resp.resolvedError();
            throw McpException.fromRpcError(err.code(), "tools/list failed: " + err.message(), err.data());
        }
        var result = resp.result();
        if (result == null || !result.isJsonObject()) return List.of();
        requireComplete(TOOLS_LIST, result.getAsJsonObject());
        return toolDefs(result.getAsJsonObject().getAsJsonArray("tools"));
    }

    /** As {@link #fetchTools}, but an answer with no tools array is a failure rather than an empty list. */
    private List<McpToolDef> fetchToolsStrict(Duration timeout) throws IOException, McpException {
        var resp = sendRequest(TOOLS_LIST, new JsonObject(), timeout);
        if (resp.isError()) {
            var err = resp.resolvedError();
            throw McpException.fromRpcError(err.code(), "tools/list failed: " + err.message(), err.data());
        }
        var result = resp.result() instanceof JsonObject obj ? obj : null;
        var tools = result != null ? result.get("tools") : null;
        if (result == null || tools == null || !tools.isJsonArray()) {
            throw new McpException("tools/list returned no tools array");
        }
        requireComplete(TOOLS_LIST, result);
        return toolDefs(tools.getAsJsonArray());
    }

    private static List<McpToolDef> toolDefs(@Nullable JsonArray arr) {
        if (arr == null) return List.of();
        var out = new ArrayList<McpToolDef>(arr.size());
        for (var el : arr) {
            if (el.isJsonObject()) out.add(McpToolDef.fromJson(el.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    /**
     * Until JCLAW-1444 handles result types, anything but a complete result fails: read as a plain
     * result, an {@code input_required} reply is an empty success no later check can tell apart.
     */
    private static void requireComplete(String method, JsonObject result) {
        var type = result.get("resultType");
        if (type == null || type.isJsonNull()) return;
        if (type.isJsonPrimitive() && "complete".equals(type.getAsString())) return;
        var shown = type.isJsonPrimitive() && type.getAsString().matches("[a-z_]{1,32}")
                ? "'" + type.getAsString() + "'" : "of an unknown kind";
        throw new McpException(method + " returned a result " + shown
                + ", which JClaw cannot handle yet: it accepts only complete results");
    }

    /**
     * What a refused call means. -32021 asks for a capability JClaw lacks under either era, so it
     * fails alone. On 2026-07-28, -32020, or -32022 with no 2026-07-28 left, sends the server back
     * to the handshake; and over HTTP a 4xx with no standard or modern code is the server no longer
     * answering 2026-07-28 at all, so the connection drops and the next connect detects again.
     */
    private McpException callFailure(McpException e) {
        var code = e.code();
        if (Objects.equals(code, MISSING_CLIENT_CAPABILITY)) {
            var caps = e.requiredCapabilities().isEmpty() ? "a client capability"
                    : "client capability " + String.join(", ", e.requiredCapabilities());
            return new McpException(MISSING_CLIENT_CAPABILITY,
                    "the server requires " + caps + ", which JClaw does not offer");
        }
        if (!modern) return e;
        if (Objects.equals(code, HEADER_MISMATCH)
                || (Objects.equals(code, UNSUPPORTED_PROTOCOL_VERSION)
                        && !e.supportedVersions().contains(MODERN_PROTOCOL_VERSION))) {
            return new McpException.NeedsHandshake(
                    (Objects.equals(code, HEADER_MISMATCH) ? "a request was refused for a header mismatch (-32020)"
                            : "a request was refused with -32022, offering "
                                    + describeVersions(e.supportedVersions())),
                    true, code, e.supportedVersions());
        }
        var status = e.httpStatus();
        if (status != null && status >= 400 && status < 500 && !isStandardOrModernCode(code)) {
            var lost = "MCP HTTP " + status + ": the server stopped answering 2026-07-28;"
                    + " reconnecting to detect its protocol again";
            onTransportError(new IOException(lost));
            return new McpException(lost, e);
        }
        return e;
    }

    private static boolean isStandardOrModernCode(@Nullable Integer code) {
        if (code == null) return false;
        return code == -32700 || (code <= -32600 && code >= -32603)
                || code == HEADER_MISMATCH || code == MISSING_CLIENT_CAPABILITY
                || code == UNSUPPORTED_PROTOCOL_VERSION;
    }

    private JsonRpc.Response sendRequest(String method, JsonObject params, Duration timeout)
            throws IOException, McpException {
        var id = nextId.getAndIncrement();
        return await(id, method, dispatch(id, method, params, modern), timeout);
    }

    /** Register {@code id} and put the request on the wire; a modern request carries {@code _meta} and its headers. */
    private CompletableFuture<JsonRpc.Response> dispatch(long id, String method, JsonObject params, boolean asModern)
            throws IOException {
        var future = new CompletableFuture<JsonRpc.Response>();
        pending.put(id, future);
        var req = new JsonRpc.Request(id, method, asModern ? withMeta(params) : params);
        try {
            transport.send(req, headers(method, params, asModern));
        } catch (IOException | RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        return future;
    }

    private JsonRpc.Response await(Object id, String method, CompletableFuture<JsonRpc.Response> future,
                                   Duration timeout) throws IOException, McpException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("Request " + method + " timed out after " + timeout, e);
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

    private JsonObject withMeta(JsonObject params) {
        var out = params.deepCopy();
        var meta = out.get(META) instanceof JsonObject existing ? existing : new JsonObject();
        meta.addProperty(META_PREFIX + "protocolVersion", MODERN_PROTOCOL_VERSION);
        meta.add(META_PREFIX + "clientCapabilities", new JsonObject());
        meta.add(META_PREFIX + "clientInfo", clientInfo());
        out.add(META, meta);
        return out;
    }

    /** Streamable HTTP's per-request headers; stdio drops them. */
    private Map<String, String> headers(String method, @Nullable JsonObject params, boolean asModern) {
        var out = new HashMap<String, String>();
        var version = asModern ? MODERN_PROTOCOL_VERSION : protocolVersion;
        if (version != null) out.put("MCP-Protocol-Version", version);
        if (asModern) {
            out.put("Mcp-Method", headerValue(method));
            var toolName = params != null ? params.get("name") : null;
            if (TOOLS_CALL.equals(method) && toolName != null && toolName.isJsonPrimitive()) {
                out.put("Mcp-Name", headerValue(toolName.getAsString()));
            }
        }
        return out;
    }

    /** A value that is not header-safe ASCII travels in the spec's base64 sentinel form. */
    static String headerValue(String value) {
        if (isHeaderSafe(value)) return value;
        return "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static boolean isHeaderSafe(String value) {
        if (value.isEmpty() || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ') return false;
        if (value.startsWith("=?")) return false;
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private void sendNotification(String method, @Nullable Object params) throws IOException {
        transport.send(new JsonRpc.Notification(method, params), headers(method, null, false));
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
                    var refreshed = fetchTools(requestTimeout);
                    cachedTools = refreshed;
                    onToolsChanged.accept(refreshed);
                } catch (Exception e) {
                    Logger.warn("[mcp:%s] tools/list refresh failed: %s", name, e.getMessage());
                }
            });
        }
        // Other notifications (logging/message, progress, canceled) are out of scope for JCLAW-31.
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

    /** One request failed on its own; the connection and every other in-flight call stand. */
    private void onRequestFailed(Object id, McpException e) {
        var future = pending.remove(id);
        if (future != null) future.completeExceptionally(e);
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
        // Symmetric with close(): tear down the transport so the underlying
        // host process exits and any docker `--rm` container is released.
        // Without this the host-side `docker run` subprocess stays alive
        // holding stdio open even after the MCP server inside the container
        // has died — the watchdog observes state≠READY and reconnects, but
        // the orphaned docker container persists. Idempotent in transport
        // impls (McpStdioTransport sets `closed=true` and skips repeat work).
        try { transport.close(); } catch (RuntimeException _) { /* best effort */ }
    }
}
