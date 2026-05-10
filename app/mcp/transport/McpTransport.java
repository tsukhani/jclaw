package mcp.transport;

import mcp.jsonrpc.JsonRpc;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Bidirectional MCP transport channel (JCLAW-31).
 *
 * <p>Three implementations exist: {@link McpStdioTransport} (subprocess
 * stdin/stdout), {@code McpStreamableHttpTransport} (HTTP POST with optional
 * SSE upgrade per the MCP 2025-06-18 spec). The shared contract:
 *
 * <ol>
 *   <li>{@link #start} begins the read loop and registers callbacks for
 *       inbound messages and unrecoverable errors. Must be called before
 *       any {@link #send}.</li>
 *   <li>{@link #send} writes a single JSON-RPC message to the wire. May
 *       block on I/O. Implementations MUST be safe to call concurrently.</li>
 *   <li>{@link #close} stops the read loop and releases resources. Idempotent.</li>
 * </ol>
 *
 * <p>Implementations deliver every inbound message — responses, notifications,
 * AND server-initiated requests — through {@code onMessage}. The
 * {@link mcp.McpClient} above does the request/response correlation and
 * notification dispatch.
 */
public interface McpTransport extends AutoCloseable {

    void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) throws IOException;

    void send(JsonRpc.Message msg) throws IOException;

    @Override
    void close();
}
