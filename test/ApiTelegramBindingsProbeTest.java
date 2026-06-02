import channels.ChannelTransport;
import channels.TelegramChannel;
import channels.TelegramPollingRunnerTestHooks;
import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import play.test.*;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional HTTP tests for the binding health-probe endpoint
 * {@code POST /api/channels/telegram/bindings/{id}/test} (JCLAW-362). The
 * probe calls {@code getMe} (always) and {@code getWebhookInfo} (WEBHOOK
 * bindings only) against the live Bot API; here that traffic is redirected
 * to an embedded {@link MockTelegramServer} via
 * {@link TelegramChannel#installForTest}, so we can assert the structured
 * {@code ProbeResult} JSON for the ok, bad-token (401), and webhook-state
 * cases without touching api.telegram.org.
 *
 * <p>Companion to {@code ApiTelegramBindingsControllerTest} (CRUD side) —
 * this file owns the probe side.
 */
class ApiTelegramBindingsProbeTest extends FunctionalTest {

    private static final String OK_BOT_TOKEN = "probe:ok-token";
    private static final String BAD_BOT_TOKEN = "probe:bad-token";

    // A minimal valid getMe User payload the SDK deserializer accepts.
    private static final String GET_ME_OK =
            "{\"ok\":true,\"result\":{\"id\":4242,\"is_bot\":true,"
                    + "\"first_name\":\"JClaw Bot\",\"username\":\"jclaw_test_bot\"}}";
    // Telegram's 401 shape for a revoked/typo'd token.
    private static final String UNAUTHORIZED =
            "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";

    private MockTelegramServer server;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        server = new MockTelegramServer();
        server.start();
        // Both tokens target the mock; per-test respondWith overrides shape
        // the getMe / getWebhookInfo responses.
        TelegramChannel.installForTest(OK_BOT_TOKEN, server.telegramUrl());
        TelegramChannel.installForTest(BAD_BOT_TOKEN, server.telegramUrl());
    }

    @AfterEach
    void teardown() {
        if (server != null) server.close();
        TelegramChannel.clearForTest(OK_BOT_TOKEN);
        TelegramChannel.clearForTest(BAD_BOT_TOKEN);
        try { TelegramPollingRunnerTestHooks.clear(); } catch (Exception _) { /* best-effort */ }
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        assertIsOk(POST("/api/auth/login", "application/json", body));
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

    private Long seedBinding(Long agentId, String token, ChannelTransport transport) {
        return commitInFreshTx(() -> {
            var agent = (Agent) Agent.findById(agentId);
            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = token;
            b.telegramUserId = "42";
            b.transport = transport;
            if (transport == ChannelTransport.WEBHOOK) {
                b.webhookSecret = "ws-secret";
                b.webhookBaseUrl = "https://example.com/tg";
            }
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    // ===== Auth gate =====

    @Test
    void testRequiresAuth() {
        var response = POST("/api/channels/telegram/bindings/1/test", "application/json", "");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void testReturns404ForUnknownBinding() {
        login();
        var response = POST("/api/channels/telegram/bindings/9999999/test", "application/json", "");
        assertEquals(404, response.status.intValue());
    }

    // ===== Probe outcomes =====

    @Test
    void probeReportsOkAndBotUsernameForPollingBinding() {
        server.respondWith("getMe", 200, GET_ME_OK);
        var agentId = seedAgent("probe-agent");
        var bindingId = seedBinding(agentId, OK_BOT_TOKEN, ChannelTransport.POLLING);
        login();
        var response = POST("/api/channels/telegram/bindings/" + bindingId + "/test",
                "application/json", "");
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertTrue(content.contains("\"ok\":true"),
                "probe should report ok: " + content);
        assertTrue(content.contains("\"botUsername\":\"jclaw_test_bot\""),
                "probe should surface the bot username: " + content);
        assertTrue(content.contains("\"transport\":\"POLLING\""),
                "probe should echo the transport: " + content);
        // POLLING bindings don't call getWebhookInfo.
        assertEquals(0, server.countRequests("getWebhookInfo"),
                "polling probe must not call getWebhookInfo");
        assertEquals(1, server.countRequests("getMe"),
                "probe should call getMe exactly once");
    }

    @Test
    void probeReportsErrorOnBadToken() {
        server.respondWith("getMe", 401, UNAUTHORIZED);
        var agentId = seedAgent("probe-agent");
        var bindingId = seedBinding(agentId, BAD_BOT_TOKEN, ChannelTransport.POLLING);
        login();
        var response = POST("/api/channels/telegram/bindings/" + bindingId + "/test",
                "application/json", "");
        // The endpoint stays 200 — the verdict travels in the ok flag.
        assertIsOk(response);
        var content = getContent(response);
        assertTrue(content.contains("\"ok\":false"),
                "bad-token probe should report not-ok: " + content);
        assertTrue(content.contains("getMe failed"),
                "bad-token probe should carry an error reason: " + content);
    }

    @Test
    void probeReportsWebhookStateForWebhookBinding() {
        server.respondWith("getMe", 200, GET_ME_OK);
        server.respondWith("getWebhookInfo", 200,
                "{\"ok\":true,\"result\":{\"url\":\"https://example.com/tg/api/webhooks/telegram/1/ws-secret\","
                        + "\"has_custom_certificate\":false,\"pending_update_count\":3,"
                        + "\"last_error_message\":\"Wrong response from the webhook\"}}");
        var agentId = seedAgent("probe-agent");
        var bindingId = seedBinding(agentId, OK_BOT_TOKEN, ChannelTransport.WEBHOOK);
        login();
        var response = POST("/api/channels/telegram/bindings/" + bindingId + "/test",
                "application/json", "");
        assertIsOk(response);
        var content = getContent(response);
        assertTrue(content.contains("\"ok\":true"),
                "webhook probe should report ok: " + content);
        assertTrue(content.contains("\"transport\":\"WEBHOOK\""),
                "webhook probe should echo the transport: " + content);
        assertTrue(content.contains("\"webhookPendingUpdates\":3"),
                "webhook probe should surface the pending-update count: " + content);
        assertTrue(content.contains("Wrong response from the webhook"),
                "webhook probe should surface the last error: " + content);
        assertEquals(1, server.countRequests("getWebhookInfo"),
                "webhook probe should call getWebhookInfo once");
    }
}
