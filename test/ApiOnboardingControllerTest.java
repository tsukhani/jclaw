import controllers.ApiOnboardingController;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.Tx;

class ApiOnboardingControllerTest extends FunctionalTest {

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
    void statusReturnsZeroForFreshInstall() {
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":0"), "got: " + body);
        assertTrue(body.contains("\"totalSteps\":6"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":true"), "got: " + body);
    }

    @Test
    void statusReturnsRecordedValueAndSuppressesAutoShow() {
        // Any recorded progress (including step 1, which we write on intro
        // Start or Skip) flips shouldAutoShow off. The "seen/unseen" flag is
        // binary — it's just encoded in the same maxStepReached counter.
        seedTourMaxStep(2);
        var response = GET("/api/onboarding/tour-status");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"maxStepReached\":2"), "got: " + body);
        assertTrue(body.contains("\"shouldAutoShow\":false"), "got: " + body);
    }

    @Test
    void statusRequiresAuth() {
        logout();
        var response = GET("/api/onboarding/tour-status");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void recordProgressUpsertsValue() {
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
    void recordProgressClampsToMax() {
        seedTourMaxStep(3);
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":1}");
        assertIsOk(response);
        var body = getContent(response);
        // max stays at 3 — earlier writes can't lower the recorded high-water mark
        assertTrue(body.contains("\"maxStepReached\":3"), "got: " + body);
    }

    @Test
    void recordProgressRejectsOutOfRange() {
        var low = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":0}");
        assertEquals(400, low.status.intValue());

        var high = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":99}");
        assertEquals(400, high.status.intValue());
    }

    @Test
    void recordProgressRejectsMissingStep() {
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void recordProgressRequiresAuth() {
        logout();
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":2}");
        assertEquals(401, response.status.intValue());
    }

}
