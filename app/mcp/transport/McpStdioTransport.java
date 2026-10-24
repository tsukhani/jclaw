package mcp.transport;

import com.google.errorprone.annotations.MustBeClosed;
import mcp.jsonrpc.JsonRpc;
import org.jspecify.annotations.Nullable;
import play.Logger;
import utils.SubprocessEnv;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * MCP stdio transport: spawn a subprocess and exchange line-delimited JSON-RPC
 * over its stdin/stdout (JCLAW-31).
 *
 * <p>Per the MCP stdio spec each message is a single UTF-8 JSON line terminated
 * by {@code \n}. Servers may write logs to stderr; we forward those to SLF4J
 * at DEBUG so they're visible on demand without drowning the application log
 * (many servers print multi-line boot banners on every connect).
 *
 * <p><b>Threading.</b> Two virtual threads per connection: one reads stdout
 * and dispatches every received line as a {@link JsonRpc.Message}; one drains
 * stderr to the log. {@link #send} is guarded by a {@link ReentrantLock} (not
 * {@code synchronized}) so a virtual-thread writer blocked on the subprocess
 * pipe releases its carrier instead of pinning it.
 */
public final class McpStdioTransport implements McpTransport {

    /** A stderr line is cut here, so a server writing without newlines cannot grow the drain's buffer. */
    private static final int STDERR_LINE_MAX_BYTES = 16 * 1024;

    private final List<String> command;
    private final Map<String, String> env;
    private final String name;

    private @Nullable Process process;
    private @Nullable BufferedWriter stdin;
    private @Nullable InputStream stdout;
    private Consumer<JsonRpc.Message> onMessage = _ -> {};
    private Consumer<Throwable> onError = _ -> {};
    private volatile boolean closed;
    private final ReentrantLock sendLock = new ReentrantLock();

    @MustBeClosed
    public McpStdioTransport(String name, List<String> command, Map<String, String> env) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command required");
        }
        this.name = name;
        this.command = List.copyOf(command);
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    @Override
    public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) throws IOException {
        this.onMessage = onMessage;
        this.onError = onError;
        var pb = new ProcessBuilder(command);
        // JCLAW-779: strip inherited host secrets (PLAY_SECRET, AWS_/ANTHROPIC_/…
        // keys) — the child gets the secret-filtered host env with the
        // operator-supplied MCP config env layered on top (config wins).
        SubprocessEnv.apply(pb, env);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        var out = process.getInputStream();
        stdout = out;
        var reader = new MessageReader(out);
        var stderr = process.getErrorStream();
        Thread.ofVirtual().name("mcp-stdio-" + name).start(() -> readLoop(reader));
        Thread.ofVirtual().name("mcp-stderr-" + name).start(() -> drainStderr(stderr));
    }

    @Override
    public void send(JsonRpc.Message msg) throws IOException {
        sendLock.lock();
        try {
            var out = stdin;
            if (closed) throw new IOException("transport closed");
            if (out == null) throw new IOException("transport not started");
            out.write(JsonRpc.encode(msg));
            out.write('\n');
            out.flush();
        } finally {
            sendLock.unlock();
        }
    }

    @Override
    public void cancel(Object requestId, String reason) {
        try {
            send(new JsonRpc.Notification("notifications/cancelled", Map.of("requestId", requestId, "reason", reason)));
        } catch (IOException e) {
            Logger.warn("[mcp:%s] failed to cancel request %s: %s", name, requestId, e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        // Close stdin first so the subprocess sees EOF on its input and a
        // well-behaved server can exit cleanly. Then destroy the process BEFORE
        // closing stdout (JCLAW-439): the readLoop is blocked in a
        // non-interruptible read that only the subprocess's end of the pipe
        // closing returns; closing stdout first stalled ShutdownJob's full 15s
        // on any MCP server that doesn't exit on stdin EOF.
        try { if (stdin != null) stdin.close(); } catch (IOException _) { /* best effort */ }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        try { if (stdout != null) stdout.close(); } catch (IOException _) { /* best effort */ }
    }

    // S1141: outer try catches IOException (terminates the loop); inner try
    // catches RuntimeException (per-line parse failure, loop continues). They
    // are not collapsible — one bad line shouldn't kill the stdio reader.
    @SuppressWarnings("java:S1141")
    private void readLoop(MessageReader in) {
        try {
            String line;
            while (!closed && (line = in.next(McpTransport.MAX_MESSAGE_BYTES, McpTransport.oversizeMessage(name))) != null) {
                if (line.isBlank()) continue;
                try {
                    onMessage.accept(JsonRpc.decode(line));
                } catch (RuntimeException e) {
                    Logger.warn("[mcp:%s] discarding unparseable stdio line: %s", name, e.getMessage());
                }
            }
            if (!closed) onError.accept(new IOException("stdio EOF"));
        } catch (IOException e) {
            if (!closed) onError.accept(e);
        }
    }

    private void drainStderr(InputStream stderr) {
        try (stderr) {
            var reader = new MessageReader(stderr);
            String line;
            while ((line = reader.next(STDERR_LINE_MAX_BYTES, null)) != null) {
                // DEBUG (not INFO): MCP servers commonly use stderr for boot
                // banners, framework copy, and operational chatter (FastMCP
                // prints a 16-line ASCII art on every connect). Default
                // visibility would drown the application log; flip on DEBUG
                // when diagnosing a misbehaving server.
                Logger.debug("[mcp:%s:stderr] %s", name, line);
            }
        } catch (IOException _) { /* process closed */ }
    }

    /**
     * Splits a stream into {@code \n}-framed lines over its bytes, so a line past a limit is found
     * before the rest of it is read. A {@code \r} before the {@code \n} is dropped and not counted.
     */
    private static final class MessageReader {
        private final InputStream in;
        private final byte[] buf = new byte[64 * 1024];
        private int pos;
        private int end;

        MessageReader(InputStream in) {
            this.in = in;
        }

        /**
         * The next line, or null at end of stream. A line over {@code max} bytes throws
         * {@code refusal} as an {@link IOException}, or, when {@code refusal} is null, is cut at {@code max}.
         */
        @Nullable String next(int max, @Nullable String refusal) throws IOException {
            var line = new LineBuffer();
            while (true) {
                if (pos == end) {
                    int n = in.read(buf);
                    if (n < 0) return line.size() == 0 ? null : line.text(max, refusal);
                    pos = 0;
                    end = n;
                }
                int newline = pos;
                while (newline < end && buf[newline] != '\n') newline++;
                int take = newline - pos;
                int room = max + 1 - line.size();
                if (take > room) {
                    if (refusal != null) throw new IOException(refusal);
                    take = Math.max(room, 0);
                }
                line.write(buf, pos, take);
                if (newline < end) {
                    pos = newline + 1;
                    return line.text(max, refusal);
                }
                pos = end;
            }
        }
    }

    /** Holds one line's bytes; reads them in place rather than copying a line of up to 16 MiB. */
    private static final class LineBuffer extends ByteArrayOutputStream {
        String text(int max, @Nullable String refusal) throws IOException {
            int length = count > 0 && buf[count - 1] == '\r' ? count - 1 : count;
            if (length > max) {
                if (refusal != null) throw new IOException(refusal);
                length = max;
            }
            return new String(buf, 0, length, StandardCharsets.UTF_8);
        }
    }
}
