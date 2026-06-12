import channels.ChannelTransport;
import models.Agent;
import models.SlackBinding;
import org.junit.jupiter.api.*;
import play.test.*;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional HTTP tests for {@code ApiSlackBindingsController} (JCLAW-441).
 * Covers the CRUD surface behind {@code /api/channels/slack/bindings}: the auth
 * boundary, body-validation matrix, the 409 conflict paths (duplicate bot token,
 * agent already bound), secret elision in the projection, and the
 * {@code effectiveRequestUrl}. Mirrors {@code ApiTelegramBindingsControllerTest};
 * companion to {@code WebhookSlackControllerTest} (the webhook side).
 *
 * <p>The controller probes Slack's {@code auth.test} on create (and on a
 * token-changing update) to cache the bot's user id. That probe is best-effort:
 * a fixture token yields {@code ok=false} (or an IOException offline), so the
 * binding still saves — we just don't assert the cached {@code botUserId}.
 * Validation/conflict cases short-circuit before the probe (no network); only the
 * single create-success test below exercises it, so the suite touches the network
 * at most once.
 */
class ApiSlackBindingsControllerTest extends FunctionalTest {

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

    /** Seed a binding directly via the model (no controller probe). */
    private Long seedBinding(Long agentId, String token) {
        return commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = token;
            b.signingSecret = "seed-signing-secret";
            b.transport = ChannelTransport.HTTP;
            b.webhookBaseUrl = "https://example.com";
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    // ===== Auth gate =====

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/channels/slack/bindings").status.intValue());
    }

