package services;

import models.Agent;
import okhttp3.MediaType;
import play.db.jpa.JPA;
import utils.HttpKeys;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import llm.ProviderRegistry;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import play.Logger;
import play.Play;
import play.libs.Crypto;
import play.libs.F;
import play.mvc.CookieDataCodec;
import utils.HttpFactories;
import utils.LatencyStats;

/**
 * Drives concurrent HTTP traffic against /api/chat/stream for load testing.
 * Exercises the full pipeline (Netty → Invoker pool → controller → AgentRunner →
 * provider HTTP client → SSE response → persist) so the LatencyTrace histograms
 * populate with realistic numbers, including {@code queue_wait}.
 *
 * <p>Uses a local {@link LoadTestHarness} mock provider so no external LLM is
 * called. Auth-gated at the controller layer.
 */
public final class LoadTestRunner {

    private static final String DEFAULT_DB = "default";
    private static final String PARAM_AGENT = "agent";
    private static final String FIELD_CONVERSATION_ID = "conversationId";
    private static final String PROVIDER_PREFIX = "provider.";

    /**
     * Reserved agent name. The API layer hides this agent from every list and
     * by-id endpoint and rejects user attempts to create or rename to this
     * name — the load-test harness is the only legitimate writer, and it goes
     * through direct JPA here, bypassing the API.
     */
    public static final String LOADTEST_AGENT_NAME = "__loadtest__";
    /**
     * Reserved provider name. Config keys under {@code provider.loadtest-mock.*}
     * are filtered out of the public {@code /api/config} endpoints for the
     * same reason — the harness drives this provider via {@code ConfigService}
     * directly, which bypasses the filter.
     */
    public static final String LOADTEST_PROVIDER = "loadtest-mock";
    private static final String LOADTEST_MODEL = "mock-model";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get(HttpKeys.APPLICATION_JSON);

    /**
     * Default real-provider name when {@link Request#realProvider} is true and
     * {@link Request#provider} is null/blank. Routes through
     * {@link llm.OllamaProvider} via the substring match in
     * {@link llm.LlmProvider#forConfig}; the provider config is seeded by
     * {@link jobs.DefaultConfigJob} at boot.
     */
    public static final String DEFAULT_REAL_PROVIDER = "ollama-local";

    /**
     * Default user message when {@link Request#userMessage} is not provided.
     * Length-constrained, factual, and well-known across every model so
     * cross-provider tokens-per-second comparisons measure speed instead of
     * "model A volunteered 500 tokens, model B volunteered 50 tokens".
     */
    public static final String DEFAULT_USER_MESSAGE =
            "Why is the sky blue? Answer in exactly 50 words.";

    /**
     * Loadtest run shape.
     *
     * @param concurrency  number of parallel virtual workers
     * @param turns        number of turns each worker runs in its
     *                     conversation
     * @param compress     when true, force-compaction is invoked between
     *                     turns to exercise the compaction path
     * @param scenario     mock-endpoint timing scenario (ignored when
     *                     {@code realProvider} is true)
     * @param realProvider when true, the run hits the configured real
     *                     provider instead of the mock harness
     * @param provider     real-provider name override (only meaningful when
     *                     {@code realProvider} is true)
     * @param model        real-provider model id override
     * @param userMessage  single message replayed every turn — useful for
     *                     measuring in-context recall and provider
     *                     prompt-cache hits on a stable prefix
     * @param prompts      when non-null and non-empty, OVERRIDES
     *                     {@code userMessage}: each turn {@code t} sends
     *                     {@code prompts.get(t)}, exercising the model with
     *                     a varied question sequence inside the same growing
     *                     conversation. Must contain at least {@link #turns()}
     *                     entries; the controller validates this before
     *                     dispatch. Mutually exclusive with
     *                     {@code userMessage} on the wire.
     */
    public record Request(int concurrency, int turns, boolean compress,
                          LoadTestHarness.Scenario scenario,
                          boolean realProvider,
                          String provider, String model,
                          String userMessage,
                          List<String> prompts) {

        /** Backwards-compat constructor — defaults userMessage to {@link #DEFAULT_USER_MESSAGE} and prompts to {@code null}. */
        public Request(int concurrency, int turns, boolean compress,
                       LoadTestHarness.Scenario scenario,
                       boolean realProvider, String provider, String model) {
            this(concurrency, turns, compress, scenario, realProvider, provider, model, null, null);
        }

        /** Backwards-compat constructor — defaults prompts to {@code null} (single-message mode). */
        public Request(int concurrency, int turns, boolean compress,
                       LoadTestHarness.Scenario scenario,
                       boolean realProvider, String provider, String model,
                       String userMessage) {
            this(concurrency, turns, compress, scenario, realProvider, provider, model, userMessage, null);
        }
    }

