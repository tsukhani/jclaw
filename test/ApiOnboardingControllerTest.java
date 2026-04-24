import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.Tx;

public class ApiOnboardingControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";
    private static final String CONFIG_KEY = "onboarding.tourMaxStep";

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
        runInFreshTx(() -> ConfigService.delete(CONFIG_KEY));
    }

    private static void seedTourMaxStep(int step) {
        runInFreshTx(() -> ConfigService.set(CONFIG_KEY, String.valueOf(step)));
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
        POST("/api/auth/logout", "application/json", "{}");
        var response = GET("/api/onboarding/tour-status");
        assertEquals(401, response.status.intValue());
    }
}