    @Test
    void createRequiresAuth() {
        assertEquals(401, POST("/api/channels/slack/bindings",
                "application/json", "{}").status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        assertEquals(401, PUT("/api/channels/slack/bindings/1",
                "application/json", "{}").status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/channels/slack/bindings/1").status.intValue());
    }

    // ===== List =====

    @Test
    void listReturnsEmptyArrayWhenNoBindings() {
        login();
        var response = GET("/api/channels/slack/bindings");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertEquals("[]", getContent(response).trim());
    }

    @Test
    void listReturnsBindingsHidingSecrets() {
        var agentId = seedAgent("sb-agent");
        seedBinding(agentId, "xoxb-secret-bytes");
        login();
        var response = GET("/api/channels/slack/bindings");
        assertIsOk(response);
        var content = getContent(response);
        // BindingView elides botToken/signingSecret/appToken — only presence flags.
        assertFalse(content.contains("xoxb-secret-bytes"),
                "list response should not surface botToken bytes: " + content);
        assertFalse(content.contains("seed-signing-secret"),
                "list response should not surface signingSecret bytes: " + content);
        assertTrue(content.contains("\"hasSigningSecret\":true"),
                "list should flag signing-secret presence: " + content);
        assertTrue(content.contains("\"transport\":\"HTTP\""),
                "list should surface transport: " + content);
        // effectiveRequestUrl carries the binding id appended to the public base.
        assertTrue(content.contains("/api/webhooks/slack/"),
                "list should surface the Events API Request URL: " + content);
    }

    // ===== Create =====

    @Test
    void createRejectsBlankBody() {
        login();
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", "").status.intValue());
    }

    @Test
    void createRejectsMissingBotToken() {
        var agentId = seedAgent("sb-agent");
        login();
        var body = """
                {"signingSecret": "s", "agentId": %d}
                """.formatted(agentId);
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createRejectsMissingSigningSecret() {
        var agentId = seedAgent("sb-agent");
        login();
        var body = """
                {"botToken": "xoxb-t", "agentId": %d}
                """.formatted(agentId);
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createRejectsMissingAgentId() {
        login();
        var body = """
                {"botToken": "xoxb-t", "signingSecret": "s"}
                """;
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createRejectsUnknownAgent() {
        login();
        var body = """
                {"botToken": "xoxb-t", "signingSecret": "s", "agentId": 9999999}
                """;
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createRejectsDisabledAgent() {
        var disabledAgentId = seedDisabledAgent("disabled-agent");
        login();
        var body = """
                {"botToken": "xoxb-t", "signingSecret": "s", "agentId": %d}
                """.formatted(disabledAgentId);
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createRejectsMainAgentWithoutOwner() {
        // JCLAW-354: the main agent has full system access, so a binding to it must set
        // an owner user id — a random workspace user must not be able to reach it. The
        // guard runs before the identity probe, so no network call is made.
        var agentId = seedAgent("main");
        login();
        var body = """
                {"botToken": "xoxb-t", "signingSecret": "s", "agentId": %d}
                """.formatted(agentId);
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createConflictsOnDuplicateBotToken() {
        var firstAgentId = seedAgent("agent-a");
        var secondAgentId = seedAgent("agent-b");
        seedBinding(firstAgentId, "xoxb-duplicate");
        login();
        var body = """
                {"botToken": "xoxb-duplicate", "signingSecret": "s", "agentId": %d}
                """.formatted(secondAgentId);
        // Conflict is detected before the auth.test probe — no network.
        assertEquals(409, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createConflictsWhenAgentAlreadyBound() {
        // Privacy invariant: one agent → one binding (memory is scoped per agent
        // so re-binding would leak memories across workspaces).
        var agentId = seedAgent("shared-agent");
        seedBinding(agentId, "xoxb-first");
        login();
        var body = """
                {"botToken": "xoxb-second", "signingSecret": "s", "agentId": %d}
                """.formatted(agentId);
        assertEquals(409, POST("/api/channels/slack/bindings",
                "application/json", body).status.intValue());
    }

    @Test
    void createSucceedsElidesSecretsDefaultsHttpAndBuildsRequestUrl() {
        // The one success-path test that exercises the create endpoint end to end
        // (and the best-effort auth.test probe). Asserts the projection contract:
        // secrets elided, presence flagged, HTTP default, and the Request URL.
        var agentId = seedAgent("sb-agent");
        login();
        var body = """
                {"botToken": "xoxb-create-success", "signingSecret": "shh-sec-xyz",
                 "agentId": %d, "webhookBaseUrl": "https://example.com"}
                """.formatted(agentId);
        var response = POST("/api/channels/slack/bindings", "application/json", body);
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertFalse(content.contains("xoxb-create-success"),
                "create response must elide botToken: " + content);
        assertFalse(content.contains("shh-sec-xyz"),
                "create response must elide signingSecret: " + content);
        assertTrue(content.contains("\"hasSigningSecret\":true"),
                "create response should flag signingSecret presence: " + content);
        assertTrue(content.contains("\"transport\":\"HTTP\""),
                "default transport should be HTTP: " + content);
        assertTrue(content.contains("/api/webhooks/slack/"),
                "create response should carry the Events API Request URL: " + content);
    }

    // ===== Update =====

    @Test
    void updateReturns404ForUnknownBinding() {
        login();
        assertEquals(404, PUT("/api/channels/slack/bindings/9999999",
                "application/json", "{}").status.intValue());
    }

    @Test
    void updateRejectsBlankBody() {
        var agentId = seedAgent("sb-agent");
        var bindingId = seedBinding(agentId, "xoxb-t");
        login();
        assertEquals(400, PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "").status.intValue());
    }

    @Test
    void updateAcceptsEnabledToggle() {
        // Toggling enabled doesn't change the token, so no auth.test probe runs.
        var agentId = seedAgent("sb-agent");
        var bindingId = seedBinding(agentId, "xoxb-t");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"enabled\": false}");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"enabled\":false"),
                "update should surface the toggled enabled state: " + getContent(response));
    }

    @Test
    void updateLeavesSigningSecretWhenOmitted() {
        // Omitting signingSecret (or sending blank) keeps the stored one — the
        // required secret is never nulled out. hasSigningSecret stays true.
        var agentId = seedAgent("sb-agent");
        var bindingId = seedBinding(agentId, "xoxb-t");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"enabled\": true}");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"hasSigningSecret\":true"),
                "omitting signingSecret must keep the stored secret: " + getContent(response));
    }

    @Test
    void updateConflictsOnSwapToTokenAlreadyUsed() {
        var aId = seedAgent("a");
        var bId = seedAgent("b");
        seedBinding(aId, "xoxb-AAA");
        var bBindingId = seedBinding(bId, "xoxb-BBB");
        login();
        // Attempt to change binding B's token to the one A already has.
        var response = PUT("/api/channels/slack/bindings/" + bBindingId,
                "application/json", "{\"botToken\": \"xoxb-AAA\"}");
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateConflictsOnReassignToAgentAlreadyBound() {
        var aId = seedAgent("a");
        var bId = seedAgent("b");
        seedBinding(aId, "xoxb-AAA");
        var bBindingId = seedBinding(bId, "xoxb-BBB");
        login();
        // Attempt to reassign binding B to agent A — A is already bound.
        var response = PUT("/api/channels/slack/bindings/" + bBindingId,
                "application/json", "{\"agentId\": %d}".formatted(aId));
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateAcceptsReassignWhenAgentBoundIsItself() {
        // Reassigning a binding "to itself" must NOT trip the agent-already-bound
        // 409 — the conflict check filters by (agent matches AND id != current).
        var agentId = seedAgent("sb-agent");
        var bindingId = seedBinding(agentId, "xoxb-t");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"agentId\": %d}".formatted(agentId));
        assertIsOk(response);
    }

    // ===== Delete =====

    @Test
    void deleteReturns404ForUnknownBinding() {
        login();
        assertEquals(404, DELETE("/api/channels/slack/bindings/9999999").status.intValue());
    }

    @Test
    void deleteRemovesBinding() {
        var agentId = seedAgent("sb-agent");
        var bindingId = seedBinding(agentId, "xoxb-t");
        login();
        var response = DELETE("/api/channels/slack/bindings/" + bindingId);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"status\":\"ok\""),
                "delete should report ok: " + getContent(response));
        var list = GET("/api/channels/slack/bindings");
        assertIsOk(list);
        assertEquals("[]", getContent(list).trim());
    }
}
