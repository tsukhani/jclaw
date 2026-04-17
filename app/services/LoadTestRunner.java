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

    public record Request(int concurrency, int iterations, LoadTestHarness.Scenario scenario) {}

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
        var mockPort = ensureHarnessStarted();
        LoadTestHarness.setScenario(req.scenario());
        // Run setup in a dedicated transaction so the __loadtest__ agent and
        // provider config are committed and visible to the HTTP request threads
        // before any loadtest requests fire.
        long agentId;
        try {
            agentId = JPA.withTransaction("default", false,
                    (play.libs.F.Function0<Long>) () -> ensureLoadtestAgentInner(mockPort));
        } catch (Throwable t) {
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }

        var sessionCookie = internalLogin();

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
        warmupRequest(client, baseUrl, sessionCookie, body);
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
                                var httpReq = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/chat/stream"))
                                        .header("Content-Type", "application/json")
                                        .header("Cookie", sessionCookie)
                                        .timeout(Duration.ofSeconds(120))
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build();
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
    }

    private static void warmupRequest(HttpClient client, String baseUrl,
                                       String sessionCookie, String body) {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", sessionCookie)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
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

    private static String internalLogin() throws Exception {
        var baseUrl = "http://127.0.0.1:" + play.Play.configuration.getProperty("http.port", "9000");
        var user = play.Play.configuration.getProperty("jclaw.admin.username");
        var pass = play.Play.configuration.getProperty("jclaw.admin.password");
        if (user == null || pass == null) {
            throw new IllegalStateException("jclaw.admin.username/password not configured");
        }
        var body = "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Internal login failed: HTTP " + resp.statusCode());
        }
        var cookies = resp.headers().allValues("Set-Cookie").stream()
                .map(c -> c.split(";")[0])
                .toList();
        if (cookies.isEmpty()) {
            throw new RuntimeException("Internal login returned no Set-Cookie header");
        }
        return String.join("; ", cookies);
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
