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
    public record Request(int concurrency, int iterations, boolean compress,
                          LoadTestHarness.Scenario scenario,
                          boolean realProvider,
                          String provider, String model) {}

    /**
     * @param avgTtftMs         Mean time-to-first-token across this run's requests, in ms.
     *                          Computed from the {@code web} channel {@code ttft} histogram delta.
     * @param avgResponseTokens Mean completion-tokens per assistant message persisted by the
     *                          loadtest agent during this run. 0 when no usage data was found
     *                          (e.g. all requests errored before the LLM responded).
     * @param avgTokensPerSec   Throughput inside the streaming body, computed as
     *                          {@code avgResponseTokens / (avgStreamBodyMs / 1000)}.
     *                          0 when stream_body data is unavailable.
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
            double avgTokensPerSec) {}

    private LoadTestRunner() {}

    public static Result run(Request req) throws Exception {
        if (req.concurrency() < 1 || req.iterations() < 1) {
            throw new IllegalArgumentException("concurrency and iterations must be ≥ 1");
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
        var body = "{\"agentId\":" + agentId + ",\"message\":\"Load test message\"}";
        // Drive the loadtest through the same OkHttp client tuning that the
        // production LLM stack uses — virtual-thread dispatcher, 64-slot
        // ConnectionPool — so concurrent loadtest workers exercise the
        // exact connection-pooling and threading model that production chat
        // traffic does. Per-call 120s timeout is set on each individual
        // Call below.
        var client = utils.HttpFactories.llmSingleShot();

        // Warmup: a single sequential request ensures agent lookup, provider
        // cache, session affinity, and JIT are stable before concurrent workers
        // start. Without this, the first few requests of a cold run can error
        // in a pattern that's indistinguishable from a real performance problem.
        //
        // Snapshot before warmup and restore after, so we drop only the warmup
        // sample without losing data accumulated by prior runs (or by real
        // chat traffic the operator cares about).
        var resetPoint = utils.LatencyStats.captureResetPoint();
        warmupRequest(client, baseUrl, sessionCookie, body, req.compress());
        resetPoint.run();

        var success = new AtomicInteger();
        var error = new AtomicInteger();
        var totalDuration = new AtomicLong();
        var minDur = new AtomicLong(Long.MAX_VALUE);
        var maxDur = new AtomicLong(Long.MIN_VALUE);

        // Snapshot ttft counter BEFORE the workers fire so we can subtract
        // at the end and get this-run-only mean. The reset point above only
        // drops the warmup contribution; histograms still carry whatever was
        // there from prior runs / real chat traffic.
        var ttftBefore = readSegmentSnapshot("web", "ttft");
        long persistMarker = System.currentTimeMillis();

        var latch = new CountDownLatch(req.concurrency());
        long startNs = System.nanoTime();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < req.concurrency(); w++) {
                exec.submit(() -> {
                    try {
                        for (int i = 0; i < req.iterations(); i++) {
                            long t0 = System.nanoTime();
                            try {
                                var builder = new okhttp3.Request.Builder()
                                        .url(baseUrl + "/api/chat/stream")
                                        .header("Cookie", sessionCookie)
                                        .post(okhttp3.RequestBody.create(body, JSON_MEDIA_TYPE));
                                if (req.compress()) builder.header("Accept-Encoding", "br, gzip");
                                var call = client.newCall(builder.build());
                                call.timeout().timeout(120, java.util.concurrent.TimeUnit.SECONDS);
                                try (var resp = call.execute()) {
                                    // Drain the SSE body so the timing covers the full
                                    // round-trip — matches the JDK
                                    // BodyHandlers.discarding() semantics where bytes
                                    // are consumed-and-discarded as they arrive.
                                    if (resp.body() != null) resp.body().bytes();
                                    if (resp.code() == 200) success.incrementAndGet();
                                    else error.incrementAndGet();
                                }
                            } catch (Exception _) {
                                error.incrementAndGet();
                            } finally {
                                long d = (System.nanoTime() - t0) / 1_000_000L;
                                totalDuration.addAndGet(d);
                                updateMinMax(minDur, maxDur, d);
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
        int total = req.concurrency() * req.iterations();

        // Compute this-run mean TTFT from the post-run ttft histogram minus
        // the pre-run snapshot. The histogram already aggregates per-request
        // values, so sum_ms / count IS the arithmetic mean of per-request
        // ttfts — what we want.
        var ttftAfter = readSegmentSnapshot("web", "ttft");
        long ttftCount = ttftAfter[0] - ttftBefore[0];
        long ttftSum = ttftAfter[1] - ttftBefore[1];
        long avgTtftMs = ttftCount > 0 ? ttftSum / ttftCount : 0;

        // Pull avg completion tokens AND avg per-request tokens-per-second
        // from the assistant messages this run persisted under the loadtest
        // agent. Per-row tokens/s = completion / (durationMs/1000), then
        // arithmetic mean across rows — this is the "average generation
        // speed observed" metric, not the aggregate-throughput ratio
        // (which biases toward longer responses).
        var tokenStats = perRequestTokenStats(agentId, persistMarker);
        long avgResponseTokens = tokenStats.avgTokens();
        double avgTokensPerSec = tokenStats.avgRate();

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
                avgTokensPerSec);
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

    /** {@link #perRequestTokenStats} return shape. */
    private record TokenStats(long avgTokens, double avgRate) {}

    /**
     * Average completion-tokens AND average per-request tokens-per-second
     * across the assistant messages this run persisted under the loadtest
     * agent. Reads {@code completion} and {@code streamBodyMs} (with
     * {@code durationMs} as fallback for older runs) out of each message's
     * {@code usage_json} payload set by {@link agents.AgentRunner#buildUsageJson}.
     *
     * <p>The rate is computed PER ROW as {@code completion / (streamBodyMs / 1000)}
     * and then arithmetically averaged across rows. This is the "tokens per
     * second of LLM emit time" metric — the model's pure generation speed,
     * excluding the TTFT wait. Falls back to {@code durationMs} (= TTFT +
     * stream body) when {@code streamBodyMs} is missing, which underestimates
     * generation rate but is better than reporting nothing.
     *
     * <p>Differs from {@code mean(tokens) / mean(timeMs)} (system throughput)
     * when response sizes vary.
     *
     * <p>Off the per-request hot path; the JPA round-trip happens once,
     * after the workers have already reported their wall-clock times.
     */
    private static TokenStats perRequestTokenStats(long agentId, long sinceMillis) {
        try {
            return JPA.withTransaction("default", true, () -> {
                @SuppressWarnings("unchecked")
                var rows = (java.util.List<String>) JPA.em().createQuery(
                        "SELECT m.usageJson FROM Message m "
                        + "WHERE m.conversation.agent.id = :aid "
                        + "AND m.role = 'assistant' "
                        + "AND m.usageJson IS NOT NULL "
                        + "AND m.createdAt >= :since")
                    .setParameter("aid", agentId)
                    .setParameter("since", java.time.Instant.ofEpochMilli(sinceMillis))
                    .getResultList();
                long tokSum = 0;
                long tokCount = 0;
                double rateSum = 0.0;
                long rateCount = 0;
                for (var json : rows) {
                    try {
                        var obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                        if (!obj.has("completion")) continue;
                        long tokens = obj.get("completion").getAsLong();
                        tokSum += tokens;
                        tokCount++;
                        if (tokens > 0) {
                            // Prefer streamBodyMs (LLM emit time only) — that's
                            // the right denominator for "model generation rate".
                            // Fall back to durationMs (whole stream incl. TTFT)
                            // for compatibility with messages persisted before
                            // streamBodyMs was added; reported rate will be
                            // lower in that case.
                            long denomMs = obj.has("streamBodyMs") ? obj.get("streamBodyMs").getAsLong()
                                          : obj.has("durationMs")  ? obj.get("durationMs").getAsLong()
                                          : 0L;
                            if (denomMs > 0) {
                                rateSum += (tokens * 1000.0) / denomMs;
                                rateCount++;
                            }
                        }
                    } catch (Exception _) { /* skip malformed entries */ }
                }
                return new TokenStats(
                        tokCount > 0 ? tokSum / tokCount : 0L,
                        rateCount > 0 ? rateSum / rateCount : 0.0);
            });
        } catch (Throwable _) {
            return new TokenStats(0L, 0.0);
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
                if (resp.body() != null) resp.body().bytes();  // drain
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
