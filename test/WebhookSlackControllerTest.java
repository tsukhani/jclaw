import channels.ChannelTransport;
import models.Agent;
import models.EventLog;
import models.SlackBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * (JCLAW-441: per-binding). The companion {@code WebhookControllerTest} owns the
 * pre-signature gating (unknown binding 404, disabled 403, missing headers 401);
 * this file covers the accepted-signature + event-parse paths the negative tests
 * can't reach.
 *
 * <p>The webhook URL now carries the binding id ({@code /api/webhooks/slack/{id}})
 * and the signature is verified against THAT binding's signing secret. Each test
 * seeds a binding and computes the HMAC-SHA256 signature Slack would send so the
 * request flows past {@code verifySignature}. Asserts what's observable
 * synchronously from the HTTP response — agent processing runs on a virtual
 * thread and returns 200 before that work completes.
 */
class WebhookSlackControllerTest extends FunctionalTest {

    private static final String SIGNING_SECRET = "slack-signing-secret-fixture";
    private static final String BOT_USER_ID = "UBOT0001";

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

    /** Seed an enabled binding (with a cached bot user id) and return its id. */
    private Long seedBinding() {
        return seedBinding(true, BOT_USER_ID);
    }

    private Long seedBinding(boolean enabled, String botUserId) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "slack-agent-" + System.nanoTime();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = "xoxb-test-" + System.nanoTime();
            b.signingSecret = SIGNING_SECRET;
            b.botUserId = botUserId;
            b.transport = ChannelTransport.HTTP;
            b.enabled = enabled;
            b.save();
            return b.id;
        });
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

    private static play.mvc.Http.Response postWithSlackHeaders(Long bindingId, String body,
                                                                String timestamp, String signature) {
        var url = "/api/webhooks/slack/" + bindingId;
        var req = newRequest();
        req.method = "POST";
        req.contentType = "application/json";
        req.url = url;
        req.path = url;
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
        // Signature header is well-formed (starts with "v0=") but the HMAC doesn't
        // match this binding's secret — must 401 rather than slip through.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\"}";
        var response = postWithSlackHeaders(id, body, ts, "v0=deadbeef");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectsStaleTimestamp() {
        // Slack's verifySignature rejects timestamps more than 5 minutes off. We
        // compute a valid HMAC for an ancient timestamp; the verifier still rejects.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond() - 3600);
        var body = "{\"type\":\"event_callback\"}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void acceptsUrlVerificationChallenge() {
        // The url_verification challenge runs AFTER signature verification: a
        // signed challenge echoes the challenge string back as plain text.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"url_verification\",\"challenge\":\"hello-slack\"}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertIsOk(response);
        assertContentType("text/plain", response);
        assertEquals("hello-slack", getContent(response));
    }

    /**
     * Every well-signed event yields a synchronous 200 — whether it's processed
     * (message, slash, file_share) or dropped at parse time (non-message subtype,
     * bot message). The parse + dispatch decision runs on the dispatch virtual
     * thread; here we pin only the 200 contract Slack relies on. The self-loop
     * guard (own bot user id) is covered separately below — it depends on the
     * seeded {@code botUserId}.
     */
    @ParameterizedTest(name = "signedEventReturns200[{0}]")
    @CsvSource(delimiter = '|', value = {
            "non-message event   | {\"type\":\"event_callback\",\"event\":{\"type\":\"reaction_added\"}}",
            "plain message       | {\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U456\",\"text\":\"hi\"}}",
            "slash command       | {\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U456\",\"text\":\"/new\"}}",
            "file_share          | {\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"file_share\",\"channel\":\"C123\",\"user\":\"U456\",\"text\":\"pic\",\"files\":[{\"id\":\"F1\",\"name\":\"p.png\",\"mimetype\":\"image/png\",\"size\":10,\"url_private_download\":\"https://files.slack.com/files-pri/T/F1/p.png\"}]}}",
            "bot message dropped | {\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"channel\":\"C123\",\"bot_id\":\"B789\",\"text\":\"echo\"}}"
    })
    void signedEventReturns200(String label, String body) {
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertEquals(200, response.status.intValue(), "event '" + label + "' must return 200");
    }

    @Test
    void ignoresOwnUserMessages() {
        // JCLAW-357 self-loop guard: a message whose user is the binding's own
        // cached bot user id is dropped (parseEvent returns null), even without a
        // bot_id. Returns 200 without dispatching.
        var id = seedBinding(true, BOT_USER_ID);
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"C123\",\"user\":\"" + BOT_USER_ID + "\",\"text\":\"my own echo\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertIsOk(response);
    }

    @Test
    void rejectsSignatureWithoutV0Prefix() {
        // verifySignature requires the "v0=" prefix; a hex string alone must not
        // authenticate.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\"}";
        var sig = hmac(body, ts);
        var bareHex = sig.substring(3); // strip "v0="
        var response = postWithSlackHeaders(id, body, ts, bareHex);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectsNonNumericTimestamp() {
        // verifySignature's Long.parseLong on the timestamp must fail closed.
        var id = seedBinding();
        var body = "{\"type\":\"event_callback\"}";
        var sig = hmac(body, "not-a-number");
        var response = postWithSlackHeaders(id, body, "not-a-number", sig);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectionsAreAuditedAsWebhookSignatureFailure() {
        // Rejections must hit the WEBHOOK_SIGNATURE_FAILURE audit category so the
        // operator dashboard can highlight them.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var response = postWithSlackHeaders(id, "{}", ts, "v0=bad");
        assertEquals(401, response.status.intValue());

        EventLogger.flush();
        var events = EventLog.findRecent(20);
        var found = events.stream().anyMatch(e ->
                "WEBHOOK_SIGNATURE_FAILURE".equals(e.category)
                        && "slack".equals(e.channel));
        assertTrue(found, "expected WEBHOOK_SIGNATURE_FAILURE event for slack");
    }
}