    /**
     * Aggregated outcome of a single load-test run, returned to the API caller.
     *
     * @param totalRequests      Total requests issued in the run (sum across
     *                           all workers and turns).
     * @param successCount       Requests that produced a complete response
     *                           without error.
     * @param errorCount         Requests that errored or timed out.
     * @param wallClockMs        End-to-end wall-clock duration of the run
     *                           in ms.
     * @param avgPerRequestMs    Mean per-request duration in ms.
     * @param minPerRequestMs    Fastest per-request duration in ms.
     * @param maxPerRequestMs    Slowest per-request duration in ms.
     * @param avgTtftMs          Mean time-to-first-token across this run's requests, in ms.
     *                           Computed from the {@code web} channel {@code ttft}
     *                           histogram delta. For reasoning models, TTFT includes
     *                           the time the model spent thinking before emitting
     *                           the first visible token.
     * @param avgResponseTokens  Mean visible response tokens (provider-reported
     *                           {@code completion} minus {@code reasoning}) per
     *                           assistant message persisted by the loadtest
     *                           agent. 0 when no usage data was found.
     * @param avgReasoningTokens Mean internal reasoning tokens — visible
     *                           separately so reasoning-model overhead doesn't
     *                           silently inflate the visible-token count.
     *                           0 for non-reasoning models.
     * @param avgTokensPerSec    Per-request mean of
     *                           {@code visibleTokens / streamBodyMs * 1000} —
     *                           visible-content emission rate while streaming.
     *                           0 when stream timing data is unavailable.
     * @param turnBuckets        Per-turn-position breakdown of TTFT and
     *                           duration distributions. {@code null} when
     *                           {@code turns == 1} (nothing to distribute).
     *                           Reveals provider prompt-cache cliffs, growing-
     *                           history TTFT creep, and per-turn variance that
     *                           a flat aggregate hides.
     * @param serverSegments     Server-side {@link utils.LatencyTrace} segment
     *                           means for the just-completed run only (snapshot
     *                           delta from before workers fire to after they
     *                           complete). Splits client-measured turn duration
     *                           into where it was actually spent inside JClaw —
     *                           {@code prologue_conv} (DB conversation create/
     *                           lookup), {@code prologue_prompt} (system-prompt
     *                           assembly), {@code ttft} (provider-side wait),
     *                           {@code stream_body} (token generation), etc.
     *                           Combined with {@code turnBuckets}, lets you
     *                           attribute the cold-start cliff to the dominant
     *                           server-side segment.
     */
    public record Result(
            int totalRequests,
            int successCount,
            int errorCount,
            long wallClockMs,
            long avgPerRequestMs,
            long minPerRequestMs,
            long maxPerRequestMs,
            long avgTtftMs,
            long avgResponseTokens,
            long avgReasoningTokens,
            double avgTokensPerSec,
            List<TurnBucket> turnBuckets,
            List<SegmentBreakdown> serverSegments) {}

    /**
     * Per-turn-position aggregate across all workers in a run. TTFT is
     * client-measured (request-send → first {@code type:"token"} SSE frame),
     * so it includes loopback round-trip — close to but not identical to the
     * server-side {@code web/ttft} histogram.
     *
     * @param turn           turn position (1-indexed)
     * @param count          successful turns at this position (excludes
     *                       errors / timeouts)
     * @param ttftMeanMs     mean TTFT in ms across the {@code count} successful turns
     * @param ttftP50Ms      median TTFT in ms
     * @param ttftP95Ms      95th-percentile TTFT in ms
     * @param durationMeanMs mean total turn duration in ms
     * @param durationP50Ms  median total turn duration in ms
     * @param durationP95Ms  95th-percentile total turn duration in ms
     */
    public record TurnBucket(int turn, int count,
                             long ttftMeanMs, long ttftP50Ms, long ttftP95Ms,
                             long durationMeanMs, long durationP50Ms, long durationP95Ms) {}

    /**
     * Per-segment server-side latency for one loadtest run. Percentiles
     * aren't included because deltas of HdrHistograms are not directly
     * subtractable; mean is sufficient for dominant-segment diagnosis.
     *
     * @param segment segment name (e.g. {@code "queue_wait"},
     *                {@code "prologue_conv"}, {@code "ttft"},
     *                {@code "stream_body"})
     * @param count   number of recordings in the segment's histogram during
     *                this run (post-warmup → post-workers)
     * @param sumMs   total ms accumulated across the {@code count} recordings
     * @param meanMs  {@code sumMs / count}, or {@code 0} when
     *                {@code count == 0}
     */
    public record SegmentBreakdown(String segment, long count, long sumMs, long meanMs) {}

    /**
     * Server-side trace segments captured for the loadtest result. Subset
     * chosen for cold-start diagnosis: queue_wait reveals JClaw-internal
     * queueing before AgentRunner; prologue_* split server-side prep into
     * parse → conversation resolve/create → prompt assembly → tool config;
     * ttft isolates the wait for the provider's first byte; stream_body and
     * persist cover the streaming and post-stream paths.
     */
    private static final String[] TRACKED_SEGMENTS = {
            "queue_wait",
            "prologue", "prologue_parse", "prologue_conv",
            "prologue_prompt", "prologue_tools",
            "dispatcher_wait",
            "ttft", "stream_body", "persist", "terminal_tail"
    };

    private LoadTestRunner() {}

