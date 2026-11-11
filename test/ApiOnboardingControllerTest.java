import com.google.gson.JsonParser;
import controllers.ApiOnboardingController;
import controllers.RequestPrincipal;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.ConfigService;
import services.Tx;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void anAgentOtherThanMainCannotMoveTheTourCounter() {
        // JF-293: the counter decides whether the operator's dashboard opens the tour, so it is not an agent's to write.
        seedTourMaxStep(2);
        var agentId = seedCustomAgent();
        clearCookies();
        try {
            for (var request : List.of(agentRequest(agentId), bearerRequest())) {
                var response = POST(request, "/api/onboarding/tour-progress", "application/json", "{\"step\":5}");
                clearCookies();

                assertEquals(403, response.status.intValue());
                var error = JsonParser.parseString(getContent(response)).getAsJsonObject();
                assertEquals(List.of("agent_scope", "Only the operator or the main agent may record the guided tour's progress."),
                        List.of(error.get("code").getAsString(), error.get("message").getAsString()));
            }
            assertEquals(2, readTourMaxStep(), "the counter is where the operator left it");

            var status = GET(agentRequest(agentId), "/api/onboarding/tour-status");
            assertEquals(200, status.status.intValue());
            assertEquals("{\"maxStepReached\":2,\"totalSteps\":6,\"shouldAutoShow\":false}", getContent(status));
        } finally {
            clearCookies();
        }
    }

    private static Long seedCustomAgent() {
        var id = new AtomicReference<Long>();
        runInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "jf293-tour-" + UUID.randomUUID().toString().substring(0, 8);
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            id.set(agent.id);
        });
        return id.get();
    }

    private static int readTourMaxStep() {
        var step = new AtomicReference<Integer>();
        runInFreshTx(() -> step.set(ConfigService.getInt(ApiOnboardingController.CONFIG_KEY, 0)));
        return step.get();
    }

    /** The bearer as {@code jclaw_api} sends it for {@code agentId}. */
    private static Http.Request agentRequest(Long agentId) {
        var request = bearerRequest();
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A bearer with no agent stamp, a token used outside the tool. */
    private static Http.Request bearerRequest() {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        return request;
    }

    @Test
    void recordProgressRejectsNonIntegerStep() {
        // getAsInt throws on a non-numeric value — the controller's catch
        // block surfaces this as 400.
        var response = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":\"abc\"}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void recordProgressIsIdempotentWhenStepEqualsExisting() {
        // Two writes at the same step exercise the newMax == existing branch
        // (no ConfigService.set call, no event log entry).
        var first = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":4}");
        assertIsOk(first);
        var second = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":4}");
        assertIsOk(second);
        assertTrue(getContent(second).contains("\"maxStepReached\":4"));
    }

    @Test
    void recordProgressKeepsHigherExistingWhenLowerStepSubmitted() {
        // newMax = Math.max(existing, step) — a later request for a smaller
        // step must not regress the max.
        assertIsOk(POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":5}"));
        var resp = POST("/api/onboarding/tour-progress",
                "application/json", "{\"step\":2}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"maxStepReached\":5"),
                "smaller step must not lower the max: " + getContent(resp));
    }
}
