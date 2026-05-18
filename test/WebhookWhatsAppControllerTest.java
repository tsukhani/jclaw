import models.ChannelConfig;
import models.EventLog;
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
 * Happy-path and verify-flow coverage for
 * {@link controllers.WebhookWhatsAppController} (JCLAW-313). The companion
 * {@code WebhookControllerTest} owns the unconfigured / missing-signature
 * rejections; this file covers:
 *
 * <ul>
 *   <li>GET hub-verify success/failure paths;</li>
 *   <li>POST with valid x-hub-signature-256 (HMAC-SHA256 over raw body);</li>
 *   <li>parse-failure pathways (non-text message, no entry).</li>
 * </ul>
 */
class WebhookWhatsAppControllerTest extends FunctionalTest {

    private static final String APP_SECRET = "whatsapp-app-secret-fixture";
    private static final String VERIFY_TOKEN = "whatsapp-verify-token-fixture";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ChannelConfig.evictCache("whatsapp");
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

    private void seedWhatsAppConfig() {
        commitInFreshTx(() -> {
            var cc = new ChannelConfig();
            cc.channelType = "whatsapp";
            cc.enabled = true;
            cc.configJson = "{\"phoneNumberId\":\"PN1\",\"accessToken\":\"AT1\","
                    + "\"appSecret\":\"" + APP_SECRET + "\","
                    + "\"verifyToken\":\"" + VERIFY_TOKEN + "\"}";
            cc.save();
            return cc.id;
        });
        ChannelConfig.evictCache("whatsapp");
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

    // ===== GET hub verify =====

    @Test
    void verifyReturns403WhenUnconfigured() {
        // No config row at all → 403.
        var response = GET("/api/webhooks/whatsapp?hub.mode=subscribe"
                + "&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=c123");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void verifyReturns403OnWrongVerifyToken() {
        seedWhatsAppConfig();
        var response = GET("/api/webhooks/whatsapp?hub.mode=subscribe"
                + "&hub.verify_token=wrong&hub.challenge=c123");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void verifyReturns403OnWrongMode() {
        seedWhatsAppConfig();
        // Meta sends mode=subscribe; anything else (e.g. mode=unsubscribe)
        // must 403.
        var response = GET("/api/webhooks/whatsapp?hub.mode=unsubscribe"
                + "&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=c123");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void verifyEchoesChallengeOnSuccess() {
        seedWhatsAppConfig();
        var response = GET("/api/webhooks/whatsapp?hub.mode=subscribe"
                + "&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=echo-me-123");
        assertIsOk(response);
        assertEquals("echo-me-123", getContent(response));
    }

    // ===== POST signature =====

    @Test
    void postRejectsSignatureWithoutSha256Prefix() {
        // verifySignature requires the "sha256=" prefix — a bare hex
        // string must fail closed.
        seedWhatsAppConfig();
        var body = "{}";
        var sig = sign(body);
        var bareHex = sig.substring("sha256=".length());
        var response = postWithSig(body, bareHex);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void postRejectsMismatchedSignature() {
        // Right prefix, wrong digest → 401.
        seedWhatsAppConfig();
        var response = postWithSig("{}", "sha256=deadbeef");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void postAcceptsValidSignatureForEmptyPayload() {
        // Valid HMAC over a payload that parseWebhook returns null for
        // (no "entry" key) → controller falls through to ok().
        seedWhatsAppConfig();
        var body = "{}";
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void postAcceptsValidSignatureForStatusUpdate() {
        // Meta sends "statuses" payloads for delivery receipts; parseWebhook
        // returns null because there's no "messages" array. Controller
        // returns 200.
        seedWhatsAppConfig();
        var body = "{\"entry\":[{\"changes\":[{\"value\":"
                + "{\"statuses\":[{\"id\":\"wamid.x\",\"status\":\"delivered\"}]}}]}]}";
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void postAcceptsValidSignatureForTextMessage() {
        // Full text-message payload: signature passes, parseWebhook
        // returns an InboundMessage, controller spawns a virtual thread
        // for processing and returns 200 synchronously.
        seedWhatsAppConfig();
        var body = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"PN1\"},"
                + "\"messages\":[{\"from\":\"15551234567\",\"id\":\"wamid.x\","
                + "\"type\":\"text\",\"text\":{\"body\":\"hi\"}}]"
                + "}}]}]}";
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void postSkipsNonTextMessageTypes() {
        // Non-text message types (image, audio, sticker, etc.) currently
        // return null from parseWebhook — controller returns 200 without
        // spawning the dispatch VT.
        seedWhatsAppConfig();
        var body = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"messages\":[{\"from\":\"15551234567\",\"id\":\"wamid.y\","
                + "\"type\":\"sticker\"}]"
                + "}}]}]}";
        var response = postWithSig(body, sign(body));
        assertIsOk(response);
    }

    @Test
    void rejectionsAreAuditedAsWebhookSignatureFailure() {
        seedWhatsAppConfig();
        var response = postWithSig("{}", "sha256=bad");
        assertEquals(401, response.status.intValue());

        EventLogger.flush();
        var events = EventLog.findRecent(20);
        var found = events.stream().anyMatch(e ->
                "WEBHOOK_SIGNATURE_FAILURE".equals(e.category)
                        && "whatsapp".equals(e.channel));
        assertTrue(found, "expected WEBHOOK_SIGNATURE_FAILURE event for whatsapp");
    }
}
