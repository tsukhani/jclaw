package mcp;

import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.transport.McpStdioTransport;
import mcp.transport.McpStreamableHttpTransport;
import mcp.transport.McpTransport;
import models.McpServer;
import play.Play;
import services.EventLogger;
import services.Tx;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owner of all live MCP server connections (JCLAW-31).
 *
 * <p>Loads {@link McpServer} rows at startup, drives each through the
 * connect → read → reconnect lifecycle on virtual threads, and exposes
 * the discovered tools via {@link ToolRegistry#publishExternal} so the
 * agent loop can invoke them.
 *
 * <p><b>Threading model.</b> Connect/disconnect attempts run on virtual
 * threads (blocking I/O is fine — they unmount cleanly). Backoff timers
 * run on a small {@link ScheduledExecutorService} of <em>platform</em>
 * threads. The platform-thread choice is deliberate: per JDK-8373224,
 * many concurrent VTs sleeping inside {@code Thread.sleep} starve the
 * ForkJoinPool work queue and produce multi-second tail latency. Our
 * memory note (jdk25 vt_thread_sleep) calls this out — the timer pool
 * stays platform-only and only schedules work onto the VT pool.
 *
 * <p><b>Backoff schedule.</b> {@code min(2^attempts, ceiling) seconds},
 * reset on successful connect. Defaults to 30s ceiling per the AC ("max
 * 1 attempt per 30s") with a 1s initial. Configurable via the
 * {@code backoff*} setters for tests.
 *
 * <p>All status mutations on {@link McpServer} rows go through this
 * class; the admin UI (JCLAW-33) reads them but doesn't write them.
 */
public final class McpConnectionManager {

    private static final String CLIENT_VERSION_FALLBACK = "0.0.0-dev";
    private static final String CATEGORY_CONNECT = "MCP_CONNECT";
    private static final String CATEGORY_DISCONNECT = "MCP_DISCONNECT";
    private static final String TIMESTAMP_LAST_DISCONNECTED = "lastDisconnectedAt";

    private static volatile long backoffInitialMillis = 1_000L;
    private static volatile long backoffCeilingMillis = 30_000L;

    /** JCLAW-288: per-request timeout used on the very first connect attempt
     *  for an entry. Cold-cache uvx / npx / pipx subprocesses typically need
     *  30–60 s to install + bootstrap before they can respond to
     *  {@code initialize}; the steady-state 30 s {@link McpClient} default
     *  is too tight and races the install. After the first attempt resolves
     *  (success or failure), subsequent retries fall back to
     *  {@link #DEFAULT_REQUEST_TIMEOUT}. Volatile + setter so tests can
     *  shrink it without waiting two minutes per failure case. */
    @SuppressWarnings("java:S3008") // mutable test hook deliberately not final
    private static volatile Duration firstAttemptRequestTimeout = Duration.ofSeconds(120);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final ConcurrentHashMap<String, Entry> connections = new ConcurrentHashMap<>();
    // Reference is reassigned under synchronized ensureScheduler(); the held
    // executor is itself thread-safe, so volatile-on-reference is sufficient.
    @SuppressWarnings("java:S3077")
    private static volatile ScheduledExecutorService scheduler;

    private McpConnectionManager() {}

    // ==================== lifecycle ====================

    /** JCLAW-496: max time {@link #startAll} waits for all enabled servers'
     *  first-connect attempts to resolve before returning. The db-scheduler
     *  starts after MCP startup (priority ordering), so this bounds how long
     *  boot-time task fires wait for MCP tools; slower servers keep retrying
     *  via the watchdog after it elapses. */
    private static final Duration STARTUP_CONNECT_BUDGET = Duration.ofSeconds(20);

    /** Load every enabled MCP server row, start a connector for each, then wait
     *  (bounded) for the first-connect attempts to resolve. The scheduler starts
     *  after this {@code @OnApplicationStart} job (DbSchedulerBootstrapJob,
     *  priority 100), so joining here keeps the first task fires from racing
     *  still-in-flight MCP connections (JCLAW-496). Connects run concurrently —
     *  we only join them. */
    public static void startAll() {
        ensureScheduler();
        var configs = Tx.run(McpServer::findEnabled);
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var server : configs) futures.add(connectAndAwait(server));
        awaitFirstConnects(futures, STARTUP_CONNECT_BUDGET);
    }

    /**
     * JCLAW-496: await every first-connect future, bounded by {@code budget}.
     * Each resolves on success OR failure (see {@link #signalFirstAttemptResolved}),
     * so only genuinely slow / still-in-flight connects consume the budget —
     * failed servers resolve immediately and keep retrying via the watchdog.
     * Never throws: a slow or unreachable server must not block boot indefinitely.
     *
     * @return true if every future resolved within the budget.
     */
    public static boolean awaitFirstConnects(List<CompletableFuture<Void>> futures, Duration budget) {
        if (futures.isEmpty()) return true;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(budget.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            EventLogger.warn("system", "MCP startup: %d/%d servers connected within the %ds budget; proceeding (slow servers keep retrying)"
                    .formatted(connectionCount(), futures.size(), budget.toSeconds()));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            EventLogger.warn("system", "MCP startup await ended early: " + e.getMessage());
            return false;
        }
    }

    /** Connect (or restart) one configured server. Idempotent: replaces
     *  any prior entry for the same name. Fire-and-forget: returns once the
     *  first attempt has been scheduled, not when it resolves. Used by
     *  {@link #startAll()} on boot, where waiting per-server would serialize
     *  startup. Callers that need to know the first-attempt outcome (the
     *  admin API on registration) should use {@link #connectAndAwait}. */
    public static void connect(McpServer server) {
        connectInternal(server, null);
    }

    /** JCLAW-288: variant that returns a future completing when the first
     *  attempt resolves (success OR failure). The returned future is
     *  signal-only — callers read live state via {@link #status} and
     *  {@link #lastError} after it completes. Cancellation completes with
     *  {@link java.util.concurrent.CancellationException} (e.g. when a
     *  concurrent {@link #stop} or {@link #connect} replaces the entry
     *  while the first attempt is still in flight). Subsequent retries
     *  after this future completes are unaffected: the watchdog and the
     *  exponential backoff schedule continue to drive reconnects with the
     *  steady-state {@link #DEFAULT_REQUEST_TIMEOUT}. */
    public static CompletableFuture<Void> connectAndAwait(McpServer server) {
        var future = new CompletableFuture<Void>();
        connectInternal(server, future);
        return future;
    }

    private static void connectInternal(McpServer server, CompletableFuture<Void> firstAttemptFuture) {
        ensureScheduler();
        // Tear down any prior entry for this name first.
        stop(server.name);
        var entry = new Entry(server.name);
        entry.firstAttemptFuture = firstAttemptFuture;
        // JCLAW-388: capture the approval flag from the row now so the
        // dispatch-path lookup (McpServerTool.dangerous → requiresApproval)
        // never touches the DB. A subsequent toggle re-runs connectInternal
        // (McpServerService.syncRuntime → connect replaces the entry), so the
        // captured value tracks the persisted flag across edits.
        entry.requiresApproval = server.requiresApproval;
        connections.put(server.name, entry);
        scheduleConnect(entry, server, 0);
    }

    /** Disconnect a single server and remove its tools.
     *
     *  <p>Idempotent against missing entries: even when no in-memory connection
     *  exists for {@code serverName}, the allowlist sweep + audit still run.
     *  This matters for the admin {@code DELETE /api/mcp-servers/{id}} path
     *  (JCLAW-33) which must clean up any orphaned {@code agent_skill_allowed_tool}
     *  rows from prior crashes or pre-restart state. */
    public static void stop(String serverName) {
        var entry = connections.remove(serverName);
        if (entry != null) {
            if (entry.scheduledRetry != null) entry.scheduledRetry.cancel(false);
            var client = entry.client;
            if (client != null) {
                try { client.close(); } catch (RuntimeException _) { /* best effort */ }
            }
            // JCLAW-288: unblock any caller awaiting the first-attempt future
            // for this entry. Replacing the entry (via a subsequent connect)
            // or explicitly stopping mid-handshake counts as "first attempt
            // resolved" for the awaiter — they'll read DISCONNECTED status
            // and decide what to do.
            var pending = entry.firstAttemptFuture;
            if (pending != null && !pending.isDone()) pending.cancel(false);
            ToolRegistry.unpublishExternal(serverName);
        }
        clearAllowlistAndAudit(serverName);
    }

    /** Stop all connections. Called from {@code ShutdownJob}. */
    public static void shutdown() {
        for (var name : new ArrayList<>(connections.keySet())) stop(name);
        var sched = scheduler;
        if (sched != null) {
            sched.shutdownNow();
            try { sched.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            scheduler = null;
        }
    }

    // ==================== queries ====================

    public static McpServer.Status status(String serverName) {
        var e = connections.get(serverName);
        return e != null ? e.status : McpServer.Status.DISCONNECTED;
    }

    public static String lastError(String serverName) {
        var e = connections.get(serverName);
        return e != null ? e.lastError : null;
    }

    /**
     * JCLAW-388: does this server require an interactive approve/deny prompt
     * before each of its tool calls? Resolved from the in-memory connection
     * {@link Entry} (captured from the {@link McpServer} row at connect time),
     * so the dispatch-path lookup in {@link McpServerTool#dangerous()} costs a
     * single {@link ConcurrentHashMap} read with no DB round-trip. Returns
     * {@code false} for any server without a live entry — a disconnected
     * server has no tools to gate, and the default is opt-out anyway.
     */
    public static boolean requiresApproval(String serverName) {
        var e = connections.get(serverName);
        return e != null && e.requiresApproval;
    }

    public static List<McpToolDef> tools(String serverName) {
        var e = connections.get(serverName);
        if (e == null || e.client == null) return List.of();
        return e.client.tools();
    }

    /** Invoke an MCP tool on a connected server. Used by {@link McpToolAdapter}. */
    public static CallToolResult callTool(String serverName, String toolName, JsonObject arguments)
            throws java.io.IOException, McpException {
        var entry = connections.get(serverName);
        if (entry == null || entry.client == null
                || entry.client.state() != McpClient.State.READY) {
            throw new McpException("MCP server '" + serverName + "' not ready");
        }
        return entry.client.callTool(toolName, arguments);
    }

    /** Close an MCP client, swallowing any runtime error — used in race-recovery paths. */
    private static void bestEffortClose(McpClient client) {
        try { client.close(); } catch (RuntimeException _) { /* best effort */ }
    }

    // ==================== test hooks ====================

    public static void setBackoff(long initialMillis, long ceilingMillis) {
        backoffInitialMillis = initialMillis;
        backoffCeilingMillis = ceilingMillis;
    }

    /** JCLAW-288: shrink (or restore) the first-attempt handshake timeout
     *  for tests that need to exercise the cold-cache failure path without
     *  waiting the production 120 s. Production code never calls this. */
    public static void setFirstAttemptRequestTimeout(Duration timeout) {
        firstAttemptRequestTimeout = timeout;
    }

    public static int connectionCount() { return connections.size(); }

    /** Names of every server with an in-memory connection entry, regardless
     *  of state. Used by {@link McpAllowlist#backfillForAgent} to know
     *  which server scopes a new agent should be granted. */
    public static java.util.Set<String> connectedServerNames() {
        return java.util.Set.copyOf(connections.keySet());
    }

    // ==================== internals ====================

    private static void scheduleConnect(Entry entry, McpServer server, int attempt) {
        var delay = backoffDelay(attempt);
        if (delay == 0) {
            launchConnect(entry, server, attempt);
            return;
        }
        var sched = scheduler;
        if (sched == null) return;  // shutdown raced
        entry.scheduledRetry = sched.schedule(
                () -> launchConnect(entry, server, attempt),
                delay, TimeUnit.MILLISECONDS);
    }

    private static void launchConnect(Entry entry, McpServer server, int attempt) {
        if (!connections.containsKey(server.name)) return;  // stopped while waiting
        Thread.ofVirtual().name("mcp-connect-" + server.name).start(() -> doConnect(entry, server, attempt));
    }

    private static void doConnect(Entry entry, McpServer server, int attempt) {
        entry.status = McpServer.Status.CONNECTING;
        persistStatus(server.id, McpServer.Status.CONNECTING, null);

        McpTransport transport;
        try {
            transport = buildTransport(server);
        } catch (RuntimeException e) {
            handleFailure(entry, server, attempt, "transport build failed: " + e.getMessage());
            signalFirstAttemptResolved(entry, attempt);
            return;
        }

        // JCLAW-288: extended request timeout on the first attempt so
        // cold-cache uvx / npx / pipx subprocesses have headroom to install
        // + bootstrap before the initialize handshake. Steady-state retries
        // keep the snappier default — by the second attempt the cache is
        // warm (or there's a real problem) and a long timeout would only
        // delay the failure signal.
        var requestTimeout = (attempt == 0) ? firstAttemptRequestTimeout : DEFAULT_REQUEST_TIMEOUT;
        var client = new McpClient(server.name, transport, clientVersion(), requestTimeout);
        client.onToolsChanged(tools -> republishTools(server.name, tools));
        try {
            client.connect();
            // If the operator toggled this server off (or replaced its
            // config) while client.connect() was in-flight, the entry was
            // removed from the connections map by stop(). Detect that and
            // roll back the just-finished handshake — without this, the
            // orphaned doConnect would re-publish tools, re-write the
            // allowlist, and persist status=CONNECTED for a server the
            // user just disabled. (The first-attempt future on the orphan
            // entry was already cancelled by the stop() that replaced us.)
            if (connections.get(server.name) != entry) {
                bestEffortClose(client);
                return;
            }
            // Defensive: if a prior client is still attached (e.g. a watchdog-
            // driven reconnect after onTransportError), close it before
            // overwriting the reference. The transport close above on the
            // McpClient.onTransportError path normally has this covered, but
            // any future path that calls scheduleConnect without going
            // through transport-error should still leave us leak-free.
            var prior = entry.client;
            if (prior != null && prior != client) bestEffortClose(prior);
            entry.client = client;
            // republishTools publishes the in-memory tool adapters AND syncs
            // the DB-authoritative allowlist (JCLAW-32). Both must complete
            // before status flips to CONNECTED so observers polling state
            // (tests, admin UI) only see CONNECTED after grants are durable.
            republishTools(server.name, client.tools());
            entry.status = McpServer.Status.CONNECTED;
            entry.lastError = null;
            entry.attempts = 0;
            persistStatus(server.id, McpServer.Status.CONNECTED, null);
            persistTimestamp(server.id, "lastConnectedAt");
            EventLogger.info(CATEGORY_CONNECT,
                    "MCP server '%s' connected (%d tools)".formatted(server.name, client.tools().size()));
            // Watch for transport-driven disconnects: after connect returns,
            // the McpClient runs READY until either close() or transport
            // error trips it back to DISCONNECTED. Spawn a tiny watchdog VT
            // that polls the state and reconnects when it changes.
            startWatchdog(entry, server);
            signalFirstAttemptResolved(entry, attempt);
        } catch (Exception e) {
            try { client.close(); } catch (RuntimeException _) {}
            handleFailure(entry, server, attempt, e.getMessage());
            signalFirstAttemptResolved(entry, attempt);
        }
    }

    /** JCLAW-288: complete the awaiter's future on the FIRST attempt's
     *  resolution (success or failure). Subsequent attempts don't touch
     *  the future — the awaiter only ever waits one cycle, and the
     *  watchdog/backoff loop handles long-running reconnects. */
    private static void signalFirstAttemptResolved(Entry entry, int attempt) {
        if (attempt != 0) return;
        var future = entry.firstAttemptFuture;
        if (future != null && !future.isDone()) future.complete(null);
    }

    private static void startWatchdog(Entry entry, McpServer server) {
        Thread.ofVirtual().name("mcp-watchdog-" + server.name).start(() -> {
            var client = entry.client;
            try {
                while (client != null && client.state() == McpClient.State.READY
                        && connections.get(server.name) == entry) {
                    Thread.sleep(250);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            // Reached here because state changed off READY OR entry was replaced/stopped.
            if (connections.get(server.name) != entry) return;  // we were stopped or replaced
            if (client.state() == McpClient.State.READY) return;  // false alarm

            ToolRegistry.unpublishExternal(server.name);
            clearAllowlistAndAudit(server.name);
            EventLogger.warn(CATEGORY_DISCONNECT,
                    "MCP server '%s' disconnected: %s".formatted(server.name, client.lastError()));
            entry.status = McpServer.Status.DISCONNECTED;
            entry.lastError = client.lastError();
            persistStatus(server.id, McpServer.Status.DISCONNECTED, client.lastError());
            persistTimestamp(server.id, TIMESTAMP_LAST_DISCONNECTED);
            scheduleConnect(entry, server, entry.attempts + 1);
        });
    }

    private static void handleFailure(Entry entry, McpServer server, int attempt, String error) {
        var hadConnection = entry.client != null;
        if (entry.client != null) {
            try { entry.client.close(); } catch (RuntimeException _) {}
            entry.client = null;
        }
        ToolRegistry.unpublishExternal(server.name);
        if (hadConnection) clearAllowlistAndAudit(server.name);
        entry.status = McpServer.Status.ERROR;
        entry.lastError = error;
        entry.attempts = attempt + 1;
        persistStatus(server.id, McpServer.Status.ERROR, error);
        if (hadConnection) {
            EventLogger.warn(CATEGORY_DISCONNECT,
                    "MCP server '%s' disconnected: %s".formatted(server.name, error));
            persistTimestamp(server.id, TIMESTAMP_LAST_DISCONNECTED);
        } else {
            EventLogger.warn(CATEGORY_CONNECT,
                    "MCP server '%s' connect attempt %d failed: %s".formatted(
                            server.name, attempt + 1, error));
        }
        scheduleConnect(entry, server, attempt + 1);
    }

    private static void republishTools(String serverName, List<McpToolDef> defs) {
        // JCLAW-281: the server-level handle is what the LLM sees in its
        // function-calling defs (one parameterized entry per server). The
        // per-action McpToolAdapter wrappers stay in the registry too —
        // they're the execution path that McpServerTool delegates to once
        // the model has chosen an action — but they're hidden from the
        // function-calling defs by Tool.isServerLevel + the filter in
        // ToolRegistry.getToolDefsForAgent. Server-level handle goes
        // first so iteration order keeps the per-server card grouping
        // intact in the admin UI.
        var tools = new ArrayList<ToolRegistry.Tool>(defs.size() + 1);
        tools.add(new McpServerTool(serverName));
        for (var def : defs) {
            tools.add(new McpToolAdapter(serverName, def, McpConnectionManager::callTool));
        }
        ToolRegistry.publishExternal(serverName, tools);
        // JCLAW-32: keep the DB-authoritative allowlist in sync with the
        // in-memory ToolRegistry on every tool-list change. Idempotent —
        // McpAllowlist.registerForAllAgents clears the prior set first so
        // a shrinking tool list doesn't leave orphaned grants.
        try {
            Tx.run(() -> McpAllowlist.registerForAllAgents(serverName, defs));
        } catch (RuntimeException e) {
            EventLogger.warn("MCP_TOOL_REGISTER",
                    "Allowlist sync failed for '%s': %s".formatted(serverName, e.getMessage()));
        }
    }

    /** Atomic delete-and-audit: drop every allowlist row for this server in
     *  the same tx as the {@code MCP_TOOL_UNREGISTER} log entry, so the
     *  audit trail can never disagree with the live row state. Used by
     *  every disconnect path: explicit {@link #stop}, watchdog teardown,
     *  and connect-failure rollback. */
    private static void clearAllowlistAndAudit(String serverName) {
        // During graceful shutdown the JPA layer is tearing down, so this DELETE
        // can't begin a transaction — and it's redundant: the next boot's connect
        // calls McpAllowlist.registerForAllAgents, which clears the prior set
        // first. Nothing can use the grants while the app is down, so skipping the
        // revoke here is safe and avoids a spurious "begin transaction failed" WARN.
        // (Runtime disconnect paths — admin DELETE, watchdog — are not shutting
        // down, so they still revoke with JPA alive.)
        if (EventLogger.isShuttingDown()) return;
        try {
            Tx.run(() -> {
                int removed = McpAllowlist.unregister(serverName);
                if (removed == 0) return;
                var ev = new models.EventLog();
                ev.timestamp = Instant.now();
                ev.level = "INFO";
                ev.category = "MCP_TOOL_UNREGISTER";
                ev.message = "Removed %d MCP allowlist row(s) for server '%s'"
                        .formatted(removed, serverName);
                ev.save();
            });
        } catch (RuntimeException e) {
            EventLogger.warn("MCP_TOOL_UNREGISTER",
                    "Failed to clear allowlist for '%s': %s".formatted(serverName, e.getMessage()));
        }
    }

    private static long backoffDelay(int attempt) {
        if (attempt <= 0) return 0;
        long delay = backoffInitialMillis << Math.min(attempt - 1, 30);  // 2^(attempt-1) * initial, capped to avoid overflow
        return Math.min(delay, backoffCeilingMillis);
    }

    private static McpTransport buildTransport(McpServer server) {
        var cfg = JsonParser.parseString(server.configJson).getAsJsonObject();
        return switch (server.transport) {
            case STDIO -> {
                var command = stringList(cfg, "command", "args");
                var env = stringMap(cfg, "env");
                yield new McpStdioTransport(server.name, command, env);
            }
            case HTTP -> {
                var url = cfg.get("url").getAsString();
                var headers = stringMap(cfg, "headers");
                yield new McpStreamableHttpTransport(server.name, URI.create(url), headers);
            }
        };
    }

    private static List<String> stringList(JsonObject cfg, String firstKey, String restKey) {
        var out = new ArrayList<String>();
        if (cfg.has(firstKey)) out.add(cfg.get(firstKey).getAsString());
        if (cfg.has(restKey) && cfg.get(restKey).isJsonArray()) {
            for (JsonElement el : cfg.getAsJsonArray(restKey)) out.add(el.getAsString());
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonObject cfg, String key) {
        if (!cfg.has(key) || !cfg.get(key).isJsonObject()) return Map.of();
        var map = new HashMap<String, String>();
        for (var entry : cfg.getAsJsonObject(key).entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }

    /**
     * Persist a status field with a partial JPQL UPDATE — never load + save
     * the entity. The connector's persist calls run in their own VTs, in
     * separate transactions from the controller that just toggled
     * {@code enabled}. If we used {@code findById + setField + save()},
     * Hibernate would write back ALL fields of the loaded entity from the
     * connector's tx, which would race with the controller's pending
     * {@code enabled=true} update. The lost-update bug surfaced as
     * "row stays enabled=false even after toggling on" because
     * persistStatus would load the pre-controller-commit row state and
     * re-save it. Targeted UPDATE statements only touch the column we
     * want, so concurrent enabled/configJson changes can't be clobbered.
     */
    private static void persistStatus(Long serverId, McpServer.Status status, String error) {
        if (serverId == null) return;
        var truncated = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        try {
            Tx.run(() -> play.db.jpa.JPA.em().createQuery(
                        "UPDATE McpServer s SET s.status = :status, s.lastError = :err, s.updatedAt = :now WHERE s.id = :id")
                        .setParameter("status", status)
                        .setParameter("err", truncated)
                        .setParameter("now", Instant.now())
                        .setParameter("id", serverId)
                        .executeUpdate());
        } catch (RuntimeException _) { /* best effort persistence */ }
    }

    /** Stamp a timestamp column to now(). {@code column} is a fixed,
     *  caller-supplied literal (never user input) — mapped here to a fully-static
     *  JPQL string so nothing is ever concatenated into the query. That removes
     *  the injection surface entirely (and stays safe even if a future caller
     *  passed a tainted value — an unknown column is rejected). Best-effort —
     *  swallows persistence failures (and an unsupported column) like its
     *  siblings. */
    private static void persistTimestamp(Long serverId, String column) {
        if (serverId == null) return;
        try {
            String jpql = switch (column) {
                case "lastConnectedAt" ->
                        "UPDATE McpServer s SET s.lastConnectedAt = :now, s.updatedAt = :now WHERE s.id = :id";
                case TIMESTAMP_LAST_DISCONNECTED ->
                        "UPDATE McpServer s SET s.lastDisconnectedAt = :now, s.updatedAt = :now WHERE s.id = :id";
                default -> throw new IllegalArgumentException("Unsupported timestamp column: " + column);
            };
            Tx.run(() -> play.db.jpa.JPA.em().createQuery(jpql)
                        .setParameter("now", Instant.now())
                        .setParameter("id", serverId)
                        .executeUpdate());
        } catch (RuntimeException _) { /* best effort persistence */ }
    }

    private static synchronized void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            // Two platform threads — small, plenty for backoff-timer fan-out;
            // platform deliberately (NOT virtual) to dodge JDK-8373224.
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                var t = new Thread(r);
                t.setName("mcp-backoff-" + t.threadId());
                t.setDaemon(true);
                return t;
            });
        }
    }

    private static String clientVersion() {
        var v = Play.configuration.getProperty("application.version");
        return v != null ? v : CLIENT_VERSION_FALLBACK;
    }

    /** Per-server runtime state. Lives in {@link #connections}. */
    private static final class Entry {
        final String name;
        // McpClient manages its own internal thread-safety (state via AtomicReference,
        // ConcurrentHashMap for pending requests); volatile here just publishes the
        // reference. Likewise ScheduledFuture is thread-safe by JDK contract.
        @SuppressWarnings("java:S3077")
        volatile McpClient client;
        volatile McpServer.Status status = McpServer.Status.DISCONNECTED;
        volatile String lastError;
        volatile int attempts;
        /** JCLAW-388: per-server interactive-approval flag, captured from the
         *  {@link McpServer} row at connect time. Read on the tool-dispatch
         *  path via {@link #requiresApproval(String)}; volatile so a
         *  reconnect that replaces the entry publishes a fresh value. */
        volatile boolean requiresApproval;
        @SuppressWarnings("java:S3077")
        volatile ScheduledFuture<?> scheduledRetry;
        /** JCLAW-288: when non-null, completed on the FIRST attempt's
         *  resolution (success or failure). Populated by
         *  {@link #connectAndAwait}; left null for fire-and-forget
         *  {@link #connect}. CompletableFuture is itself thread-safe;
         *  this volatile only publishes the reference. */
        @SuppressWarnings("java:S3077")
        volatile CompletableFuture<Void> firstAttemptFuture;

        Entry(String name) { this.name = name; }
    }
}
