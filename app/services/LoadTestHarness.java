package services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Embedded OpenAI-compatible SSE server for load testing. Serves
 * deterministic streaming responses so latency measurements isolate
 * JClaw's own overhead from real-provider variance.
 *
 * <p>Binds to loopback only (127.0.0.1) on a configured port. Started
 * on demand by {@link LoadTestRunner}; safe to leave running (no auth,
 * but not reachable off-host). Started and stopped by the loadtest endpoint.
 *
 * <p><b>Architecture (JCLAW-201 follow-up):</b> request handlers run on
 * virtual threads, matching the production chat-stream path end-to-end so
 * any future regression that's NOT in the LLM provider itself surfaces
 * here too. Inter-chunk cadence comes from a small shared
 * {@link ScheduledExecutorService} on platform threads — each chunk write
 * is scheduled at an absolute deadline, the handler VT blocks on a
 * {@link CompletableFuture}'s untimed park (which JDK-8373224 doesn't
 * affect), and the scheduler thread fires the write when the deadline
 * arrives. Net effect: no {@code Thread.sleep} loops on VTs (the trigger
 * pattern for JDK-8373224, which can stretch tail sleeps to ~5 s when
 * many concurrent VTs are in the FJP timer queue) and the test harness
 * exercises the same VT scheduling JClaw uses for real provider calls.
 */
public final class LoadTestHarness {

    /**
     * Scenario shape streamed by the mock endpoint.
     *
     * <p>When {@code simulatedToolCalls > 0}, the first response in a round
     * emits that many {@code loadtest_sleep} tool_calls (each with
     * {@code ms=toolSleepMs}) instead of content. The follow-up request
     * carrying tool results triggers a normal content stream. This drives
     * the agent's parallel tool-execution path end-to-end.
     */
    public record Scenario(int ttftMs, int tokensPerSecond, int responseTokens,
                            int simulatedToolCalls, int toolSleepMs) {
        public static Scenario defaults() { return new Scenario(100, 50, 40, 0, 200); }

        /** Backwards-compat overload — content-only scenario, no tool calls. */
        public Scenario(int ttftMs, int tokensPerSecond, int responseTokens) {
            this(ttftMs, tokensPerSecond, responseTokens, 0, 200);
        }
    }

    private static final Object lock = new Object();
    // AtomicReference rather than `volatile HttpServer` so the type signature
    // unambiguously says "atomic reference, not atomic state" — Sonar's
    // S3077 fires on volatile non-primitive fields because volatile only
    // protects the reference read/write, not operations on the object
    // itself, and a future maintainer reading `volatile HttpServer` could
    // plausibly assume thread-safe access to the server's internal state.
    // All writes still happen inside synchronized(lock); the atomic wrapper
    // is the fast-path for unsynchronized readers (isRunning(), port(),
    // and the scheduler.get().schedule() call inside handle()).
    private static final java.util.concurrent.atomic.AtomicReference<HttpServer> server =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<ScheduledExecutorService> scheduler =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile int port;
    private static volatile Scenario scenario = Scenario.defaults();

    private LoadTestHarness() {}

    public static int port() { return port; }
    public static boolean isRunning() { return server.get() != null; }
    public static void setScenario(Scenario s) { scenario = s; }
    public static Scenario scenario() { return scenario; }

    public static int start(int requestedPort) throws IOException {
        synchronized (lock) {
            if (server.get() != null) return port;
            try {
                return bindAndStart(requestedPort);
            } catch (java.net.BindException e) {
                // Port may be held by a stale server from a previous run or
                // class-reload cycle. Stop, wait for OS socket release, retry.
                stop();
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return bindAndStart(requestedPort);
            }
        }
    }

