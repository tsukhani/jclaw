package jclaw.mcp.server;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Wire-level glue. Reads {@link JsonRpc.Message}s from one end, writes
 * them back the other. The MCP spec describes stdio + HTTP transports;
 * v1 ships stdio (see {@link StdioTransport}) and leaves HTTP/SSE for
 * a follow-up — the interface exists so the future transport plugs in
 * without touching {@link McpServer}.
 */
public interface Transport extends AutoCloseable {

    /** Begin reading. Each decoded message is handed to {@code onMessage};
     *  unrecoverable failures (broken pipe, bad framing) go to {@code onError}.
     *  Implementations should run the read loop on their own thread so
     *  the caller's thread remains free to dispatch responses. */
    void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) throws IOException;

    /** Write a single message. Implementations must serialize concurrent
     *  writes so partial frames can't interleave on the wire. */
    void send(JsonRpc.Message msg) throws IOException;

    /** Idempotent — stops the read loop and releases any underlying
     *  resources. Declared without {@code throws} so try-with-resources
     *  on a {@link Transport} doesn't force callers to catch
     *  {@link Exception}. */
    @Override
    void close();
}
