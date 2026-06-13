import channels.WhatsAppCloudApiProbe;
import com.google.gson.JsonParser;
import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.*;
import play.test.*;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional HTTP tests for {@code ApiWhatsAppBindingsController} (JCLAW-444 +
 * JCLAW-445). Covers the CRUD surface behind {@code /api/channels/whatsapp/bindings}:
 * the auth boundary, transport-aware body validation (Cloud API needs a phone number
 * id + access token; WhatsApp-Web needs only an agent), the 409 conflict paths
 * (duplicate phone number id, agent already bound), secret elision in the
 * projection, blank-to-keep on update, and the JCLAW-445 Cloud-API credential
 * probe (verified-name caching on success, 422 on failure).
 *
 * <p>The Graph probe is stubbed via {@link WhatsAppCloudApiProbe#installForTest}
 * so the suite never touches the network; the default stub verifies, and the
 * failure tests install a {@code Failed} stub.
 */
class ApiWhatsAppBindingsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        // Default: every Cloud-API probe verifies. Failure tests override this.
        WhatsAppCloudApiProbe.installForTest((phoneNumberId, accessToken) ->
                new WhatsAppCloudApiProbe.Verified("Test Biz", "+1 555-0000"));
    }

    @AfterEach
    void teardown() {
        WhatsAppCloudApiProbe.clearForTest();
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
    void createCloudApiBindingRoundTripsDefaultTarget() {
        // JCLAW-425: the proactive-send recipient is an identifier (like phoneNumberId),
        // not a secret, so it must round-trip in the projection for the form to display
        // and edit it.
        login();
        var agentId = seedAgent("wb-create-default-target");
        var body = """
                {"transport": "CLOUD_API", "phoneNumberId": "phone-dt",
                 "accessToken": "tok", "defaultTarget": "+15551234567", "agentId": %d}
                """.formatted(agentId);
        var response = POST("/api/channels/whatsapp/bindings", "application/json", body);
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("+15551234567", obj.get("defaultTarget").getAsString(),
                "the default recipient must round-trip in the projection");
    }

    @Test
    void updateSetsAndClearsDefaultTarget() {
        // JCLAW-425: defaultTarget is plain config (not a secret) — a present key
        // replaces, including clearing to null. (Secrets are blank-to-keep instead.)
        login();
        var agentId = seedAgent("wb-update-default-target");
        var id = seedCloudBinding(agentId, "phone-dt-upd");
        // Set it. The update sends only defaultTarget, so no credential re-probe runs.
        var setResp = PUT("/api/channels/whatsapp/bindings/" + id, "application/json",
                "{\"defaultTarget\": \"+15559876543\"}");
        assertIsOk(setResp);
        var setObj = JsonParser.parseString(getContent(setResp)).getAsJsonObject();
        assertEquals("+15559876543", setObj.get("defaultTarget").getAsString());
        // Clear it: a present-but-blank value resets the stored recipient to null.
        var clrResp = PUT("/api/channels/whatsapp/bindings/" + id, "application/json",
                "{\"defaultTarget\": \"\"}");
        assertIsOk(clrResp);
        var clrObj = JsonParser.parseString(getContent(clrResp)).getAsJsonObject();
        assertTrue(clrObj.get("defaultTarget").isJsonNull(),
                "a present-but-blank defaultTarget must clear the stored value");
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

    // ===== JCLAW-445: credential verification =====

    @Test
    void createCachesVerifiedNameAndDisplayNumberOnSuccess() {
        login();
        var agentId = seedAgent("wb-verify-ok");
        WhatsAppCloudApiProbe.installForTest((p, t) ->
                new WhatsAppCloudApiProbe.Verified("Acme Corp", "+1 555-0100"));
        var body = """
                {"transport": "CLOUD_API", "phoneNumberId": "phone-verify",
                 "accessToken": "tok", "agentId": %d}
                """.formatted(agentId);
        var response = POST("/api/channels/whatsapp/bindings", "application/json", body);
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("Acme Corp", obj.get("verifiedName").getAsString(),
                "the probe's verified name must be cached + surfaced");
        assertEquals("+1 555-0100", obj.get("displayPhoneNumber").getAsString());
    }

    @Test
    void createRejectsFailedProbeWith422AndDoesNotPersist() {
        login();
        var agentId = seedAgent("wb-verify-fail");
        WhatsAppCloudApiProbe.installForTest((p, t) ->
                new WhatsAppCloudApiProbe.Failed("Invalid OAuth access token"));
        var body = """
                {"transport": "CLOUD_API", "phoneNumberId": "phone-bad",
                 "accessToken": "tok-bad", "agentId": %d}
                """.formatted(agentId);
        var response = POST("/api/channels/whatsapp/bindings", "application/json", body);
        assertEquals(422, response.status.intValue());
        assertTrue(getContent(response).contains("Invalid OAuth access token"),
                "422 body should surface the probe reason");
        // Nothing persisted.
        long count = commitInFreshTx(WhatsAppBinding::count);
        assertEquals(0, count, "a failed probe must not persist the binding");
    }

    @Test
    void whatsappWebCreateSkipsProbe() {
        login();
        var agentId = seedAgent("wb-web-noprobe");
        // Install a probe that would FAIL if called — WHATSAPP_WEB must never call it.
        WhatsAppCloudApiProbe.installForTest((p, t) -> {
            throw new AssertionError("WhatsApp-Web create must not probe the Graph API");
        });
        var response = POST("/api/channels/whatsapp/bindings", "application/json",
                "{\"transport\": \"WHATSAPP_WEB\", \"agentId\": %d}".formatted(agentId));
        assertIsOk(response);
    }

    @Test
    void updateReProbesWhenAccessTokenChanges() {
        login();
        var agentId = seedAgent("wb-upd-reprobe");
        var bindingId = seedCloudBinding(agentId, "phone-reprobe");
        WhatsAppCloudApiProbe.installForTest((p, t) ->
                new WhatsAppCloudApiProbe.Failed("token revoked"));
        var response = PUT("/api/channels/whatsapp/bindings/" + bindingId,
                "application/json", "{\"accessToken\": \"new-bad-token\"}");
        assertEquals(422, response.status.intValue(),
                "changing the access token must re-probe and reject on failure");
    }

    @Test
    void updateUnrelatedFieldDoesNotReProbe() {
        login();
        var agentId = seedAgent("wb-upd-noprobe");
        var bindingId = seedCloudBinding(agentId, "phone-noprobe");
        // A probe that fails if called — toggling enabled must not re-probe.
        WhatsAppCloudApiProbe.installForTest((p, t) -> {
            throw new AssertionError("an unrelated update must not re-probe");
        });
        var response = PUT("/api/channels/whatsapp/bindings/" + bindingId,
                "application/json", "{\"enabled\": false}");
        assertIsOk(response);
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
