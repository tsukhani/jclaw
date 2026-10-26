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
 * <p>Two implementations exist: {@link McpStdioTransport} (subprocess
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

    /**
     * The most of one inbound message a transport holds, fixed by the operator's choice (JF-29) with no
     * override. A larger one is refused before the rest is read: stdio, which cannot tell which call a
     * line answers before parsing it, drops the connection; HTTP fails the one request it answers.
     */
    int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    /** The refusal for a message over {@link #MAX_MESSAGE_BYTES}; never quotes the message. */
    static String oversizeMessage(String server) {
        return "MCP server '%s' sent a message over the %d MiB limit".formatted(server, MAX_MESSAGE_BYTES / (1024 * 1024));
    }

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

    /** Whether {@link #send(JsonRpc.Message, Map)} puts its headers on the wire. */
    default boolean carriesHeaders() {
        return false;
    }

    /**
     * Give up on request {@code requestId}, which its caller stopped waiting for (JCLAW-1444):
     * stdio tells the server with {@code notifications/cancelled}, HTTP cancels the request's
     * call. Never fails the connection; the default does nothing.
     */
    default void cancel(Object requestId, String reason) {}

    @Override
    void close();
}
