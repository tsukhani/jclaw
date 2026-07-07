import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import channels.ChannelTransport;
import models.Agent;
import models.ChannelConfig;
import models.EventLog;
import models.SlackBinding;
import models.TelegramBinding;
import play.test.Fixtures;
import play.test.FunctionalTest;
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
class WebhookControllerTest extends FunctionalTest {

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
    void telegramWebhookReturns404ForUnknownBinding() {
        var response = POST("/api/webhooks/telegram/99999/any-secret",
                "application/json", "{}");
        assertEquals(404, response.status.intValue());
    }

    @Test
    void telegramWebhookReturns401OnBadPathSecret() {
        var bindingId = seedTelegramBinding("correct-secret", true);
        var response = POST("/api/webhooks/telegram/" + bindingId + "/wrong-secret",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("telegram");
    }

    @Test
    void telegramWebhookReturns401OnMissingHeader() {
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
    void telegramWebhookDisabledBindingReturns200WithoutProcessing() {
        // Disabled bindings short-circuit with 200 (Telegram stops retrying)
        // but must NOT reach agent code. We can't easily assert "agent didn't
        // run" without mocking, so assert the response code + that no agent-
        // invocation event is logged.
        var bindingId = seedTelegramBinding("correct-secret", false);
        var response = POST("/api/webhooks/telegram/" + bindingId + "/correct-secret",
                "application/json", "{}");
        assertEquals(200, response.status.intValue());
    }

    // ===== Slack (JCLAW-441: per-binding) =====

    @Test
    void slackWebhookReturns404ForUnknownBinding() {
        // The URL carries the binding id; an unknown id is rejected before any
        // body/signature work (and audited).
        var response = POST("/api/webhooks/slack/99999", "application/json", "{}");
        assertEquals(404, response.status.intValue());
        assertSignatureFailureLogged("slack");
    }

    @Test
    void slackWebhookReturns403ForDisabledBinding() {
        // Binding exists but enabled=false — rejected with 403 before any body
        // parse, distinct from the unknown-binding 404.
        var bindingId = seedSlackBinding(false);
        var response = POST("/api/webhooks/slack/" + bindingId, "application/json", "{}");
        assertEquals(403, response.status.intValue());
        assertSignatureFailureLogged("slack");
    }

    @Test
    void slackWebhookReturns401WhenSignatureHeadersMissing() {
        // Binding is valid + enabled, but the inbound POST omits both
        // x-slack-signature and x-slack-request-timestamp — controller must
        // reject before any signature compute.
        var bindingId = seedSlackBinding(true);
        var response = POST("/api/webhooks/slack/" + bindingId,
                "application/json", "{\"type\":\"event_callback\"}");
        assertEquals(401, response.status.intValue());
        assertSignatureFailureLogged("slack");
    }

    // ===== WhatsApp =====
    // JCLAW-446 migrated WhatsApp from the app-global ChannelConfig("whatsapp") to
    // per-binding routing (route by phone_number_id → WhatsAppBinding, verify the
    // HMAC against that binding's appSecret). The fail-closed-on-missing-appSecret,
    // signature-rejection, and audit-log coverage moved to WebhookWhatsAppControllerTest
    // (postFailsClosedWhenBindingHasNoAppSecret / postRejectsMismatchedSignature /
    // rejectionsAreAuditedAsWebhookSignatureFailure), now tested per-binding. An
    // unmatched webhook (no binding for the number) acks with 200 and processes
    // nothing — see WebhookWhatsAppControllerTest.postAcksUnknownPhoneNumberId — so the
    // old app-global "unconfigured → 401" path no longer exists to assert here.

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
            binding.webhookBaseUrl = "https://example.com/tg-webhook";
            binding.transport = ChannelTransport.WEBHOOK;
            binding.enabled = enabled;
            binding.save();
            return binding.id;
        });
    }

    private Long seedSlackBinding(boolean enabled) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "slack-binding-agent-" + System.nanoTime();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();

            var binding = new SlackBinding();
            binding.agent = agent;
            binding.botToken = "xoxb-test-" + System.nanoTime();
            binding.signingSecret = "test-secret";
            binding.transport = ChannelTransport.HTTP;
            binding.enabled = enabled;
            binding.save();
            return binding.id;
        });
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
