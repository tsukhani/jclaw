package jclaw.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP wire-protocol handler.
 *
 * <p>Implements the three methods every MCP host calls during a
 * session:
 *
 * <ul>
 *   <li>{@code initialize} — protocol handshake; returns the server's
 *       advertised capabilities. JClaw advertises {@code tools} only
 *       — no resources, no prompts, no sampling.</li>
 *   <li>{@code tools/list} — the precomputed catalog from
 *       {@link ToolGenerator}. Returned in a single response; we don't
 *       paginate because JClaw's spec is hundreds of operations at
 *       most.</li>
 *   <li>{@code tools/call} — delegated to {@link ToolInvoker}.</li>
 * </ul>
 *
 * <p>Unknown methods reply with the standard JSON-RPC
 * {@code method_not_found} (-32601) so a host running a newer MCP spec
 * than this server understands gets a clear refusal rather than silence.
 *
 * <p><b>Threading.</b> {@code tools/call} can take seconds (it issues
 * an outbound HTTP request) so each call runs on a virtual thread.
 * That keeps the protocol read loop responsive to e.g. a host that
 * pipelines {@code tools/list} and {@code tools/call} on the same
 * connection.
 */
public final class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    /** Protocol version advertised in the {@code initialize} reply.
     *  Pinned because the MCP spec moves fast — when the next stable
     *  revision lands, bump this and re-test. */
    static final String PROTOCOL_VERSION = "2024-11-05";
    static final String SERVER_NAME = "jclaw-mcp-server";
    static final String SERVER_VERSION = "0.1.0";

    private final Transport transport;
    private final List<ToolDefinition> tools;
    private final Map<String, ToolDefinition> toolsByName;
    private final ToolInvoker invoker;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public McpServer(Transport transport, List<ToolDefinition> tools, ToolInvoker invoker) {
        this.transport = transport;
        this.tools = List.copyOf(tools);
        this.toolsByName = new java.util.HashMap<>();
        for (var t : this.tools) toolsByName.put(t.name(), t);
        this.invoker = invoker;
    }

    /** Wire the transport and block until {@link Transport} signals EOF
     *  (client disconnect) or {@link #stop()} is called. Returns
     *  normally on clean shutdown. */
    public void run() throws IOException, InterruptedException {
        transport.start(this::handleMessage, this::handleTransportError);
        shutdown.await();
        transport.close();
    }

    void stop() { shutdown.countDown(); }

    private void handleTransportError(Throwable t) {
        log.warn("Transport error: {}", t.getMessage());
        stop();
    }

    private void handleMessage(JsonRpc.Message msg) {
        if (msg instanceof JsonRpc.Request req) {
            // tools/call hits the network. Run it off the read thread so
            // a slow JClaw response can't block notifications or the
            // host's next request.
            if ("tools/call".equals(req.method())) {
                Thread.ofVirtual().name("mcp-tools-call").start(() -> respondTo(req));
            } else {
                respondTo(req);
            }
        } else if (msg instanceof JsonRpc.Notification note) {
            handleNotification(note);
        }
        // Responses arrive on the host->server channel only if WE
        // initiate requests (we don't yet). Ignore them.
    }

    private void respondTo(JsonRpc.Request req) {
        JsonRpc.Response response;
        try {
            response = switch (req.method()) {
                case "initialize" -> handleInitialize(req);
                case "tools/list" -> handleListTools(req);
                case "tools/call" -> handleCallTool(req);
                case "ping" -> new JsonRpc.Response(req.id(), new JsonObject(), null);
                default -> new JsonRpc.Response(req.id(), null,
                        new JsonRpc.Error(JsonRpc.ERROR_METHOD_NOT_FOUND,
                                "Method not found: " + req.method()));
            };
        }
        catch (RuntimeException e) {
            log.warn("Error handling {} request: {}", req.method(), e.getMessage());
            response = new JsonRpc.Response(req.id(), null,
                    new JsonRpc.Error(JsonRpc.ERROR_INTERNAL, e.getMessage()));
        }
        try {
            transport.send(response);
        }
        catch (IOException e) {
            log.warn("Failed to send response to {}: {}", req.method(), e.getMessage());
        }
    }

    private void handleNotification(JsonRpc.Notification note) {
        // The host sends "notifications/initialized" after consuming
        // our initialize reply. Nothing JClaw needs to do besides
        // mark itself ready for tool calls. Anything else is logged
        // and dropped — notifications never reply.
        if ("notifications/initialized".equals(note.method())) {
            log.info("Initialization complete; advertising {} tool(s)", tools.size());
            initialized.set(true);
            return;
        }
        log.debug("Ignoring notification: {}", note.method());
    }

    private JsonRpc.Response handleInitialize(JsonRpc.Request req) {
        var result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        var caps = new JsonObject();
        var toolsCap = new JsonObject();
        // listChanged stays false — JClaw's spec doesn't reload at
        // runtime. If we ever want hot-reload we'd send a
        // tools/list_changed notification when the spec refreshes.
        toolsCap.addProperty("listChanged", false);
        caps.add("tools", toolsCap);
        result.add("capabilities", caps);
        var info = new JsonObject();
        info.addProperty("name", SERVER_NAME);
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        return new JsonRpc.Response(req.id(), result, null);
    }

    private JsonRpc.Response handleListTools(JsonRpc.Request req) {
        var result = new JsonObject();
        var arr = new JsonArray();
        for (var t : tools) {
            var entry = new JsonObject();
            entry.addProperty("name", t.name());
            entry.addProperty("description", t.description());
            entry.add("inputSchema", t.inputSchema());
            arr.add(entry);
        }
        result.add("tools", arr);
        return new JsonRpc.Response(req.id(), result, null);
    }

    private JsonRpc.Response handleCallTool(JsonRpc.Request req) {
        if (req.params() == null || !req.params().isJsonObject()) {
            return new JsonRpc.Response(req.id(), null,
                    new JsonRpc.Error(JsonRpc.ERROR_INVALID_PARAMS, "params must be an object"));
        }
        var params = req.params().getAsJsonObject();
        if (!params.has("name") || params.get("name").isJsonNull()) {
            return new JsonRpc.Response(req.id(), null,
                    new JsonRpc.Error(JsonRpc.ERROR_INVALID_PARAMS, "params.name required"));
        }
        var name = params.get("name").getAsString();
        var tool = toolsByName.get(name);
        if (tool == null) {
            return new JsonRpc.Response(req.id(), null,
                    new JsonRpc.Error(JsonRpc.ERROR_METHOD_NOT_FOUND,
                            "Unknown tool: " + name));
        }
        var args = ToolInvoker.extractArguments(req.params());
        var result = invoker.invoke(tool, args);
        return new JsonRpc.Response(req.id(), result, null);
    }
}
