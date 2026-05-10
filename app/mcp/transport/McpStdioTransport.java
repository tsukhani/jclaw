package mcp.transport;

import mcp.jsonrpc.JsonRpc;
import play.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MCP stdio transport: spawn a subprocess and exchange line-delimited JSON-RPC
 * over its stdin/stdout (JCLAW-31).
 *
 * <p>Per the MCP stdio spec each message is a single UTF-8 JSON line terminated
 * by {@code \n}. Servers may write logs to stderr; we forward those to SLF4J
 * at INFO so they're visible without polluting the protocol stream.
 *
 * <p><b>Threading.</b> Two virtual threads per connection: one reads stdout
 * and dispatches every received line as a {@link JsonRpc.Message}; one drains
 * stderr to the log. {@link #send} is synchronized so concurrent writes can't
 * interleave bytes within a single JSON line.
 */
public final class McpStdioTransport implements McpTransport {

    private final List<String> command;
    private final Map<String, String> env;
    private final String name;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread readerThread;
    private Thread errReaderThread;
    private Consumer<JsonRpc.Message> onMessage;
    private Consumer<Throwable> onError;
    private volatile boolean closed;

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
        pb.environment().putAll(env);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        readerThread = Thread.ofVirtual().name("mcp-stdio-" + name).start(this::readLoop);
        errReaderThread = Thread.ofVirtual().name("mcp-stderr-" + name).start(() -> drainStderr(stderr));
    }

    @Override
    public synchronized void send(JsonRpc.Message msg) throws IOException {
        if (closed) throw new IOException("transport closed");
        stdin.write(JsonRpc.encode(msg));
        stdin.write('\n');
        stdin.flush();
    }

    @Override
    public void close() {
        closed = true;
        try { if (stdin != null) stdin.close(); } catch (IOException ignored) { /* best effort */ }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if (readerThread != null) readerThread.interrupt();
        if (errReaderThread != null) errReaderThread.interrupt();
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = stdout.readLine()) != null) {
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

    private void drainStderr(BufferedReader stderr) {
        try (stderr) {
            String line;
            while ((line = stderr.readLine()) != null) {
                // DEBUG (not INFO): MCP servers commonly use stderr for boot
                // banners, framework copy, and operational chatter (FastMCP
                // prints a 16-line ASCII art on every connect). Default
                // visibility would drown the application log; flip on DEBUG
                // when diagnosing a misbehaving server.
                Logger.debug("[mcp:%s:stderr] %s", name, line);
            }
        } catch (IOException ignored) { /* process closed */ }
    }
}
