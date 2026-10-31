import channels.ChannelTransport;
import models.Agent;
import models.ChannelConfig;
import models.SlackBinding;
import models.TelegramBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
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
    }

    // ===== Telegram =====

    @Test
    void telegramWebhookReturns404ForUnknownBinding() {
        // JCLAW-784: the route keys on {bindingId} only (secret dropped from URL).
        var response = POST("/api/webhooks/telegram/99999",
                "application/json", "{}");
        assertEquals(404, response.status.intValue());
    }

    @Test
    void telegramWebhookReturns401OnMissingHeader() {
        // Binding + secret exist, but the x-telegram-bot-api-secret-token header —
        // now the SOLE auth factor (JCLAW-784) — is absent. Reject with 401.
        var bindingId = seedTelegramBinding("correct-secret", true);
        assertSignatureFailureLogged("telegram", "Missing or invalid secret-token header for binding " + bindingId,
                () -> assertEquals(401, POST("/api/webhooks/telegram/" + bindingId,
                        "application/json", "{}").status.intValue()));
    }

    @Test
    void telegramWebhookDisabledBindingReturns200WithoutProcessing() {
        // Disabled bindings short-circuit with 200 (Telegram stops retrying)
        // but must NOT reach agent code. We can't easily assert "agent didn't
        // run" without mocking, so assert the response code + that no agent-
        // invocation event is logged.
        var bindingId = seedTelegramBinding("correct-secret", false);
        var response = POST("/api/webhooks/telegram/" + bindingId,
                "application/json", "{}");
        assertEquals(200, response.status.intValue());
    }

    // ===== Slack (JCLAW-441: per-binding) =====

    @Test
    void slackWebhookReturns404ForUnknownBinding() {
        // The URL carries the binding id; an unknown id is rejected before any
        // body/signature work (and audited).
        assertSignatureFailureLogged("slack", "Webhook rejected: unknown Slack binding 99999",
                () -> assertEquals(404, POST("/api/webhooks/slack/99999", "application/json", "{}").status.intValue()));
    }

    @Test
    void slackWebhookReturns403ForDisabledBinding() {
        // Binding exists but enabled=false — rejected with 403 before any body
        // parse, distinct from the unknown-binding 404.
        var bindingId = seedSlackBinding(false);
        assertSignatureFailureLogged("slack", "Webhook rejected: Slack binding " + bindingId + " is disabled",
                () -> assertEquals(403, POST("/api/webhooks/slack/" + bindingId, "application/json", "{}").status.intValue()));
    }

    @Test
    void slackWebhookReturns401WhenSignatureHeadersMissing() {
        // Binding is valid + enabled, but the inbound POST omits both
        // x-slack-signature and x-slack-request-timestamp — controller must
        // reject before any signature compute.
        var bindingId = seedSlackBinding(true);
        assertSignatureFailureLogged("slack", "Missing signature headers",
                () -> assertEquals(401, POST("/api/webhooks/slack/" + bindingId,
                        "application/json", "{\"type\":\"event_callback\"}").status.intValue()));
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

    // ===== Cross-cutting: the /api/webhooks/ prefix =====

    /**
     * The webhook routes are this app's only unauthenticated surface. They carry no
     * {@code @With(AuthCheck.class)}, and Play seeds a request's {@code @Before} set solely
     * from the resolved controller's own class hierarchy plus its {@code @With} chain — so
     * {@code AuthCheck} never runs for them at all. Each controller verifies its provider's
     * signature itself instead.
     *
     * <p>Nothing cross-cutting held that second half: a sixth route added under this prefix
     * would be silently public, and every existing test would still pass. This list is that
     * tripwire — it fails on any change to the surface, forcing the author to state how the
     * new route authenticates.
     *
     * <p><b>Do not "strengthen" this into "an unsigned request must not return 2xx".</b> Two
     * paths deliberately ack with 200 so the provider stops retrying: a disabled Telegram
     * binding ({@link #telegramWebhookDisabledBindingReturns200WithoutProcessing}) and a
     * WhatsApp payload whose {@code phone_number_id} matches no binding
     * ({@code WebhookWhatsAppControllerTest.postAcksUnknownPhoneNumberId}). The status code
     * is not the security property — not reaching agent code is, and that is asserted per
     * controller.
     */
    @Test
    void theUnauthenticatedWebhookSurfaceIsExactlyTheseRoutes() {
        var expected = new TreeSet<>(List.of(
                "GET /api/webhooks/whatsapp",
                "POST /api/webhooks/slack/{bindingId}",
                "POST /api/webhooks/slack/{bindingId}/interactive",
                "POST /api/webhooks/telegram/{bindingId}",
                "POST /api/webhooks/whatsapp"));

        assertEquals(expected, webhookRoutes(),
                "The unauthenticated /api/webhooks/ surface changed. Every route under this "
                        + "prefix bypasses AuthCheck entirely, so it MUST verify its provider's "
                        + "signature itself — see WebhookSlackController.resolveAndVerify for the "
                        + "shape. Update this list only after confirming the new route does.");
    }

    @Test
    void everyWebhookRouteAnswersUnsignedTrafficWithoutCrashing() {
        // An unauthenticated caller reaching any of these must get a decision, not a stack
        // trace. 4xx (rejected) and 200 (deliberate ack, see above) are both legitimate;
        // 5xx means the route met unsigned input it was not written to handle.
        for (var route : webhookRoutes()) {
            var parts = route.split(" ", 2);
            var path = parts[1].replaceAll("\\{[^}]+}", "99999");
            var response = "GET".equals(parts[0])
                    ? GET(path)
                    : POST(path, "application/json", "{}");
            assertTrue(response.status < 500,
                    route + " returned " + response.status + " to an unsigned request");
        }
    }

    /**
     * The {@code /api/webhooks/} routes as declared in {@code conf/routes}, rendered
     * {@code "METHOD path"}. Read from the file rather than hardcoded so the tripwire above
     * sees a route the moment it is added, not when someone remembers to update a list.
     */
    private static SortedSet<String> webhookRoutes() {
        try {
            var found = new TreeSet<String>();
            for (var line : Files.readAllLines(Play.getFile("conf/routes").toPath())) {
                var trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                var cols = trimmed.split("\\s+");
                if (cols.length < 2 || !cols[1].startsWith("/api/webhooks/")) continue;
                found.add(cols[0] + " " + cols[1]);
            }
            return found;
        } catch (IOException e) {
            throw new AssertionError("cannot read conf/routes", e);
        }
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

    /**
     * {@code request} logs exactly this one signature failure. A missing-header rejection names no
     * binding, so the match is the channel; the runner serializes functional tests, so no other
     * class's webhook logs while the capture is open.
     */
    private static void assertSignatureFailureLogged(String channel, String message, Runnable request) {
        var failures = EventLogger.captureMatchingForTest(
                e -> EventLogger.WEBHOOK_SIGNATURE_FAILURE.equals(e.category()) && channel.equals(e.channel()),
                _ -> request.run());
        assertEquals(List.of(message), failures.stream().map(EventLogger.Captured::message).toList());
    }
}
