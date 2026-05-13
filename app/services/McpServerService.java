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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Snapshot of one MCP server row as the admin UI sees it: persisted
     * fields plus current runtime state. The flat transport-specific
     * fields are exploded out of {@code configJson} so forms can bind
     * directly without re-parsing.
     */
    public record View(Long id, String name, boolean enabled, String transport,
                       String command, List<String> args, Map<String, String> env,
                       String url, Map<String, String> headers,
                       String status, String lastError,
                       String lastConnectedAt, String lastDisconnectedAt,
                       int toolCount,
                       String createdAt, String updatedAt) {

        public static View of(McpServer row) {
            var cfg = explodeConfigJson(row.transport, row.configJson);
            int tools = McpConnectionManager.tools(row.name).size();
            // Live status preferred over the persisted column. The in-memory
            // state is updated synchronously by connect()/stop() and the
            // watchdog; the DB column lags behind by however long the
            // background persistStatus call takes to commit. For rows with
            // no in-memory entry (disabled or never connected), the manager
            // returns DISCONNECTED naturally. The persisted column remains
            // useful for post-restart display before the connector reports.
            var liveStatus = McpConnectionManager.status(row.name).name();
            return new View(
                    row.id, row.name, row.enabled, row.transport.name(),
                    cfg.command, cfg.args, cfg.env,
                    cfg.url, cfg.headers,
                    liveStatus,
                    row.lastError,
                    row.lastConnectedAt != null ? row.lastConnectedAt.toString() : null,
                    row.lastDisconnectedAt != null ? row.lastDisconnectedAt.toString() : null,
                    tools,
                    row.createdAt != null ? row.createdAt.toString() : null,
                    row.updatedAt != null ? row.updatedAt.toString() : null);
        }
    }

    public static List<View> listAll() {
        var rows = McpServer.<McpServer>find("ORDER BY name ASC").<McpServer>fetch();
        return rows.stream().map(View::of).toList();
    }

    // ==================== translation ====================

    /** Parsed form fields ready for a controller's response or for
     *  rebuilding the {@code McpServer.configJson} on a save. */
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
            case STDIO -> {
                if (body.has("command") && !body.get("command").isJsonNull()) {
                    cfg.addProperty("command", body.get("command").getAsString());
                }
                if (body.has("args") && body.get("args").isJsonArray()) {
                    cfg.add("args", body.get("args").getAsJsonArray());
                }
                if (body.has("env") && body.get("env").isJsonObject()) {
                    cfg.add("env", body.get("env").getAsJsonObject());
                }
            }
            case HTTP -> {
                if (body.has("url") && !body.get("url").isJsonNull()) {
                    cfg.addProperty("url", body.get("url").getAsString());
                }
                if (body.has("headers") && body.get("headers").isJsonObject()) {
                    cfg.add("headers", body.get("headers").getAsJsonObject());
                }
            }
        }
        return cfg.toString();
    }

    /** Inverse of {@link #composeConfigJson}: parse the row's stored JSON
     *  into form-ready fields so the admin UI can pre-populate inputs. */
    public static TransportConfig explodeConfigJson(McpServer.Transport transport, String configJson) {
        if (configJson == null || configJson.isBlank()) return TransportConfig.empty();
        JsonObject cfg;
        try {
            cfg = JsonParser.parseString(configJson).getAsJsonObject();
        } catch (RuntimeException e) {
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
            row.lastDisconnectedAt = java.time.Instant.now();
            row.save();
        }
    }

    // ==================== test connection ====================

    /** Result of a {@link #testConnection} run. {@code success=false}
     *  carries a {@code message} the UI surfaces near the Test button. */
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
        var clientVersion = play.Play.configuration.getProperty("application.version", "0.0.0-dev");
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
    public static final java.util.regex.Pattern NAME_RE =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_][a-zA-Z0-9_-]{0,63}$");

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
                catch (IllegalArgumentException e) {
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
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
