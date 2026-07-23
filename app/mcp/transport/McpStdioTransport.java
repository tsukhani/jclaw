package mcp.transport;

import mcp.jsonrpc.JsonRpc;
import play.Logger;
import utils.SubprocessEnv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private final ReentrantLock sendLock = new ReentrantLock();

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
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        readerThread = Thread.ofVirtual().name("mcp-stdio-" + name).start(this::readLoop);
        errReaderThread = Thread.ofVirtual().name("mcp-stderr-" + name).start(() -> drainStderr(stderr));
    }

    @Override
    public void send(JsonRpc.Message msg) throws IOException {
        sendLock.lock();
        try {
            if (closed) throw new IOException("transport closed");
            stdin.write(JsonRpc.encode(msg));
            stdin.write('\n');
            stdin.flush();
        } finally {
            sendLock.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        // Close stdin first so the subprocess sees EOF on its input and a
        // well-behaved server can exit cleanly. Then destroy the process BEFORE
        // closing stdout (JCLAW-439): the readLoop holds the stdout BufferedReader's
        // monitor while blocked in a non-interruptible readLine(), so stdout.close()
        // — synchronized on that same monitor — cannot acquire it until the read
        // returns. Destroying the process closes the subprocess's stdout, which
        // makes the blocked readLine() return EOF and release the monitor; only
        // then is stdout.close() lock-free. Closing stdout first deadlocked
        // graceful shutdown for ShutdownJob's full 15s on any MCP server that
        // doesn't exit on stdin EOF.
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
        if (readerThread != null) readerThread.interrupt();
        if (errReaderThread != null) errReaderThread.interrupt();
        try { if (stdout != null) stdout.close(); } catch (IOException _) { /* best effort */ }
    }

    // S1141: outer try catches IOException (terminates the loop); inner try
    // catches RuntimeException (per-line parse failure, loop continues). They
    // are not collapsible — one bad line shouldn't kill the stdio reader.
    @SuppressWarnings("java:S1141")
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
        } catch (IOException _) { /* process closed */ }
    }
}
