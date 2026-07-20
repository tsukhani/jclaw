import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

/**
 * Functional coverage for the read-aloud API (JCLAW-789/793). Exercises the
 * auth gate, the Settings state snapshot, and the request-validation paths —
 * all of which return WITHOUT spawning the sidecar or loading the JVM engine,
 * so the suite stays hermetic (no uv, no model downloads, no network).
 */
class ApiTtsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    @Test
    void stateRequiresAuth() {
        assertEquals(401, GET("/api/tts/state").status.intValue());
    }

    @Test
    void synthesizeRequiresAuth() {
        var resp = POST("/api/tts/synthesize", "application/json", "{\"text\":\"hello\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void stateSnapshotsBothEngines() {
        login();
        var resp = GET("/api/tts/state");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        // Selected engine + both backends present, so the Settings panel can render the selector.
        assertTrue(body.contains("\"engine\""), body);
        assertTrue(body.contains("\"sidecar\""), body);
        assertTrue(body.contains("\"jvm\""), body);
    }

    @Test
    void synthesizeRejectsMissingText() {
        login();
        assertEquals(400, POST("/api/tts/synthesize", "application/json", "{}").status.intValue());
    }

    @Test
    void synthesizeRejectsBlankText() {
        login();
        assertEquals(400,
                POST("/api/tts/synthesize", "application/json", "{\"text\":\"   \"}").status.intValue());
    }

    @Test
    void streamRequiresAuth() {
        var resp = POST("/api/tts/stream", "application/json", "{\"text\":\"hello\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void streamRejectsMissingText() {
        // Validation returns before the SSE stream opens, so no engine is touched.
        login();
        assertEquals(400, POST("/api/tts/stream", "application/json", "{}").status.intValue());
    }

    @Test
    void streamRejectsBlankText() {
        login();
        assertEquals(400,
                POST("/api/tts/stream", "application/json", "{\"text\":\"   \"}").status.intValue());
    }

    @Test
    void downloadRejectsUnknownModel() {
        login();
        assertEquals(400,
                POST("/api/tts/models/not-a-real-model/download", "application/json", "{}").status.intValue());
    }

    @Test
    void downloadOfSidecarModelIsManagedNoDownload() {
        login();
        // A sidecar model is pulled by the sidecar on first use, not here — the
        // endpoint reports "managed" and triggers no download.
        var resp = POST("/api/tts/models/qwen3-0.6b/download", "application/json", "{}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("managed"), getContent(resp));
    }
}
