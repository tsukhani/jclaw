import channels.ChannelTransport;
import channels.SlackApprovalService;
import channels.SlackApprovalService.Outcome;
import models.Agent;
import models.EventLog;
import models.SlackBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.EventLogger;
import services.Tx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
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

    /** Seed a binding to the privileged "main" agent with the given owner (JCLAW-354). */
    private Long seedMainBinding(String ownerUserId) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "main"; // Agent.isMain() → owner-locked binding
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = "xoxb-main-" + System.nanoTime();
            b.signingSecret = SIGNING_SECRET;
            b.botUserId = BOT_USER_ID;
            b.ownerUserId = ownerUserId;
            b.transport = ChannelTransport.HTTP;
            b.enabled = true;
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

    // ── Interactivity endpoint (JCLAW-350: exec-approval taps) ──────────

    private static final String OWNER_USER = "U_OWNER";

    /**
     * POST a form-encoded interactivity payload ({@code payload=<urlencoded-json>}),
     * mirroring Slack. Signature is over the raw form body. Content-type is
     * form-encoded, but binding the Number-typed {@code bindingId} route arg skips
     * Play's body parse, so the controller still reads the raw body for HMAC.
     */
    private static play.mvc.Http.Response postInteractive(Long bindingId, String body,
                                                          String timestamp, String signature) {
        var url = "/api/webhooks/slack/" + bindingId + "/interactive";
        var req = newRequest();
        req.method = "POST";
        req.contentType = "application/x-www-form-urlencoded";
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

    private static String interactiveBody(String json) {
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    @Test
    void interactiveRejectsBadSignature() {
        // The interactivity endpoint shares the per-binding HMAC gate; a wrong
        // signature must 401 before any payload handling.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = interactiveBody("{\"type\":\"block_actions\"}");
        var response = postInteractive(id, body, ts, "v0=deadbeef");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void interactiveResolvesApprovalAndReturns200() throws Exception {
        // End-to-end wiring: a signed block_actions tap from the authorized owner on
        // an sa:o: button resolves the pending approval (registered via the in-memory
        // seam) and acks 200. Resolution runs on a virtual thread, so wait on the future.
        var id = seedBinding();
        var future = SlackApprovalService.registerForTest("apX", OWNER_USER);
        var json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"" + OWNER_USER + "\"},"
                + "\"actions\":[{\"action_id\":\"sa:o:apX\"}]}";
        var body = interactiveBody(json);
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var sig = hmac(body, ts);

        var response = postInteractive(id, body, ts, sig);

        assertEquals(200, response.status.intValue());
        assertEquals(Outcome.APPROVED_ONCE, future.get(2, TimeUnit.SECONDS));
        SlackApprovalService.clearAll();
    }

    @Test
    void interactiveIgnoresNonBlockActionsPayload() {
        // A signed but non-block_actions payload (e.g. a shortcut) acks 200 without
        // touching the approval registry.
        var id = seedBinding();
        var body = interactiveBody("{\"type\":\"shortcut\",\"callback_id\":\"x\"}");
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var sig = hmac(body, ts);
        var response = postInteractive(id, body, ts, sig);
        assertEquals(200, response.status.intValue());
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

    // ── Access policy gate (JCLAW-354) ──────────────────────────────────

    @Test
    void channelMessageWithoutMentionIsDroppedByAccessPolicy() {
        // A signed channel message that doesn't @mention the bot is dropped (200, but
        // not dispatched). Exercises parseEvent's channel_type + botMentioned and the
        // gate wiring together. Asserted via the drop audit line.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"C123\",\"channel_type\":\"channel\",\"user\":\"U456\",\"text\":\"hello team\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertEquals(200, response.status.intValue());

        EventLogger.flush();
        var dropped = EventLog.findRecent(20).stream().anyMatch(e ->
                e.message != null && e.message.contains("dropped by access policy"));
        assertTrue(dropped, "an unaddressed channel message must be dropped by the access policy");
    }

    @Test
    void channelMessageMentioningBotIsServed() {
        // The same channel message, now @mentioning the bot (<@UBOT0001> = the seeded
        // botUserId), passes the gate and is accepted for processing.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"C123\",\"channel_type\":\"channel\",\"user\":\"U456\","
                + "\"text\":\"<@" + BOT_USER_ID + "> hi\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertEquals(200, response.status.intValue());

        EventLogger.flush();
        var events = EventLog.findRecent(20);
        assertTrue(events.stream().anyMatch(e ->
                        e.message != null && e.message.contains("Message received from")),
                "a mention-addressed channel message must be accepted");
        assertFalse(events.stream().anyMatch(e ->
                        e.message != null && e.message.contains("dropped by access policy")),
                "a mention-addressed channel message must not be dropped");
    }

    @Test
    void mainAgentDmFromNonOwnerIsDropped() {
        // The main agent is owner-locked: a DM from anyone but the owner is dropped,
        // even though the same DM to a non-main binding (no owner) would be served.
        var id = seedMainBinding(OWNER_USER);
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"D123\",\"channel_type\":\"im\",\"user\":\"U_INTRUDER\",\"text\":\"hi\"}}";
        var sig = hmac(body, ts);
        var response = postWithSlackHeaders(id, body, ts, sig);
        assertEquals(200, response.status.intValue());

        EventLogger.flush();
        var dropped = EventLog.findRecent(20).stream().anyMatch(e ->
                e.message != null && e.message.contains("dropped by access policy"));
        assertTrue(dropped, "a non-owner DM to the main agent must be dropped (owner-locked)");
    }

    @Test
    void redeliveredEventIsDeduped() {
        // JCLAW-357: posting the same event twice (a Slack redelivery) processes it once;
        // the second arrival is dropped as a duplicate. A unique message ts keys the dedup
        // so the global cache can't collide with other tests.
        var id = seedBinding();
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var msgTs = "1700000000." + System.nanoTime();
        var body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"channel\":\"C123\",\"channel_type\":\"im\",\"user\":\"U456\",\"text\":\"hi\","
                + "\"ts\":\"" + msgTs + "\"}}";
        var sig = hmac(body, ts);
        assertEquals(200, postWithSlackHeaders(id, body, ts, sig).status.intValue());
        assertEquals(200, postWithSlackHeaders(id, body, ts, sig).status.intValue());

        EventLogger.flush();
        var deduped = EventLog.findRecent(20).stream().anyMatch(e ->
                e.message != null && e.message.contains("Duplicate Slack event") && e.message.contains(msgTs));
        assertTrue(deduped, "the redelivered event must be logged as a duplicate drop");
    }
}
