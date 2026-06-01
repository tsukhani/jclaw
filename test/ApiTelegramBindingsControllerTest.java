import channels.ChannelTransport;
import channels.TelegramPollingRunner;
import channels.TelegramPollingRunnerTestHooks;
import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.*;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional HTTP tests for {@code ApiTelegramBindingsController} (JCLAW-313).
 * Covers the CRUD surface behind {@code /api/channels/telegram/bindings},
 * including the auth boundary, body-validation matrix, and the 409 conflict
 * paths (duplicate bot token, agent already bound). The controller calls
 * {@link TelegramPollingRunner#reconcile} after each mutation; we drain
 * any sessions it stood up in {@code @AfterEach} so the SDK's background
 * scheduler doesn't leak between tests.
 *
 * <p>Companion to {@code WebhookControllerTest} (webhook side) — this file
 * owns the admin API side.
 */
class ApiTelegramBindingsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void teardown() {
        // reconcile() may have stood up live polling sessions against
        // api.telegram.org. Clear the runner's static state via the
        // JCLAW-316 test seam so the SDK scheduler doesn't carry state
        // across tests. Do NOT call TelegramPollingRunner.stop() — that
        // terminates the singleton SCHEDULER (final field, can't be
        // replaced) and poisons subsequent test classes that rely on
        // scheduled cooldown re-reconciles (TelegramPollingRunnerTest).
        try { TelegramPollingRunnerTestHooks.clear(); } catch (Exception _) { /* best-effort */ }
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

    private Long seedBinding(Long agentId, String token, String tgUserId) {
        return commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = token;
            b.telegramUserId = tgUserId;
            b.transport = ChannelTransport.WEBHOOK;
            b.webhookSecret = "ws-secret";
            b.webhookUrl = "https://example.com/tg";
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    // ===== Auth gate =====

    @Test
    void listRequiresAuth() {
        var response = GET("/api/channels/telegram/bindings");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void createRequiresAuth() {
        var response = POST("/api/channels/telegram/bindings",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        var response = PUT("/api/channels/telegram/bindings/1",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        var response = DELETE("/api/channels/telegram/bindings/1");
        assertEquals(401, response.status.intValue());
    }

    // ===== List =====

    @Test
    void listReturnsEmptyArrayWhenNoBindings() {
        login();
        var response = GET("/api/channels/telegram/bindings");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertEquals("[]", getContent(response).trim());
    }

    @Test
    void listReturnsBindingsHidingSecrets() {
        var agentId = seedAgent("tg-agent");
        seedBinding(agentId, "111:tok", "42");
        login();
        var response = GET("/api/channels/telegram/bindings");
        assertIsOk(response);
        var content = getContent(response);
        // BindingView projects botToken/webhookSecret as elided fields —
        // botToken must not appear in the payload.
        assertFalse(content.contains("111:tok"),
                "list response should not surface botToken bytes: " + content);
        assertTrue(content.contains("\"telegramUserId\":\"42\""),
                "list should include telegramUserId: " + content);
        assertTrue(content.contains("\"hasWebhookSecret\":true"),
                "list should flag webhook-secret presence: " + content);
        assertTrue(content.contains("\"transport\":\"WEBHOOK\""),
                "list should surface transport: " + content);
    }

    // ===== Create =====

    @Test
    void createRejectsBlankBody() {
        login();
        var response = POST("/api/channels/telegram/bindings",
                "application/json", "");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsMissingBotToken() {
        var agentId = seedAgent("tg-agent");
        login();
        var body = """
                {"agentId": %d, "telegramUserId": "42"}
                """.formatted(agentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsMissingAgentId() {
        login();
        var body = """
                {"botToken": "111:tok", "telegramUserId": "42"}
                """;
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsNonNumericTelegramUserId() {
        var agentId = seedAgent("tg-agent");
        login();
        var body = """
                {"botToken": "111:tok", "agentId": %d, "telegramUserId": "abc"}
                """.formatted(agentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsUnknownAgent() {
        login();
        var body = """
                {"botToken": "111:tok", "agentId": 9999999, "telegramUserId": "42"}
                """;
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createRejectsDisabledAgent() {
        var disabledAgentId = seedDisabledAgent("disabled-agent");
        login();
        var body = """
                {"botToken": "111:tok", "agentId": %d, "telegramUserId": "42"}
                """.formatted(disabledAgentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void createConflictsOnDuplicateBotToken() {
        var firstAgentId = seedAgent("agent-a");
        var secondAgentId = seedAgent("agent-b");
        seedBinding(firstAgentId, "duplicate:tok", "1");
        login();
        var body = """
                {"botToken": "duplicate:tok", "agentId": %d, "telegramUserId": "2"}
                """.formatted(secondAgentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void createConflictsWhenAgentAlreadyBound() {
        // Privacy invariant: one agent → one binding (memory is scoped per
        // agent so re-binding would leak memories across users).
        var agentId = seedAgent("shared-agent");
        seedBinding(agentId, "111:tok", "1");
        login();
        var body = """
                {"botToken": "222:tok", "agentId": %d, "telegramUserId": "2"}
                """.formatted(agentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void createSucceedsAndReturnsViewWithoutSecrets() {
        var agentId = seedAgent("tg-agent");
        login();
        var body = """
                {"botToken": "200:tok", "agentId": %d, "telegramUserId": "42",
                 "transport": "WEBHOOK", "webhookSecret": "ws", "webhookUrl": "https://example.com/tg"}
                """.formatted(agentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertFalse(content.contains("200:tok"),
                "create response must elide botToken: " + content);
        assertFalse(content.contains("\"ws\""),
                "create response must elide webhookSecret: " + content);
        assertTrue(content.contains("\"hasWebhookSecret\":true"),
                "create response should flag webhookSecret presence: " + content);
        assertTrue(content.contains("\"telegramUserId\":\"42\""),
                "create response should surface telegramUserId: " + content);
    }

    @Test
    void createDefaultsTransportToPolling() {
        var agentId = seedAgent("tg-agent");
        login();
        // enabled=false so post-mutation reconcile() doesn't trigger a real api.telegram.org call via the SDK; the persisted transport field is still POLLING for assertion.
        var body = """
                {"botToken": "300:tok", "agentId": %d, "telegramUserId": "7", "enabled": false}
                """.formatted(agentId);
        var response = POST("/api/channels/telegram/bindings",
                "application/json", body);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"transport\":\"POLLING\""),
                "default transport should be POLLING: " + getContent(response));
    }

    // ===== Update =====

    @Test
    void updateReturns404ForUnknownBinding() {
        login();
        var response = PUT("/api/channels/telegram/bindings/9999999",
                "application/json", "{}");
        assertEquals(404, response.status.intValue());
    }

    @Test
    void updateRejectsBlankBody() {
        var agentId = seedAgent("tg-agent");
        var bindingId = seedBinding(agentId, "111:tok", "42");
        login();
        var response = PUT("/api/channels/telegram/bindings/" + bindingId,
                "application/json", "");
        assertEquals(400, response.status.intValue());
    }

    /**
     * Accepted single-field updates that share the seed + PUT + 200 +
     * response-contains skeleton: the enabled toggle, clearing the webhook
     * secret, and switching transport. {@code enabled:false} on the transport
     * case keeps post-mutation reconcile() from hitting api.telegram.org while
     * the persisted transport field still asserts as POLLING.
     */
    @ParameterizedTest(name = "updateAccepts[{0}]")
    @CsvSource(delimiter = '|', value = {
            "EnabledToggle      | {\"enabled\": false}                          | \"enabled\":false",
            "ClearWebhookSecret | {\"webhookSecret\": null}                     | \"hasWebhookSecret\":false",
            "TransportChange    | {\"transport\": \"POLLING\", \"enabled\": false} | \"transport\":\"POLLING\""
    })
    void updateAcceptsSingleFieldChange(String label, String body, String expectedSubstring) {
        var agentId = seedAgent("tg-agent");
        var bindingId = seedBinding(agentId, "111:tok", "42");
        login();
        var response = PUT("/api/channels/telegram/bindings/" + bindingId,
                "application/json", body);
        assertIsOk(response);
        assertTrue(getContent(response).contains(expectedSubstring),
                label + " should surface " + expectedSubstring + ": " + getContent(response));
    }

    /**
     * Two body-validation rejections that share the seed + PUT + 400
     * skeleton: non-numeric telegramUserId and reference to an unknown
     * agentId.
     */
    @ParameterizedTest(name = "updateRejects[{0}]")
    @CsvSource(delimiter = '|', value = {
            "NonNumericTelegramUserId | {\"telegramUserId\": \"abc\"}",
            "UnknownAgentId           | {\"agentId\": 9999999}"
    })
    void updateRejectsInvalidPayload(String label, String body) {
        var agentId = seedAgent("tg-agent");
        var bindingId = seedBinding(agentId, "111:tok", "42");
        login();
        var response = PUT("/api/channels/telegram/bindings/" + bindingId,
                "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void updateConflictsOnSwapToTokenAlreadyUsed() {
        var aId = seedAgent("a");
        var bId = seedAgent("b");
        seedBinding(aId, "AAA:tok", "1");
        var bBindingId = seedBinding(bId, "BBB:tok", "2");
        login();
        // Attempt to change binding B's token to the one A already has.
        var body = "{\"botToken\": \"AAA:tok\"}";
        var response = PUT("/api/channels/telegram/bindings/" + bBindingId,
                "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateConflictsOnReassignToAgentAlreadyBound() {
        var aId = seedAgent("a");
        var bId = seedAgent("b");
        seedBinding(aId, "AAA:tok", "1");
        var bBindingId = seedBinding(bId, "BBB:tok", "2");
        login();
        // Attempt to reassign binding B to agent A — A is already bound.
        var body = "{\"agentId\": %d}".formatted(aId);
        var response = PUT("/api/channels/telegram/bindings/" + bBindingId,
                "application/json", body);
        assertEquals(409, response.status.intValue());
    }

    @Test
    void updateAcceptsReassignWhenAgentBoundIsItself() {
        // Reassigning a binding "to itself" must NOT trip the agent-already-bound
        // 409 — the conflict check filters by (agent matches AND id != current).
        var agentId = seedAgent("tg-agent");
        var bindingId = seedBinding(agentId, "111:tok", "42");
        login();
        var body = "{\"agentId\": %d}".formatted(agentId);
        var response = PUT("/api/channels/telegram/bindings/" + bindingId,
                "application/json", body);
        assertIsOk(response);
    }

    // updateCanClearWebhookSecret and updateAcceptsTransportChange merged into
    // updateAcceptsSingleFieldChange above.

    // ===== effectiveWebhookUrl (JCLAW-338) =====

    @Test
    void pollingBindingNeverExposesEffectiveWebhookUrl() {
        // The funnel-derived webhook URL is WEBHOOK-only. A POLLING binding must
        // never carry it — the deriver short-circuits on transport before even
        // probing tailscale — so the read side stays deterministic regardless of
        // whether a Funnel is live on the host running the tests. (The WEBHOOK
        // case can't be asserted here: its value depends on a real `tailscale`
        // CLI + an active Funnel, which CI doesn't have.)
        var agentId = seedAgent("tg-agent");
        login();
        var body = """
                {"botToken": "400:tok", "agentId": %d, "telegramUserId": "9", "enabled": false}
                """.formatted(agentId);
        var create = POST("/api/channels/telegram/bindings", "application/json", body);
        assertIsOk(create);
        assertFalse(getContent(create).contains("/api/webhooks/telegram/"),
                "polling create must not carry a funnel webhook URL: " + getContent(create));

        var list = GET("/api/channels/telegram/bindings");
        assertIsOk(list);
        assertFalse(getContent(list).contains("/api/webhooks/telegram/"),
                "polling list must not carry a funnel webhook URL: " + getContent(list));
    }

    // ===== Delete =====

    @Test
    void deleteReturns404ForUnknownBinding() {
        login();
        var response = DELETE("/api/channels/telegram/bindings/9999999");
        assertEquals(404, response.status.intValue());
    }

    @Test
    void deleteRemovesBinding() {
        var agentId = seedAgent("tg-agent");
        var bindingId = seedBinding(agentId, "111:tok", "42");
        login();
        var response = DELETE("/api/channels/telegram/bindings/" + bindingId);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"status\":\"ok\""),
                "delete should report ok: " + getContent(response));
        // Confirm the row is gone from the read side.
        var list = GET("/api/channels/telegram/bindings");
        assertIsOk(list);
        assertEquals("[]", getContent(list).trim());
    }
}