    private static int bindAndStart(int requestedPort) throws IOException {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        s.createContext("/v1/chat/completions", LoadTestHarness::handle);
        // Handlers run on VTs to mirror the production agent-stream path —
        // this lets a future codebase-side regression that affects VT
        // scheduling surface in mock loadtests as well, instead of being
        // hidden by a platform-thread mock harness. Per-chunk timing comes
        // from the shared scheduler below, NOT from Thread.sleep on the VT.
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // Tiny platform-thread pool whose only job is to fire scheduled
        // chunk writes at their absolute deadlines. 2 threads is plenty
        // even at c=50 × 150 tps = 7500 writes/sec because each write is
        // a few µs of socket work; the chunked-write order across streams
        // is independent so two scheduler threads can serve them in
        // parallel without any cross-stream ordering risk (within a stream
        // consecutive chunk deadlines are always ≥ ~3 ms apart thanks to
        // the inter-token jitter, so even if two scheduler threads picked
        // them up, the earlier write has long completed before the later
        // one fires).
        scheduler.set(Executors.newScheduledThreadPool(2, r -> {
            var t = new Thread(r, "loadtest-mock-scheduler");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server.set(s);
        port = s.getAddress().getPort();
        return port;
    }

    public static void stop() {
        synchronized (lock) {
            var s = server.getAndSet(null);
            if (s != null) {
                s.stop(0);
                port = 0;
            }
            var sch = scheduler.getAndSet(null);
            if (sch != null) {
                sch.shutdownNow();
            }
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            var scn = scenario;
            boolean continuation = isToolResultContinuation(body);

            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                if (!continuation && scn.simulatedToolCalls() > 0) {
                    streamToolCalls(out, scn);
                } else {
                    streamResponse(out, scn);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns true when the last message in the request has {@code role=tool},
     * meaning the agent is carrying tool results back to us for a follow-up.
     * The mock responds with content tokens in that case instead of another
     * tool_calls round, preventing infinite loops.
     */
    private static boolean isToolResultContinuation(byte[] body) {
        try {
            var json = com.google.gson.JsonParser.parseString(
                    new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("messages")) return false;
            var msgs = json.getAsJsonArray("messages");
            if (msgs.isEmpty()) return false;
            var last = msgs.get(msgs.size() - 1).getAsJsonObject();
            return last.has("role") && "tool".equals(last.get("role").getAsString());
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Wait for the scheduled stream to complete. Uses {@code get()} (untimed),
     * which parks on a {@link java.util.concurrent.locks.LockSupport#park}
     * — NOT the {@code parkNanos} timer-queue path that JDK-8373224 affects.
     * Unwraps any {@link IOException} that the scheduled write produced.
     */
    private static void awaitDone(CompletableFuture<Void> done)
            throws IOException, InterruptedException {
        try {
            done.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static void streamToolCalls(OutputStream out, Scenario scn)
            throws IOException, InterruptedException {
        // tool_calls path is one-shot: at TTFT, write all simulated tool calls
        // back-to-back and the terminator. No per-chunk cadence because the
        // agent doesn't see "streaming" tool calls — it gets them as one
        // round. The scheduler still drives the wait so the handler VT
        // stays off Thread.sleep.
        var done = new CompletableFuture<Void>();
        scheduler.get().schedule(() -> {
            try {
                for (int i = 0; i < scn.simulatedToolCalls(); i++) {
                    var callId = "call-mock-" + i;
                    // Arguments string is a JSON document embedded *inside* the outer
                    // JSON chunk, so its quotes need double-escaping.
                    var argsJson = "{\\\"ms\\\":" + scn.toolSleepMs() + "}";
                    var chunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                            + "[{\"index\":" + i + ",\"id\":\"" + callId + "\","
                            + "\"type\":\"function\",\"function\":{\"name\":\"loadtest_sleep\","
                            + "\"arguments\":\"" + argsJson + "\"}}]}}]}\n\n";
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                var finalChunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                        + "data: [DONE]\n\n";
                out.write(finalChunk.getBytes(StandardCharsets.UTF_8));
                out.flush();
                done.complete(null);
            } catch (IOException e) {
                done.completeExceptionally(e);
            }
        }, Math.max(0, scn.ttftMs()), TimeUnit.MILLISECONDS);
        awaitDone(done);
    }

    private static void streamResponse(OutputStream out, Scenario scn)
            throws IOException, InterruptedException {
        int delayMs = scn.tokensPerSecond() > 0
                ? Math.max(1, 1000 / scn.tokensPerSecond())
                : 20;
        int n = scn.responseTokens();
        var done = new CompletableFuture<Void>();
        // Per-call dedicated lock for serializing the per-stream write+flush
        // pairs below. Replaces the prior `synchronized(out)` which Sonar
        // flags as S2445 (synchronizing on a method parameter is a frequent
        // source of bugs — caller could share the object, reentry could
        // surprise, parameter could be null). Allocating a fresh Object per
        // streamResponse call gives us per-stream serialization (the
        // closure captures writeLock so all scheduled tasks for THIS
        // stream share one mutex) without depending on the parameter's
        // identity. Different streams get different writeLock instances
        // and continue to write in parallel.
        final var writeLock = new Object();
        // Schedule each chunk at an absolute deadline. TTFT is honored exactly
        // for the first chunk (so ttftDelayIsHonored() stays correct).
        // Subsequent chunks are spaced by jittered cadence (±50% around delayMs)
        // — preserves mean rate while modeling real-LLM network jitter. The
        // +1 floor ensures consecutive deadlines are always strictly increasing
        // even when delayMs=1 (tps=1000+), without which two chunks could be
        // scheduled at the same instant and race each other on different
        // scheduler threads. (Per-stream serialize via synchronized(writeLock)
        // below also guards against the race; the floor is belt-and-suspenders.)
        var rnd = ThreadLocalRandom.current();
        long cumDelayMs = Math.max(0, scn.ttftMs());
        for (int i = 0; i < n; i++) {
            int idx = i;
            boolean isLast = (i == n - 1);
            scheduler.get().schedule(() -> {
                try {
                    var tok = (idx == 0 ? "Hello" : " tok" + idx);
                    var chunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                            + tok + "\"}}]}\n\n";
                    // Serialize per-stream writes — out is the per-request
                    // ChunkedOutputStream from HttpServer, and a write+flush
                    // pair is the unit of HTTP chunk encoding. Without this
                    // sync, two concurrently-firing scheduled tasks for the
                    // same stream interleave bytes inside chunk-size headers
                    // and the receiver throws "Illegal character in chunk
                    // size: N". writeLock is per-call (allocated above), so
                    // different streams' writes still proceed in parallel.
                    synchronized (writeLock) {
                        out.write(chunk.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        if (isLast) {
                            var finalChunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":"
                                    + n + ",\"total_tokens\":"
                                    + (n + 10) + "}}\n\n"
                                    + "data: [DONE]\n\n";
                            out.write(finalChunk.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            done.complete(null);
                        }
                    }
                } catch (IOException e) {
                    done.completeExceptionally(e);
                }
            }, cumDelayMs, TimeUnit.MILLISECONDS);
            // 1-ms floor: even when delayMs=1 (tps=1000), consecutive deadlines
            // strictly increase. Without the floor, integer division gives
            // jitter=0 for delayMs=1 and every chunk lands at the same instant.
            int jitterMs = Math.max(1, (delayMs / 2) + rnd.nextInt(Math.max(1, delayMs)));
            cumDelayMs += jitterMs;
        }
        awaitDone(done);
    }
}
