import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LoadTestHarness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Unit tests for the embedded mock SSE server. Covers format, timing, and
 * lifecycle — but does NOT drive the full load-test flow (no JClaw loopback,
 * no agent creation). See LoadTestRunner for end-to-end exercise.
 */
class LoadTestHarnessTest extends UnitTest {

    // JCLAW-890: the harness is a JVM-global singleton and ApiMetricsControllerLoadtestRunTest
    // reaches the same statics from the functional lane, so these tests must own it
    // exclusively for the duration of each method. See LoadTestHarnessSync.
    @BeforeEach
    void lockHarness() {
        LoadTestHarnessSync.acquire();
    }

    @AfterEach
    void stopHarness() {
        LoadTestHarnessSync.release();
    }

    @Test
    void startAndStopBindAndReleasePort() throws Exception {
        int port = LoadTestHarness.start(0); // 0 → ephemeral
        assertTrue(port > 0);
        assertTrue(LoadTestHarness.isRunning());
        LoadTestHarness.stop();
        assertFalse(LoadTestHarness.isRunning());
    }

    @Test
    void startIsIdempotent() throws Exception {
        int first = LoadTestHarness.start(0);
        int second = LoadTestHarness.start(0);
        assertEquals(first, second);
    }

    @Test
    void mockServerStreamsOpenAiCompatibleSse() throws Exception {
        int port = LoadTestHarness.start(0);
        LoadTestHarness.setScenario(new LoadTestHarness.Scenario(10, 1000, 3));

        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        var body = resp.body();
        assertTrue(body.contains("data: {"), body);
        assertTrue(body.contains("\"delta\":{\"content\":\"Hello\"}"), body);
        assertTrue(body.contains("\"finish_reason\":\"stop\""), body);
        assertTrue(body.contains("data: [DONE]"), body);
        assertTrue(body.contains("\"completion_tokens\":3"), body);
    }

    @Test
    void emitsToolCallsWhenScenarioRequests() throws Exception {
        int port = LoadTestHarness.start(0);
        LoadTestHarness.setScenario(new LoadTestHarness.Scenario(5, 1000, 5, 3, 150));

        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var initial = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        var firstResp = client.send(initial, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, firstResp.statusCode());
        var firstBody = firstResp.body();
        assertTrue(firstBody.contains("\"name\":\"loadtest_sleep\""), firstBody);
        assertTrue(firstBody.contains("\"finish_reason\":\"tool_calls\""), firstBody);
        // 3 tool_calls requested → 3 distinct call IDs in the stream.
        assertTrue(firstBody.contains("\"id\":\"call-mock-0\""), firstBody);
        assertTrue(firstBody.contains("\"id\":\"call-mock-1\""), firstBody);
        assertTrue(firstBody.contains("\"id\":\"call-mock-2\""), firstBody);

        // Continuation: last message role=tool → mock emits content instead
        // of another tool_calls round.
        var continuation = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"hi\"},"
                                + "{\"role\":\"assistant\",\"tool_calls\":[]},"
                                + "{\"role\":\"tool\",\"content\":\"slept 150ms\"}"
                                + "]}"))
                .build();
        var secondResp = client.send(continuation, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, secondResp.statusCode());
        var secondBody = secondResp.body();
        assertTrue(secondBody.contains("\"content\":\"Hello\""), secondBody);
        assertTrue(secondBody.contains("\"finish_reason\":\"stop\""), secondBody);
        assertFalse(secondBody.contains("tool_calls"), secondBody);
    }

    private String streamBody(int port) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return resp.body();
    }

    private static int contentFrames(String body) {
        return body.split("\"delta\":\\{\"content\"", -1).length - 1;
    }

    /**
     * JCLAW-942: a frame cannot be scheduled less than a millisecond after the one before
     * it, so one token per frame capped the mock at ~1000 tokens/sec and silently clamped
     * anything faster. Real providers already exceed that, so the ceiling sat inside the
     * range being measured and showed up as server-side streaming cost.
     */
    @Test
    void aRateAboveAThousandTokensPerSecondBatchesInsteadOfClamping() throws Exception {
        int port = LoadTestHarness.start(0);
        // 200 tokens at 100k/sec is 2ms of generation — two frames of 100 tokens.
        LoadTestHarness.setScenario(new LoadTestHarness.Scenario(0, 100_000, 200));

        var body = streamBody(port);

        // The frame count IS the property: one token per frame cannot produce two frames for
        // 200 tokens, so this proves the ceiling is gone without timing anything. A companion
        // wall-clock bound used to sit here and was the only machine-dependent line in the
        // class — it failed at 160ms against a 150ms bound on a loaded host while the frame
        // count was still exactly 2. It could not be repaired by loosening either: the
        // regression it guarded cost >=200ms and the healthy path is ~2ms of scheduled time,
        // so any robust bound stops catching the regression. Cadence is covered separately by
        // theDeliveredRateMatchesTheRequestedRate.
        assertEquals(2, contentFrames(body), "expected 2 batched frames, body: " + body);
        assertTrue(body.contains("\"completion_tokens\":200"), "token accounting must survive batching");
        assertTrue(body.contains("Hello") && body.contains("tok199"), "every token must still be emitted");
    }

    /**
     * The jittered cadence must average the requested rate, not undershoot it. The previous
     * integer form — {@code spacing/2 + nextInt(spacing)} — averaged {@code spacing-0.5} and
     * delivered measurably faster than asked, which reads as the server being quicker than
     * the scenario configured.
     */
    @Test
    void theDeliveredRateMatchesTheRequestedRate() throws Exception {
        int port = LoadTestHarness.start(0);
        // 40 tokens at 200/sec is 200ms of generation, one token per frame.
        LoadTestHarness.setScenario(new LoadTestHarness.Scenario(0, 200, 40));

        long t0 = System.nanoTime();
        var body = streamBody(port);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(40, contentFrames(body), "at 200 tok/s each token gets its own frame");
        // The biased form averaged 4ms of a nominal 5ms spacing — ~156ms across 39 gaps.
        // Frames fire at absolute deadlines, so load adds a fixed delay: the full suite in a
        // 4-CPU sandbox took 1016ms. The bound still fails a cadence 10x too slow.
        assertTrue(elapsedMs >= 170 && elapsedMs < 2000,
                "expected ~200ms of simulated generation, got " + elapsedMs + "ms");
    }

    @Test
    void ttftDelayIsHonored() throws Exception {
        int port = LoadTestHarness.start(0);
        LoadTestHarness.setScenario(new LoadTestHarness.Scenario(150, 1000, 1));

        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        long t0 = System.nanoTime();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(200, resp.statusCode());
        // Should take at least ~150ms because of the simulated TTFT. Allow
        // generous slack since test hosts can be slow and Thread.sleep only
        // guarantees a lower bound.
        assertTrue(elapsedMs >= 140,
                "expected >=140ms, got " + elapsedMs + "ms (ttft=150)");
    }
}
