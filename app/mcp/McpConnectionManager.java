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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static volatile long backoffInitialMillis = 1_000L;
    private static volatile long backoffCeilingMillis = 30_000L;

    private static final ConcurrentHashMap<String, Entry> connections = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService scheduler;

    private McpConnectionManager() {}

    // ==================== lifecycle ====================

    /** Load every enabled MCP server row and start a connector for each. */
    public static void startAll() {
        ensureScheduler();
        var configs = Tx.run(McpServer::findEnabled);
        for (var server : configs) connect(server);
    }

    /** Connect (or restart) one configured server. Idempotent: replaces
     *  any prior entry for the same name. */
    public static void connect(McpServer server) {
        ensureScheduler();
        // Tear down any prior entry for this name first.
        stop(server.name);
        var entry = new Entry(server.name);
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
                try { client.close(); } catch (RuntimeException ignored) { /* best effort */ }
            }
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
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    public static List<McpToolDef> tools(String serverName) {
        var e = connections.get(serverName);
        if (e == null || e.client == null) return List.of();
        return e.client.tools();
    }

    /** Invoke an MCP tool on a connected server. Used by {@link McpToolAdapter}. */
    public static CallToolResult callTool(String serverName, String toolName, JsonObject arguments)
            throws Exception {
        var entry = connections.get(serverName);
        if (entry == null || entry.client == null
                || entry.client.state() != McpClient.State.READY) {
            throw new McpException("MCP server '" + serverName + "' not ready");
        }
        return entry.client.callTool(toolName, arguments);
    }

    // ==================== test hooks ====================

    public static void setBackoff(long initialMillis, long ceilingMillis) {
        backoffInitialMillis = initialMillis;
        backoffCeilingMillis = ceilingMillis;
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
            return;
        }

        var client = new McpClient(server.name, transport, clientVersion());
        client.onToolsChanged(tools -> republishTools(server.name, tools));
        try {
            client.connect();
            // If the operator toggled this server off (or replaced its
            // config) while client.connect() was in-flight, the entry was
            // removed from the connections map by stop(). Detect that and
            // roll back the just-finished handshake — without this, the
            // orphaned doConnect would re-publish tools, re-write the
            // allowlist, and persist status=CONNECTED for a server the
            // user just disabled.
            if (connections.get(server.name) != entry) {
                try { client.close(); } catch (RuntimeException ignored) { /* best effort */ }
                return;
            }
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
            persistConnectedAt(server.id);
            EventLogger.info(CATEGORY_CONNECT,
                    "MCP server '%s' connected (%d tools)".formatted(server.name, client.tools().size()));
            // Watch for transport-driven disconnects: after connect returns,
            // the McpClient runs READY until either close() or transport
            // error trips it back to DISCONNECTED. Spawn a tiny watchdog VT
            // that polls the state and reconnects when it changes.
            startWatchdog(entry, server);
        } catch (Exception e) {
            try { client.close(); } catch (RuntimeException ignored) {}
            handleFailure(entry, server, attempt, e.getMessage());
        }
    }

    private static void startWatchdog(Entry entry, McpServer server) {
        Thread.ofVirtual().name("mcp-watchdog-" + server.name).start(() -> {
            var client = entry.client;
            try {
                while (client != null && client.state() == McpClient.State.READY
                        && connections.get(server.name) == entry) {
                    Thread.sleep(250);
                }
            } catch (InterruptedException e) {
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
            persistDisconnectedAt(server.id);
            scheduleConnect(entry, server, entry.attempts + 1);
        });
    }

    private static void handleFailure(Entry entry, McpServer server, int attempt, String error) {
        var hadConnection = entry.client != null;
        if (entry.client != null) {
            try { entry.client.close(); } catch (RuntimeException ignored) {}
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
            persistDisconnectedAt(server.id);
        } else {
            EventLogger.warn(CATEGORY_CONNECT,
                    "MCP server '%s' connect attempt %d failed: %s".formatted(
                            server.name, attempt + 1, error));
        }
        scheduleConnect(entry, server, attempt + 1);
    }

    private static void republishTools(String serverName, List<McpToolDef> defs) {
        var adapters = new ArrayList<ToolRegistry.Tool>(defs.size());
        for (var def : defs) {
            adapters.add(new McpToolAdapter(serverName, def, McpConnectionManager::callTool));
        }
        ToolRegistry.publishExternal(serverName, adapters);
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
            Tx.run(() -> {
                play.db.jpa.JPA.em().createQuery(
                        "UPDATE McpServer s SET s.status = :status, s.lastError = :err, s.updatedAt = :now WHERE s.id = :id")
                        .setParameter("status", status)
                        .setParameter("err", truncated)
                        .setParameter("now", Instant.now())
                        .setParameter("id", serverId)
                        .executeUpdate();
            });
        } catch (RuntimeException ignored) { /* best effort persistence */ }
    }

    private static void persistConnectedAt(Long serverId) {
        if (serverId == null) return;
        try {
            Tx.run(() -> {
                play.db.jpa.JPA.em().createQuery(
                        "UPDATE McpServer s SET s.lastConnectedAt = :now, s.updatedAt = :now WHERE s.id = :id")
                        .setParameter("now", Instant.now())
                        .setParameter("id", serverId)
                        .executeUpdate();
            });
        } catch (RuntimeException ignored) {}
    }

    private static void persistDisconnectedAt(Long serverId) {
        if (serverId == null) return;
        try {
            Tx.run(() -> {
                play.db.jpa.JPA.em().createQuery(
                        "UPDATE McpServer s SET s.lastDisconnectedAt = :now, s.updatedAt = :now WHERE s.id = :id")
                        .setParameter("now", Instant.now())
                        .setParameter("id", serverId)
                        .executeUpdate();
            });
        } catch (RuntimeException ignored) {}
    }

    private static synchronized void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            // Two platform threads — small, plenty for backoff-timer fan-out;
            // platform deliberately (NOT virtual) to dodge JDK-8373224.
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                var t = new Thread(r);
                t.setName("mcp-backoff-" + t.getId());
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
        volatile McpClient client;
        volatile McpServer.Status status = McpServer.Status.DISCONNECTED;
        volatile String lastError;
        volatile int attempts;
        volatile ScheduledFuture<?> scheduledRetry;

        Entry(String name) { this.name = name; }
    }
}
