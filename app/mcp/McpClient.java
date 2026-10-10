package mcp;

import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import org.jspecify.annotations.Nullable;
import play.Logger;
import utils.AppClock;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** What became of a {@link #openToolsSubscription} stream. */
    public enum SubscriptionEvent {
        /** The server granted {@code toolsListChanged}: the stream is up. */
        ACKNOWLEDGED,
        /** The stream ended or never acknowledged in time; reopen it. */
        DROPPED,
        /** The server refused the subscription or did not grant it; do not reopen. */
        REFUSED
    }

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
    private static final String LISTEN = "subscriptions/listen";
    private static final String TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    private static final String SUBSCRIPTION_ACKNOWLEDGED = "notifications/subscriptions/acknowledged";
    private static final String SUBSCRIPTION_ID = META_PREFIX + "subscriptionId";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Retries of one request on a state-only {@code input_required}; a server needing more is looping. */
    public static final int MAX_INPUT_RETRIES = 3;
    /** {@code tools/list} pages read per fetch, so a server that never ends the list cannot hang a connect. */
    public static final int MAX_TOOL_PAGES = 50;
    /** A {@code ttlMs} of zero or none is stale at once, so this bounds how often a turn may refetch. */
    public static final Duration MIN_TOOLS_REFRESH_INTERVAL = Duration.ofSeconds(60);

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

    private final AtomicReference<ToolList> toolList =
            new AtomicReference<>(new ToolList(List.of(), Duration.ZERO, Instant.EPOCH, Instant.EPOCH));
    private final AtomicBoolean refreshing = new AtomicBoolean();
    /** A list_changed arrived during a refresh, whose result may predate the change. */
    private final AtomicBoolean rerun = new AtomicBoolean();
    private final AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();
    private volatile boolean toolsListChangedOffered;
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

    public List<McpToolDef> tools() { return Objects.requireNonNull(toolList.get()).tools(); }

    /** Whether the server's DiscoverResult advertised {@code capabilities.tools.listChanged}. */
    public boolean toolsListChangedOffered() { return toolsListChangedOffered; }

    /** True once the connection speaks 2026-07-28. */
    public boolean isModern() { return modern; }

    /** The protocol version the connection settled on, or null before {@link #connect()} succeeds. */
    public @Nullable String protocolVersion() { return protocolVersion; }

    /** Whether the server said it offers 2026-07-28, in a DiscoverResult or a -32022 version list. */
    public boolean offersModern() { return offersModern; }

    /** The probe ran out the handshake budget unanswered, and the connection went on over the handshake. */
    public boolean probeTimedOut() { return probeTimedOut; }

    /** Set a callback fired when a refresh changes the tool list. */
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
        var args = arguments != null ? arguments : new JsonObject();
        var params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", args);
        var mirrored = mirrorsParams() ? mirroredHeaders(toolName, args) : Map.<String, String>of();
        return CallToolResult.fromResultObject(untilComplete(TOOLS_CALL, params, p -> {
            JsonRpc.Response resp;
            try {
                resp = sendRequest(TOOLS_CALL, p, requestTimeout, mirrored);
            } catch (McpException e) {
                throw callFailure(e);
            }
            if (resp.isError()) {
                var err = resp.resolvedError();
                throw callFailure(McpException.fromRpcError(err.code(), err.message(), err.data()));
            }
            if (!(resp.result() instanceof JsonObject result)) {
                throw new McpException("tools/call returned non-object result");
            }
            return result;
        }));
    }

    /** Parameters are mirrored into headers only on 2026-07-28 over a transport that sends headers. */
    private boolean mirrorsParams() {
        return modern && transport.carriesHeaders();
    }

    private Map<String, String> mirroredHeaders(String toolName, JsonObject arguments) {
        for (var tool : tools()) {
            if (tool.name().equals(toolName)) return McpParamHeaders.headersFor(tool.inputSchema(), arguments);
        }
        return Map.of();
    }

    @Override
    public void close() {
        if (state.getAndSet(State.CLOSING) == State.CLOSING) return;
        subscription.set(null);
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
        toolsListChangedOffered = result.get("capabilities") instanceof JsonObject caps
                && caps.get("tools") instanceof JsonObject tools
                && tools.get("listChanged") instanceof JsonElement listChanged
                && listChanged.isJsonPrimitive() && listChanged.getAsJsonPrimitive().isBoolean()
                && listChanged.getAsBoolean();
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
        storeConnectList(fetchTools(handshakeTimeout));
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
        // The server answered on 2026-07-28 after all, however late: the probe stays worth sending.
        probeTimedOut = false;
        try {
            storeConnectList(fetchToolsStrict(handshakeTimeout));
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

    // ==================== tool-list freshness ====================

    /** The cached list, its {@code ttlMs} and when it was received; {@code lastFetchStartedAt} rate-limits refreshes. */
    private record ToolList(List<McpToolDef> tools, Duration ttl, Instant fetchedAt, Instant lastFetchStartedAt) {}

    /** One fetch's tools and its effective ttl, the minimum over its pages. */
    private record Fetched(List<McpToolDef> tools, Duration ttl) {}

    private void storeConnectList(Fetched fetched) {
        var now = AppClock.now();
        toolList.set(new ToolList(fetched.tools(), fetched.ttl(), now, now));
    }

    /**
     * On a modern READY connection whose list has outlived its {@code ttlMs} and the
     * {@link #MIN_TOOLS_REFRESH_INTERVAL}, start one refresh off this thread. Never blocks:
     * {@link #tools()} keeps serving the previous list until the refresh lands.
     */
    public void refreshToolsIfStale() {
        if (!modern || state.get() != State.READY) return;
        var now = AppClock.now();
        var list = Objects.requireNonNull(toolList.get());
        if (Duration.between(list.fetchedAt(), now).compareTo(list.ttl()) < 0) return;
        if (Duration.between(list.lastFetchStartedAt(), now).compareTo(MIN_TOOLS_REFRESH_INTERVAL) < 0) return;
        startRefresh(now, false);
    }

    /**
     * Single-flight: a forced refresh that finds one in flight queues exactly one more. The
     * instant is read by the caller, because an {@link AppClock} binding does not reach the new thread.
     */
    private void startRefresh(Instant startedAt, boolean force) {
        if (!refreshing.compareAndSet(false, true)) {
            if (force) rerun.set(true);
            return;
        }
        var startNanos = System.nanoTime();
        Thread.ofVirtual().name("mcp-tools-refresh-" + name).start(() -> {
            do {
                rerun.set(false);
                var at = startedAt.plusNanos(System.nanoTime() - startNanos);
                toolList.updateAndGet(l -> new ToolList(l.tools(), l.ttl(), l.fetchedAt(), at));
                refreshOnce(startedAt, startNanos);
                refreshing.set(false);
            } while (rerun.get() && refreshing.compareAndSet(false, true));
        });
    }

    private void refreshOnce(Instant base, long baseNanos) {
        Fetched fetched;
        try {
            fetched = fetchTools(requestTimeout);
        } catch (Exception e) {
            Logger.warn("[mcp:%s] tools/list refresh failed: %s", name, e.getMessage());
            return;
        }
        if (state.get() != State.READY) return;
        var landed = base.plusNanos(System.nanoTime() - baseNanos);
        var previous = Objects.requireNonNull(toolList.getAndUpdate(
                l -> new ToolList(fetched.tools(), fetched.ttl(), landed, l.lastFetchStartedAt())));
        if (!previous.tools().equals(fetched.tools())) onToolsChanged.accept(fetched.tools());
    }

    // ==================== tools/list_changed subscription ====================

    private record Subscription(long id, CompletableFuture<JsonObject> ack, AtomicBoolean ended) {}

    /**
     * Open one {@code subscriptions/listen} for {@code toolsListChanged} and return at once.
     * {@code onEvent} hears {@link SubscriptionEvent#ACKNOWLEDGED} when the server grants it, then
     * at most one of {@link SubscriptionEvent#DROPPED} or {@link SubscriptionEvent#REFUSED}; nothing
     * once the client is closing or has left READY.
     */
    public void openToolsSubscription(Consumer<SubscriptionEvent> onEvent) {
        if (!modern || state.get() != State.READY) return;
        var sub = new Subscription(nextId.getAndIncrement(), new CompletableFuture<>(), new AtomicBoolean());
        subscription.set(sub);
        var notifications = new JsonObject();
        notifications.addProperty("toolsListChanged", true);
        var params = new JsonObject();
        params.add("notifications", notifications);
        CompletableFuture<JsonRpc.Response> listen;
        try {
            listen = dispatch(sub.id(), LISTEN, params, true);
        } catch (IOException | RuntimeException e) {
            Logger.warn("[mcp:%s] %s could not be sent: %s", name, LISTEN, e.getMessage());
            endSubscription(sub, SubscriptionEvent.DROPPED, onEvent, false);
            return;
        }
        // A response, success or error, is the server ending the subscription.
        listen.whenComplete((resp, _) -> {
            if (resp != null && resp.isError()) {
                Logger.warn("[mcp:%s] %s refused: %s", name, LISTEN, resp.resolvedError().message());
                endSubscription(sub, SubscriptionEvent.REFUSED, onEvent, false);
            } else {
                endSubscription(sub, SubscriptionEvent.DROPPED, onEvent, false);
            }
        });
        Thread.ofVirtual().name("mcp-listen-" + name).start(() -> awaitAcknowledgment(sub, onEvent));
    }

    private void awaitAcknowledgment(Subscription sub, Consumer<SubscriptionEvent> onEvent) {
        JsonObject granted;
        try {
            granted = sub.ack().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            endSubscription(sub, SubscriptionEvent.DROPPED, onEvent, true);
            return;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            endSubscription(sub, SubscriptionEvent.DROPPED, onEvent, true);
            return;
        } catch (ExecutionException | CancellationException _) {
            return;  // the subscription ended before it was acknowledged, and reported that
        }
        if (!isGranted(granted.get("toolsListChanged"))) {
            Logger.warn("[mcp:%s] %s was acknowledged without toolsListChanged", name, LISTEN);
            endSubscription(sub, SubscriptionEvent.REFUSED, onEvent, true);
            return;
        }
        if (subscription.get() == sub && !sub.ended().get() && state.get() == State.READY) {
            onEvent.accept(SubscriptionEvent.ACKNOWLEDGED);
        }
    }

    private static boolean isGranted(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return false;
        return !(el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && !el.getAsBoolean());
    }

    /** Reports {@code event} once per subscription, and only while it is still the active one on a READY client. */
    private void endSubscription(Subscription sub, SubscriptionEvent event, Consumer<SubscriptionEvent> onEvent,
                                 boolean cancel) {
        if (!sub.ended().compareAndSet(false, true)) return;
        sub.ack().cancel(false);
        pending.remove(sub.id());
        if (cancel) {
            try { transport.cancel(sub.id(), LISTEN + " ended"); } catch (RuntimeException _) { /* best effort */ }
        }
        if (!subscription.compareAndSet(sub, null) || state.get() != State.READY) return;
        onEvent.accept(event);
    }

    private static @Nullable JsonElement meta(JsonRpc.Notification n) {
        return n.params() instanceof JsonObject params ? params.get(META) : null;
    }

    /** Numeric and string ids compare alike: the server may echo our long id either way. */
    private boolean subscriptionIdMatches(@Nullable JsonElement meta) {
        var sub = subscription.get();
        if (sub == null || !(meta instanceof JsonObject obj)) return false;
        if (!(obj.get(SUBSCRIPTION_ID) instanceof JsonElement id) || !id.isJsonPrimitive()) return false;
        var p = id.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsBigDecimal().compareTo(BigDecimal.valueOf(sub.id())) == 0;
        return p.isString() && p.getAsString().equals(Long.toString(sub.id()));
    }

    private static boolean hasSubscriptionId(@Nullable JsonElement meta) {
        return meta instanceof JsonObject obj && obj.has(SUBSCRIPTION_ID);
    }

    /**
     * Over the handshake every list_changed counts. On 2026-07-28 one must carry the active
     * subscription's id, except over HTTP, where an id-less one can only ride a stream JClaw opened.
     */
    private boolean acceptsListChanged(JsonRpc.Notification n) {
        if (!modern) return true;
        var meta = meta(n);
        if (hasSubscriptionId(meta)) return subscriptionIdMatches(meta);
        return transport.carriesHeaders();
    }

    // ==================== protocol ====================

    private Fetched fetchTools(Duration timeout) throws IOException, McpException {
        return fetchTools(timeout, false);
    }

    /** As {@link #fetchTools(Duration)}, but a page with no tools array is a failure rather than an empty list. */
    private Fetched fetchToolsStrict(Duration timeout) throws IOException, McpException {
        return fetchTools(timeout, true);
    }

    /** Every page of {@code tools/list}, following {@code nextCursor} up to {@link #MAX_TOOL_PAGES}. */
    private Fetched fetchTools(Duration timeout, boolean strict) throws IOException, McpException {
        var out = new ArrayList<McpToolDef>();
        @Nullable String cursor = null;
        long ttlMs = Long.MAX_VALUE;
        for (var page = 0; page < MAX_TOOL_PAGES; page++) {
            var params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            var result = untilComplete(TOOLS_LIST, params, p -> toolsListPage(p, timeout, strict));
            ttlMs = Math.min(ttlMs, ttlMs(result.get("ttlMs")));
            var tools = result.get("tools");
            if (tools == null || !tools.isJsonArray()) {
                if (strict) throw new McpException("tools/list returned no tools array");
            } else {
                out.addAll(toolDefs(tools.getAsJsonArray()));
            }
            var next = result.get("nextCursor");
            if (next == null || !next.isJsonPrimitive()) return new Fetched(publishable(out), Duration.ofMillis(ttlMs));
            cursor = next.getAsString();
        }
        Logger.warn("[mcp:%s] tools/list was still paging after %d pages; publishing the tools those listed",
                name, MAX_TOOL_PAGES);
        return new Fetched(publishable(out), Duration.ofMillis(ttlMs));
    }

    /** Absent, non-numeric and negative all mean stale at once. */
    private static long ttlMs(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return 0;
        var ms = el.getAsDouble();
        return ms > 0 ? (long) ms : 0;
    }

    private JsonObject toolsListPage(JsonObject params, Duration timeout, boolean strict)
            throws IOException, McpException {
        var resp = sendRequest(TOOLS_LIST, params, timeout);
        if (resp.isError()) {
            var err = resp.resolvedError();
            throw McpException.fromRpcError(err.code(), "tools/list failed: " + err.message(), err.data());
        }
        if (resp.result() instanceof JsonObject result) return result;
        if (strict) throw new McpException("tools/list returned no tools array");
        return new JsonObject();
    }

    /** Drops a tool whose {@code x-mcp-header} annotations could not be mirrored as the spec requires. */
    private List<McpToolDef> publishable(List<McpToolDef> tools) {
        if (!mirrorsParams()) return List.copyOf(tools);
        var out = new ArrayList<McpToolDef>(tools.size());
        for (var tool : tools) {
            var invalid = McpParamHeaders.invalidReason(tool.inputSchema());
            if (invalid == null) {
                out.add(tool);
            } else {
                Logger.warn("[mcp:%s] dropping tool '%s': %s", name, tool.name(), invalid);
            }
        }
        return List.copyOf(out);
    }

    private static List<McpToolDef> toolDefs(JsonArray arr) {
        var out = new ArrayList<McpToolDef>(arr.size());
        for (var el : arr) {
            if (el.isJsonObject()) out.add(McpToolDef.fromJson(el.getAsJsonObject()));
        }
        return out;
    }

    /** One request and its answer; {@link #untilComplete} repeats it with new params. */
    @FunctionalInterface
    private interface Round {
        JsonObject send(JsonObject params) throws IOException, McpException;
    }

    /**
     * Send {@code params} until the result is complete, retrying a state-only {@code input_required}
     * (2026-07-28 Multi Round-Trip Requests) with its {@code requestState} echoed unchanged. Each
     * retry is a new request, so it gets a new id.
     */
    private static JsonObject untilComplete(String method, JsonObject params, Round round)
            throws IOException, McpException {
        var p = params;
        for (var retries = 0; ; retries++) {
            var result = round.send(p);
            var state = inputRequiredState(method, result);
            if (state == null) return result;
            if (retries == MAX_INPUT_RETRIES) {
                throw new McpException(method + " still asked for another round after " + MAX_INPUT_RETRIES
                        + " retries, so JClaw stopped: the server never completed the request");
            }
            p = params.deepCopy();
            p.add("requestState", state);
        }
    }

    /**
     * Null for a complete result, which is also what an absent {@code resultType} means; the
     * {@code requestState} to echo for a state-only {@code input_required}; otherwise a failure.
     */
    private static @Nullable JsonElement inputRequiredState(String method, JsonObject result) {
        var type = result.get("resultType");
        if (type == null || type.isJsonNull()) return null;
        var kind = type.isJsonPrimitive() ? type.getAsString() : "";
        if ("complete".equals(kind)) return null;
        if (!"input_required".equals(kind)) {
            var shown = kind.matches("[a-z_]{1,32}") ? "'" + kind + "'" : "of an unknown kind";
            throw new McpException(method + " returned a result " + shown
                    + ", which JClaw cannot handle: it knows complete and input_required");
        }
        if (isPresent(result.get("inputRequests"))) {
            // JClaw declares no elicitation, sampling or roots, so a conforming server never asks.
            throw new McpException("the server asked for input mid-request (" + inputKinds(result.get("inputRequests"))
                    + "), which JClaw cannot give: it offers no elicitation, sampling or roots. Call the tool"
                    + " again with every argument it needs filled in, or use another tool.");
        }
        var state = result.get("requestState");
        if (state == null || state.isJsonNull()) {
            throw new McpException(method + " returned input_required with neither inputRequests nor requestState");
        }
        return state;
    }

    private static boolean isPresent(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return false;
        if (el instanceof JsonObject obj) return !obj.isEmpty();
        return !el.isJsonArray() || !el.getAsJsonArray().isEmpty();
    }

    /** The methods the server's input requests name, when they look like methods; never other text it sent. */
    private static String inputKinds(@Nullable JsonElement requests) {
        var kinds = new TreeSet<String>();
        var items = new ArrayList<JsonElement>();
        if (requests instanceof JsonObject obj) obj.entrySet().forEach(e -> items.add(e.getValue()));
        if (requests instanceof JsonArray arr) arr.forEach(items::add);
        for (var item : items) {
            if (item instanceof JsonObject req && req.get("method") instanceof JsonElement m && m.isJsonPrimitive()
                    && m.getAsString().matches("[a-z]{1,32}/[A-Za-z]{1,32}")) {
                kinds.add(m.getAsString());
            }
        }
        return kinds.isEmpty() ? "of an unknown kind" : String.join(", ", kinds);
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
        return sendRequest(method, params, timeout, Map.of());
    }

    private JsonRpc.Response sendRequest(String method, JsonObject params, Duration timeout,
                                         Map<String, String> extraHeaders) throws IOException, McpException {
        var id = nextId.getAndIncrement();
        return await(id, method, dispatch(id, method, params, modern, extraHeaders), timeout);
    }

    /** Register {@code id} and put the request on the wire; a modern request carries {@code _meta} and its headers. */
    private CompletableFuture<JsonRpc.Response> dispatch(long id, String method, JsonObject params, boolean asModern)
            throws IOException {
        return dispatch(id, method, params, asModern, Map.of());
    }

    private CompletableFuture<JsonRpc.Response> dispatch(long id, String method, JsonObject params, boolean asModern,
                                                         Map<String, String> extraHeaders) throws IOException {
        var future = new CompletableFuture<JsonRpc.Response>();
        pending.put(id, future);
        var req = new JsonRpc.Request(id, method, asModern ? withMeta(params) : params);
        var headers = headers(method, params, asModern);
        headers.putAll(extraHeaders);
        try {
            transport.send(req, headers);
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
            var reason = "Request " + method + " timed out after " + timeout;
            // The spec forbids cancelling initialize.
            if (!INITIALIZE.equals(method)) {
                try { transport.cancel(id, reason); } catch (RuntimeException _) { /* best effort */ }
            }
            throw new McpException(reason, e);
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
        switch (n.method()) {
            case SUBSCRIPTION_ACKNOWLEDGED -> {
                var sub = subscription.get();
                if (sub != null && subscriptionIdMatches(meta(n))) {
                    var params = n.params() instanceof JsonObject p ? p : new JsonObject();
                    sub.ack().complete(params.get("notifications") instanceof JsonObject granted
                            ? granted : new JsonObject());
                }
            }
            case TOOLS_LIST_CHANGED -> {
                if (acceptsListChanged(n)) startRefresh(AppClock.now(), true);
            }
            default -> { /* logging/message, progress, canceled: out of scope */ }
        }
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
        // Cleared first, so the listen future failing below reports nothing.
        subscription.set(null);
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
