import models.ChannelConfig;
import models.EventLog;
import org.junit.jupiter.api.*;
import play.test.*;
import services.EventLogger;
import services.Tx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Happy-path and edge coverage for {@link controllers.WebhookSlackController}
 * (JCLAW-313). The companion {@code WebhookControllerTest} owns the
 * negative gating (unconfigured, missing headers); this file covers the
 * accepted-signature paths the negative tests can't reach.
 *
 * <p>Each test computes the HMAC-SHA256 signature Slack would send so the
 * request flows past {@code verifySignature}. Asserts what's observable
 * synchronously from the HTTP response — agent processing runs on a
 * virtual thread and returns 200 before that work completes.
 */
class WebhookSlackControllerTest extends FunctionalTest {

    private static final String SIGNING_SECRET = "slack-signing-secret-fixture";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ChannelConfig.evictCache("slack");
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

    private void seedSlackConfig() {
        commitInFreshTx(() -> {
            var cc = new ChannelConfig();
            cc.channelType = "slack";
            cc.enabled = true;
            cc.configJson = "{\"botToken\":\"xoxb-test\",\"signingSecret\":\""
                    + SIGNING_SECRET + "\"}";
            cc.save();
            return cc.id;
        });
        ChannelConfig.evictCache("slack");
    }

    private static String hmac(String body, String timestamp) {
        try {
            var baseString = "v0:" + timestamp + ":" + body;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var digest = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static play.mvc.Http.Response postWithSlackHeaders(String body, String timestamp,
                                                                String signature) {
        var req = newRequest();
        req.method = "POST";
        req.contentType = "application/json";
        req.url = "/api/webhooks/slack";
        req.path = "/api/webhooks/slack";
        req.querystring = "";
        req.body = new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        req.headers.put("x-slack-request-timestamp",
                new play.mvc.Http.Header("x-slack-request-timestamp", timestamp));
        req.headers.put("x-slack-signature",
                new play.mvc.Http.Header("x-slack-signature", signature));
        return makeRequest(req);
    }

    @Test
    void rejectsBadSignatureWithCorrectHeaderShape() {
        // Signature header is well-formed (starts with "v0=") but the
        // HMAC doesn't match — must 401 rather than slip through.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\"}";
        var response = postWithSlackHeaders(body, ts, "v0=deadbeef");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectsStaleTimestamp() {
        // Slack's verifySignature rejects timestamps more than 5 minutes
        // off. We compute a valid HMAC for an ancient timestamp; the
        // verifier should still reject.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond() - 3600);
        var body = "{\"type\":\"event_callback\"}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(body, ts, sig);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void acceptsUrlVerificationChallenge() {
        // The url_verification challenge must run AFTER signature
        // verification (JCLAW-16): a signed challenge echoes the
        // challenge string back as plain text.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"url_verification\",\"challenge\":\"hello-slack\"}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(body, ts, sig);
        assertIsOk(response);
        assertContentType("text/plain", response);
        assertEquals("hello-slack", getContent(response));
    }

    @Test
    void acceptsValidEventCallbackAndReturns200() {
        // A signed event_callback for a non-message event — the
        // controller's parseEvent returns null for non-message types,
        // so it falls through to ok() without spawning the dispatch
        // thread. We assert the synchronous outcome.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"reaction_added\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(body, ts, sig);
        assertIsOk(response);
    }

    @Test
    void acceptsValidMessageEventAndReturns200() {
        // A real message event with valid signature: the controller
        // spawns a virtual thread for processing and returns 200. We
        // don't assert downstream side effects (would race the VT) —
        // only the synchronous 200 contract Slack relies on.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"C123\",\"user\":\"U456\",\"text\":\"hi\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(body, ts, sig);
        assertIsOk(response);
    }

    @Test
    void ignoresBotMessages() {
        // Bot messages carry a bot_id or subtype and must NOT round-trip
        // through the agent — guard against feedback loops. parseEvent
        // returns null, controller returns 200.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"C123\",\"bot_id\":\"B789\",\"text\":\"echo\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(body, ts, sig);
        assertIsOk(response);
    }

    @Test
    void rejectsSignatureWithoutV0Prefix() {
        // verifySignature requires the "v0=" prefix; a hex string alone
        // must not authenticate.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\"}";
        var sig = hmac(body, ts);
        var bareHex = sig.substring(3); // strip "v0="
        var response = postWithSlackHeaders(body, ts, bareHex);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectsNonNumericTimestamp() {
        // verifySignature's Long.parseLong on the timestamp must fail
        // closed, not throw.
        seedSlackConfig();
        var body = "{\"type\":\"event_callback\"}";
        var sig = hmac(body, "not-a-number");
        var response = postWithSlackHeaders(body, "not-a-number", sig);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectionsAreAuditedAsWebhookSignatureFailure() {
        // Rejections must hit the WEBHOOK_SIGNATURE_FAILURE audit
        // category so the operator dashboard can highlight them.
        seedSlackConfig();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var response = postWithSlackHeaders("{}", ts, "v0=bad");
        assertEquals(401, response.status.intValue());

        EventLogger.flush();
        var events = EventLog.findRecent(20);
        var found = events.stream().anyMatch(e ->
                "WEBHOOK_SIGNATURE_FAILURE".equals(e.category)
                        && "slack".equals(e.channel));
        assertTrue(found, "expected WEBHOOK_SIGNATURE_FAILURE event for slack");
    }
}
