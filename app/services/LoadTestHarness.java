package services;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import utils.HttpKeys;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
     * @param ttftMs             simulated time-to-first-token in ms
     * @param tokensPerSecond    streaming throughput in tokens/sec
     * @param responseTokens     total tokens to emit in the response body
     * @param simulatedToolCalls when {@code > 0}, the first response in a
     *                           round emits this many {@code loadtest_sleep}
     *                           tool_calls (each with {@code ms=toolSleepMs})
     *                           instead of content. The follow-up request
     *                           carrying tool results triggers a normal
     *                           content stream — drives the agent's parallel
     *                           tool-execution path end-to-end.
     * @param toolSleepMs        per-tool-call sleep duration in ms
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
    private static final AtomicReference<HttpServer> server =
            new AtomicReference<>();
    private static final AtomicReference<ScheduledExecutorService> scheduler =
            new AtomicReference<>();
    private static volatile int port;
    private static volatile Scenario scenario = Scenario.defaults();

    /** Closing SSE chunks for the tool-calls path. Hoisted out of
     *  {@link #streamToolCalls}'s lambda to satisfy Sonar S6203 — a
     *  text block inside a lambda body holds the parent scope alive
     *  even when the value is invariant. */
    private static final String TOOL_CALL_FINAL_CHUNK = """
            data: {"id":"mock","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

            data: [DONE]

            """;

    private LoadTestHarness() {}

    public static int port() { return port; }
    public static boolean isRunning() { return server.get() != null; }
    public static void setScenario(Scenario s) { scenario = s; }
    public static Scenario scenario() { return scenario; }

    public static int start(int requestedPort) throws IOException {
        // First attempt under the lock. If the port is busy (stale server from
        // a previous run / class-reload cycle), stop and exit the synchronized
        // block so the backoff doesn't hold the lock — Thread.sleep inside
        // synchronized would block any concurrent start()/stop() (S2276), and
        // lock.wait without a while-loop wakeup guard would trip S2274.
        synchronized (lock) {
            if (server.get() != null) return port;
            try {
                return bindAndStart(requestedPort);
            } catch (BindException _) {
                stop();
            }
        }
        try { Thread.sleep(500); } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        // Re-acquire the lock for the retry. Re-check server.get() because a
        // racing start() could have bound during the unlocked sleep.
        synchronized (lock) {
            if (server.get() != null) return port;
            try {
                return bindAndStart(requestedPort);
            } catch (BindException _) {
                // The configured port is held by another process entirely (a
                // second JClaw on the same host, a parallel CI build) — not a
                // stale server of ours, or the retry would have won. The mock
                // is loopback-only and every consumer reads the ACTUAL port
                // back via port(), so an ephemeral port is fully equivalent:
                // never fail a loadtest over a port squat.
                stop();
                return bindAndStart(0);
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
        // parallel without any cross-stream ordering risk. Within a stream the
        // deadlines can be ~1 ms apart and a stalled pool brings several due at
        // once, so streamResponse tracks frame completion explicitly instead of
        // inferring it from deadline order (JCLAW-1141). Never park a pool thread
        // waiting on another frame: with 2 threads, frames 1 and 2 would hold both
        // while frame 0 waits for one.
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

            // Snapshot the scheduler once. A concurrent stop() nulls the
            // AtomicReference and shutdownNow()s the pool; reading it per-chunk
            // could NPE mid-stream, and scheduling onto a shut-down pool throws
            // RejectedExecutionException. Bail with 503 if it's already gone so
            // the caller sees a clean "harness stopped" rather than a 500.
            var sch = scheduler.get();
            if (sch == null) {
                ex.sendResponseHeaders(503, -1);
                return;
            }

            ex.getResponseHeaders().add(HttpKeys.CONTENT_TYPE, "text/event-stream");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                if (!continuation && scn.simulatedToolCalls() > 0) {
                    streamToolCalls(sch, out, scn);
                } else {
                    streamResponse(sch, out, scn);
                }
            }
        } catch (InterruptedException _) {
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
            var json = JsonParser.parseString(
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

    private static void streamToolCalls(ScheduledExecutorService sch, OutputStream out, Scenario scn)
            throws IOException, InterruptedException {
        // tool_calls path is one-shot: at TTFT, write all simulated tool calls
        // back-to-back and the terminator. No per-chunk cadence because the
        // agent doesn't see "streaming" tool calls — it gets them as one
        // round. The scheduler still drives the wait so the handler VT
        // stays off Thread.sleep.
        var done = new CompletableFuture<Void>();
        Runnable task = () -> {
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
                out.write(TOOL_CALL_FINAL_CHUNK.getBytes(StandardCharsets.UTF_8));
                out.flush();
                done.complete(null);
            } catch (IOException e) {
                done.completeExceptionally(e);
            }
        };
        try {
            sch.schedule(task, Math.max(0, scn.ttftMs()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            // Concurrent stop() shut the pool down between the snapshot and here.
            done.completeExceptionally(new IOException("loadtest harness stopped", rejected));
        }
        awaitDone(done);
    }

    private static void streamResponse(ScheduledExecutorService sch, OutputStream out, Scenario scn)
            throws IOException, InterruptedException {
        int n = scn.responseTokens();
        // How long the whole response should take to generate, and how many SSE frames
        // that affords at the scheduler's usable 1 ms resolution (JCLAW-942).
        //
        // One token per frame caps the mock at ~1000 tokens/sec, because a frame cannot be
        // scheduled less than a millisecond after the one before it. Rates above that
        // silently clamped: `--tokens-per-second 100000` streamed at 1000, and the 1 ms per
        // token showed up in the server's stream_body segment as if it were serving cost.
        // Real providers already exceed 1000 tok/s, so the ceiling was inside the range
        // being measured.
        //
        // Packing several tokens into one frame removes the ceiling without needing finer
        // scheduling — 100 tokens per frame at 1 ms is 100k tok/s on the same timer. It is
        // also the more faithful shape: an SSE delta carries whatever text was decoded since
        // the last frame, not exactly one token, so one-token frames overstate the per-frame
        // write+flush+chunk-encode work a real stream would cost.
        double totalMs = scn.tokensPerSecond() > 0
                ? 1000.0 * n / scn.tokensPerSecond()
                : 20.0 * n;
        int frames = Math.max(1, Math.min(n, (int) Math.floor(totalMs)));
        double spacingMs = totalMs / frames;
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
        // One future per scheduled frame; the terminator goes out only once every one of
        // them has written. JCLAW-1141: it used to be written by whichever task carried the
        // last frame, which silently dropped frames. Deadlines forced 1 ms apart order task
        // *dispatch*, not execution, so a stalled pool brings every deadline due at once and
        // a later frame can terminate the stream while an earlier one is still queued behind
        // writeLock — that frame then wrote to a closed stream, and its IOException landed on
        // an already-completed future where completeExceptionally is a no-op.
        var pending = new ArrayList<CompletableFuture<Void>>(frames);
        // Schedule each frame at an absolute deadline. TTFT is honored exactly for the first
        // frame (so ttftDelayIsHonored() stays correct). Subsequent frames are spaced by a
        // jittered cadence centred on spacingMs — uniform over ±50%, so the mean is the
        // requested rate rather than under it. The previous form, `spacing/2 +
        // nextInt(spacing)`, averaged spacing-0.5 in integer arithmetic and delivered
        // measurably faster than asked: at 500 tok/s it streamed 40 tokens in 58 ms against
        // the nominal 80. Accumulating in double and rounding only at schedule time keeps
        // that bias out.
        //
        // Deadlines are forced strictly increasing so two frames never target the same
        // millisecond; writeLock guards the byte interleaving when they overlap anyway.
        var rnd = ThreadLocalRandom.current();
        double cumDelayMs = Math.max(0, scn.ttftMs());
        long prevDeadline = -1;
        for (int f = 0; f < frames; f++) {
            // Even split, exact: consecutive boundaries sum to n with no remainder lost.
            int from = (int) ((long) f * n / frames);
            int to = (int) ((long) (f + 1) * n / frames);
            var text = new StringBuilder();
            for (int t = from; t < to; t++) text.append(t == 0 ? "Hello" : " tok" + t);
            var content = text.toString();
            var frameDone = new CompletableFuture<Void>();
            pending.add(frameDone);
            Runnable chunkTask = () -> {
                try {
                    var chunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                            + content + "\"}}]}\n\n";
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
                    }
                    frameDone.complete(null);
                } catch (IOException e) {
                    frameDone.completeExceptionally(e);
                }
            };
            long deadline = Math.max(prevDeadline + 1, Math.round(cumDelayMs));
            prevDeadline = deadline;
            try {
                sch.schedule(chunkTask, deadline, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException rejected) {
                // Concurrent stop() shut the pool down mid-schedule. Fail this frame so the
                // wait below unblocks instead of parking on a task that will never run.
                frameDone.completeExceptionally(new IOException("loadtest harness stopped", rejected));
                break;
            }
            cumDelayMs += spacingMs * (0.5 + rnd.nextDouble());
        }
        // allOf waits for every frame to finish, so no writer is still running here and a
        // failed frame surfaces as an IOException rather than a token missing from the body.
        awaitDone(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
        var finalChunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":"
                + n + ",\"total_tokens\":"
                + (n + 10) + "}}\n\n"
                + "data: [DONE]\n\n";
        synchronized (writeLock) {
            out.write(finalChunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
