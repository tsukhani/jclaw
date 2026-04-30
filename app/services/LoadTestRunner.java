package services;

import models.Agent;
import play.db.jpa.JPA;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    /**
     * Real-provider name used when {@link Request#realProvider} is true.
     * Routes through {@link llm.OllamaProvider} via the substring match in
     * {@link llm.LlmProvider#forConfig}; the provider config is seeded by
     * {@link jobs.DefaultConfigJob} at boot.
     */
    public static final String REAL_PROVIDER = "ollama-local";

    /**
     * Load-test request shape. {@code client} (jdk|okhttp) flips
     * {@code play.llm.client} for the duration of the run, then restores
     * the prior value in a finally block — so an okhttp ↔ jdk comparison is
     * one operator command per driver. {@code realProvider} swaps the
     * in-process mock for {@link #REAL_PROVIDER} (ollama-local) running
     * {@code model}; the AC3 perf baseline for JCLAW-185 needs real network
     * and real SSE timing, which the mock can't reproduce.
     */
    public record Request(int concurrency, int iterations, boolean compress,
                          LoadTestHarness.Scenario scenario,
                          String client, boolean realProvider, String model) {}

    public record Result(
            int totalRequests,
            int successCount,
            int errorCount,
            long wallClockMs,
            long avgPerRequestMs,
            long minPerRequestMs,
            long maxPerRequestMs,
            int mockPort,
            long agentId) {}

    private LoadTestRunner() {}

    public static Result run(Request req) throws Exception {
        if (req.concurrency() < 1 || req.iterations() < 1) {
            throw new IllegalArgumentException("concurrency and iterations must be ≥ 1");
        }

        // Flip play.llm.client for the duration of this run if requested. Each
        // chat request reads the flag at LlmHttpDriver.pick() — flipping here
        // before the worker pool starts means every request the loadtest fires
        // sees the new value; AC4's per-request commit guarantees no in-flight
        // request gets split across drivers. We restore in finally below so a
        // crashed run doesn't leave the JVM in the wrong mode for subsequent
        // chat traffic.
        var savedClient = swapClientFlag(req.client());
        try {
        var mockPort = req.realProvider() ? -1 : ensureHarnessStarted();
        if (!req.realProvider()) LoadTestHarness.setScenario(req.scenario());
        // Run setup in a dedicated transaction so the __loadtest__ agent and
        // provider config are committed and visible to the HTTP request threads
        // before any loadtest requests fire.
        long agentId;
        try {
            agentId = JPA.withTransaction("default", false,
                    (play.libs.F.Function0<Long>) () -> req.realProvider()
                            ? ensureLoadtestAgentRealInner(req.model())
                            : ensureLoadtestAgentInner(mockPort));
        } catch (Throwable t) {
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }

        var sessionCookie = mintAdminSessionCookie();

        var baseUrl = "http://127.0.0.1:" + play.Play.configuration.getProperty("http.port", "9000");
        var body = "{\"agentId\":" + agentId + ",\"message\":\"Load test message\"}";
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

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

        var latch = new CountDownLatch(req.concurrency());
        long startNs = System.nanoTime();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < req.concurrency(); w++) {
                exec.submit(() -> {
                    try {
                        for (int i = 0; i < req.iterations(); i++) {
                            long t0 = System.nanoTime();
                            try {
                                var builder = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/chat/stream"))
                                        .header("Content-Type", "application/json")
                                        .header("Cookie", sessionCookie)
                                        .timeout(Duration.ofSeconds(120))
                                        .POST(HttpRequest.BodyPublishers.ofString(body));
                                if (req.compress()) builder.header("Accept-Encoding", "br, gzip");
                                var httpReq = builder.build();
                                var resp = client.send(httpReq, HttpResponse.BodyHandlers.discarding());
                                if (resp.statusCode() == 200) success.incrementAndGet();
                                else error.incrementAndGet();
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
        return new Result(
                total,
                success.get(),
                error.get(),
                wall,
                total > 0 ? totalDuration.get() / total : 0,
                minDur.get() == Long.MAX_VALUE ? 0 : minDur.get(),
                maxDur.get() == Long.MIN_VALUE ? 0 : maxDur.get(),
                mockPort,
                agentId);
        } finally {
            restoreClientFlag(savedClient);
        }
    }

    /**
     * Read the current {@code play.llm.client} value, set it to {@code requested}
     * (when non-null and different), and return the prior value so
     * {@link #restoreClientFlag} can put it back when the run ends. When
     * {@code requested} is null or matches the current value this is a no-op
     * and the returned saved value is the current value (restoring it back is
     * also a no-op). The flag mutates {@link play.Play#configuration} globally;
     * concurrent operator-initiated chat traffic during a loadtest run will
     * pick up the loadtest's chosen driver — that's acceptable because the
     * loadtest is the only thing supposed to be running.
     */
    private static String swapClientFlag(String requested) {
        if (requested == null || requested.isBlank()) return null;
        if (!"jdk".equals(requested) && !"okhttp".equals(requested)) {
            throw new IllegalArgumentException(
                    "client must be 'jdk' or 'okhttp', got: " + requested);
        }
        var prior = play.Play.configuration.getProperty("play.llm.client");
        play.Play.configuration.setProperty("play.llm.client", requested);
        return prior == null ? "" : prior;  // empty sentinel = "was unset"
    }

    private static void restoreClientFlag(String saved) {
        if (saved == null) return;  // no swap performed
        if (saved.isEmpty()) play.Play.configuration.remove("play.llm.client");
        else play.Play.configuration.setProperty("play.llm.client", saved);
    }

    private static void warmupRequest(HttpClient client, String baseUrl,
                                       String sessionCookie, String body, boolean compress) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", sessionCookie)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (compress) builder.header("Accept-Encoding", "br, gzip");
            var req = builder.build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
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
     * {@code provider.ollama-local.*} alone (DefaultConfigJob seeds it at
     * boot) and just rebinds the {@code __loadtest__} agent to ollama-local
     * with the requested model. The agent name stays
     * {@link #LOADTEST_AGENT_NAME} so the API-layer hide/reject filters
     * still apply, and so {@link #cleanupConversations} can find data from
     * either run mode through one query.
     */
    private static long ensureLoadtestAgentRealInner(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model is required when realProvider=true");
        }
        var agent = Agent.findByName(LOADTEST_AGENT_NAME);
        if (agent == null) {
            agent = new Agent();
            agent.name = LOADTEST_AGENT_NAME;
        }
        agent.modelProvider = REAL_PROVIDER;
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
