import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.videogen.ReplicateVideoGenerationClient;
import services.videogen.VideoGenerationException;
import services.videogen.VideoGenerationRouter;
import services.videogen.VideoGenerationService.State;
import services.videogen.VideoGenerationService.VideoGenRequest;

/**
 * JCLAW-231 coverage for the Replicate cloud video-generation client against a mocked transport.
 * Mirrors {@code ImageGenerationClientTest} but exercises the <em>async</em> shape: {@code submit}
 * returns a provider job id, and {@code poll} maps Replicate's status vocabulary onto {@link State}
 * plus an optional result URL. Replicate is the sole cloud provider (SV-1 / JCLAW-510 decision);
 * the local engines arrive in JCLAW-232/233.
 */
class VideoGenerationClientTest extends UnitTest {

    private MockWebServer server;
    private OkHttpClient testClient;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        // Plain default client — sidesteps the shared general/LLM dispatcher pools.
        testClient = new OkHttpClient.Builder().build();
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private VideoGenRequest req() {
        return new VideoGenRequest("a cat surfing a wave", null, 5, "16:9", null);
    }

    @Test
    void submitReturnsPredictionId() {
        server.enqueue(json("{\"id\":\"pred_123\",\"status\":\"starting\","
                + "\"urls\":{\"get\":\"" + server.url("/p") + "\"}}"));
        var id = new ReplicateVideoGenerationClient(testClient).submit(req());
        assertEquals("pred_123", id);
    }

    @Test
    void submitThrowsOnHttpError() {
        server.enqueue(new MockResponse.Builder().code(402).body(buf("insufficient credits")).build());
        var client = new ReplicateVideoGenerationClient(testClient);
        var request = req();
        var ex = assertThrows(VideoGenerationException.class, () -> client.submit(request));
        assertTrue(ex.getMessage().contains("submit failed"), ex.getMessage());
    }

    @Test
    void pollSucceededReturnsUrl() {
        server.enqueue(json("{\"status\":\"succeeded\",\"output\":[\"https://cdn/clip.mp4\"]}"));
        var r = new ReplicateVideoGenerationClient(testClient).poll("pred_123");
        assertEquals(State.SUCCEEDED, r.state());
        assertEquals("https://cdn/clip.mp4", r.resultUrl());
    }

    @Test
    void pollProcessingIsRunning() {
        server.enqueue(json("{\"status\":\"processing\"}"));
        var r = new ReplicateVideoGenerationClient(testClient).poll("pred_123");
        assertEquals(State.RUNNING, r.state());
        assertNull(r.resultUrl());
    }

    @Test
    void pollFailedCarriesUpstreamError() {
        server.enqueue(json("{\"status\":\"failed\",\"error\":\"NSFW content blocked\"}"));
        var r = new ReplicateVideoGenerationClient(testClient).poll("pred_123");
        assertEquals(State.FAILED, r.state());
        assertTrue(r.error().contains("NSFW"), r.error());
    }

    @Test
    void pollSucceededWithNoOutputIsFailed() {
        server.enqueue(json("{\"status\":\"succeeded\"}"));
        var r = new ReplicateVideoGenerationClient(testClient).poll("pred_123");
        assertEquals(State.FAILED, r.state());
        assertTrue(r.error().contains("no output"), r.error());
    }

    @Test
    void submitRequiresPrompt() {
        var client = new ReplicateVideoGenerationClient(testClient);
        var request = new VideoGenRequest("  ", null, null, null, null);
        var ex = assertThrows(VideoGenerationException.class,
                () -> client.submit(request),
                "a blank prompt must be rejected before any HTTP call");
        assertTrue(ex.getMessage().contains("prompt is required"), ex.getMessage());
    }

    @Test
    void routerSelectsClientByProvider() {
        // ConfigService's static cache outlives Fixtures.deleteDatabase(), so clear the one key this
        // test owns to stay hermetic across the concurrent unit/functional lanes (see ImageGenerationClientTest).
        ConfigService.delete("videogen.provider");
        assertTrue(VideoGenerationRouter.configuredService().isEmpty(), "no videogen.provider → empty (off)");

        ConfigService.set("videogen.provider", "replicate");
        assertTrue(VideoGenerationRouter.configuredService().orElseThrow() instanceof ReplicateVideoGenerationClient);

        ConfigService.set("videogen.provider", "nonsense");
        assertTrue(VideoGenerationRouter.configuredService().isEmpty(), "unknown provider → empty");
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json").body(buf(body)).build();
    }

    private static Buffer buf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
