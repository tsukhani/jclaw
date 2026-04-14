import org.junit.jupiter.api.*;
import play.test.*;
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
public class LoadTestHarnessTest extends UnitTest {

    @AfterEach
    void stopHarness() {
        LoadTestHarness.stop();
    }

    @Test
    public void startAndStopBindAndReleasePort() throws Exception {
        int port = LoadTestHarness.start(0); // 0 → ephemeral
        assertTrue(port > 0);
        assertTrue(LoadTestHarness.isRunning());
        LoadTestHarness.stop();
        assertFalse(LoadTestHarness.isRunning());
    }

    @Test
    public void startIsIdempotent() throws Exception {
        int first = LoadTestHarness.start(0);
        int second = LoadTestHarness.start(0);
        assertEquals(first, second);
    }

    @Test
    public void mockServerStreamsOpenAiCompatibleSse() throws Exception {
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
    public void ttftDelayIsHonored() throws Exception {
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
