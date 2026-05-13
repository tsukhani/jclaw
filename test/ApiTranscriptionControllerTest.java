import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.Tx;
import services.transcription.FfmpegProbe;
import services.transcription.WhisperModel;
import services.transcription.WhisperModelManager;

/**
 * JCLAW-164: smoke coverage for the transcription Settings backend.
 * State endpoint shape, ffmpeg probe surfacing, model status enumeration,
 * and the download trigger. The actual download path is exercised in
 * {@link WhisperModelManagerTest} — here we just verify the controller
 * routes to it.
 */
class ApiTranscriptionControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-tx-164";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        WhisperModelManager.resetForTest();
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
        WhisperModelManager.resetForTest();
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
        // Real download would hit Hugging Face — we just verify the endpoint
        // accepts the request and returns the expected ack shape. The
        // single-flight CompletableFuture in WhisperModelManager swallows
        // the actual network attempt; failures land in the in-memory status
        // map, never on this thread.
        var response = POST("/api/transcription/models/" + WhisperModel.DEFAULT.id() + "/download",
                "application/json", "{}");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"status\":\"downloading\""), "ack body: " + body);
        assertTrue(body.contains("\"modelId\":\"" + WhisperModel.DEFAULT.id() + "\""),
                "modelId echoed: " + body);
    }
}