    public static Result run(Request req) throws Exception {
        if (req.concurrency() < 1 || req.turns() < 1) {
            throw new IllegalArgumentException("concurrency and turns must be ≥ 1");
        }

        long agentId = setupLoadtestAgent(req);

        var sessionCookie = mintAdminSessionCookie();
        var baseUrl = "http://127.0.0.1:" + Play.configuration.getProperty("http.port", "9000");

        // Resolve per-turn message strategy. {@code prompts} (when present)
        // overrides {@code userMessage}: turn t sends prompts.get(t), exposing
        // the model to a varied question sequence inside the same growing
        // conversation. Falling back to userMessage replays the same single
        // prompt every turn — useful for in-context recall and prompt-cache
        // diagnostics. JsonObject + addProperty handles escaping for both
        // paths so quotes, backslashes, or non-ASCII flow through the wire
        // format unchanged.
        var messageFor = resolveMessageStrategy(req);

        // Drive the loadtest through the same OkHttp client tuning that the
        // production LLM stack uses — virtual-thread dispatcher, 64-slot
        // ConnectionPool — so concurrent loadtest workers exercise the
        // exact connection-pooling and threading model that production chat
        // traffic does. Per-call 120s timeout is set on each individual
        // Call below.
        var client = HttpFactories.llmSingleShot();

        runWarmup(client, baseUrl, sessionCookie, agentId, messageFor, req.compress());

        var metrics = new RunMetrics(req.concurrency(), req.turns());
        var segmentsBefore = snapshotTrackedSegments();
        long persistMarker = System.currentTimeMillis();

        long startNs = System.nanoTime();
        runConcurrentWorkers(req, baseUrl, sessionCookie, agentId, messageFor, client, metrics);
        long wall = (System.nanoTime() - startNs) / 1_000_000L;

        var serverSegments = computeSegmentDeltas(segmentsBefore);
        long avgTtftMs = extractServerTtftMs(serverSegments);

        // Pull avg completion tokens AND avg per-request tokens-per-second
        // from the assistant messages this run persisted under the loadtest
        // agent. Per-row tokens/s = completion / (durationMs/1000), then
        // arithmetic mean across rows — this is the "average generation
        // speed observed" metric, not the aggregate-throughput ratio
        // (which biases toward longer responses).
        var tokenStats = perRequestTokenStats(agentId, persistMarker);

        // Per-turn breakdown only when there's something to distribute over.
        // Single-turn runs collapse to a 1-row table that adds noise, not signal.
        var turnBuckets = req.turns() > 1
                ? buildTurnBuckets(metrics.turnTtftMs, metrics.turnDurationMs)
                : null;

        int total = req.concurrency() * req.turns();
        return new Result(
                total,
                metrics.success.get(),
                metrics.error.get(),
                wall,
                total > 0 ? metrics.totalDuration.get() / total : 0,
                metrics.minDur.get() == Long.MAX_VALUE ? 0 : metrics.minDur.get(),
                metrics.maxDur.get() == Long.MIN_VALUE ? 0 : metrics.maxDur.get(),
                avgTtftMs,
                tokenStats.avgVisibleTokens(),
                tokenStats.avgReasoningTokens(),
                tokenStats.avgRate(),
                turnBuckets,
                serverSegments);
    }

