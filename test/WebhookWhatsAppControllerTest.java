import models.Agent;
import models.EventLog;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import models.WhatsAppTransport;
import org.junit.jupiter.api.*;
import play.test.*;
import services.EventLogger;
import services.Tx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Coverage for the per-binding-routed Cloud-API webhook
 * {@link controllers.WebhookWhatsAppController} (JCLAW-446). The controller now
 * resolves a {@link WhatsAppBinding} by {@code verify_token} (GET) or
 * {@code phone_number_id} (POST) rather than the pre-446 app-global
 * {@code ChannelConfig("whatsapp")}. This file covers:
 *
 * <ul>
 *   <li>GET hub-verify success/failure routed by the binding's verify token;</li>
 *   <li>POST HMAC verification against the resolved binding's app secret
 *       (fail-closed when absent), routed by phone_number_id;</li>
 *   <li>fast-ack on status/unsupported payloads and on unknown phone-number-ids;</li>
 *   <li>the JCLAW-447 24h-window write on a successfully parsed inbound.</li>
 * </ul>
 */
class WebhookWhatsAppControllerTest extends FunctionalTest {

    private static final String APP_SECRET = "whatsapp-app-secret-fixture";
    private static final String VERIFY_TOKEN = "whatsapp-verify-token-fixture";
    private static final String PHONE_NUMBER_ID = "PN1";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
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

    /** Seed an enabled Cloud-API binding with full inbound credentials. */
    private Long seedBinding(String appSecret) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "wa-webhook-agent-" + System.nanoTime();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = WhatsAppTransport.CLOUD_API;
            b.phoneNumberId = PHONE_NUMBER_ID;
            b.accessToken = "AT1";
            b.appSecret = appSecret;
            b.verifyToken = VERIFY_TOKEN;
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    private static String sign(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static play.mvc.Http.Response postWithSig(String body, String signature) {
        var req = newRequest();
        req.method = "POST";
        req.contentType = "application/json";
        req.url = "/api/webhooks/whatsapp";
        req.path = "/api/webhooks/whatsapp";
        req.querystring = "";
        req.body = new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            req.headers.put("x-hub-signature-256",
                    new play.mvc.Http.Header("x-hub-signature-256", signature));
        }
        return makeRequest(req);
    }

    // ===== GET hub verify (routed by verify token) =====

    @Test
    void verifyReturns403WhenNoBindingOwnsToken() {
        // No binding at all → 403.
        var response = GET("/api/webhooks/whatsapp?hub.mode=subscribe"
                + "&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=c123");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void verifyReturns403OnWrongVerifyToken() {
        seedBinding(APP_SECRET);
        var response = GET("/api/webhooks/whatsapp?hub.mode=subscribe"
                + "&hub.verify_token=wrong&hub.challenge=c123");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void verifyReturns403OnWrongMode() {
        seedBinding(APP_SECRET);
        var response = GET("/api/webhooks/whatsapp?hub.mode=unsubscribe"
                + "&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=c123");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void verifyEchoesChallengeWhenBindingOwnsToken() {
        seedBinding(APP_SECRET);
        var response = GET("/api/webhooks/whatsapp?hub.mode=subscribe"
                + "&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=echo-me-123");
        assertIsOk(response);
        assertEquals("echo-me-123", getContent(response));
    }

    // ===== POST signature (routed by phone_number_id, verified against binding.appSecret) =====

    @Test
    void postRejectsSignatureWithoutSha256Prefix() {
        seedBinding(APP_SECRET);
        var body = textPayload();
        var bareHex = sign(body).substring("sha256=".length());
        var response = postWithSig(body, bareHex);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void postRejectsMismatchedSignature() {
        seedBinding(APP_SECRET);
        var response = postWithSig(textPayload(), "sha256=deadbeef");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void postFailsClosedWhenBindingHasNoAppSecret() {
        // JCLAW-16: an absent appSecret must never become a verification bypass.
        seedBinding(null);
        var body = textPayload();
        var response = postWithSig(body, sign(body));
        assertEquals(401, response.status.intValue());
    }

    @Test
    void postAcksUnknownPhoneNumberId() {
        seedBinding(APP_SECRET);
        // A payload whose phone_number_id matches no binding → fast-ack, no work.
        var body = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"UNKNOWN\"},"
                + "\"messages\":[{\"from\":\"15551234567\",\"id\":\"wamid.u\","
                + "\"type\":\"text\",\"text\":{\"body\":\"hi\"}}]}}]}]}";
        // Signed with the right secret, but routing fails first → 200 (acked, ignored).
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void postAcceptsValidSignatureForStatusUpdate() {
        seedBinding(APP_SECRET);
        var body = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"" + PHONE_NUMBER_ID + "\"},"
                + "\"statuses\":[{\"id\":\"wamid.x\",\"status\":\"delivered\"}]}}]}]}";
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void postAcceptsValidSignatureForTextMessageAndOpensWindow() {
        var bindingId = seedBinding(APP_SECRET);
        var body = textPayload();
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
        // JCLAW-447: a parsed inbound opens the 24h window for (binding, sender).
        boolean within = commitInFreshTx(() ->
                WhatsAppConversationWindow.isWithinWindow(bindingId, "15551234567", java.time.Instant.now()));
        assertTrue(within, "a parsed inbound must open the 24h customer-service window");
    }

    @Test
    void postAcceptsUnsupportedMessageType() {
        seedBinding(APP_SECRET);
        var body = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"" + PHONE_NUMBER_ID + "\"},"
                + "\"messages\":[{\"from\":\"15551234567\",\"id\":\"wamid.y\","
                + "\"type\":\"system\",\"system\":{\"body\":\"x\"}}]}}]}]}";
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void rejectionsAreAuditedAsWebhookSignatureFailure() {
        seedBinding(APP_SECRET);
        var response = postWithSig(textPayload(), "sha256=bad");
        assertEquals(401, response.status.intValue());

        EventLogger.flush();
        var events = EventLog.findRecent(20);
        var found = events.stream().anyMatch(e ->
                "WEBHOOK_SIGNATURE_FAILURE".equals(e.category)
                        && "whatsapp".equals(e.channel));
        assertTrue(found, "expected WEBHOOK_SIGNATURE_FAILURE event for whatsapp");
    }

    private static String textPayload() {
        return "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"" + PHONE_NUMBER_ID + "\"},"
                + "\"contacts\":[{\"profile\":{\"name\":\"Ada\"},\"wa_id\":\"15551234567\"}],"
                + "\"messages\":[{\"from\":\"15551234567\",\"id\":\"wamid.x\","
                + "\"type\":\"text\",\"text\":{\"body\":\"hi\"}}]}}]}]}";
    }
}
