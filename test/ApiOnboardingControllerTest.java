import controllers.ApiOnboardingController;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.Tx;

public class ApiOnboardingControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        clearTourState();
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        clearTourState();
    }

    /** Commit Config writes on a fresh virtual thread so they're visible to
     *  the in-process HTTP handler — the carrier thread is already inside a
     *  JPA tx (see project_functionaltest_tx_isolation memory). */
    private static void runInFreshTx(Runnable block) {
        var t = Thread.ofVirtual().start(() -> Tx.run(block));
        try { t.join(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ConfigService.clearCache();
    }

    private static void clearTourState() {
        runInFreshTx(() -> ConfigService.delete(ApiOnboardingController.CONFIG_KEY));
    }

    private static void seedTourMaxStep(int step) {
        runInFreshTx(() -> ConfigService.set(ApiOnboardingController.CONFIG_KEY, String.valueOf(step)));
    }

    private void logout() {
        POST("/api/auth/logout", "application/json", "{}");
    }

    @Test
    public void statusReturnsZeroForFreshInstall() {
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":0"), "got: " + body);
        assertTrue(body.contains("\"totalSteps\":6"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":true"), "got: " + body);
    }

    @Test
    public void statusReturnsRecordedValue() {
        seedTourMaxStep(2);
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":2"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":true"), "got: " + body);
    }

    @Test
    public void statusShouldAutoShowFalseAtThreshold() {
        seedTourMaxStep(4);
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":4"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":false"), "got: " + body);
    }

    @Test
    public void statusRequiresAuth() {
        // Use a fresh test that does NOT call seedAndLogin's login step.
        // Easiest: log out first.
        logout();
        var response = GET("/api/onboarding/tour-status");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void recordProgressUpsertsValue() {
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":3}");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":3"), "got: " + body);

        // Status now reflects the new max
        var statusResponse = GET("/api/onboarding/tour-status");
        assertTrue(getContent(statusResponse).contains("\"maxStepReached\":3"));
    }

    @Test
    public void recordProgressClampsToMax() {
        seedTourMaxStep(3);
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":1}");
        assertIsOk(response);
        var body = getContent(response);
        // max stays at 3 — earlier writes can't lower the recorded high-water mark
        assertTrue(body.contains("\"maxStepReached\":3"), "got: " + body);
    }

    @Test
    public void recordProgressRejectsOutOfRange() {
        var low = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":0}");
        assertEquals(400, low.status.intValue());

        var high = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":99}");
        assertEquals(400, high.status.intValue());
    }

    @Test
    public void recordProgressRejectsMissingStep() {
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void recordProgressRequiresAuth() {
        logout();
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":2}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void resetClearsToZero() {
        seedTourMaxStep(4);
        var response = POST("/api/onboarding/tour-reset", "application/json", "{}");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":0"), "got: " + body);

        var statusResponse = GET("/api/onboarding/tour-status");
        assertTrue(getContent(statusResponse).contains("\"maxStepReached\":0"));
        assertTrue(getContent(statusResponse).contains("\"shouldAutoShow\":true"));
    }

    @Test
    public void resetIsIdempotent() {
        // Reset on a fresh install should also succeed and report 0
        var response = POST("/api/onboarding/tour-reset", "application/json", "{}");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"maxStepReached\":0"));
    }

    @Test
    public void resetRequiresAuth() {
        logout();
        var response = POST("/api/onboarding/tour-reset", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }
}