    /**
     * Setup the harness (when in mock mode) and the {@code __loadtest__}
     * agent + provider config in a committed transaction so HTTP request
     * threads can read them. Returns the agent id used by all workers.
     */
    private static long setupLoadtestAgent(Request req) throws IOException {
        var mockPort = req.realProvider() ? -1 : ensureHarnessStarted();
        if (!req.realProvider()) LoadTestHarness.setScenario(req.scenario());
        var realProviderName = (req.provider() == null || req.provider().isBlank())
                ? DEFAULT_REAL_PROVIDER : req.provider();
        try {
            return JPA.withTransaction(DEFAULT_DB, false,
                    (F.Function0<Long>) () -> req.realProvider()
                            ? ensureLoadtestAgentRealInner(realProviderName, req.model())
                            : ensureLoadtestAgentInner(mockPort));
        } catch (Throwable t) {
            // JPA.withTransaction wraps callee throws as Throwable; surface IOException
            // unwrapped (harness start can throw it) so the caller's narrower throws clause holds.
            if (t instanceof IOException io) throw io;
            if (t instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Loadtest agent setup failed", t);
        }
    }

    private static IntFunction<String> resolveMessageStrategy(Request req) {
        var userMessage = (req.userMessage() == null || req.userMessage().isBlank())
                ? DEFAULT_USER_MESSAGE : req.userMessage();
        boolean variedPrompts = req.prompts() != null && !req.prompts().isEmpty();
        return variedPrompts
                ? idx -> req.prompts().get(idx)
                : idx -> userMessage;
    }

    /**
     * Single sequential request: stabilises agent lookup, provider cache,
     * session affinity, and JIT before concurrent workers start. Snapshot
     * before/restore after so only the warmup sample is dropped — data
     * accumulated by prior runs (or by real chat traffic the operator cares
     * about) survives.
     */
    private static void runWarmup(OkHttpClient client, String baseUrl, String sessionCookie,
                                   long agentId, IntFunction<String> messageFor,
                                   boolean compress) {
        // Warmup body uses the first prompt (or the single message). Any
        // valid request shape works for warmup since its purpose is JIT/cache
        // warming, not measurement; the result is discarded by resetPoint.
        var warmupBodyObj = new JsonObject();
        warmupBodyObj.addProperty("agentId", agentId);
        warmupBodyObj.addProperty("message", messageFor.apply(0));
        var warmupBody = warmupBodyObj.toString();
        var resetPoint = LatencyStats.captureResetPoint();
        warmupRequest(client, baseUrl, sessionCookie, warmupBody, compress);
        resetPoint.run();
    }

    /**
     * Snapshot all tracked segments BEFORE workers fire so we can subtract
     * at the end and get this-run-only deltas. The warmup resetPoint only
     * drops the warmup contribution; histograms still carry whatever was
     * there from prior runs / real chat traffic.
     */
    private static LinkedHashMap<String, long[]> snapshotTrackedSegments() {
        var segmentsBefore = new LinkedHashMap<String, long[]>();
        for (var seg : TRACKED_SEGMENTS) {
            segmentsBefore.put(seg, readSegmentSnapshot("web", seg));
        }
        return segmentsBefore;
    }

    /**
     * Compute this-run deltas across all tracked segments. The histograms
     * already aggregate per-request values, so sum_ms / count IS the
     * arithmetic mean of per-request samples — what we want for dominant-
     * segment diagnosis. Order is preserved (LinkedHashMap) so the
     * breakdown renders in pipeline order rather than alphabetical.
     */
    private static ArrayList<SegmentBreakdown> computeSegmentDeltas(
            LinkedHashMap<String, long[]> segmentsBefore) {
        var serverSegments = new ArrayList<SegmentBreakdown>(TRACKED_SEGMENTS.length);
        for (var seg : TRACKED_SEGMENTS) {
            var before = segmentsBefore.get(seg);
            var after = readSegmentSnapshot("web", seg);
            long countDelta = after[0] - before[0];
            long sumDelta = after[1] - before[1];
            long meanMs = countDelta > 0 ? sumDelta / countDelta : 0;
            serverSegments.add(new SegmentBreakdown(seg, countDelta, sumDelta, meanMs));
        }
        return serverSegments;
    }

    private static long extractServerTtftMs(List<SegmentBreakdown> serverSegments) {
        var ttftSeg = serverSegments.stream()
                .filter(s -> "ttft".equals(s.segment())).findFirst().orElse(null);
        return ttftSeg != null ? ttftSeg.meanMs() : 0;
    }

    /**
     * Per-worker, per-turn metric arrays plus aggregate counters. Heap is
     * trivial: at the configured ceiling (c=100, t=50) this is 100*50*8*2 =
     * 80 KB. Arrays pre-filled with -1 so error/timeout slots are distinguishable
     * from real 0-ms readings during aggregation.
     */
    private static final class RunMetrics {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger error = new AtomicInteger();
        final AtomicLong totalDuration = new AtomicLong();
        final AtomicLong minDur = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong maxDur = new AtomicLong(Long.MIN_VALUE);
        final long[][] turnTtftMs;
        final long[][] turnDurationMs;

        RunMetrics(int concurrency, int turns) {
            turnTtftMs = new long[concurrency][turns];
            turnDurationMs = new long[concurrency][turns];
            for (int w = 0; w < concurrency; w++) {
                Arrays.fill(turnTtftMs[w], -1L);
                Arrays.fill(turnDurationMs[w], -1L);
            }
        }
    }

    /**
     * Per-run worker context. Bundles the wire-level inputs and shared
     * collaborators (request shape, target URL/cookie, agent id, message
     * resolver, HTTP client, metrics aggregator) so the worker / turn
     * methods stay readable. Created once per run() and passed to every
     * worker; immutable references throughout.
     */
    private record WorkerCtx(Request req, String baseUrl, String sessionCookie,
                              long agentId, IntFunction<String> messageFor,
                              OkHttpClient client, RunMetrics metrics) {}

    private static void runConcurrentWorkers(Request req, String baseUrl, String sessionCookie,
                                              long agentId, IntFunction<String> messageFor,
                                              OkHttpClient client, RunMetrics metrics)
            throws InterruptedException {
        var ctx = new WorkerCtx(req, baseUrl, sessionCookie, agentId, messageFor, client, metrics);
        var latch = new CountDownLatch(req.concurrency());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < req.concurrency(); w++) {
                final int workerIdx = w;
                exec.submit(() -> runWorker(workerIdx, ctx, latch));
            }
            latch.await();
        }
    }

    private static void runWorker(int workerIdx, WorkerCtx ctx, CountDownLatch latch) {
        // Captured from turn 1's SSE init event; subsequent turns
        // POST it back so the server resumes the same conversation
        // (loading prior messages, growing the assembled prompt,
        // hitting any provider-side prompt cache).
        Long conversationId = null;
        try {
            for (int t = 0; t < ctx.req().turns(); t++) {
                conversationId = runTurn(workerIdx, t, ctx, conversationId);
            }
        } finally {
            latch.countDown();
        }
    }

    /**
     * Send one turn for a worker; updates metrics + returns the (possibly
     * newly-discovered) conversationId for the next turn.
     */
    private static Long runTurn(int workerIdx, int t, WorkerCtx ctx, Long conversationId) {
        // Build per-turn body: pull message from prompts[t] when in
        // varied-prompts mode, replay userMessage otherwise.
        // conversationId is set from turn 2 onward so the server
        // resumes the same row.
        var turnBodyObj = new JsonObject();
        turnBodyObj.addProperty("agentId", ctx.agentId());
        turnBodyObj.addProperty("message", ctx.messageFor().apply(t));
        if (conversationId != null) {
            turnBodyObj.addProperty(FIELD_CONVERSATION_ID, conversationId);
        }
        String turnBody = turnBodyObj.toString();
        long t0 = System.nanoTime();
        Long newConversationId = conversationId;
        var metrics = ctx.metrics();
        try {
            var resolved = executeChatRequest(ctx.client(), ctx.baseUrl(), ctx.sessionCookie(),
                    turnBody, ctx.req().compress(), t0);
            if (resolved != null) {
                if (newConversationId == null && resolved.conversationId() != null) {
                    newConversationId = resolved.conversationId();
                }
                if (resolved.ttftMs() >= 0) {
                    metrics.turnTtftMs[workerIdx][t] = resolved.ttftMs();
                }
                metrics.success.incrementAndGet();
            } else {
                metrics.error.incrementAndGet();
            }
        } catch (Exception _) {
            metrics.error.incrementAndGet();
        } finally {
            long d = (System.nanoTime() - t0) / 1_000_000L;
            metrics.totalDuration.addAndGet(d);
            updateMinMax(metrics.minDur, metrics.maxDur, d);
            metrics.turnDurationMs[workerIdx][t] = d;
        }
        return newConversationId;
    }

    /**
     * Send one /api/chat/stream request and parse the SSE response. Returns
     * the SSE result on 200, null on any non-200 status (caller increments
     * error counter). Throws on socket errors / timeouts (caller catches).
     */
    private static SseConsumeResult executeChatRequest(OkHttpClient client, String baseUrl,
                                                        String sessionCookie, String turnBody,
                                                        boolean compress, long t0) throws IOException {
        var builder = new okhttp3.Request.Builder()
                .url(baseUrl + "/api/chat/stream")
                .header("Cookie", sessionCookie)
                .post(RequestBody.create(turnBody, JSON_MEDIA_TYPE));
        if (compress) builder.header("Accept-Encoding", "br, gzip");
        var call = client.newCall(builder.build());
        call.timeout().timeout(120, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (resp.code() == 200) {
                // Stream-parse the SSE body to capture the server-assigned
                // conversationId (init event) and client-side TTFT (first
                // token frame). Drain to end so timing covers full round-trip.
                return consumeSseStream(resp.body(), t0);
            }
            resp.body().bytes();
            return null;
        }
    }

    /**
     * Result of streaming-parsing one SSE response body.
     *
     * <p>{@code conversationId} is captured from the {@code type:"init"} frame
     * the server emits at the start of every chat-stream response (see
     * {@code ApiChatController.streamChat}); {@code null} means the frame was
     * absent or malformed (server error / non-streaming response).
     *
     * <p>{@code ttftMs} is the client-side wall-clock from request-send to the
     * first {@code type:"token"} frame. {@code -1} means no token frame was
     * observed (the stream ended without any visible content — e.g. an
     * error response, or an empty completion). Distinct from the server-side
     * {@code web/ttft} histogram, which excludes the network round-trip.
     */
    private record SseConsumeResult(Long conversationId, long ttftMs) {}

    /**
     * Read the SSE response body line-by-line, capturing the conversationId
     * from the init frame and timestamping the first token frame for TTFT.
     * Drains to EOF so the request timing covers the full round-trip.
     *
     * <p>Uses substring matching on the raw {@code data:} line as a cheap
     * pre-filter before parsing JSON — token frames fire 50-200/sec per
     * stream and parsing every one with Gson is wasted work when we only
     * need the first one.
     */
    private static SseConsumeResult consumeSseStream(ResponseBody body, long t0Nanos)
            throws IOException {
        Long conversationId = null;
        long ttftMs = -1L;
        try (var source = body.source()) {
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (line.length() < 7 || !line.startsWith("data: ")) continue;
                var jsonStr = line.substring(6);
                if (ttftMs < 0 && jsonStr.contains("\"type\":\"token\"")) {
                    ttftMs = (System.nanoTime() - t0Nanos) / 1_000_000L;
                }
                if (conversationId == null && jsonStr.contains("\"type\":\"init\"")) {
                    conversationId = tryParseConversationId(jsonStr);
                }
            }
        }
        return new SseConsumeResult(conversationId, ttftMs);
    }

    /** Parse the conversationId out of an init frame; null on any parse error. */
    private static Long tryParseConversationId(String jsonStr) {
        try {
            var json = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (json.has(FIELD_CONVERSATION_ID)) {
                return json.get(FIELD_CONVERSATION_ID).getAsLong();
            }
        } catch (Exception _) { /* malformed init — skip, keep draining */ }
        return null;
    }

    /**
     * Aggregate per-turn metrics across all workers into one
     * {@link TurnBucket} per turn position. {@code count} reflects only the
     * turns that succeeded at this position — error/timeout slots (recorded as
     * {@code -1} in the input arrays) are excluded so percentiles aren't
     * polluted by sentinel values.
     */
    private static List<TurnBucket> buildTurnBuckets(
            long[][] turnTtftMs, long[][] turnDurationMs) {
        int turns = turnDurationMs[0].length;
        int workers = turnDurationMs.length;
        var buckets = new ArrayList<TurnBucket>(turns);
        for (int t = 0; t < turns; t++) {
            var ttfts = new ArrayList<Long>(workers);
            var durations = new ArrayList<Long>(workers);
            for (int w = 0; w < workers; w++) {
                if (turnDurationMs[w][t] >= 0) durations.add(turnDurationMs[w][t]);
                if (turnTtftMs[w][t] >= 0) ttfts.add(turnTtftMs[w][t]);
            }
            var ttftStats = computeStats(ttfts);
            var durStats = computeStats(durations);
            buckets.add(new TurnBucket(
                    t + 1, durations.size(),
                    ttftStats[0], ttftStats[1], ttftStats[2],
                    durStats[0], durStats[1], durStats[2]));
        }
        return buckets;
    }

    /**
     * Compute {@code [mean, p50, p95]} for a list of millisecond values.
     * Returns zeros for an empty input. Percentile uses nearest-rank with a
     * clamped index, so c=5 workers correctly maps p95 to the worst sample
     * (rank 5 → index 4) without an out-of-bounds for small samples.
     */
    private static long[] computeStats(List<Long> values) {
        if (values.isEmpty()) return new long[]{0L, 0L, 0L};
        var sorted = values.stream().sorted().toList();
        long mean = (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p50 = sorted.get(Math.min(sorted.size() - 1, sorted.size() / 2));
        int p95Idx = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1);
        long p95 = sorted.get(Math.max(0, p95Idx));
        return new long[]{mean, p50, p95};
    }

    /**
     * Read the {@code count} and {@code sum_ms} for one channel/segment cell
     * from {@link utils.LatencyStats#snapshot()}. Returned as a 2-element
     * {@code long[]} so callers can compute deltas without allocating a
     * record. Returns {@code {0, 0}} when the cell is absent — the loadtest
     * may snapshot a freshly-reset stats surface that has not yet seen
     * the segment.
     */
    private static long[] readSegmentSnapshot(String channel, String segment) {
        try {
            var snap = LatencyStats.snapshot();
            var ch = snap.getAsJsonObject(channel);
            if (ch == null) return new long[]{0L, 0L};
            var seg = ch.getAsJsonObject(segment);
            if (seg == null) return new long[]{0L, 0L};
            return new long[]{seg.get("count").getAsLong(), seg.get("sum_ms").getAsLong()};
        } catch (Exception _) {
            return new long[]{0L, 0L};
        }
    }

    /**
     * {@link #perRequestTokenStats} return shape.
     *
     * <p>{@code avgVisibleTokens} = mean of visible response tokens
     * (completion minus reasoning, clamped to ≥ 0). {@code avgReasoningTokens}
     * = mean of model-reported reasoning tokens, surfaced so operators can
     * see "the model burned 3000 reasoning tokens to write 70 visible
     * tokens" rather than that being silently rolled into the rate.
     * {@code avgRate} = mean of per-request {@code visibleTokens / streamBodyMs * 1000}.
     */
    private record TokenStats(long avgVisibleTokens, long avgReasoningTokens, double avgRate) {}

    /** Same chars-per-token heuristic used by SystemPromptAssembler.approxTokens. */
    private static long approxTokens(int chars) {
        return Math.round(chars / 4.0);
    }

    /**
     * Average per-row token counts AND per-request tokens-per-second across
     * the assistant messages this run persisted under the loadtest agent.
     *
     * <p>The "visible token count" used for the rate is derived from the
     * stored {@code Message.content} length (chars/4), NOT from the
     * provider-reported {@code completion} field. Provider accounting is
     * unreliable across reasoning models: gemini-3-flash-preview through
     * ollama-cloud, for instance, reports ~6900 completion tokens with
     * ~1500 reasoning tokens for a 50-word visible answer (288 chars,
     * ~70 visible tokens). The remaining ~5300 tokens are internal
     * "verbose thinking" the provider bills for but doesn't tag as
     * reasoning. Trusting completion would inflate the visible-token
     * rate by 50-100x; trusting (completion - reasoning) is still off by
     * ~70x. Char count IS the truth for "what the user sees".
     *
     * <p>Reasoning is computed as {@code completion - visible_chars_estimate}
     * — a more honest estimate of internal model work than the provider's
     * partial {@code reasoning_tokens} field. Operators comparing models
     * see "model A: 70 visible / 6800 internal" which captures the actual
     * compute spent, not just the slice the provider chose to label.
     *
     * <p>Rate is per-request {@code visible_chars_estimate / streamBodyMs * 1000},
     * arithmetically averaged across rows — "average generation speed
     * observed across requests".
     *
     * <p>Off the per-request hot path; the JPA round-trip happens once,
     * after the workers have already reported their wall-clock times.
     */
    private static TokenStats perRequestTokenStats(long agentId, long sinceMillis) {
        try {
            return JPA.withTransaction(DEFAULT_DB, true, () -> aggregateTokenStats(agentId, sinceMillis));
        } catch (Throwable _) {
            return new TokenStats(0L, 0L, 0.0);
        }
    }

    private static TokenStats aggregateTokenStats(long agentId, long sinceMillis) {
        @SuppressWarnings("unchecked")
        var rows = (List<Object[]>) JPA.em().createQuery(
                "SELECT m.content, m.usageJson FROM Message m "
                + "WHERE m.conversation.agent.id = :aid "
                + "AND m.role = 'assistant' "
                + "AND m.usageJson IS NOT NULL "
                + "AND m.createdAt >= :since")
            .setParameter("aid", agentId)
            .setParameter("since", Instant.ofEpochMilli(sinceMillis))
            .getResultList();
        long visSum = 0;
        long reasonSum = 0;
        long tokCount = 0;
        double rateSum = 0.0;
        long rateCount = 0;
        for (var row : rows) {
            var parsed = parseTokenRow(row);
            if (parsed == null) continue;
            visSum += parsed.visible();
            reasonSum += parsed.internal();
            tokCount++;
            if (parsed.rate() > 0) {
                rateSum += parsed.rate();
                rateCount++;
            }
        }
        return new TokenStats(
                tokCount > 0 ? visSum / tokCount : 0L,
                tokCount > 0 ? reasonSum / tokCount : 0L,
                rateCount > 0 ? rateSum / rateCount : 0.0);
    }

    /** Per-row token accounting extracted from a Message: visible tokens, "internal" (provider billed but not visible), and the per-second rate (0 when undefined). */
    private record RowTokenStats(long visible, long internal, double rate) {}

    private static RowTokenStats parseTokenRow(Object[] row) {
        try {
            var content = (String) row[0];
            var json = (String) row[1];
            var obj = JsonParser.parseString(json).getAsJsonObject();
            long visible = approxTokens(content == null ? 0 : content.length());
            // completion - visible = "everything else the provider
            // billed for but didn't show the user" (true internal
            // work). More accurate than the provider's reasoning
            // field for models that under-report it.
            long completion = obj.has("completion") ? obj.get("completion").getAsLong() : 0L;
            long internal = Math.max(0L, completion - visible);
            double rate = 0.0;
            if (visible > 0) {
                long denomMs = extractDenominatorMs(obj);
                if (denomMs > 0) {
                    rate = (visible * 1000.0) / denomMs;
                }
            }
            return new RowTokenStats(visible, internal, rate);
        } catch (Exception _) {
            return null;
        }
    }

    private static long extractDenominatorMs(JsonObject obj) {
        if (obj.has("streamBodyMs")) return obj.get("streamBodyMs").getAsLong();
        if (obj.has("durationMs")) return obj.get("durationMs").getAsLong();
        return 0L;
    }

    private static void warmupRequest(OkHttpClient client, String baseUrl,
                                       String sessionCookie, String body, boolean compress) {
        try {
            var builder = new okhttp3.Request.Builder()
                    .url(baseUrl + "/api/chat/stream")
                    .header("Cookie", sessionCookie)
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE));
            if (compress) builder.header("Accept-Encoding", "br, gzip");
            var call = client.newCall(builder.build());
            call.timeout().timeout(60, TimeUnit.SECONDS);
            try (var resp = call.execute()) {
                resp.body().bytes();  // drain
            }
            // The warmup response populates all the caches but does not count
            // in the result set. Histograms recorded during warmup will show
            // in GET /api/metrics/latency — the caller is expected to reset.
        } catch (Exception _) {
            // Warmup failures are ignored: the real run will surface them.
        }
        // Give the LatencyStats recording path a beat to complete before the
        // real run starts, so the warmup sample doesn't race with the reset.
        try { Thread.sleep(50); } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void updateMinMax(AtomicLong minA, AtomicLong maxA, long v) {
        long cur;
        do { cur = minA.get(); if (v >= cur) break; } while (!minA.compareAndSet(cur, v));
        do { cur = maxA.get(); if (v <= cur) break; } while (!maxA.compareAndSet(cur, v));
    }

    private static int ensureHarnessStarted() throws IOException {
        if (!LoadTestHarness.isRunning()) {
            int portCfg = ConfigService.getInt("provider.loadtest-mock.port", 19999);
            LoadTestHarness.start(portCfg);
        }
        return LoadTestHarness.port();
    }

    private static long ensureLoadtestAgentInner(int mockPort) {
        ConfigService.set(PROVIDER_PREFIX + LOADTEST_PROVIDER + ".baseUrl",
                "http://127.0.0.1:" + mockPort + "/v1");
        ConfigService.set(PROVIDER_PREFIX + LOADTEST_PROVIDER + ".apiKey", "mock");
        ConfigService.set(PROVIDER_PREFIX + LOADTEST_PROVIDER + ".models",
                "[{\"id\":\"" + LOADTEST_MODEL + "\",\"name\":\"Load Test Mock\","
                + "\"maxTokens\":0,\"contextWindow\":128000,"
                + "\"promptPrice\":0,\"completionPrice\":0,"
                + "\"supportsThinking\":false,\"effectiveThinkingLevels\":[]}]");

        ProviderRegistry.refresh();

        var agent = Agent.findByName(LOADTEST_AGENT_NAME);
        if (agent == null) {
            agent = new Agent();
            agent.name = LOADTEST_AGENT_NAME;
        }
        agent.modelProvider = LOADTEST_PROVIDER;
        agent.modelId = LOADTEST_MODEL;
        agent.enabled = true;
        agent.save();
        return agent.id;
    }

    /**
     * Real-provider twin of {@link #ensureLoadtestAgentInner}: leaves
     * {@code provider.<name>.*} alone (DefaultConfigJob and operator
     * configuration seed those at boot or via the Settings UI) and just
     * rebinds the {@code __loadtest__} agent to the requested provider with
     * the requested model. The agent name stays
     * {@link #LOADTEST_AGENT_NAME} so the API-layer hide/reject filters
     * still apply, and so {@link #cleanupConversations} can find data from
     * either run mode through one query.
     *
     * <p>{@code providerName} must match a registered provider in
     * {@link llm.ProviderRegistry} (e.g. {@code ollama-local},
     * {@code ollama-cloud}, {@code openrouter}, {@code openai}). Validation
     * is intentionally lazy — an unknown name fails at chat time with the
     * provider's own clear error rather than a duplicated up-front check
     * here that could rot out of sync with the registry.
     */
    private static long ensureLoadtestAgentRealInner(String providerName, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model is required when realProvider=true");
        }
        var agent = Agent.findByName(LOADTEST_AGENT_NAME);
        if (agent == null) {
            agent = new Agent();
            agent.name = LOADTEST_AGENT_NAME;
        }
        agent.modelProvider = providerName;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent.id;
    }

    /**
     * Mint a PLAY_SESSION cookie value server-side with admin credentials,
     * matching the format {@link play.mvc.CookieSessionStore#save} produces
     * after a successful login. Used by the loadtest workers to drive
     * authenticated traffic at /api/chat/stream without an HTTP login
     * round-trip.
     *
     * <p>JCLAW-181 replaced the previous {@code internalLogin()} HTTP call
     * with this in-process mint. The previous version read
     * {@code jclaw.admin.password} from {@code application.conf} and POSTed
     * {@code /api/auth/login}, but commit caf9422 moved the admin password
     * to a PBKDF2 hash in the Config DB — there is no plaintext to log in
     * with anymore. Signing with {@link play.Play#secretKey} produces a
     * cookie {@link AuthCheck} accepts identically to a real login.
     *
     * <p>Session data: only the keys {@code AuthCheck} actually checks.
     * No {@code ___TS} entry — application.session.maxAge is not set in
     * jclaw, so {@link play.mvc.CookieSessionStore} doesn't enforce a
     * timestamp and won't reject a cookie that lacks one.
     */
    @SuppressWarnings("java:S112") // Play's CookieDataCodec.encode declares throws Exception; rewrapping is noise for a test-only helper
    private static String mintAdminSessionCookie() throws Exception {
        var data = new LinkedHashMap<String, String>();
        data.put("authenticated", "true");
        data.put("username", "admin");
        var sessionData = CookieDataCodec.encode(data);
        var sign = Crypto.sign(sessionData, Play.secretKey.getBytes());
        return "PLAY_SESSION=" + sign + "-" + sessionData;
    }

    /**
     * Disable the mock provider so LoadTestSleepTool is unregistered from
     * the tool list. Runs in its own transaction.
     */
    public static void disable() {
        try {
            JPA.withTransaction(DEFAULT_DB, false, (F.Function0<Void>) () -> {
                ConfigService.setWithSideEffects("provider.loadtest-mock.enabled", "false");
                return null;
            });
        } catch (Throwable e) {
            Logger.warn("Loadtest disable failed: %s", e.getMessage());
        }
    }

    /**
     * Delete conversations, messages, and event-log entries created by
     * load-test runs. Called explicitly via {@code DELETE /api/metrics/loadtest/data}
     * so the operator can inspect results before clearing them.
     */
    public static void cleanupConversations() {
        try {
            JPA.withTransaction(DEFAULT_DB, false, (F.Function0<Void>) () -> {
                var agent = Agent.findByName(LOADTEST_AGENT_NAME);
                if (agent != null) {
                    // MessageAttachment first — FK has no ON DELETE CASCADE (see ConversationService.deleteByIds).
                    JPA.em().createQuery("DELETE FROM MessageAttachment a WHERE a.message.conversation IN " +
                            "(SELECT c FROM Conversation c WHERE c.agent = :agent)")
                            .setParameter(PARAM_AGENT, agent)
                            .executeUpdate();
                    JPA.em().createQuery("DELETE FROM Message m WHERE m.conversation IN " +
                            "(SELECT c FROM Conversation c WHERE c.agent = :agent)")
                            .setParameter(PARAM_AGENT, agent)
                            .executeUpdate();
                    JPA.em().createQuery("DELETE FROM Conversation c WHERE c.agent = :agent")
                            .setParameter(PARAM_AGENT, agent)
                            .executeUpdate();
                    JPA.em().createQuery("DELETE FROM EventLog e WHERE e.agentId = :name")
                            .setParameter("name", LOADTEST_AGENT_NAME)
                            .executeUpdate();
                }
                return null;
            });
        } catch (Throwable e) {
            Logger.warn("Loadtest conversation cleanup failed: %s", e.getMessage());
        }
    }
}
