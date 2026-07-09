package services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpClient;
import mcp.McpConnectionManager;
import mcp.McpToolDef;
import mcp.transport.McpStdioTransport;
import mcp.transport.McpStreamableHttpTransport;
import mcp.transport.McpTransport;
import models.McpServer;
import play.Play;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service helpers for the MCP server admin surface (JCLAW-33).
 *
 * <p>Splits responsibilities into three concerns the controller would
 * otherwise mix together:
 *
 * <ul>
 *   <li>{@link #composeConfigJson} / {@link #explodeConfigJson} —
 *       symmetric translation between the form-friendly flat shape
 *       (transport, command/args/env OR url/headers) the API exchanges
 *       and the {@code McpServer.configJson} TEXT column the runtime
 *       reads.</li>
 *   <li>{@link #syncRuntime} — orchestrate the in-memory connection
 *       state (connect / restart / disconnect) when a row is created,
 *       updated, or deleted.</li>
 *   <li>{@link #testConnection} — synchronous "Test connection"
 *       handler: spin a throwaway {@link McpClient} with the candidate
 *       config, run initialize + tools/list, return success+tool count
 *       or error. Doesn't disturb any active connection for the row.</li>
 * </ul>
 */
public final class McpServerService {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private McpServerService() {}

    // ==================== view / list ====================

    /**
     * Snapshot of a single advertised tool on an MCP server. Exposed
     * inside {@link View#tools} so the admin UI can render the
     * inline-expandable action list under each MCP Servers card —
     * no separate per-server tools endpoint needed.
     *
     * @param name        tool name from the MCP server's {@code tools/list}
     * @param description tool description from the MCP server
     */
    public record ToolInfo(String name, String description) {}

    /**
     * Snapshot of one MCP server row as the admin UI sees it: persisted
     * fields plus current runtime state. The flat transport-specific
     * fields are exploded out of {@code configJson} so forms can bind
     * directly without re-parsing.
     */
    public record View(Long id, String name, boolean enabled, boolean requiresApproval, String transport,
                       String command, List<String> args, Map<String, String> env,
                       String url, Map<String, String> headers,
                       String status, String lastError,
                       String lastConnectedAt, String lastDisconnectedAt,
                       int toolCount,
                       List<ToolInfo> tools,
                       String createdAt, String updatedAt) {

        public static View of(McpServer row) {
            var cfg = explodeConfigJson(row.transport, row.configJson);
            var liveTools = McpConnectionManager.tools(row.name);
            int tools = liveTools.size();
            // Expose name + description per advertised tool so the UI
            // can render the action list without a follow-up fetch.
            // Description falls back to empty string when the MCP server
            // doesn't supply one — keeps the JSON shape stable.
            var toolInfo = liveTools.stream()
                    .map(t -> new ToolInfo(t.name(), t.description() == null ? "" : t.description()))
                    .toList();
            // Live status preferred over the persisted column. The in-memory
            // state is updated synchronously by connect()/stop() and the
            // watchdog; the DB column lags behind by however long the
            // background persistStatus call takes to commit. For rows with
            // no in-memory entry (disabled or never connected), the manager
            // returns DISCONNECTED naturally. The persisted column remains
            // useful for post-restart display before the connector reports.
            var liveStatus = McpConnectionManager.status(row.name).name();
            // JCLAW-288: same live-preferred read for lastError so admin
            // endpoints that just blocked on the first connect attempt
            // (syncRuntime → connectAndAwait) see the error reason on the
            // same response. Fall back to the persisted column when the
            // manager has no entry (e.g. disabled rows after restart).
            var liveLastError = McpConnectionManager.lastError(row.name);
            var effectiveLastError = liveLastError != null ? liveLastError : row.lastError;
            return new View(
                    row.id, row.name, row.enabled, row.requiresApproval, row.transport.name(),
                    cfg.command, cfg.args, cfg.env,
                    cfg.url, cfg.headers,
                    liveStatus,
                    effectiveLastError,
                    row.lastConnectedAt != null ? row.lastConnectedAt.toString() : null,
                    row.lastDisconnectedAt != null ? row.lastDisconnectedAt.toString() : null,
                    tools,
                    toolInfo,
                    row.createdAt != null ? row.createdAt.toString() : null,
                    row.updatedAt != null ? row.updatedAt.toString() : null);
        }
    }

    public static List<View> listAll() {
        var rows = McpServer.<McpServer>find("ORDER BY name ASC").<McpServer>fetch();
        return rows.stream().map(View::of).toList();
    }

    /**
     * JCLAW-153: entity-lookup accessor so controllers route through the
     * service layer instead of calling {@code McpServer.findById(...)} raw.
     * Thin passthrough relying on the caller's ambient JPA transaction.
     */
    public static McpServer findById(Long id) {
        return McpServer.findById(id);
    }

    // ==================== translation ====================

    /**
     * Parsed form fields ready for a controller's response or for
     * rebuilding the {@code McpServer.configJson} on a save.
     *
     * @param command stdio-transport executable to launch (null for HTTP
     *                transport)
     * @param args    command-line arguments
     * @param env     environment variables to set on the spawned process
     * @param url     HTTP-transport endpoint URL (null for stdio)
     * @param headers HTTP-transport request headers
     */
    public record TransportConfig(String command, List<String> args, Map<String, String> env,
                                  String url, Map<String, String> headers) {

        public static TransportConfig empty() {
            return new TransportConfig(null, List.of(), Map.of(), null, Map.of());
        }
    }

    /**
     * Build a {@code configJson} string from the form-shape JSON the API
     * received. Discards fields that don't apply to the chosen transport
     * so the stored row is canonical.
     */
    public static String composeConfigJson(McpServer.Transport transport, JsonObject body) {
        var cfg = new JsonObject();
        switch (transport) {
            case STDIO -> composeStdioConfig(body, cfg);
            case HTTP -> composeHttpConfig(body, cfg);
        }
        return cfg.toString();
    }

    private static void composeStdioConfig(JsonObject body, JsonObject cfg) {
        copyStringProperty(body, cfg, "command");
        copyJsonArray(body, cfg, "args");
        copyJsonObject(body, cfg, "env");
    }

    private static void composeHttpConfig(JsonObject body, JsonObject cfg) {
        copyStringProperty(body, cfg, "url");
        copyJsonObject(body, cfg, "headers");
    }

    private static void copyStringProperty(JsonObject src, JsonObject dst, String key) {
        if (src.has(key) && !src.get(key).isJsonNull()) {
            dst.addProperty(key, src.get(key).getAsString());
        }
    }

    private static void copyJsonArray(JsonObject src, JsonObject dst, String key) {
        if (src.has(key) && src.get(key).isJsonArray()) {
            dst.add(key, src.get(key).getAsJsonArray());
        }
    }

    private static void copyJsonObject(JsonObject src, JsonObject dst, String key) {
        if (src.has(key) && src.get(key).isJsonObject()) {
            dst.add(key, src.get(key).getAsJsonObject());
        }
    }

    /** Inverse of {@link #composeConfigJson}: parse the row's stored JSON
     *  into form-ready fields so the admin UI can pre-populate inputs. */
    public static TransportConfig explodeConfigJson(McpServer.Transport transport, String configJson) {
        if (configJson == null || configJson.isBlank()) return TransportConfig.empty();
        JsonObject cfg;
        try {
            cfg = JsonParser.parseString(configJson).getAsJsonObject();
        } catch (RuntimeException _) {
            return TransportConfig.empty();
        }
        return switch (transport) {
            case STDIO -> new TransportConfig(
                    optionalString(cfg, "command"),
                    optionalStringList(cfg, "args"),
                    optionalStringMap(cfg, "env"),
                    null, Map.of());
            case HTTP -> new TransportConfig(
                    null, List.of(), Map.of(),
                    optionalString(cfg, "url"),
                    optionalStringMap(cfg, "headers"));
        };
    }

    // ==================== runtime sync ====================

    /**
     * Reconcile in-memory connection state with the row's persisted
     * config + enabled flag. Caller invokes after every save (create or
     * update). Idempotent: {@link McpConnectionManager#connect} replaces
     * any prior entry, and we call {@link McpConnectionManager#stop} for
     * disabled rows so a recently-flipped-off server actually disconnects.
     *
     * <p><b>The enable path is fire-and-forget.</b> We kick the connect off on
     * its own virtual thread and return at once — we must <em>not</em> block
     * the caller on the handshake. The caller is an admin HTTP action whose
     * request runs inside a single JPA transaction; the {@code row.save()}
     * that preceded this call flushed an {@code UPDATE} and holds a write lock
     * on the {@code mcp_server} row until that transaction commits (on action
     * return). Blocking on the connect (a Docker spawn / HTTP {@code
     * initialize}, up to tens of seconds) would pin that row lock — and a
     * pooled DB connection — for the whole handshake, so the connector's own
     * {@link McpConnectionManager#status status} writes (and any concurrent
     * toggle) collide with it and time out against H2's 2 s lock timeout
     * ({@code LockTimeoutException}). Returning now lets the request
     * transaction commit in milliseconds. The admin UI polls the list while
     * any row is {@code CONNECTING} ({@code pollUntilStable} in
     * {@code mcp-servers.vue}), so the badge still ticks over to {@code
     * CONNECTED}/{@code ERROR} with no synchronous wait. (This reverts
     * JCLAW-288's synchronous-await contract, which was the cause of the lock
     * storm; boot-time readiness gating still awaits via
     * {@link McpConnectionManager#startAll}, where no request tx is in play.)
     *
     * <p>For the disable path we also reset the row's persisted status to
     * {@code DISCONNECTED}. The {@link McpConnectionManager#status} call
     * site reads {@code McpServer.status} (the DB column), but
     * {@link McpConnectionManager#stop} only mutates the in-memory map —
     * without this explicit reset, a server toggled off would keep
     * surfacing whatever status its last connect persisted.
     */
    public static void syncRuntime(McpServer row) {
        if (row.enabled) {
            McpConnectionManager.connect(row);
        } else {
            McpConnectionManager.stop(row.name);
            row.status = McpServer.Status.DISCONNECTED;
            row.lastError = null;
            row.lastDisconnectedAt = Instant.now();
            row.save();
        }
    }

    // ==================== test connection ====================

    /**
     * Result of a {@link #testConnection} run.
     *
     * @param success   true when the throwaway client successfully connected
     *                  and listed tools
     * @param toolCount number of tools the server advertised
     * @param message   human-readable message — on failure this is the
     *                  reason surfaced near the Test button
     * @param toolNames names of the tools the server advertised
     */
    public record TestResult(boolean success, int toolCount, String message,
                             List<String> toolNames) {}

    /**
     * Spin a throwaway {@link McpClient} with the row's config, run
     * initialize + tools/list, then close. Bounded by {@link #TEST_TIMEOUT};
     * does not interact with the live {@link McpConnectionManager} entry
     * for this server, so it's safe to test while a connection is active.
     */
    public static TestResult testConnection(McpServer row) {
        McpTransport transport;
        try {
            transport = buildTransport(row);
        } catch (RuntimeException e) {
            return new TestResult(false, 0, "Bad transport config: " + e.getMessage(), List.of());
        }
        var clientVersion = Play.configuration.getProperty("application.version", "0.0.0-dev");
        try (var client = new McpClient("test:" + row.name, transport, clientVersion, TEST_TIMEOUT)) {
            client.connect();
            var names = client.tools().stream().map(McpToolDef::name).sorted().toList();
            return new TestResult(true, names.size(),
                    "Connected. " + names.size() + " tool(s) advertised.", names);
        } catch (Exception e) {
            return new TestResult(false, 0, e.getMessage() != null ? e.getMessage() : e.toString(), List.of());
        }
    }

    // ==================== validation ====================

    /** Lower bound on what a sane {@code mcp_server.name} can hold so the
     *  prefixed allowlist {@code skill_name} ("mcp:&lt;name&gt;") fits into
     *  the existing column and stays readable in event-log messages. */
    public static final Pattern NAME_RE =
            Pattern.compile("^\\w[\\w-]{0,63}$");

    /** Throws {@link IllegalArgumentException} on any structural problem
     *  the form ought to have caught client-side. Server-side defense so
     *  an API client (or a future admin UI bug) can't insert garbage rows. */
    @SuppressWarnings("java:S6916") // Enum switch (not pattern switch): replacing `if` inside an arm with `when` would change exhaustiveness semantics
    public static void validate(McpServer row) {
        if (row.name == null || !NAME_RE.matcher(row.name).matches()) {
            throw new IllegalArgumentException("name must match " + NAME_RE.pattern());
        }
        if (row.transport == null) throw new IllegalArgumentException("transport required");
        if (row.configJson == null || row.configJson.isBlank()) {
            throw new IllegalArgumentException("configJson required");
        }
        var cfg = explodeConfigJson(row.transport, row.configJson);
        switch (row.transport) {
            case STDIO -> {
                if (cfg.command() == null || cfg.command().isBlank()) {
                    throw new IllegalArgumentException("STDIO transport requires command");
                }
            }
            case HTTP -> {
                if (cfg.url() == null || cfg.url().isBlank()) {
                    throw new IllegalArgumentException("HTTP transport requires url");
                }
                try { URI.create(cfg.url()); }
                catch (IllegalArgumentException _) {
                    throw new IllegalArgumentException("HTTP transport url is not a valid URI");
                }
            }
        }
    }

    // ==================== internals ====================

    private static McpTransport buildTransport(McpServer row) {
        var cfg = explodeConfigJson(row.transport, row.configJson);
        return switch (row.transport) {
            case STDIO -> {
                var argv = new ArrayList<String>();
                argv.add(cfg.command());
                argv.addAll(cfg.args());
                yield new McpStdioTransport(row.name, argv, cfg.env());
            }
            case HTTP -> new McpStreamableHttpTransport(row.name, URI.create(cfg.url()), cfg.headers());
        };
    }

    private static String optionalString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static List<String> optionalStringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        var list = new ArrayList<String>();
        for (JsonElement el : obj.getAsJsonArray(key)) list.add(el.getAsString());
        return List.copyOf(list);
    }

    private static Map<String, String> optionalStringMap(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) return Map.of();
        var out = new HashMap<String, String>();
        for (var entry : obj.getAsJsonObject(key).entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                out.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return out.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
