package mcp;

import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import org.jspecify.annotations.Nullable;
import play.Logger;
import services.EventLogger;
import utils.AppClock;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

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
    private static final int METHOD_NOT_FOUND = -32601;
    /** What {@code resources/read} answers for a URI that names nothing, and the legacy code for it. */
    private static final int INVALID_PARAMS = -32602;
    private static final int LEGACY_RESOURCE_NOT_FOUND = -32002;
    private static final String CLIENT_NAME = "jclaw";
    private static final String CATEGORY_RESOURCES = "MCP_RESOURCES";
    private static final String META = "_meta";
    private static final String META_PREFIX = "io.modelcontextprotocol/";
    private static final String UI_EXTENSION = META_PREFIX + "ui";
    private static final String DISCOVER = "server/discover";
    private static final String TOOLS_CALL = "tools/call";
    private static final String ELICITATION_CREATE = "elicitation/create";
    private static final String TOOLS_LIST = "tools/list";
    private static final String RESOURCES_LIST = "resources/list";
    private static final String RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    private static final String RESOURCES_READ = "resources/read";
    private static final String INITIALIZE = "initialize";
    private static final String LISTEN = "subscriptions/listen";
    private static final String TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    private static final String SUBSCRIPTION_ACKNOWLEDGED = "notifications/subscriptions/acknowledged";
    private static final String SUBSCRIPTION_ID = META_PREFIX + "subscriptionId";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Retries of one request on {@code input_required}; a server needing more is looping. */
    public static final int MAX_INPUT_RETRIES = 3;
    /** Pages read per {@code tools/list} fetch and per resource listing, so a server that never ends a list cannot hang the caller. */
    public static final int MAX_TOOL_PAGES = 50;
    /** A {@code ttlMs} of zero or none is stale at once, so this bounds how often a turn may refetch. */
    public static final Duration MIN_TOOLS_REFRESH_INTERVAL = Duration.ofSeconds(60);
    /** Resources, and templates, one listing shows; each list is paged until one more than this arrives. */
    public static final int MAX_LISTED_RESOURCES = 50;
    /** A listing records nothing on the breaker, so a hung server must not cost each one the request budget. */
    public static final Duration LISTING_TIMEOUT = Duration.ofSeconds(5);

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
    private volatile boolean resourcesOffered;
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

    /**
     * Whether the server declared {@code capabilities.resources}, in its InitializeResult or its
     * DiscoverResult. A connection that reached 2026-07-28 through a -32022 refusal saw neither, so false.
     */
    public boolean offersResources() { return resourcesOffered; }

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
        requireReady("callTool");
        var args = arguments != null ? arguments : new JsonObject();
        var params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", args);
        var mirrored = mirrorsParams() ? mirroredHeaders(toolName, args) : Map.<String, String>of();
        Completed done;
        try {
            done = untilComplete(TOOLS_CALL, params, true,
                    (p, elicit) -> roundTrip(TOOLS_CALL, p, mirrored, requestTimeout, this::callFailure, elicit));
        } catch (McpException.InputRequired questions) {
            // The tool ran and asked; returned as its failure, so the agent (or a view) reads the questions as its own.
            var part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", Objects.requireNonNullElse(questions.getMessage(), ""));
            var content = new JsonArray();
            content.add(part);
            var raw = new JsonObject();
            raw.add("content", content);
            raw.addProperty("isError", true);
            return CallToolResult.fromResultObject(raw);
        }
        var result = CallToolResult.fromResultObject(done.result());
        for (var note : done.notes()) result = result.withNote(note);
        return result;
    }

    /**
     * Up to {@link #MAX_LISTED_RESOURCES} of the server's resources and as many of its templates, each
     * list paged until one more arrives. Fetched live on each call: nothing is cached, so a
     * {@code notifications/resources/list_changed} needs no handling.
     */
    public McpResources.Listing listResources() throws IOException, McpException {
        requireReady("listResources");
        var resources = new ArrayList<McpResources.Resource>();
        var ended = pages(RESOURCES_LIST, listPage(RESOURCES_LIST), result -> {
            for (var obj : objects(result.get("resources"))) {
                var resource = McpResources.Resource.fromJson(obj);
                if (resource != null) resources.add(resource);
            }
            return resources.size() > MAX_LISTED_RESOURCES;
        });
        var templates = new ArrayList<McpResources.Template>();
        var templatesEnded = true;
        try {
            templatesEnded = pages(RESOURCES_TEMPLATES_LIST, listPage(RESOURCES_TEMPLATES_LIST), result -> {
                for (var obj : objects(result.get("resourceTemplates"))) {
                    var template = McpResources.Template.fromJson(obj);
                    if (template != null) templates.add(template);
                }
                return templates.size() > MAX_LISTED_RESOURCES;
            });
        } catch (McpException e) {
            // A server that serves resources without implementing templates has none.
            if (!Objects.equals(e.code(), METHOD_NOT_FOUND)) throw e;
        }
        var more = !ended || !templatesEnded
                || resources.size() > MAX_LISTED_RESOURCES || templates.size() > MAX_LISTED_RESOURCES;
        return new McpResources.Listing(firstListed(resources), firstListed(templates), more);
    }

    /**
     * The contents of the resource at {@code uri}, read through the server.
     *
     * @throws McpException.ResourceNotFound when the server answers that {@code uri} names nothing
     */
    public List<McpResources.Content> readResource(String uri) throws IOException, McpException {
        requireReady("readResource");
        var params = new JsonObject();
        params.addProperty("uri", uri);
        Round read = (p, elicit) -> roundTrip(RESOURCES_READ, p, Map.of(), requestTimeout, e -> readFailure(uri, e), elicit);
        var result = untilComplete(RESOURCES_READ, params, false, read).result();
        return McpResources.contents(result.get("contents"), uri);
    }

    private Round listPage(String method) {
        return (p, elicit) -> roundTrip(method, p, Map.of(), LISTING_TIMEOUT, this::callFailure, elicit);
    }

    private static <T> List<T> firstListed(List<T> items) {
        return List.copyOf(items.size() > MAX_LISTED_RESOURCES ? items.subList(0, MAX_LISTED_RESOURCES) : items);
    }

    /**
     * Read before {@link #callFailure}, which over HTTP would take a legacy -32002 for a server leaving
     * MCP v2. A JSON-RPC error's message is the server's own text, and this failure is shown to the
     * model, so it is replaced there (JCLAW-778) and kept for the operator in the event log.
     */
    private McpException readFailure(String uri, McpException e) {
        if (e.code() instanceof Integer code && (code == INVALID_PARAMS || code == LEGACY_RESOURCE_NOT_FOUND)) {
            return new McpException.ResourceNotFound(code, uri);
        }
        if (e.httpStatus() != null || e.code() == null) return callFailure(e);
        EventLogger.warn(CATEGORY_RESOURCES, "MCP server '%s' refused %s of %s: %s"
                .formatted(name, RESOURCES_READ, new JsonPrimitive(uri), e.getMessage()));
        return callFailure(e.withMessage(RESOURCES_READ + " was refused"));
    }

    private void requireReady(String operation) {
        if (state.get() != State.READY) {
            throw new McpException(operation + " requires READY state, was " + state.get());
        }
    }

    /** One request after the handshake, a refusal of it passed through {@code refused}; {@code elicit} declares elicitation. */
    private JsonObject roundTrip(String method, JsonObject params, Map<String, String> headers, Duration timeout,
                                 UnaryOperator<McpException> refused, boolean elicit) throws IOException, McpException {
        JsonRpc.Response resp;
        try {
            resp = sendRequest(method, params, timeout, headers, elicit);
        } catch (McpException e) {
            throw refused.apply(e);
        }
        if (resp.isError()) {
            var err = resp.resolvedError();
            throw refused.apply(McpException.fromRpcError(err.code(), err.message(), err.data()));
        }
        if (!(resp.result() instanceof JsonObject result)) {
            throw new McpException(method + " returned non-object result");
        }
        return result;
    }

    private static List<JsonObject> objects(@Nullable JsonElement array) {
        var out = new ArrayList<JsonObject>();
        if (array == null || !array.isJsonArray()) return out;
        for (var el : array.getAsJsonArray()) {
            if (el instanceof JsonObject obj) out.add(obj);
        }
        return out;
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
                    "the server refused MCP v2 and offers " + describeVersions(refusal.supportedVersions()),
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
        resourcesOffered = declaresResources(result);
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
        resourcesOffered = result instanceof JsonObject initialized && declaresResources(initialized);
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
                    "the first tools/list over MCP v2 failed: " + e.getMessage(), true, e.code(), List.of());
        }
    }

    private JsonObject initializeParams() {
        var params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", clientCapabilities(false));
        params.add("clientInfo", clientInfo());
        return params;
    }

    /**
     * MCP Apps (SEP-1865): JClaw's web chat renders a tool's {@code ui://} page. With {@code elicitation}, form-mode
     * elicitation too, which only a 2026-07-28 tool call from a top-level web-chat turn declares.
     */
    private static JsonObject clientCapabilities(boolean elicitation) {
        var mimeTypes = new JsonArray();
        mimeTypes.add(McpResources.APP_MIME_TYPE);
        var ui = new JsonObject();
        ui.add("mimeTypes", mimeTypes);
        var extensions = new JsonObject();
        extensions.add(UI_EXTENSION, ui);
        var capabilities = new JsonObject();
        capabilities.add("extensions", extensions);
        if (elicitation) {
            var elicit = new JsonObject();
            elicit.add("form", new JsonObject());
            capabilities.add("elicitation", elicit);
        }
        return capabilities;
    }

    private JsonObject clientInfo() {
        var info = new JsonObject();
        info.addProperty("name", CLIENT_NAME);
        info.addProperty("version", clientVersion);
        return info;
    }

    /** An InitializeResult or a DiscoverResult whose {@code capabilities} carry a {@code resources} object. */
    private static boolean declaresResources(JsonObject result) {
        return result.get("capabilities") instanceof JsonObject caps && caps.get("resources") instanceof JsonObject;
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

    /** 1 for every handshake-era revision, 2 from 2026-07-28 on (ISO dates sort as strings): what an operator reads. */
    public static int generation(String version) {
        return version.compareTo(MODERN_PROTOCOL_VERSION) >= 0 ? 2 : 1;
    }

    /** {@code MCP v1} or {@code MCP v2}, the name a revision goes by wherever an operator reads it. */
    public static String label(String version) {
        return "MCP v" + generation(version);
    }

    /** {@code MCP v1 (2025-06-18, 2025-11-25) and MCP v2 (2026-07-28)}: the generation, then the exact revisions. */
    static String describeVersions(List<String> versions) {
        if (versions.isEmpty()) return "no version JClaw knows";
        var byLabel = new LinkedHashMap<String, List<String>>();
        for (var v : versions) byLabel.computeIfAbsent(label(v), _ -> new ArrayList<>()).add(v);
        return byLabel.entrySet().stream()
                .map(e -> e.getKey() + " (" + String.join(", ", e.getValue()) + ")")
                .collect(Collectors.joining(" and "));
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
                try {
                    refreshOnce(startedAt, startNanos);
                } finally {
                    refreshing.set(false);
                }
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
        if (previous.tools().equals(fetched.tools())) return;
        try {
            onToolsChanged.accept(fetched.tools());
        } catch (RuntimeException e) {
            Logger.warn("[mcp:%s] publishing the refreshed tool list failed: %s", name, e.getMessage());
        }
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
        listen.whenComplete((resp, failure) -> {
            var cause = failure instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : failure;
            if (resp != null && resp.isError()) {
                Logger.warn("[mcp:%s] %s refused: %s", name, LISTEN, resp.resolvedError().message());
                endSubscription(sub, SubscriptionEvent.REFUSED, onEvent, false);
            } else if (cause instanceof McpException me && me.code() != null) {
                // An HTTP 4xx carrying a JSON-RPC error is a refusal too, not a dropped stream.
                Logger.warn("[mcp:%s] %s refused: %s", name, LISTEN, me.getMessage());
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
        if (el == null) return false;
        return el.isJsonObject() || (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean());
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
        var ttlMs = new AtomicLong(Long.MAX_VALUE);
        pages(TOOLS_LIST, (p, _) -> toolsListPage(p, timeout, strict), result -> {
            ttlMs.accumulateAndGet(ttlMs(result.get("ttlMs")), Math::min);
            var tools = result.get("tools");
            if (tools == null || !tools.isJsonArray()) {
                if (strict) throw new McpException("tools/list returned no tools array");
            } else {
                out.addAll(toolDefs(tools.getAsJsonArray()));
            }
            return false;
        });
        return new Fetched(publishable(out), Duration.ofMillis(ttlMs.get()));
    }

    /** One page's result; true stops the paging. */
    @FunctionalInterface
    private interface Page {
        boolean read(JsonObject result) throws McpException;
    }

    /**
     * Every page of {@code method}, following {@code nextCursor} until {@code page} stops it, up to
     * {@link #MAX_TOOL_PAGES} so a server that never ends the list cannot hang the caller.
     *
     * @return false when that cap ended it with pages still to come
     */
    private boolean pages(String method, Round round, Page page) throws IOException, McpException {
        @Nullable String cursor = null;
        for (var n = 0; n < MAX_TOOL_PAGES; n++) {
            var params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            var result = untilComplete(method, params, false, round).result();
            if (page.read(result)) return true;
            var next = result.get("nextCursor");
            // An empty cursor names no next page, so it ends the list as an absent one does.
            if (next == null || !next.isJsonPrimitive() || next.getAsString().isEmpty()) return true;
            cursor = next.getAsString();
        }
        Logger.warn("[mcp:%s] %s was still paging after %d pages; using what those listed",
                name, method, MAX_TOOL_PAGES);
        return false;
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

    /** One request and its answer, declaring elicitation when {@code elicit}; {@link #untilComplete} repeats it with new params. */
    @FunctionalInterface
    private interface Round {
        JsonObject send(JsonObject params, boolean elicit) throws IOException, McpException;
    }

    /** A complete result, and JClaw's lines on how the operator answered the server's forms along the way. */
    private record Completed(JsonObject result, List<String> notes) {}

    /**
     * Send {@code params} until the result is complete (2026-07-28 Multi Round-Trip Requests). An
     * {@code input_required} result is retried with its {@code requestState} echoed unchanged and, when it holds
     * {@code inputRequests}, {@code inputResponses} answering each under its key. Each retry is a new request, so
     * it gets a new id. With {@code mayAsk}, a request from a top-level web-chat turn declares elicitation and its
     * forms go to the operator, one after another on this thread.
     *
     * @throws McpException.InputRequired when a form cannot be put to the operator: none is asked, and nothing goes back
     */
    private Completed untilComplete(String method, JsonObject params, boolean mayAsk, Round round)
            throws IOException, McpException {
        var unasked = McpElicitations.unaskedHere(mayAsk, modern);
        var offered = unasked == null;
        McpElicitations.@Nullable Wait wait = null;
        var notes = new LinkedHashSet<String>();
        var p = params;
        for (var retries = 0; ; retries++) {
            var result = round.send(p, offered);
            var input = inputRequired(method, result);
            if (input == null) return new Completed(result, List.copyOf(notes));
            if (retries == MAX_INPUT_RETRIES) {
                throw new McpException(withNotes(method + " still asked for another round after " + MAX_INPUT_RETRIES
                        + " retries, so JClaw stopped: the server never completed the request", notes));
            }
            p = params.deepCopy();
            var requestState = input.state();
            if (requestState != null) p.add("requestState", requestState);
            if (!input.forms().isEmpty()) {
                var questions = McpElicitations.questionsForAgent(name, input.forms().values(), unasked);
                if (questions != null) throw new McpException.InputRequired(withNotes(questions, notes));
                // One wait for the whole call, from its first form, so its forms cannot add up past the chat's own limit.
                if (wait == null) wait = McpElicitations.newWait();
                var responses = new JsonObject();
                for (var form : input.forms().entrySet()) {
                    var answer = McpElicitations.ask(name, form.getValue(), wait);
                    responses.add(form.getKey(), answer.response());
                    notes.add(answer.note());
                }
                p.add("inputResponses", responses);
            }
        }
    }

    /** {@code message}, then JClaw's lines on the forms the operator already answered in this call. */
    private static String withNotes(String message, Collection<String> notes) {
        return notes.isEmpty() ? message : message + "\n" + String.join("\n", notes);
    }

    /** An {@code input_required} result: the {@code requestState} to echo, and each form's params by its key. */
    private record InputRequired(@Nullable JsonElement state, Map<String, JsonObject> forms) {}

    /**
     * Null for a complete result, which is also what an absent {@code resultType} means; otherwise what to answer
     * an {@code input_required} with, or a failure for any other result and for a request JClaw cannot answer.
     */
    private static @Nullable InputRequired inputRequired(String method, JsonObject result) {
        var type = result.get("resultType");
        if (type == null || type.isJsonNull()) return null;
        var kind = type.isJsonPrimitive() ? type.getAsString() : "";
        if ("complete".equals(kind)) return null;
        if (!"input_required".equals(kind)) {
            var shown = kind.matches("[a-z_]{1,32}") ? "'" + kind + "'" : "of an unknown kind";
            throw new McpException(method + " returned a result " + shown
                    + ", which JClaw cannot handle: it knows complete and input_required");
        }
        var forms = forms(result.get("inputRequests"));
        var state = result.get("requestState");
        if (state != null && state.isJsonNull()) state = null;
        if (forms.isEmpty() && state == null) {
            throw new McpException(method + " returned input_required with neither inputRequests nor requestState");
        }
        return new InputRequired(state, forms);
    }

    /** Each {@code elicitation/create}'s params by its key; a request of any other kind fails the call. */
    private static Map<String, JsonObject> forms(@Nullable JsonElement requests) {
        var forms = new LinkedHashMap<String, JsonObject>();
        if (!isPresent(requests)) return forms;
        if (!(requests instanceof JsonObject byKey)) {
            // Answers go back under each request's key, so anything but an object of them cannot be answered.
            throw new McpException("the server asked for input mid-request in a shape JClaw cannot answer: its"
                    + " inputRequests is not an object of requests by key. Call the tool again with every argument it"
                    + " needs filled in, or use another tool.");
        }
        var refused = new JsonArray();
        for (var e : byKey.entrySet()) {
            if (e.getValue() instanceof JsonObject req && req.get("method") instanceof JsonPrimitive m
                    && ELICITATION_CREATE.equals(m.getAsString())) {
                forms.put(e.getKey(), req.get("params") instanceof JsonObject params ? params : new JsonObject());
            } else {
                refused.add(e.getValue());
            }
        }
        if (!refused.isEmpty()) {
            // JClaw declares no sampling or roots, so a conforming server never asks for them.
            throw new McpException("the server asked for input mid-request (" + inputKinds(refused)
                    + "), which JClaw cannot give: it offers no sampling or roots. Call the tool"
                    + " again with every argument it needs filled in, or use another tool.");
        }
        return forms;
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
            var lost = "MCP HTTP " + status + ": the server stopped answering MCP v2;"
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
        return sendRequest(method, params, timeout, Map.of(), false);
    }

    private JsonRpc.Response sendRequest(String method, JsonObject params, Duration timeout,
                                         Map<String, String> extraHeaders, boolean elicit) throws IOException, McpException {
        var id = nextId.getAndIncrement();
        return await(id, method, dispatch(id, method, params, modern, extraHeaders, elicit), timeout);
    }

    /** Register {@code id} and put the request on the wire; a modern request carries {@code _meta} and its headers. */
    private CompletableFuture<JsonRpc.Response> dispatch(long id, String method, JsonObject params, boolean asModern)
            throws IOException {
        return dispatch(id, method, params, asModern, Map.of(), false);
    }

    private CompletableFuture<JsonRpc.Response> dispatch(long id, String method, JsonObject params, boolean asModern,
                                                         Map<String, String> extraHeaders, boolean elicit) throws IOException {
        var future = new CompletableFuture<JsonRpc.Response>();
        pending.put(id, future);
        var req = new JsonRpc.Request(id, method, asModern ? withMeta(params, elicit) : params);
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

    private JsonObject withMeta(JsonObject params, boolean elicit) {
        var out = params.deepCopy();
        var meta = out.get(META) instanceof JsonObject existing ? existing : new JsonObject();
        meta.addProperty(META_PREFIX + "protocolVersion", MODERN_PROTOCOL_VERSION);
        meta.add(META_PREFIX + "clientCapabilities", clientCapabilities(elicit));
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
            var named = switch (method) {
                case TOOLS_CALL -> params != null ? params.get("name") : null;
                case RESOURCES_READ -> params != null ? params.get("uri") : null;
                default -> null;
            };
            if (named != null && named.isJsonPrimitive()) out.put("Mcp-Name", headerValue(named.getAsString()));
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
        // We declared no capability a server calls back on; reply method-not-found per JSON-RPC spec.
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
