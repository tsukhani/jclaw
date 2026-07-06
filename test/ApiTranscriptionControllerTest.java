import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.Tx;
import services.transcription.FfmpegProbe;
import services.transcription.WhisperModel;
import services.transcription.AsrModelStore;

/**
 * JCLAW-164: smoke coverage for the transcription Settings backend.
 * State endpoint shape, ffmpeg probe surfacing, model status enumeration,
 * and the download trigger. uv is forced OFF so AsrModelStore takes its
 * deterministic UNAVAILABLE path — no sidecar is spawned in tests
 * (JCLAW-650: status/downloads are host-engine artifacts via the sidecar).
 */
class ApiTranscriptionControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-tx-164";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        AsrModelStore.resetForTest();
        services.UvProbe.setForTest(new services.UvProbe.ProbeResult(false, "forced off in test"));
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(true, "available"));
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        AsrModelStore.resetForTest();
        services.UvProbe.setForTest(null);
        FfmpegProbe.setForTest(null);
    }

    private static void runInFreshTx(Runnable block) {
        var t = Thread.ofVirtual().start(() -> Tx.run(block));
        try { t.join(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ConfigService.clearCache();
    }

    @Test
    void stateReturnsModelListAndConfigKeys() {
        runInFreshTx(() -> {
            ConfigService.set("transcription.provider", "whisper-local");
            ConfigService.set("transcription.localModel", WhisperModel.DEFAULT.id());
        });
        var response = GET("/api/transcription/state");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"provider\":\"whisper-local\""), "provider key in payload: " + body);
        assertTrue(body.contains("\"localModel\":\"" + WhisperModel.DEFAULT.id() + "\""),
                "localModel key in payload: " + body);
        for (var m : WhisperModel.values()) {
            assertTrue(body.contains("\"id\":\"" + m.id() + "\""),
                    "model %s present in payload".formatted(m.id()));
        }
    }

    @Test
    void stateSurfacesFfmpegProbeResult() {
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "ffmpeg not on PATH"));
        var response = GET("/api/transcription/state");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"ffmpegAvailable\":false"), "ffmpeg flag false: " + body);
        assertTrue(body.contains("\"ffmpegReason\":\"ffmpeg not on PATH\""),
                "ffmpeg reason surfaced: " + body);
    }

    @Test
    void stateRequiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        var response = GET("/api/transcription/state");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void downloadRejectsUnknownModelId() {
        var response = POST("/api/transcription/models/not-a-real-id/download",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void downloadAcceptsKnownModelId() {
        // Real prefetch would need the sidecar — with uv forced off, the
        // async single-flight in AsrModelStore fails fast into its in-memory
        // error map (ensureRunning throws before any spawn); the endpoint
        // still acks with the expected shape, matching production behavior
        // where downloads are fire-and-poll.
        var response = POST("/api/transcription/models/" + WhisperModel.DEFAULT.id() + "/download",
                "application/json", "{}");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"status\":\"downloading\""), "ack body: " + body);
        assertTrue(body.contains("\"modelId\":\"" + WhisperModel.DEFAULT.id() + "\""),
                "modelId echoed: " + body);
    }
}
