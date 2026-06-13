import com.google.gson.JsonParser;
import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.*;
import play.test.*;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional HTTP tests for {@code ApiWhatsAppBindingsController} (JCLAW-444).
 * Covers the CRUD surface behind {@code /api/channels/whatsapp/bindings}: the
 * auth boundary, transport-aware body validation (Cloud API needs a phone number
 * id + access token; WhatsApp-Web needs only an agent), the 409 conflict paths
 * (duplicate phone number id, agent already bound), secret elision in the
 * projection, and blank-to-keep on update. Mirrors {@code ApiSlackBindingsControllerTest}.
 *
 * <p>Unlike Slack there is no network probe on create (credential verification is
 * JCLAW-445), so the suite never touches the network.
 */
class ApiWhatsAppBindingsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private Long seedAgent(String name) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
    }

    private Long seedDisabledAgent(String name) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = false;
            agent.save();
            return agent.id;
        });
    }

    /** Seed a Cloud-API binding directly via the model. */
    private Long seedCloudBinding(Long agentId, String phoneNumberId) {
        return commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.CLOUD_API;
            b.phoneNumberId = phoneNumberId;
            b.accessToken = "seed-access-token";
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    // ===== Auth gate =====

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/channels/whatsapp/bindings").status.intValue());
    }

    @Test
    void createRequiresAuth() {
        assertEquals(401, POST("/api/channels/whatsapp/bindings",
                "application/json", "{}").status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        assertEquals(401, PUT("/api/channels/whatsapp/bindings/1",
                "application/json", "{}").status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/channels/whatsapp/bindings/1").status.intValue());
    }

    // ===== List =====

    @Test
    void listReturnsEmptyArrayWhenNoBindings() {
        login();
        var response = GET("/api/channels/whatsapp/bindings");
        assertIsOk(response);
        assertEquals("[]", getContent(response).trim());
    }

    // ===== Create =====

    @Test
    void createCloudApiBindingSucceedsAndElidesSecrets() {
        login();
        var agentId = seedAgent("wb-create-cloud");
        var body = """
                {"transport": "CLOUD_API", "phoneNumberId": "phone-123",
                 "accessToken": "tok-secret", "appSecret": "app-secret",
                 "verifyToken": "verify-secret", "agentId": %d}
                """.formatted(agentId);
        var response = POST("/api/channels/whatsapp/bindings", "application/json", body);
        assertIsOk(response);
        var content = getContent(response);
        var obj = JsonParser.parseString(content).getAsJsonObject();
        assertEquals("CLOUD_API", obj.get("transport").getAsString());
        assertEquals("phone-123", obj.get("phoneNumberId").getAsString());
        assertTrue(obj.get("hasAccessToken").getAsBoolean());
        assertTrue(obj.get("hasAppSecret").getAsBoolean());
        assertTrue(obj.get("hasVerifyToken").getAsBoolean());
        assertTrue(obj.get("enabled").getAsBoolean());
        // Secrets must never be serialized into the projection.
        assertFalse(content.contains("tok-secret"), "accessToken must be elided");
        assertFalse(content.contains("app-secret"), "appSecret must be elided");
        assertFalse(content.contains("verify-secret"), "verifyToken must be elided");
    }

    @Test
    void createWhatsappWebBindingNeedsOnlyAgent() {
        login();
        var agentId = seedAgent("wb-create-web");
        var body = """
                {"transport": "WHATSAPP_WEB", "agentId": %d}
                """.formatted(agentId);
        var response = POST("/api/channels/whatsapp/bindings", "application/json", body);
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("WHATSAPP_WEB", obj.get("transport").getAsString());
        assertTrue(obj.get("phoneNumberId").isJsonNull(), "WhatsApp-Web has no phone number id");
        assertFalse(obj.get("hasAccessToken").getAsBoolean());
    }

    @Test
    void createRequiresAgentId() {
        login();
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"CLOUD_API\", \"phoneNumberId\": \"p\", \"accessToken\": \"t\"}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createCloudApiRequiresPhoneNumberId() {
        login();
        var agentId = seedAgent("wb-no-phone");
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"CLOUD_API\", \"accessToken\": \"t\", \"agentId\": %d}".formatted(agentId));
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createCloudApiRequiresAccessToken() {
        login();
        var agentId = seedAgent("wb-no-token");
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"CLOUD_API\", \"phoneNumberId\": \"p\", \"agentId\": %d}".formatted(agentId));
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsDisabledAgent() {
        login();
        var agentId = seedDisabledAgent("wb-disabled");
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"CLOUD_API\", \"phoneNumberId\": \"p\", \"accessToken\": \"t\", \"agentId\": %d}"
                        .formatted(agentId));
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsDuplicatePhoneNumberId() {
        login();
        var agentA = seedAgent("wb-dup-a");
        seedCloudBinding(agentA, "phone-dup");
        var agentB = seedAgent("wb-dup-b");
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"CLOUD_API\", \"phoneNumberId\": \"phone-dup\", \"accessToken\": \"t\", \"agentId\": %d}"
                        .formatted(agentB));
        assertEquals(409, response.status.intValue());
    }

    @Test
    void createRejectsAgentAlreadyBound() {
        login();
        var agentId = seedAgent("wb-already-bound");
        seedCloudBinding(agentId, "phone-first");
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"CLOUD_API\", \"phoneNumberId\": \"phone-second\", \"accessToken\": \"t\", \"agentId\": %d}"
                        .formatted(agentId));
        assertEquals(409, response.status.intValue());
    }

    // ===== Update =====

    @Test
    void updateTogglesEnabled() {
        login();
        var agentId = seedAgent("wb-toggle");
        var bindingId = seedCloudBinding(agentId, "phone-toggle");
        var response = PUT("/api/channels/whatsapp/bindings/" + bindingId,
                "application/json", "{\"enabled\": false}");
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertFalse(obj.get("enabled").getAsBoolean());
    }

    @Test
    void updateKeepsAccessTokenWhenNotProvided() {
        login();
        var agentId = seedAgent("wb-keep-secret");
        var bindingId = seedCloudBinding(agentId, "phone-keep");
        // An update that doesn't send accessToken must leave the stored one intact.
        var response = PUT("/api/channels/whatsapp/bindings/" + bindingId,
                "application/json", "{\"enabled\": true}");
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertTrue(obj.get("hasAccessToken").getAsBoolean(), "blank update must keep the existing token");
    }

    @Test
    void updateRejectsDuplicatePhoneNumberId() {
        login();
        var agentA = seedAgent("wb-upd-dup-a");
        seedCloudBinding(agentA, "phone-A");
        var agentB = seedAgent("wb-upd-dup-b");
        var bindingB = seedCloudBinding(agentB, "phone-B");
        // Re-pointing binding B at binding A's number must 409.
        var response = PUT("/api/channels/whatsapp/bindings/" + bindingB,
                "application/json", "{\"phoneNumberId\": \"phone-A\"}");
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateSwitchesTransport() {
        login();
        var agentId = seedAgent("wb-switch");
        var bindingId = seedCloudBinding(agentId, "phone-switch");
        var response = PUT("/api/channels/whatsapp/bindings/" + bindingId,
                "application/json", "{\"transport\": \"WHATSAPP_WEB\"}");
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("WHATSAPP_WEB", obj.get("transport").getAsString());
    }

    // ===== Delete =====

    @Test
    void deleteRemovesBinding() {
        login();
        var agentId = seedAgent("wb-delete");
        var bindingId = seedCloudBinding(agentId, "phone-delete");
        var response = DELETE("/api/channels/whatsapp/bindings/" + bindingId);
        assertIsOk(response);
        assertEquals("[]", getContent(GET("/api/channels/whatsapp/bindings")).trim());
    }
}
