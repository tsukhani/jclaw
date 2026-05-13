import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.OcrHealthProbe;
import services.Tx;

/**
 * Functional coverage for the JCLAW-177 follow-up OCR Settings endpoint.
 * Verifies the response shape ({@code providers[]} array with the expected
 * keys), and that {@code available} reflects {@link OcrHealthProbe} while
 * {@code enabled} reflects the Config DB row independently — the Settings
 * page needs both signals to render the toggle correctly.
 */
class ApiOcrControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";
    private static final String CONFIG_KEY = "ocr.tesseract.enabled";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        runInFreshTx(() -> ConfigService.delete(CONFIG_KEY));
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        runInFreshTx(() -> ConfigService.delete(CONFIG_KEY));
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
    void statusReturnsTesseractBackendShape() {
        var response = GET("/api/ocr/status");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"providers\""), "got: " + body);
        assertTrue(body.contains("\"name\":\"tesseract\""), "got: " + body);
        assertTrue(body.contains("\"displayName\":\"Tesseract OCR\""), "got: " + body);
        assertTrue(body.contains("\"configKey\":\"ocr.tesseract.enabled\""), "got: " + body);
        assertTrue(body.contains("\"available\""), "got: " + body);
        assertTrue(body.contains("\"enabled\""), "got: " + body);
    }

    @Test
    void statusReflectsConfigEnabledRow() {
        runInFreshTx(() -> ConfigService.set(CONFIG_KEY, "false"));
        var response = GET("/api/ocr/status");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"enabled\":false"),
                "got: " + getContent(response));

        runInFreshTx(() -> ConfigService.set(CONFIG_KEY, "true"));
        var response2 = GET("/api/ocr/status");
        assertTrue(getContent(response2).contains("\"enabled\":true"),
                "got: " + getContent(response2));
    }

    @Test
    void statusReflectsProbeAvailability() {
        // Force the probe into both states via the test seam and verify
        // available flips independently of the enabled config row. This is
        // the key invariant the Settings page depends on for greying out
        // the toggle when the binary is missing.
        var saved = OcrHealthProbe.lastResult();
        try {
            OcrHealthProbe.setForTest(new OcrHealthProbe.ProbeResult(
                    true, "tesseract 5.5.2 (test stub)", null));
            var ok = getContent(GET("/api/ocr/status"));
            assertTrue(ok.contains("\"available\":true"), "got: " + ok);
            assertTrue(ok.contains("\"version\":\"tesseract 5.5.2 (test stub)\""), "got: " + ok);

            OcrHealthProbe.setForTest(new OcrHealthProbe.ProbeResult(
                    false, null, "tesseract --version exited 127: command not found"));
            var missing = getContent(GET("/api/ocr/status"));
            assertTrue(missing.contains("\"available\":false"), "got: " + missing);
            assertTrue(missing.contains("\"reason\":\"tesseract --version exited 127"),
                    "got: " + missing);
        } finally {
            OcrHealthProbe.setForTest(saved);
        }
    }

    @Test
    void statusRequiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        var response = GET("/api/ocr/status");
        assertStatus(401, response);
    }
}
