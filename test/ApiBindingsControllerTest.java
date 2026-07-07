import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import models.Agent;
import models.AgentBinding;

/**
 * Functional HTTP tests for {@code ApiBindingsController} — the generic
 * agent ↔ channel/peer binding CRUD that backs Settings → Agent Bindings.
 * Covers auth gates, list, create, update, and delete on the four endpoints
 * declared at {@code conf/routes:42-45}.
 *
 * <p>Channel-specific bindings (Telegram, Slack, etc.) live in their own
 * controllers and tests; this file exclusively covers the generic surface.
 */
class ApiBindingsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    /**
     * Seed an agent on a fresh virtual thread so the inner Tx commits before
     * the subsequent HTTP request. Inline {@code Tx.run} on the
     * FunctionalTest carrier would join the already-open uncommitted tx and
     * the HTTP request wouldn't see the row.
     */
    private Long seedAgent(String name) {
        var holder = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var a = new Agent();
                    a.name = name;
                    a.modelProvider = "openrouter";
                    a.modelId = "gpt-4.1";
                    a.enabled = true;
                    a.save();
                    holder.set(a.id);
                });
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
        return holder.get();
    }

    private Long seedBinding(Long agentId, String channelType, String peerId, int priority) {
        var holder = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var b = new AgentBinding();
                    b.agent = (Agent) Agent.findById(agentId);
                    b.channelType = channelType;
                    b.peerId = peerId;
                    b.priority = priority;
                    b.save();
                    holder.set(b.id);
                });
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
        return holder.get();
    }

    // ==========================================================
    // Auth gates — every endpoint must 401 without a logged-in
    // session. Belongs at the @With(AuthCheck.class) class level
    // but verifying once per HTTP verb guards against accidental
    // bypass on any single endpoint.
    // ==========================================================

    @Test
    void listRequiresAuth() {
        var r = GET("/api/bindings");
        assertEquals(401, r.status.intValue());
    }

    @Test
    void createRequiresAuth() {
        var r = POST("/api/bindings", "application/json",
                "{\"agentId\":1,\"channelType\":\"telegram\"}");
        assertEquals(401, r.status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        var r = PUT("/api/bindings/1", "application/json", "{}");
        assertEquals(401, r.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        var r = DELETE("/api/bindings/1");
        assertEquals(401, r.status.intValue());
    }

    // ==========================================================
    // GET /api/bindings
    // ==========================================================

    @Test
    void listReturnsEmptyArrayWhenNoBindings() {
        login();
        var r = GET("/api/bindings");
        assertIsOk(r);
        assertEquals("[]", getContent(r));
    }

    @Test
    void listReturnsAllBindings() {
        login();
        var agentId = seedAgent("alpha");
        seedBinding(agentId, "telegram", "12345", 0);
        seedBinding(agentId, "slack", "U99", 5);

        var r = GET("/api/bindings");
        assertIsOk(r);
        var body = getContent(r);
        assertTrue(body.contains("\"channelType\":\"telegram\""),
                "expected telegram binding in body: " + body);
        assertTrue(body.contains("\"channelType\":\"slack\""),
                "expected slack binding in body: " + body);
        assertTrue(body.contains("\"peerId\":\"12345\""),
                "expected peerId 12345: " + body);
        assertTrue(body.contains("\"agentName\":\"alpha\""),
                "expected agentName alpha: " + body);
    }

    // ==========================================================
    // POST /api/bindings (create)
    // ==========================================================

    @Test
    void createWithValidBodyReturns200AndPersists() {
        login();
        var agentId = seedAgent("alpha");

        var body = """
                {"agentId":%d,"channelType":"telegram","peerId":"54321","priority":3}
                """.formatted(agentId);
        var r = POST("/api/bindings", "application/json", body);
        assertIsOk(r);

        var resp = getContent(r);
        assertTrue(resp.contains("\"channelType\":\"telegram\""), resp);
        assertTrue(resp.contains("\"peerId\":\"54321\""), resp);
        assertTrue(resp.contains("\"priority\":3"), resp);

        // Round-trip via list to confirm persistence
        var listResp = getContent(GET("/api/bindings"));
        assertTrue(listResp.contains("\"peerId\":\"54321\""), listResp);
    }

    @Test
    void createOmittingPriorityDefaultsToZero() {
        login();
        var agentId = seedAgent("alpha");

        var body = """
                {"agentId":%d,"channelType":"web"}
                """.formatted(agentId);
        var r = POST("/api/bindings", "application/json", body);
        assertIsOk(r);
        assertTrue(getContent(r).contains("\"priority\":0"));
    }

    @Test
    void createOmittingPeerIdStoresNull() {
        login();
        var agentId = seedAgent("alpha");

        var body = """
                {"agentId":%d,"channelType":"web","priority":1}
                """.formatted(agentId);
        var r = POST("/api/bindings", "application/json", body);
        assertIsOk(r);
        // peerId field still emitted in BindingView but JSON-null when absent
        assertTrue(getContent(r).contains("\"peerId\":null") || !getContent(r).contains("\"peerId\":\""));
    }

    @Test
    void createExplicitNullPeerIdStoresNull() {
        login();
        var agentId = seedAgent("alpha");

        var body = """
                {"agentId":%d,"channelType":"web","peerId":null}
                """.formatted(agentId);
        var r = POST("/api/bindings", "application/json", body);
        assertIsOk(r);
    }

    @Test
    void createWithUnknownAgentReturns404() {
        login();
        var body = "{\"agentId\":999999,\"channelType\":\"telegram\"}";
        var r = POST("/api/bindings", "application/json", body);
        assertEquals(404, r.status.intValue());
    }

    // ==========================================================
    // PUT /api/bindings/{id} (update)
    // ==========================================================

    @Test
    void updateExistingBindingChangesFields() {
        login();
        var agentId = seedAgent("alpha");
        var bindingId = seedBinding(agentId, "telegram", "old-peer", 0);

        var body = """
                {"peerId":"new-peer","priority":9}
                """;
        var r = PUT("/api/bindings/" + bindingId, "application/json", body);
        assertIsOk(r);

        var resp = getContent(r);
        assertTrue(resp.contains("\"peerId\":\"new-peer\""), resp);
        assertTrue(resp.contains("\"priority\":9"), resp);
    }

    @Test
    void updateCanReassignToDifferentAgent() {
        login();
        var alphaId = seedAgent("alpha");
        var betaId = seedAgent("beta");
        var bindingId = seedBinding(alphaId, "telegram", "peer1", 0);

        var body = """
                {"agentId":%d}
                """.formatted(betaId);
        var r = PUT("/api/bindings/" + bindingId, "application/json", body);
        assertIsOk(r);
        assertTrue(getContent(r).contains("\"agentName\":\"beta\""));
    }

    @Test
    void updateCanChangeChannelType() {
        login();
        var agentId = seedAgent("alpha");
        var bindingId = seedBinding(agentId, "telegram", "peer1", 0);

        var body = "{\"channelType\":\"slack\"}";
        var r = PUT("/api/bindings/" + bindingId, "application/json", body);
        assertIsOk(r);
        assertTrue(getContent(r).contains("\"channelType\":\"slack\""));
    }

    @Test
    void updateExplicitNullPeerIdClears() {
        login();
        var agentId = seedAgent("alpha");
        var bindingId = seedBinding(agentId, "web", "to-be-cleared", 0);

        var body = "{\"peerId\":null}";
        var r = PUT("/api/bindings/" + bindingId, "application/json", body);
        assertIsOk(r);
        var resp = getContent(r);
        assertTrue(resp.contains("\"peerId\":null"), resp);
    }

    @Test
    void updateUnknownBindingReturns404() {
        login();
        var r = PUT("/api/bindings/999999", "application/json", "{}");
        assertEquals(404, r.status.intValue());
    }

    @Test
    void updateWithUnknownAgentIdReturns404() {
        login();
        var agentId = seedAgent("alpha");
        var bindingId = seedBinding(agentId, "telegram", "peer1", 0);

        var body = "{\"agentId\":999999}";
        var r = PUT("/api/bindings/" + bindingId, "application/json", body);
        assertEquals(404, r.status.intValue());
    }

    // ==========================================================
    // DELETE /api/bindings/{id}
    // ==========================================================

    @Test
    void deleteExistingBindingReturns200AndRemovesRow() {
        login();
        var agentId = seedAgent("alpha");
        var bindingId = seedBinding(agentId, "telegram", "peer1", 0);

        var r = DELETE("/api/bindings/" + bindingId);
        assertIsOk(r);
        assertTrue(getContent(r).contains("\"status\":\"ok\""));

        // Confirm gone via list
        var listResp = getContent(GET("/api/bindings"));
        assertEquals("[]", listResp);
    }

    @Test
    void deleteUnknownBindingReturns404() {
        login();
        var r = DELETE("/api/bindings/999999");
        assertEquals(404, r.status.intValue());
    }
}
