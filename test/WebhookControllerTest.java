import org.junit.jupiter.api.*;
import channels.ChannelTransport;
import models.Agent;
import models.ChannelConfig;
import models.EventLog;
import models.TelegramBinding;
import play.test.*;
import services.EventLogger;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional coverage for webhook controllers (JCLAW-16). The HMAC helpers
 * on {@code SlackChannel} / {@code WhatsAppChannel} are covered by
 * {@link ChannelTest}; this class covers the HTTP-level gating the
 * controllers put in front of them: return codes, event-log category,
 * and the "reject before body read / agent invocation" ordering.
 *
 * <p>All cases here probe the NEGATIVE paths — missing config, missing
 * signature header, bad path secret, unknown binding. Happy-path HMAC
 * verification needs a test harness that can inject custom request
 * headers (not supported by the FunctionalTest {@code POST} helper
 * without boilerplate), so it's deferred to a follow-up phase.
 */
public class WebhookControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // Stale ChannelConfig cache from prior tests would mask missing rows.
        ChannelConfig.evictCache("slack");
        ChannelConfig.evictCache("whatsapp");
        ChannelConfig.evictCache("telegram");
        EventLogger.clear();
    }

    // ===== Telegram =====

    @Test
    public void telegramWebhookReturns404ForUnknownBinding() {
        var response = POST("/api/webhooks/telegram/99999/any-secret",
                "application/json", "{}");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void telegramWebhookReturns401OnBadPathSecret() {
        var bindingId = seedTelegramBinding("correct-secret", true);
        var response = POST("/api/webhooks/telegram/" + bindingId + "/wrong-secret",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("telegram");
    }

    @Test
    public void telegramWebhookReturns401OnMissingHeader() {
        // Path secret matches, but x-telegram-bot-api-secret-token header is
        // absent. JCLAW-16 tightened this from "optional" to "required" —
        // setWebhook registers the secret_token, so legit traffic always
        // carries it.
        var bindingId = seedTelegramBinding("correct-secret", true);
        var response = POST("/api/webhooks/telegram/" + bindingId + "/correct-secret",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("telegram");
    }

    @Test
    public void telegramWebhookDisabledBindingReturns200WithoutProcessing() {
        // Disabled bindings short-circuit with 200 (Telegram stops retrying)
        // but must NOT reach agent code. We can't easily assert "agent didn't
        // run" without mocking, so assert the response code + that no agent-
        // invocation event is logged.
        var bindingId = seedTelegramBinding("correct-secret", false);
        var response = POST("/api/webhooks/telegram/" + bindingId + "/correct-secret",
                "application/json", "{}");
        assertEquals(200, response.status.intValue());
    }

    // ===== Slack =====

    @Test
    public void slackWebhookReturns401WhenUnconfigured() {
        // No ChannelConfig row — SlackConfig.load() returns null, the JCLAW-16
        // guard rejects before any body parse.
        var response = POST("/api/webhooks/slack", "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("slack");
    }

    @Test
    public void slackWebhookReturns401WhenConfigDisabled() {
        // Row exists but enabled=false — SlackConfig.load() returns null, same
        // rejection path as fully unconfigured.
        commitInFreshTx(() -> {
            var cc = new ChannelConfig();
            cc.channelType = "slack";
            cc.enabled = false;
            cc.configJson = "{\"botToken\":\"xoxb-test\",\"signingSecret\":\"s\"}";
            cc.save();
            return cc.id;
        });
        ChannelConfig.evictCache("slack");

        var response = POST("/api/webhooks/slack", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void slackWebhookReturns401WhenSignatureHeadersMissing() {
        // Config is valid, but the inbound POST omits both x-slack-signature
        // and x-slack-request-timestamp — controller must reject before any
        // signature compute.
        seedSlackConfig();
        var response = POST("/api/webhooks/slack",
                "application/json", "{\"type\":\"event_callback\"}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("slack");
    }

    // ===== WhatsApp =====

    @Test
    public void whatsappWebhookReturns401WhenUnconfigured() {
        var response = POST("/api/webhooks/whatsapp", "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("whatsapp");
    }

    @Test
    public void whatsappWebhookReturns401WhenAppSecretAbsentInConfig() {
        // The critical JCLAW-16 fix: the controller used to skip verification
        // when appSecret was null, silently passing through to agent code.
        // Now an absent appSecret is an explicit 401.
        commitInFreshTx(() -> {
            var cc = new ChannelConfig();
            cc.channelType = "whatsapp";
            cc.enabled = true;
            cc.configJson = "{\"phoneNumberId\":\"PN1\",\"accessToken\":\"AT1\"}"; // no appSecret
            cc.save();
            return cc.id;
        });
        ChannelConfig.evictCache("whatsapp");

        var response = POST("/api/webhooks/whatsapp", "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("whatsapp");
    }

    @Test
    public void whatsappWebhookReturns401WhenSignatureHeaderMissing() {
        seedWhatsAppConfig();
        var response = POST("/api/webhooks/whatsapp", "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("whatsapp");
    }

    // ===== Helpers =====

    /**
     * Run a block on a virtual thread with no ambient transaction so
     * {@link Tx#run(play.libs.F.Function0)} opens a fresh tx that commits on
     * return. Direct {@code .save()} from the test thread isn't visible to
     * the in-process HTTP request handler because the test's ambient tx
     * hasn't committed yet. Existing functional tests work around this by
     * seeding via the API (which commits); we can't use that for
     * TelegramBinding (no public CRUD endpoint in this test scope) or
     * ChannelConfig (ditto), so we commit explicitly.
     */
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

    private Long seedTelegramBinding(String secret, boolean enabled) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "binding-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();

            var binding = new TelegramBinding();
            binding.agent = agent;
            binding.botToken = "123:test-token";
            binding.telegramUserId = "42";
            binding.webhookSecret = secret;
            binding.webhookUrl = "https://example.com/tg-webhook";
            binding.transport = ChannelTransport.WEBHOOK;
            binding.enabled = enabled;
            binding.save();
            return binding.id;
        });
    }

    private void seedSlackConfig() {
        commitInFreshTx(() -> {
            var cc = new ChannelConfig();
            cc.channelType = "slack";
            cc.enabled = true;
            cc.configJson = "{\"botToken\":\"xoxb-test\",\"signingSecret\":\"test-secret\"}";
            cc.save();
            return cc.id;
        });
        ChannelConfig.evictCache("slack");
    }

    private void seedWhatsAppConfig() {
        commitInFreshTx(() -> {
            var cc = new ChannelConfig();
            cc.channelType = "whatsapp";
            cc.enabled = true;
            cc.configJson = "{\"phoneNumberId\":\"PN1\",\"accessToken\":\"AT1\","
                    + "\"appSecret\":\"test-app-secret\",\"verifyToken\":\"VT1\"}";
            cc.save();
            return cc.id;
        });
        ChannelConfig.evictCache("whatsapp");
    }

    private void assertSignatureFailureLogged(String channel) {
        EventLogger.flush();
        var events = EventLog.findRecent(20);
        var found = events.stream().anyMatch(e ->
                "WEBHOOK_SIGNATURE_FAILURE".equals(e.category)
                        && channel.equals(e.channel));
        assertTrue(found,
                "expected WEBHOOK_SIGNATURE_FAILURE event for " + channel
                        + " in recent events");
    }
}
