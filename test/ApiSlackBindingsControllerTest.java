import channels.ChannelTransport;
import com.google.gson.JsonParser;
import models.Agent;
import models.SlackBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import play.test.Fixtures;
import play.test.FunctionalTest;
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
        var t = Thread.ofPlatform().start(() -> {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateBodies")
    void createRejectsInvalidBody(String name, String body) {
        // Seeding an agent is harmless for the missing/unknown-agentId cases (they
        // reject regardless of what agents exist) and supplies the id the
        // missing-botToken/missing-signingSecret bodies interpolate.
        var agentId = seedAgent("sb-agent");
        login();
        assertEquals(400, POST("/api/channels/slack/bindings",
                "application/json", body.formatted(agentId)).status.intValue());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidCreateBodies() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("missingBotToken",
                        """
                        {"signingSecret": "s", "agentId": %d}
                        """),
                org.junit.jupiter.params.provider.Arguments.of("missingSigningSecret",
                        """
                        {"botToken": "xoxb-t", "agentId": %d}
                        """),
                org.junit.jupiter.params.provider.Arguments.of("missingAgentId",
                        """
                        {"botToken": "xoxb-t", "signingSecret": "s"}
                        """),
                org.junit.jupiter.params.provider.Arguments.of("unknownAgent",
                        """
                        {"botToken": "xoxb-t", "signingSecret": "s", "agentId": 9999999}
                        """));
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
    void createRejectsSocketBindingWithoutAppToken() {
        // JCLAW-351: Socket Mode authenticates the WebSocket with an app-level token, so
        // a SOCKET binding must supply one (and needs no signing secret). The guard runs
        // before the identity probe — no network call.
        var agentId = seedAgent("sb-agent");
        login();
        var body = """
                {"botToken": "xoxb-t", "transport": "SOCKET", "agentId": %d}
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

    // ===== JCLAW-707: test() health probe, effectiveRequestUrl null branches, and
    // update-helper branches. Seeds use a BLANK bot token so the delivery-scope /
    // auth.test probes short-circuit with no network (SlackWebApi returns early for
    // a blank token) — validation/conflict/null-url cases never reach the probe. =====

    /** Seed a SOCKET binding (app token, no signing secret) directly via the model. */
    private Long seedSocketBinding(Long agentId, String token) {
        return commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = token;
            b.appToken = "xapp-seed";
            b.transport = ChannelTransport.SOCKET;
            b.webhookBaseUrl = "https://example.com";
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    /** Seed an HTTP binding with no public base URL (effectiveRequestUrl must be null). */
    private Long seedBindingNoBaseUrl(Long agentId, String token) {
        return commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = token;
            b.signingSecret = "seed-signing-secret";
            b.transport = ChannelTransport.HTTP;
            b.webhookBaseUrl = null;
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    /** Seed the main agent + an owner-scoped HTTP binding; returns the binding id. */
    private Long seedMainAgentOwnerBinding() {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "main";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = "";
            b.signingSecret = "seed-signing-secret";
            b.transport = ChannelTransport.HTTP;
            b.webhookBaseUrl = "https://example.com";
            b.ownerUserId = "U-owner";
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    @Test
    void testEndpointReturns404ForUnknownBinding() {
        login();
        assertEquals(404, POST("/api/channels/slack/bindings/9999999/test",
                "application/json", "{}").status.intValue());
    }

    @Test
    void testEndpointReportsOkFalseForBlankToken() {
        // A blank bot token fails auth.test WITHOUT a network call (SlackWebApi
        // short-circuits blank tokens), so the health probe returns ok=false — the
        // endpoint's failure verdict, surfaced at HTTP 200.
        var agentId = seedAgent("sb-test-probe");
        var bindingId = seedBinding(agentId, "");
        login();
        var response = POST("/api/channels/slack/bindings/" + bindingId + "/test",
                "application/json", "{}");
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertFalse(obj.get("ok").getAsBoolean(),
                "a blank bot token must report ok=false from the health probe: " + getContent(response));
    }

    @Test
    void listOmitsRequestUrlForSocketBinding() {
        // effectiveRequestUrl is null for a non-HTTP (SOCKET) binding — Socket Mode
        // needs no public Request URL.
        var agentId = seedAgent("sb-socket");
        seedSocketBinding(agentId, "xoxb-socket");
        login();
        var arr = JsonParser.parseString(getContent(GET("/api/channels/slack/bindings")))
                .getAsJsonArray();
        assertEquals(1, arr.size());
        var row = arr.get(0).getAsJsonObject();
        assertEquals("SOCKET", row.get("transport").getAsString());
        assertTrue(row.get("effectiveRequestUrl").isJsonNull(),
                "a SOCKET binding must expose no Events API Request URL: " + row);
    }

    @Test
    void listOmitsRequestUrlWhenBaseUrlMissing() {
        // effectiveRequestUrl is null for an HTTP binding with no public base URL.
        var agentId = seedAgent("sb-nobase");
        seedBindingNoBaseUrl(agentId, "xoxb-nobase");
        login();
        var arr = JsonParser.parseString(getContent(GET("/api/channels/slack/bindings")))
                .getAsJsonArray();
        var row = arr.get(0).getAsJsonObject();
        assertTrue(row.get("effectiveRequestUrl").isJsonNull(),
                "an HTTP binding without a base URL must expose no Request URL: " + row);
    }

    @Test
    void updateRebuildsRequestUrlAndSetsOptionalFields() {
        // Update webhookBaseUrl + replyToMode + ownerUserId on an HTTP binding.
        // effectiveRequestUrl must be recomputed against the new base.
        var agentId = seedAgent("sb-optional");
        var bindingId = seedBinding(agentId, "");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId, "application/json",
                "{\"webhookBaseUrl\": \"https://new.example.org\", \"replyToMode\": \"thread\", \"ownerUserId\": \"U12345\"}");
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("thread", obj.get("replyToMode").getAsString());
        assertEquals("U12345", obj.get("ownerUserId").getAsString());
        assertEquals("https://new.example.org/api/webhooks/slack/" + bindingId,
                obj.get("effectiveRequestUrl").getAsString(),
                "effectiveRequestUrl must be rebuilt from the updated base: " + obj);
    }

    @Test
    void updateWithBlankBotTokenIsANoOp() {
        // applyBotTokenUpdate returns false for a blank token (never nulls the
        // required stored one), so the binding still saves and the sibling field
        // still applies.
        var agentId = seedAgent("sb-blank-token");
        var bindingId = seedBinding(agentId, "");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"botToken\": \"\", \"enabled\": false}");
        assertIsOk(response);
        var obj = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertFalse(obj.get("enabled").getAsBoolean(),
                "the sibling enabled toggle must still apply: " + obj);
    }

    @Test
    void updateReassignsToDifferentUnboundAgent() {
        var agentA = seedAgent("sb-from");
        var bindingId = seedBinding(agentA, "");
        var agentB = seedAgent("sb-to");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"agentId\": %d}".formatted(agentB));
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"agentName\":\"sb-to\""),
                "reassigning to an unbound agent must update the binding: " + getContent(response));
    }

    @Test
    void updateRejectsUnknownAgentIdWith400() {
        var agentId = seedAgent("sb-unknown-reassign");
        var bindingId = seedBinding(agentId, "");
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"agentId\": 9999999}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void updateRejectsClearingOwnerOnMainAgentBinding() {
        // JCLAW-354: a main-agent binding must keep an owner user id — clearing it
        // on update is rejected with 400 (the guard runs before save, no network).
        var bindingId = seedMainAgentOwnerBinding();
        login();
        var response = PUT("/api/channels/slack/bindings/" + bindingId,
                "application/json", "{\"ownerUserId\": \"\"}");
        assertEquals(400, response.status.intValue());
    }
}
