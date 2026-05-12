package jclaw.mcp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * MCP stdio transport, server side: read line-delimited JSON-RPC from
 * stdin, write to stdout.
 *
 * <p>Mirrors {@code app/mcp/transport/McpStdioTransport.java} on the
 * client side. The JSON-RPC spec doesn't mandate a framing — the MCP
 * stdio profile pins it to one JSON object per line, terminated by
 * {@code \n}. That's what {@code readLine} produces on the read side
 * and what {@link #send} writes on the write side.
 *
 * <p><b>Logging discipline.</b> Stdout is the protocol channel — a
 * stray {@code System.out.println} would corrupt the wire and the
 * host would close the pipe. All logs go through SLF4J, which
 * {@code slf4j-simple}'s default configuration routes to stderr. The
 * read loop runs on a virtual thread so a blocking {@code readLine}
 * unmounts cleanly rather than parking a platform-thread carrier.
 */
public final class StdioTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final InputStream in;
    private final OutputStream out;
    private final BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean closed;

    public StdioTransport() {
        this(System.in, System.out);
    }

    /** Visible for tests so the loopback wiring can swap in piped streams. */
    StdioTransport(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    @Override
    public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
        readerThread = Thread.ofVirtual().name("mcp-stdio-reader").start(() -> readLoop(onMessage, onError));
    }

    @Override
    public synchronized void send(JsonRpc.Message msg) throws IOException {
        if (closed) throw new IOException("transport closed");
        writer.write(JsonRpc.encode(msg));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void close() {
        closed = true;
        try { writer.close(); } catch (IOException ignored) { /* best effort */ }
        if (readerThread != null) readerThread.interrupt();
    }

    private void readLoop(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    onMessage.accept(JsonRpc.decode(line));
                }
                catch (RuntimeException e) {
                    // Unparseable line on stdin shouldn't kill the
                    // server — log and continue. The host gets a
                    // protocol-level error on the next message it
                    // sends if a request-id was expected.
                    log.warn("Discarding unparseable JSON-RPC line: {}", e.getMessage());
                }
            }
            if (!closed) onError.accept(new IOException("stdin EOF"));
        }
        catch (IOException e) {
            if (!closed) onError.accept(e);
        }
    }
}
