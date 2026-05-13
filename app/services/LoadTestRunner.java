package services;

import models.Agent;
import okhttp3.MediaType;
import play.db.jpa.JPA;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    /**
     * Default real-provider name when {@link Request#realProvider} is true and
     * {@link Request#provider} is null/blank. Routes through
     * {@link llm.OllamaProvider} via the substring match in
     * {@link llm.LlmProvider#forConfig}; the provider config is seeded by
     * {@link jobs.DefaultConfigJob} at boot.
     */
    public static final String DEFAULT_REAL_PROVIDER = "ollama-local";

    /**
     * Load-test request shape. {@code realProvider} swaps the in-process
     * mock for a real provider (default {@link #DEFAULT_REAL_PROVIDER},
     * override via {@code provider}) running {@code model} — useful when
     * the mock's deterministic stubs can't reproduce real network and SSE
     * timing. {@code provider} is the registered name of any real provider
     * in the registry — {@code ollama-local} for loopback runs,
     * {@code ollama-cloud} or {@code openrouter} for cloud runs.
     */
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
     * <p>{@code userMessage} is the single message replayed every turn within
     * a conversation — useful for measuring in-context recall and provider
     * prompt-cache hits on a stable prefix. {@code prompts}, when non-null
     * and non-empty, OVERRIDES {@code userMessage}: each turn {@code t} sends
     * {@code prompts.get(t)}, exercising the model with a varied question
     * sequence inside the same growing conversation. The list must contain
     * at least {@link #turns()} entries; the controller validates this before
     * dispatch. The two are mutually exclusive on the wire.
     */
    public record Request(int concurrency, int turns, boolean compress,
                          LoadTestHarness.Scenario scenario,
                          boolean realProvider,
                          String provider, String model,
                          String userMessage,
                          java.util.List<String> prompts) {

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
            java.util.List<TurnBucket> turnBuckets,
            java.util.List<SegmentBreakdown> serverSegments) {}

    /**
     * Per-turn-position aggregate across all workers in a run. TTFT is
     * client-measured (request-send → first {@code type:"token"} SSE frame),
     * so it includes loopback round-trip — close to but not identical to the
     * server-side {@code web/ttft} histogram. {@code count} is the number of
     * successful turns at this position (excludes errors / timeouts).
     */
    public record TurnBucket(int turn, int count,
                             long ttftMeanMs, long ttftP50Ms, long ttftP95Ms,
                             long durationMeanMs, long durationP50Ms, long durationP95Ms) {}

    /**
     * Per-segment server-side latency for one loadtest run. {@code count} is
     * the number of recordings in the segment's histogram during this run
     * (post-warmup → post-workers). {@code meanMs = sumMs / count}, or 0 when
     * {@code count == 0}. Percentiles aren't included because deltas of
     * HdrHistograms are not directly subtractable; mean is sufficient for
     * dominant-segment diagnosis.
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

        var mockPort = req.realProvider() ? -1 : ensureHarnessStarted();
        if (!req.realProvider()) LoadTestHarness.setScenario(req.scenario());
        // Run setup in a dedicated transaction so the __loadtest__ agent and
        // provider config are committed and visible to the HTTP request threads
        // before any loadtest requests fire.
        var realProviderName = (req.provider() == null || req.provider().isBlank())
                ? DEFAULT_REAL_PROVIDER : req.provider();
        long agentId;
        try {
            agentId = JPA.withTransaction("default", false,
                    (play.libs.F.Function0<Long>) () -> req.realProvider()
                            ? ensureLoadtestAgentRealInner(realProviderName, req.model())
                            : ensureLoadtestAgentInner(mockPort));
        } catch (Throwable t) {
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }

        var sessionCookie = mintAdminSessionCookie();

        var baseUrl = "http://127.0.0.1:" + play.Play.configuration.getProperty("http.port", "9000");
        // Resolve per-turn message strategy. {@code prompts} (when present)
        // overrides {@code userMessage}: turn t sends prompts.get(t), exposing
        // the model to a varied question sequence inside the same growing
        // conversation. Falling back to userMessage replays the same single
        // prompt every turn — useful for in-context recall and prompt-cache
        // diagnostics. JsonObject + addProperty handles escaping for both
        // paths so quotes, backslashes, or non-ASCII flow through the wire
        // format unchanged.
        var userMessage = (req.userMessage() == null || req.userMessage().isBlank())
                ? DEFAULT_USER_MESSAGE : req.userMessage();
        boolean variedPrompts = req.prompts() != null && !req.prompts().isEmpty();
        java.util.function.IntFunction<String> messageFor = variedPrompts
                ? idx -> req.prompts().get(idx)
                : idx -> userMessage;
        // Warmup body uses the first prompt (or the single message). Any
        // valid request shape works for warmup since its purpose is JIT/cache
        // warming, not measurement; the result is discarded by resetPoint.
        var warmupBodyObj = new com.google.gson.JsonObject();
        warmupBodyObj.addProperty("agentId", agentId);
        warmupBodyObj.addProperty("message", messageFor.apply(0));
        var warmupBody = warmupBodyObj.toString();
        // Drive the loadtest through the same OkHttp client tuning that the
        // production LLM stack uses — virtual-thread dispatcher, 64-slot
        // ConnectionPool — so concurrent loadtest workers exercise the
        // exact connection-pooling and threading model that production chat
        // traffic does. Per-call 120s timeout is set on each individual
        // Call below.
        var client = utils.HttpFactories.llmSingleShot();

        // Warmup: a single sequential request ensures agent lookup, provider
        // cache, session affinity, and JIT are stable before concurrent workers
        // start. Without this, the first few turns of a cold run can error
        // in a pattern that's indistinguishable from a real performance problem.
        //
        // Snapshot before warmup and restore after, so we drop only the warmup
        // sample without losing data accumulated by prior runs (or by real
        // chat traffic the operator cares about).
        var resetPoint = utils.LatencyStats.captureResetPoint();
        warmupRequest(client, baseUrl, sessionCookie, warmupBody, req.compress());
        resetPoint.run();

        var success = new AtomicInteger();
        var error = new AtomicInteger();
        var totalDuration = new AtomicLong();
        var minDur = new AtomicLong(Long.MAX_VALUE);
        var maxDur = new AtomicLong(Long.MIN_VALUE);

        // Per-worker, per-turn metric arrays. Heap is trivial: at the
        // configured ceiling (c=100, t=50) this is 100*50*8*2 = 80 KB.
        // Pre-fill with -1 so error/timeout slots are distinguishable from
        // real 0-ms readings during aggregation.
        long[][] turnTtftMs = new long[req.concurrency()][req.turns()];
        long[][] turnDurationMs = new long[req.concurrency()][req.turns()];
        for (int w = 0; w < req.concurrency(); w++) {
            java.util.Arrays.fill(turnTtftMs[w], -1L);
            java.util.Arrays.fill(turnDurationMs[w], -1L);
        }

        // Snapshot all tracked segments BEFORE workers fire so we can
        // subtract at the end and get this-run-only deltas. The reset point
        // above only drops the warmup contribution; histograms still carry
        // whatever was there from prior runs / real chat traffic.
        var segmentsBefore = new java.util.LinkedHashMap<String, long[]>();
        for (var seg : TRACKED_SEGMENTS) {
            segmentsBefore.put(seg, readSegmentSnapshot("web", seg));
        }
        long persistMarker = System.currentTimeMillis();

        var latch = new CountDownLatch(req.concurrency());
        long startNs = System.nanoTime();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < req.concurrency(); w++) {
                final int workerIdx = w;
                exec.submit(() -> {
                    // Captured from turn 1's SSE init event; subsequent turns
                    // POST it back so the server resumes the same conversation
                    // (loading prior messages, growing the assembled prompt,
                    // hitting any provider-side prompt cache).
                    Long conversationId = null;
                    try {
                        for (int t = 0; t < req.turns(); t++) {
                            // Build per-turn body: pull message from prompts[t]
                            // when in varied-prompts mode, replay userMessage
                            // otherwise. conversationId is set from turn 2
                            // onward so the server resumes the same row.
                            var turnBodyObj = new com.google.gson.JsonObject();
                            turnBodyObj.addProperty("agentId", agentId);
                            turnBodyObj.addProperty("message", messageFor.apply(t));
                            if (conversationId != null) {
                                turnBodyObj.addProperty("conversationId", conversationId);
                            }
                            String turnBody = turnBodyObj.toString();
                            long t0 = System.nanoTime();
                            try {
                                var builder = new okhttp3.Request.Builder()
                                        .url(baseUrl + "/api/chat/stream")
                                        .header("Cookie", sessionCookie)
                                        .post(okhttp3.RequestBody.create(turnBody, JSON_MEDIA_TYPE));
                                if (req.compress()) builder.header("Accept-Encoding", "br, gzip");
                                var call = client.newCall(builder.build());
                                call.timeout().timeout(120, java.util.concurrent.TimeUnit.SECONDS);
                                try (var resp = call.execute()) {
                                    if (resp.code() == 200 && resp.body() != null) {
                                        // Stream-parse the SSE body to capture the
                                        // server-assigned conversationId (init event)
                                        // and client-side TTFT (first token frame).
                                        // Drain to end so timing covers full round-trip.
                                        var sse = consumeSseStream(resp.body(), t0);
                                        if (conversationId == null && sse.conversationId() != null) {
                                            conversationId = sse.conversationId();
                                        }
                                        if (sse.ttftMs() >= 0) {
                                            turnTtftMs[workerIdx][t] = sse.ttftMs();
                                        }
                                        success.incrementAndGet();
                                    } else {
                                        if (resp.body() != null) resp.body().bytes();
                                        error.incrementAndGet();
                                    }
                                }
                            } catch (Exception _) {
                                error.incrementAndGet();
                            } finally {
                                long d = (System.nanoTime() - t0) / 1_000_000L;
                                totalDuration.addAndGet(d);
                                updateMinMax(minDur, maxDur, d);
                                turnDurationMs[workerIdx][t] = d;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
        long wall = (System.nanoTime() - startNs) / 1_000_000L;
        int total = req.concurrency() * req.turns();

        // Compute this-run deltas across all tracked segments. The histograms
        // already aggregate per-request values, so sum_ms / count IS the
        // arithmetic mean of per-request samples — what we want for dominant-
        // segment diagnosis. Order is preserved (LinkedHashMap) so the
        // breakdown renders in pipeline order rather than alphabetical.
        var serverSegments = new java.util.ArrayList<SegmentBreakdown>(TRACKED_SEGMENTS.length);
        for (var seg : TRACKED_SEGMENTS) {
            var before = segmentsBefore.get(seg);
            var after = readSegmentSnapshot("web", seg);
            long countDelta = after[0] - before[0];
            long sumDelta = after[1] - before[1];
            long meanMs = countDelta > 0 ? sumDelta / countDelta : 0;
            serverSegments.add(new SegmentBreakdown(seg, countDelta, sumDelta, meanMs));
        }
        var ttftSeg = serverSegments.stream()
                .filter(s -> "ttft".equals(s.segment())).findFirst().orElse(null);
        long avgTtftMs = ttftSeg != null ? ttftSeg.meanMs() : 0;

        // Pull avg completion tokens AND avg per-request tokens-per-second
        // from the assistant messages this run persisted under the loadtest
        // agent. Per-row tokens/s = completion / (durationMs/1000), then
        // arithmetic mean across rows — this is the "average generation
        // speed observed" metric, not the aggregate-throughput ratio
        // (which biases toward longer responses).
        var tokenStats = perRequestTokenStats(agentId, persistMarker);
        long avgResponseTokens = tokenStats.avgVisibleTokens();
        long avgReasoningTokens = tokenStats.avgReasoningTokens();
        double avgTokensPerSec = tokenStats.avgRate();

        // Per-turn breakdown only when there's something to distribute over.
        // Single-turn runs collapse to a 1-row table that adds noise, not signal.
        var turnBuckets = req.turns() > 1
                ? buildTurnBuckets(turnTtftMs, turnDurationMs)
                : null;

        return new Result(
                total,
                success.get(),
                error.get(),
                wall,
                total > 0 ? totalDuration.get() / total : 0,
                minDur.get() == Long.MAX_VALUE ? 0 : minDur.get(),
                maxDur.get() == Long.MIN_VALUE ? 0 : maxDur.get(),
                avgTtftMs,
                avgResponseTokens,
                avgReasoningTokens,
                avgTokensPerSec,
                turnBuckets,
                serverSegments);
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
    private static SseConsumeResult consumeSseStream(okhttp3.ResponseBody body, long t0Nanos)
            throws java.io.IOException {
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
                    try {
                        var json = com.google.gson.JsonParser.parseString(jsonStr).getAsJsonObject();
                        if (json.has("conversationId")) {
                            conversationId = json.get("conversationId").getAsLong();
                        }
                    } catch (Exception _) { /* malformed init — skip, keep draining */ }
                }
            }
        }
        return new SseConsumeResult(conversationId, ttftMs);
    }

    /**
     * Aggregate per-turn metrics across all workers into one
     * {@link TurnBucket} per turn position. {@code count} reflects only the
     * turns that succeeded at this position — error/timeout slots (recorded as
     * {@code -1} in the input arrays) are excluded so percentiles aren't
     * polluted by sentinel values.
     */
    private static java.util.List<TurnBucket> buildTurnBuckets(
            long[][] turnTtftMs, long[][] turnDurationMs) {
        int turns = turnDurationMs[0].length;
        int workers = turnDurationMs.length;
        var buckets = new java.util.ArrayList<TurnBucket>(turns);
        for (int t = 0; t < turns; t++) {
            var ttfts = new java.util.ArrayList<Long>(workers);
            var durations = new java.util.ArrayList<Long>(workers);
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
    private static long[] computeStats(java.util.List<Long> values) {
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
            var snap = utils.LatencyStats.snapshot();
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
            return JPA.withTransaction("default", true, () -> {
                @SuppressWarnings("unchecked")
                var rows = (java.util.List<Object[]>) JPA.em().createQuery(
                        "SELECT m.content, m.usageJson FROM Message m "
                        + "WHERE m.conversation.agent.id = :aid "
                        + "AND m.role = 'assistant' "
                        + "AND m.usageJson IS NOT NULL "
                        + "AND m.createdAt >= :since")
                    .setParameter("aid", agentId)
                    .setParameter("since", java.time.Instant.ofEpochMilli(sinceMillis))
                    .getResultList();
                long visSum = 0;
                long reasonSum = 0;
                long tokCount = 0;
                double rateSum = 0.0;
                long rateCount = 0;
                for (var row : rows) {
                    try {
                        var content = (String) row[0];
                        var json = (String) row[1];
                        var obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                        long visible = approxTokens(content == null ? 0 : content.length());
                        // completion - visible = "everything else the provider
                        // billed for but didn't show the user" (true internal
                        // work). More accurate than the provider's reasoning
                        // field for models that under-report it.
                        long completion = obj.has("completion") ? obj.get("completion").getAsLong() : 0L;
                        long internal = Math.max(0L, completion - visible);
                        visSum += visible;
                        reasonSum += internal;
                        tokCount++;
                        if (visible > 0) {
                            long denomMs;
                            if (obj.has("streamBodyMs")) denomMs = obj.get("streamBodyMs").getAsLong();
                            else if (obj.has("durationMs")) denomMs = obj.get("durationMs").getAsLong();
                            else denomMs = 0L;
                            if (denomMs > 0) {
                                rateSum += (visible * 1000.0) / denomMs;
                                rateCount++;
                            }
                        }
                    } catch (Exception _) { /* skip malformed entries */ }
                }
                return new TokenStats(
                        tokCount > 0 ? visSum / tokCount : 0L,
                        tokCount > 0 ? reasonSum / tokCount : 0L,
                        rateCount > 0 ? rateSum / rateCount : 0.0);
            });
        } catch (Throwable _) {
            return new TokenStats(0L, 0L, 0.0);
        }
    }

    private static void warmupRequest(okhttp3.OkHttpClient client, String baseUrl,
                                       String sessionCookie, String body, boolean compress) {
        try {
            var builder = new okhttp3.Request.Builder()
                    .url(baseUrl + "/api/chat/stream")
                    .header("Cookie", sessionCookie)
                    .post(okhttp3.RequestBody.create(body, JSON_MEDIA_TYPE));
            if (compress) builder.header("Accept-Encoding", "br, gzip");
            var call = client.newCall(builder.build());
            call.timeout().timeout(60, java.util.concurrent.TimeUnit.SECONDS);
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
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void updateMinMax(AtomicLong minA, AtomicLong maxA, long v) {
        long cur;
        do { cur = minA.get(); if (v >= cur) break; } while (!minA.compareAndSet(cur, v));
        do { cur = maxA.get(); if (v <= cur) break; } while (!maxA.compareAndSet(cur, v));
    }

    private static int ensureHarnessStarted() throws java.io.IOException {
        if (!LoadTestHarness.isRunning()) {
            int portCfg = ConfigService.getInt("provider.loadtest-mock.port", 19999);
            LoadTestHarness.start(portCfg);
        }
        return LoadTestHarness.port();
    }

    private static long ensureLoadtestAgentInner(int mockPort) {
        ConfigService.set("provider." + LOADTEST_PROVIDER + ".baseUrl",
                "http://127.0.0.1:" + mockPort + "/v1");
        ConfigService.set("provider." + LOADTEST_PROVIDER + ".apiKey", "mock");
        ConfigService.set("provider." + LOADTEST_PROVIDER + ".models",
                "[{\"id\":\"" + LOADTEST_MODEL + "\",\"name\":\"Load Test Mock\","
                + "\"maxTokens\":0,\"contextWindow\":128000,"
                + "\"promptPrice\":0,\"completionPrice\":0,"
                + "\"supportsThinking\":false,\"effectiveThinkingLevels\":[]}]");

        llm.ProviderRegistry.refresh();

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
        var data = new java.util.LinkedHashMap<String, String>();
        data.put("authenticated", "true");
        data.put("username", "admin");
        var sessionData = play.mvc.CookieDataCodec.encode(data);
        var sign = play.libs.Crypto.sign(sessionData, play.Play.secretKey.getBytes());
        return "PLAY_SESSION=" + sign + "-" + sessionData;
    }

    /**
     * Disable the mock provider so LoadTestSleepTool is unregistered from
     * the tool list. Runs in its own transaction.
     */
    public static void disable() {
        try {
            JPA.withTransaction("default", false, (play.libs.F.Function0<Void>) () -> {
                ConfigService.setWithSideEffects("provider.loadtest-mock.enabled", "false");
                return null;
            });
        } catch (Throwable e) {
            play.Logger.warn("Loadtest disable failed: %s", e.getMessage());
        }
    }

    /**
     * Delete conversations, messages, and event-log entries created by
     * load-test runs. Called explicitly via {@code DELETE /api/metrics/loadtest/data}
     * so the operator can inspect results before clearing them.
     */
    public static void cleanupConversations() {
        try {
            JPA.withTransaction("default", false, (play.libs.F.Function0<Void>) () -> {
                var agent = Agent.findByName(LOADTEST_AGENT_NAME);
                if (agent != null) {
                    // MessageAttachment first — FK has no ON DELETE CASCADE (see ConversationService.deleteByIds).
                    JPA.em().createQuery("DELETE FROM MessageAttachment a WHERE a.message.conversation IN " +
                            "(SELECT c FROM Conversation c WHERE c.agent = :agent)")
                            .setParameter("agent", agent)
                            .executeUpdate();
                    JPA.em().createQuery("DELETE FROM Message m WHERE m.conversation IN " +
                            "(SELECT c FROM Conversation c WHERE c.agent = :agent)")
                            .setParameter("agent", agent)
                            .executeUpdate();
                    JPA.em().createQuery("DELETE FROM Conversation c WHERE c.agent = :agent")
                            .setParameter("agent", agent)
                            .executeUpdate();
                    JPA.em().createQuery("DELETE FROM EventLog e WHERE e.agentId = :name")
                            .setParameter("name", LOADTEST_AGENT_NAME)
                            .executeUpdate();
                }
                return null;
            });
        } catch (Throwable e) {
            play.Logger.warn("Loadtest conversation cleanup failed: %s", e.getMessage());
        }
    }
}
