package mcp.transport;

import mcp.McpException;
import mcp.jsonrpc.JsonRpc;

import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;
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

    /**
     * As {@link #start(Consumer, Consumer)}, plus {@code onRequestFailed}, called with a request's
     * id when that request alone failed and the connection did not. A transport with no such
     * failure mode keeps this default, which never calls it.
     */
    default void start(Consumer<JsonRpc.Message> onMessage,
                       BiConsumer<Object, McpException> onRequestFailed,
                       Consumer<Throwable> onError) throws IOException {
        start(onMessage, onError);
    }

    void send(JsonRpc.Message msg) throws IOException;

    /**
     * As {@link #send(JsonRpc.Message)}, with headers for this one message. Only Streamable HTTP
     * has anywhere to put them (JCLAW-1443); every other transport keeps this default.
     */
    default void send(JsonRpc.Message msg, Map<String, String> requestHeaders) throws IOException {
        send(msg);
    }

    @Override
    void close();
}
