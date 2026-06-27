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
import services.videogen.LocalVideoGenerationClient;
import services.videogen.VideoGenerationException;
import services.videogen.VideoGenerationRouter;
import services.videogen.VideoGenerationService.State;
import services.videogen.VideoGenerationService.VideoGenRequest;

/**
 * JCLAW-232/233 coverage for the local sidecar video-generation client against a mocked transport.
 * The {@link LocalVideoGenerationClient} speaks the SAME {@code VideoGenerationService} contract as the
 * Replicate cloud client (see {@code VideoGenerationClientTest}), so these mirror those cases but exercise
 * the sidecar's async protocol (SV-3): {@code POST /jobs} -> 202 {job_id} / 409 busy / 400 insufficient
 * VRAM, and {@code GET /jobs/<id>} -> running(percent) / succeeded(resultUrl) / failed(error). The mock
 * server stands in for the sidecar via the client's base-URL override, so no real {@code uv} process is
 * spawned.
 */
class LocalVideoGenerationClientTest extends UnitTest {

    private MockWebServer server;
    private OkHttpClient testClient;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        testClient = new OkHttpClient.Builder().build();
        // Match LocalVideoSidecarManager.baseUrl()'s no-trailing-slash convention.
        base = server.url("/").toString().replaceAll("/$", "");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private LocalVideoGenerationClient client() {
        return new LocalVideoGenerationClient("ltx", testClient, base);
    }

    private VideoGenRequest req() {
        return new VideoGenRequest("a cat surfing a wave", null, 5, "16:9", null);
    }

    @Test
    void submitReturnsSidecarJobId() {
        server.enqueue(json(202, "{\"job_id\":\"3c757c6946b9\",\"state\":\"running\"}"));
        assertEquals("3c757c6946b9", client().submit(req()));
    }

    @Test
    void submitMapsDurationAndFpsToFrames() throws Exception {
        server.enqueue(json(202, "{\"job_id\":\"j\",\"state\":\"running\"}"));
        // 2s @ 30fps -> 60 frames; fps carried through so the sidecar exports at the chosen rate.
        client().submit(new VideoGenRequest("waves at sunset", null, 2, "16:9", 30));
        var body = server.takeRequest().getBody().utf8();
        assertTrue(body.contains("\"num_frames\":60"), body);
        assertTrue(body.contains("\"fps\":30"), body);
    }

    @Test
    void submitMapsAspectRatioToPortraitDims() throws Exception {
        server.enqueue(json(202, "{\"job_id\":\"j\",\"state\":\"running\"}"));
        client().submit(new VideoGenRequest("a tall waterfall", null, null, "9:16", null));
        var body = server.takeRequest().getBody().utf8();
        assertTrue(body.contains("\"width\":480") && body.contains("\"height\":832"), body); // portrait
    }

    @Test
    void submitBusyThrows() {
        server.enqueue(json(409, "{\"error\":\"busy\",\"active_job_id\":\"abc\"}"));
        var client = client();
        var request = req();
        var ex = assertThrows(VideoGenerationException.class, () -> client.submit(request));
        assertTrue(ex.getMessage().contains("busy"), ex.getMessage());
    }

    @Test
    void submitInsufficientVramThrows() {
        // The free-VRAM gate (SV-2): the sidecar refuses rather than OOMs; the runner turns this into a
        // FAILED job so the user can fall back to cloud.
        server.enqueue(json(400, "{\"error\":\"insufficient_vram\",\"freeVramGb\":6.0,\"requiredGb\":8}"));
        var client = client();
        var request = req();
        var ex = assertThrows(VideoGenerationException.class, () -> client.submit(request));
        assertTrue(ex.getMessage().contains("VRAM"), ex.getMessage());
    }

    @Test
    void submitRequiresPrompt() {
        var client = client();
        var request = new VideoGenRequest("  ", null, null, null, null);
        var ex = assertThrows(VideoGenerationException.class, () -> client.submit(request),
                "a blank prompt must be rejected before any HTTP call");
        assertTrue(ex.getMessage().contains("prompt is required"), ex.getMessage());
    }

    @Test
    void pollRunningCarriesRealPercent() {
        // The local advantage over cloud: a real per-step progress number, not null.
        server.enqueue(json(200, "{\"job_id\":\"j\",\"state\":\"running\",\"percent\":42}"));
        var r = client().poll("j");
        assertEquals(State.RUNNING, r.state());
        assertEquals(Integer.valueOf(42), r.percent());
        assertNull(r.resultUrl());
    }

    @Test
    void pollSucceededPointsResultUrlAtSidecar() {
        server.enqueue(json(200, "{\"job_id\":\"j\",\"state\":\"succeeded\",\"percent\":100}"));
        var r = client().poll("j");
        assertEquals(State.SUCCEEDED, r.state());
        assertEquals(base + "/jobs/j/result", r.resultUrl());
    }

    @Test
    void pollFailedCarriesUpstreamError() {
        server.enqueue(json(200, "{\"job_id\":\"j\",\"state\":\"failed\",\"error\":\"CUDA out of memory\"}"));
        var r = client().poll("j");
        assertEquals(State.FAILED, r.state());
        assertTrue(r.error().contains("out of memory"), r.error());
    }

    @Test
    void routerSelectsLocalClientsByProvider() {
        // ConfigService's static cache outlives Fixtures.deleteDatabase(); clear the keys this test owns to
        // stay hermetic across the concurrent unit/functional lanes (see VideoGenerationClientTest).
        ConfigService.delete("videogen.local.model");

        ConfigService.set("videogen.provider", "ltx-local");
        assertTrue(VideoGenerationRouter.configuredService().orElseThrow() instanceof LocalVideoGenerationClient,
                "ltx-local → LocalVideoGenerationClient");

        ConfigService.set("videogen.provider", "wan-local");
        assertTrue(VideoGenerationRouter.configuredService().orElseThrow() instanceof LocalVideoGenerationClient,
                "wan-local → LocalVideoGenerationClient");

        ConfigService.delete("videogen.provider");
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code)
                .addHeader("Content-Type", "application/json").body(buf(body)).build();
    }

    private static Buffer buf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
