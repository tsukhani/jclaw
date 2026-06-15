import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.ConfigService;
import services.Tx;
import services.caption.VlmModel;

/**
 * JCLAW-214: smoke coverage for the Image Captioning Settings backend. State endpoint shape (cloud
 * config keys + per-local-model status enumeration), auth gating, and the routing 400s on unknown
 * model ids. The download path is exercised in {@link VlmModelManagerTest}, and the delete file
 * behaviour likewise — both manipulate the process-global model root, which (per the play1 concurrent
 * test-lane contract) must stay in the unit lane, not the functional lane. So here we deliberately
 * cover only the non-destructive controller seams: routing, auth, and the unknown-id rejections.
 */
class ApiCaptionControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-caption-214";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(TEST_PASSWORD));
        assertIsOk(loginResponse);
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
    }

    private static void runInFreshTx(Runnable block) {
        var t = Thread.ofVirtual().start(() -> Tx.run(block));
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ConfigService.clearCache();
    }

    @Test
    void stateReturnsModelListAndCloudConfig() {
        runInFreshTx(() -> {
            ConfigService.set("caption.cloud.provider", "openai");
            ConfigService.set("caption.cloud.model", "gpt-4o-mini");
        });
        var response = GET("/api/caption/state");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"cloudProvider\":\"openai\""), "cloudProvider in payload: " + body);
        assertTrue(body.contains("\"cloudModel\":\"gpt-4o-mini\""), "cloudModel in payload: " + body);
        for (var m : VlmModel.values()) {
            assertTrue(body.contains("\"id\":\"" + m.id() + "\""),
                    "model %s present in payload".formatted(m.id()));
        }
    }

    @Test
    void stateRequiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        var response = GET("/api/caption/state");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void downloadRejectsUnknownModelId() {
        // byId → empty → 400 before ensureAvailable is ever called (no global mutation, no download).
        var response = POST("/api/caption/models/not-a-real-id/download", "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void removeRejectsUnknownModelId() {
        // byId → empty → 400 before delete is ever called (no destructive filesystem op).
        var response = DELETE("/api/caption/models/not-a-real-id");
        assertEquals(400, response.status.intValue());
    }
}
