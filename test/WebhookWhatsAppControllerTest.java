import controllers.WebhookIngressGate;
import models.Agent;
import models.EventLog;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import models.WhatsAppTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
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
    private static final String PHONE_NUMBER_ID = "100000000000001";
    private static final String OTHER_PHONE_NUMBER_ID = "100000000000002";
    private static final String UNBOUND_PHONE_NUMBER_ID = "100000000000009";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        // JCLAW-783: clear the shared ingress-gate counters between cases. Safe in
        // @BeforeEach: the play1 runner serializes functional tests, and no unit
        // test touches this gate's map.
        WebhookIngressGate.resetForTest();
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

    /** Seed an enabled Cloud-API binding with full inbound credentials and no access token, so a reply stops before calling graph.facebook.com. */
    private Long seedBinding(String appSecret) {
        return seedBinding(PHONE_NUMBER_ID, appSecret, VERIFY_TOKEN, true, WhatsAppTransport.CLOUD_API);
    }

    /** A binding for another number, on its own agent, likewise without an access token. */
    private Long seedBinding(String phoneNumberId, String appSecret, boolean enabled, WhatsAppTransport transport) {
        return seedBinding(phoneNumberId, appSecret, null, enabled, transport);
    }

    private Long seedBinding(String phoneNumberId, String appSecret, String verifyToken, boolean enabled,
                             WhatsAppTransport transport) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "wa-webhook-agent-" + System.nanoTime();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = transport;
            b.phoneNumberId = phoneNumberId;
            b.appSecret = appSecret;
            b.verifyToken = verifyToken;
            b.enabled = enabled;
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
                + "\"metadata\":{\"phone_number_id\":\"" + UNBOUND_PHONE_NUMBER_ID + "\"},"
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

    // ── JCLAW-1411: delivery statuses ──

    private static final String META_WAMID_FAILED = "wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI0QUQ2MjA4NEYyRkExNjMyREUA";
    private static final String META_WAMID_DELIVERED = "wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA";

    /** Meta's failed example, routed to this test's binding, with a wamid unique to the call (dedup is process-global). */
    private static String failedStatusBody(String wamid, String timestamp) {
        return WhatsAppCloudApiParserTest.META_FAILED_EXAMPLE
                .replace("106540352242922", PHONE_NUMBER_ID)
                .replace(META_WAMID_FAILED, wamid)
                .replace("\"1751142888\"", "\"" + timestamp + "\"");
    }

    private static String uniqueWamid() {
        return "wamid.test-" + System.nanoTime();
    }

    private static String agentIdOf(Long bindingId) {
        return commitInFreshTx(() -> {
            WhatsAppBinding b = WhatsAppBinding.findById(bindingId);
            return String.valueOf(b.agent.id);
        });
    }

    private static WhatsAppBinding reload(Long bindingId) {
        return commitInFreshTx(() -> WhatsAppBinding.<WhatsAppBinding>findById(bindingId));
    }

    private static java.util.List<EventLog> eventsFor(String agentId) {
        EventLogger.flush();
        return EventLog.findRecent(500).stream()
                .filter(e -> agentId.equals(e.agentId))
                .toList();
    }

    @Test
    void signedFailedStatusIsLoggedOnceAndRecordedOnTheBinding() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var updatedBefore = reload(bindingId).updatedAt;
        var body = failedStatusBody(uniqueWamid(), "1751142888");

        assertIsOk(postWithSig(body, sign(body)));

        var events = eventsFor(agentId);
        assertEquals(1, events.size(), () -> "events: " + events);
        var e = events.get(0);
        assertEquals("WARN", e.level);
        assertEquals("channel", e.category);
        assertEquals("whatsapp", e.channel);
        assertTrue(e.message.contains("16505551234"), e.message);
        assertTrue(e.message.contains("131049"), e.message);
        assertTrue(e.message.contains("This message was not delivered to maintain healthy ecosystem engagement."),
                e.message);
        assertTrue(e.message.contains("the message failed to be delivered."), e.message);

        var b = reload(bindingId);
        assertEquals(java.time.Instant.ofEpochSecond(1751142888L), b.lastDeliveryFailureAt);
        assertEquals(131049, b.lastDeliveryFailureCode);
        assertEquals("This message was not delivered to maintain healthy ecosystem engagement.",
                b.lastDeliveryFailureTitle);
        // H2 keeps microseconds; the cached seed copy carried nanoseconds.
        assertTrue(java.time.Duration.between(updatedBefore, b.updatedAt).abs().toMillis() < 1,
                () -> "a delivery failure is not an edit to the binding: " + updatedBefore + " -> " + b.updatedAt);
    }

    @Test
    void unsignedFailedStatusIsRefusedAndRecordsNothing() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var body = failedStatusBody(uniqueWamid(), "1751142888");

        assertEquals(401, postWithSig(body, null).status.intValue());
        assertEquals(401, postWithSig(body, "sha256=bad").status.intValue());

        assertTrue(eventsFor(agentId).isEmpty());
        var b = reload(bindingId);
        assertNull(b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void theSameFailedStatusTwiceIsLoggedOnce() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var body = failedStatusBody(uniqueWamid(), "1751142888");

        assertIsOk(postWithSig(body, sign(body)));
        assertIsOk(postWithSig(body, sign(body)));

        assertEquals(1, eventsFor(agentId).size());
    }

    @Test
    void deliveredStatusLogsAndRecordsNothing() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var body = WhatsAppCloudApiParserTest.META_DELIVERED_EXAMPLE
                .replace("106540352242922", PHONE_NUMBER_ID)
                .replace(META_WAMID_DELIVERED, uniqueWamid());

        assertIsOk(postWithSig(body, sign(body)));

        assertTrue(eventsFor(agentId).isEmpty());
        var b = reload(bindingId);
        assertNull(b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void anOlderFailureNeverRegressesTheStoredOneAndANewerOneReplacesIt() {
        var bindingId = seedBinding(APP_SECRET);
        var newer = failedStatusBody(uniqueWamid(), "1751142888");
        assertIsOk(postWithSig(newer, sign(newer)));

        var older = failedStatusBody(uniqueWamid(), "1751000000");
        assertIsOk(postWithSig(older, sign(older)));
        assertEquals(java.time.Instant.ofEpochSecond(1751142888L), reload(bindingId).lastDeliveryFailureAt);
        assertEquals(131049, reload(bindingId).lastDeliveryFailureCode);

        var sameSecond = statusesBody("""
                [{"id":"%s","status":"failed","timestamp":"1751142888","errors":[{"code":131042,"title":"pay"}]}]
                """.formatted(uniqueWamid()));
        assertIsOk(postWithSig(sameSecond, sign(sameSecond)));
        assertEquals(131042, reload(bindingId).lastDeliveryFailureCode, "a failure in the same second replaces");

        var later = failedStatusBody(uniqueWamid(), "1751200000");
        assertIsOk(postWithSig(later, sign(later)));
        assertEquals(java.time.Instant.ofEpochSecond(1751200000L), reload(bindingId).lastDeliveryFailureAt);
    }

    @Test
    void aFailedStatusWithoutErrorsIsLoggedWithNoDetailAndRecordedWithNullCodeAndTitle() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var body = statusesBody("""
                [{"id":"%s","status":"failed","timestamp":"1751142888","recipient_id":"16505551234"}]
                """.formatted(uniqueWamid()));

        assertIsOk(postWithSig(body, sign(body)));

        var events = eventsFor(agentId);
        assertEquals(1, events.size(), () -> "events: " + events);
        assertTrue(events.get(0).message.contains("16505551234"), events.get(0).message);
        assertTrue(events.get(0).message.contains("no error detail"), events.get(0).message);
        var b = reload(bindingId);
        assertEquals(java.time.Instant.ofEpochSecond(1751142888L), b.lastDeliveryFailureAt);
        assertNull(b.lastDeliveryFailureCode);
        assertNull(b.lastDeliveryFailureTitle);
    }

    @Test
    void aFailedStatusWithNoUsableTimestampIsRecordedAtNow() {
        var bindingId = seedBinding(APP_SECRET);
        var before = java.time.Instant.now();
        var absent = statusesBody("[{\"id\":\"" + uniqueWamid() + "\",\"status\":\"failed\"}]");
        assertIsOk(postWithSig(absent, sign(absent)));
        var first = reload(bindingId).lastDeliveryFailureAt;
        assertNotNull(first);
        assertFalse(first.isBefore(before), () -> "absent timestamp recorded at " + first);

        var garbled = statusesBody("[{\"id\":\"" + uniqueWamid() + "\",\"status\":\"failed\",\"timestamp\":\"abc\"}]");
        assertIsOk(postWithSig(garbled, sign(garbled)));
        var second = reload(bindingId).lastDeliveryFailureAt;
        assertFalse(second.isBefore(first), () -> "unparseable timestamp recorded at " + second);
    }

    @Test
    void eachFailedStatusInOnePayloadIsLoggedOnce() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var body = statusesBody("""
                [{"id":"%s","status":"delivered","timestamp":"1751142000"},
                 {"id":"%s","status":"failed","timestamp":"1751142001","recipient_id":"111"},
                 {"id":"%s","status":"failed","timestamp":"1751142002","recipient_id":"222"}]
                """.formatted(uniqueWamid(), uniqueWamid(), uniqueWamid()));

        assertIsOk(postWithSig(body, sign(body)));

        var events = eventsFor(agentId);
        assertEquals(2, events.size(), () -> "events: " + events);
        assertTrue(events.stream().anyMatch(e -> e.message.contains("111")));
        assertTrue(events.stream().anyMatch(e -> e.message.contains("222")));
        assertEquals(java.time.Instant.ofEpochSecond(1751142002L), reload(bindingId).lastDeliveryFailureAt);
    }

    private static String statusesBody(String statusesJson) {
        return "{\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\""
                + PHONE_NUMBER_ID + "\"},\"statuses\":" + statusesJson + "}}]}]}";
    }

    @Test
    void aPayloadWithNeitherMessagesNorStatusesIsAckedSilently() {
        var bindingId = seedBinding(APP_SECRET);
        var agentId = agentIdOf(bindingId);
        var body = "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"" + PHONE_NUMBER_ID + "\"}}}]}]}";

        assertIsOk(postWithSig(body, sign(body)));

        assertTrue(eventsFor(agentId).isEmpty());
        assertNull(reload(bindingId).lastDeliveryFailureAt);
    }

    private static String textPayload() {
        // JCLAW-784: carry a fresh per-message timestamp so the replay-window guard
        // (VULN-012) admits it — a real Cloud-API message always includes one.
        return textPayload(String.valueOf(java.time.Instant.now().getEpochSecond()));
    }

    private static String textPayload(String timestamp) {
        return "{\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"" + PHONE_NUMBER_ID + "\"},"
                + "\"contacts\":[{\"profile\":{\"name\":\"Ada\"},\"wa_id\":\"15551234567\"}],"
                + "\"messages\":[{\"from\":\"15551234567\",\"id\":\"wamid.x\","
                + "\"timestamp\":\"" + timestamp + "\","
                + "\"type\":\"text\",\"text\":{\"body\":\"hi\"}}]}}]}]}";
    }

    // ── JCLAW-783: pre-auth ingress gate (rate-limit + body cap before parse/DB) ──

    /** Like {@link #postWithSig} but also sets an explicit Content-Length header so
     *  the pre-auth Content-Length guard can be exercised. */
    private static play.mvc.Http.Response postWithContentLength(String body, String signature,
                                                                String contentLength) {
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
        req.headers.put("content-length", new play.mvc.Http.Header("content-length", contentLength));
        return makeRequest(req);
    }

    @Test
    void floodIsRateLimitedWith429BeforeParse() {
        // JCLAW-783: a flood from one source starts returning 429 once the window
        // count is exceeded. The requests are UNSIGNED — a 429 (not the 401 the
        // first three get at the signature check) proves the rate-limit runs BEFORE
        // readRawBody / JsonParser.parseString / findByPhoneNumberId.
        play.Play.configuration.setProperty("whatsapp.webhook.rate-limit.max", "3");
        play.Play.configuration.setProperty("whatsapp.webhook.rate-limit.window-seconds", "60");
        try {
            seedBinding(APP_SECRET);
            var body = textPayload();
            for (int i = 0; i < 3; i++) {
                var r = postWithSig(body, null);
                assertEquals(401, r.status.intValue(),
                        "request " + (i + 1) + " is under the limit, so the missing signature yields 401");
            }
            var flooded = postWithSig(body, null);
            assertEquals(429, flooded.status.intValue(),
                    "exceeding the rate-limit window returns 429 before parse / DB");
        } finally {
            play.Play.configuration.remove("whatsapp.webhook.rate-limit.max");
            play.Play.configuration.remove("whatsapp.webhook.rate-limit.window-seconds");
        }
    }

    @Test
    void oversizedContentLengthReturns413BeforeParse() {
        // JCLAW-783: a Content-Length above the cap is rejected with 413 before the
        // body is read or parsed (unsigned + oversized — the 413 pre-empts both).
        play.Play.configuration.setProperty("whatsapp.webhook.max-body-bytes", "8");
        try {
            seedBinding(APP_SECRET);
            var response = postWithContentLength(textPayload(), null, "100000");
            assertEquals(413, response.status.intValue());
        } finally {
            play.Play.configuration.remove("whatsapp.webhook.max-body-bytes");
        }
    }

    @Test
    void oversizedBodyReadLengthReturns413() {
        // JCLAW-783: with no Content-Length header the read-length backstop still
        // rejects a body larger than the cap with 413 (before parse). textPayload is
        // well over the 8-byte cap set here.
        play.Play.configuration.setProperty("whatsapp.webhook.max-body-bytes", "8");
        try {
            seedBinding(APP_SECRET);
            var response = postWithSig(textPayload(), null);
            assertEquals(413, response.status.intValue());
        } finally {
            play.Play.configuration.remove("whatsapp.webhook.max-body-bytes");
        }
    }

    // ── JCLAW-784 (VULN-012) / JCLAW-1520: replay window on the per-message timestamp, dedup by wamid ──

    // The request runs on the functional-test executor, which an AppClock binding does not reach,
    // so every offset below is minutes or days from the real clock, never seconds.
    private static long secondsAgo(long seconds) {
        return java.time.Instant.now().getEpochSecond() - seconds;
    }

    private static final long EIGHT_DAYS = java.time.Duration.ofDays(8).toSeconds();

    private static String uniqueSender() {
        return "1555" + (System.nanoTime() % 10_000_000_000L);
    }

    private static String textMessage(String wamid, String from, long sentEpochSecond) {
        return "{\"from\":\"%s\",\"id\":\"%s\",\"timestamp\":\"%d\",\"type\":\"text\",\"text\":{\"body\":\"hi\"}}"
                .formatted(from, wamid, sentEpochSecond);
    }

    private static String change(String phoneNumberId, String... messages) {
        return "{\"value\":{\"metadata\":{\"phone_number_id\":\"" + phoneNumberId
                + "\"},\"messages\":[" + String.join(",", messages) + "]}}";
    }

    private static String messagesBody(String... messages) {
        return "{\"entry\":[{\"changes\":[" + change(PHONE_NUMBER_ID, messages) + "]}]}";
    }

    private static final java.util.List<String> INBOUND_LINES = java.util.List.of("Message received from ",
            "Duplicate WhatsApp event ", "Delayed WhatsApp message ", "WhatsApp message ");

    /** The inbound lines naming any of {@code tokens}, a sender or a wamid, logged while {@code body} was posted, signed. */
    private static java.util.List<String> loggedWhilePosting(String body, int expectedStatus, String... tokens) {
        return EventLogger.captureMatchingForTest(
                        e -> INBOUND_LINES.stream().anyMatch(e.message()::startsWith)
                                && java.util.Arrays.stream(tokens).anyMatch(t -> e.message().contains(t + " ")),
                        _ -> assertEquals(expectedStatus, postWithSig(body, sign(body)).status.intValue()))
                .stream().map(EventLogger.Captured::message).toList();
    }

    private static String served(String sender) {
        return "Message received from " + sender + " in " + sender;
    }

    private static String duplicate(String wamid) {
        return "Duplicate WhatsApp event " + wamid + " dropped";
    }

    private static void recordAccepted(String wamid) {
        commitInFreshTx(() -> models.WhatsAppAcceptedMessage.claim(wamid, java.time.Instant.now()));
    }

    private static boolean isRecorded(String wamid) {
        return commitInFreshTx(() -> models.WhatsAppAcceptedMessage.isRecorded(wamid));
    }

    @Test
    void anUnseenMessageOutsideTheSevenDayWindowIsRefusedAndNotRecorded() {
        // The HMAC covers only the body, so a captured signed body whose id was pruned stays refused.
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        var body = messagesBody(textMessage(wamid, from, secondsAgo(EIGHT_DAYS)));

        var response = postWithSig(body, sign(body));

        assertEquals(401, response.status.intValue());
        assertEquals("Basic realm=\"Stale request\"", response.getHeader("WWW-Authenticate"));
        assertFalse(isRecorded(wamid));
    }

    @Test
    void anUnseenMessageStampedTenMinutesAheadIsRefusedAndNotRecorded() {
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var body = messagesBody(textMessage(wamid, uniqueSender(), secondsAgo(-600)));

        var response = postWithSig(body, sign(body));

        assertEquals(401, response.status.intValue());
        assertEquals("Basic realm=\"Stale request\"", response.getHeader("WWW-Authenticate"));
        assertFalse(isRecorded(wamid));
    }

    @Test
    void anUnseenMessageWithNoTimestampIsRefused() {
        seedBinding(APP_SECRET);
        var from = uniqueSender();
        var body = messagesBody("{\"from\":\"" + from + "\",\"id\":\"" + uniqueWamid()
                + "\",\"type\":\"text\",\"text\":{\"body\":\"hi\"}}");

        assertEquals(java.util.List.of(), loggedWhilePosting(body, 401, from));
    }

    @Test
    void anUnseenMessageTenMinutesLateIsDispatchedAndLoggedAsDelayed() {
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        var body = messagesBody(textMessage(wamid, from, secondsAgo(600)));

        var logged = loggedWhilePosting(body, 200, wamid, from);

        assertEquals(2, logged.size(), logged::toString);
        assertTrue(logged.get(0).matches("Delayed WhatsApp message " + java.util.regex.Pattern.quote(wamid)
                + " accepted 60[0-9] s after it was sent"), logged.get(0));
        assertEquals(served(from), logged.get(1));
        assertTrue(isRecorded(wamid));
    }

    @Test
    void anUnseenMessageSixDaysLateIsDispatched() {
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        var body = messagesBody(textMessage(wamid, from, secondsAgo(java.time.Duration.ofDays(6).toSeconds())));

        var logged = loggedWhilePosting(body, 200, from);

        assertEquals(java.util.List.of(served(from)), logged);
    }

    @Test
    void aMessageFourMinutesOldIsDispatchedWithoutADelayLine() {
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        var body = messagesBody(textMessage(wamid, from, secondsAgo(240)));

        assertEquals(java.util.List.of(served(from)), loggedWhilePosting(body, 200, wamid, from));
    }

    @Test
    void aMessageWhoseIdIsAlreadyRecordedIsAnsweredAndNotDispatched() {
        // Recorded by an earlier process: this one's in-memory InboundEventDedup has never seen the id.
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        recordAccepted(wamid);
        var body = messagesBody(textMessage(wamid, from, secondsAgo(0)));

        assertEquals(java.util.List.of(duplicate(wamid)), loggedWhilePosting(body, 200, wamid, from));
    }

    @Test
    void aRecordedIdIsAnsweredBeforeItsAgeIsChecked() {
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        recordAccepted(wamid);
        var body = messagesBody(textMessage(wamid, from, secondsAgo(EIGHT_DAYS)));

        assertEquals(java.util.List.of(duplicate(wamid)), loggedWhilePosting(body, 200, wamid, from));
    }

    @Test
    void theSameBodyPostedTwiceIsDispatchedOnce() {
        seedBinding(APP_SECRET);
        var wamid = uniqueWamid();
        var from = uniqueSender();
        var body = messagesBody(textMessage(wamid, from, secondsAgo(0)));

        assertEquals(java.util.List.of(served(from)), loggedWhilePosting(body, 200, wamid, from));
        assertEquals(java.util.List.of(duplicate(wamid)), loggedWhilePosting(body, 200, wamid, from));
    }

    @Test
    void twoMessagesUnderOneChangeAreBothDispatchedAndEachDedupedOnItsOwnId() {
        seedBinding(APP_SECRET);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var idC = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var c = uniqueSender();
        var first = messagesBody(textMessage(idA, a, secondsAgo(0)), textMessage(idB, b, secondsAgo(0)));

        assertEquals(java.util.List.of(served(a), served(b)), loggedWhilePosting(first, 200, idA, idB, a, b));

        var second = messagesBody(textMessage(idA, a, secondsAgo(0)), textMessage(idC, c, secondsAgo(0)));
        assertEquals(java.util.List.of(duplicate(idA), served(c)), loggedWhilePosting(second, 200, idA, idC, a, c));
    }

    @Test
    void oneStaleMessageRefusesTheWholeBodyAndDispatchesNothing() {
        seedBinding(APP_SECRET);
        var fresh = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var body = messagesBody(textMessage(fresh, a, secondsAgo(0)), textMessage(uniqueWamid(), b, secondsAgo(EIGHT_DAYS)));

        assertEquals(java.util.List.of(), loggedWhilePosting(body, 401, a, b));
        assertFalse(isRecorded(fresh), "a refused body is redelivered whole, so none of it is claimed");
    }

    private static java.time.Instant windowStart(Long bindingId, String from) {
        return commitInFreshTx(() -> WhatsAppConversationWindow.findRow(bindingId, from).lastUserMessageAt);
    }

    private static boolean windowOpen(Long bindingId, String from) {
        return commitInFreshTx(() -> WhatsAppConversationWindow.isWithinWindow(bindingId, from, java.time.Instant.now()));
    }

    @Test
    void aMessageSentThirtyHoursAgoLeavesTheServiceWindowClosed() {
        // Meta's 24h window runs from when the customer sent the message, not from when the redelivery arrived.
        var bindingId = seedBinding(APP_SECRET);
        var from = uniqueSender();
        var sent = secondsAgo(java.time.Duration.ofHours(30).toSeconds());
        var body = messagesBody(textMessage(uniqueWamid(), from, sent));

        assertEquals(java.util.List.of(served(from)), loggedWhilePosting(body, 200, from));

        assertEquals(java.time.Instant.ofEpochSecond(sent), windowStart(bindingId, from));
        assertFalse(windowOpen(bindingId, from));
    }

    @Test
    void aLateRedeliveryNeverMovesTheWindowBehindANewerMessage() {
        var bindingId = seedBinding(APP_SECRET);
        var from = uniqueSender();
        var freshSent = secondsAgo(60);
        var fresh = messagesBody(textMessage(uniqueWamid(), from, freshSent));
        assertIsOk(postWithSig(fresh, sign(fresh)));

        var late = messagesBody(textMessage(uniqueWamid(), from, secondsAgo(java.time.Duration.ofHours(30).toSeconds())));
        assertIsOk(postWithSig(late, sign(late)));

        assertEquals(java.time.Instant.ofEpochSecond(freshSent), windowStart(bindingId, from));
        assertTrue(windowOpen(bindingId, from));
    }

    @Test
    void aMessageStampedAheadOfTheClockOpensTheWindowNoLaterThanItsReceipt() {
        var bindingId = seedBinding(APP_SECRET);
        var from = uniqueSender();
        var body = messagesBody(textMessage(uniqueWamid(), from, secondsAgo(-240)));

        var before = java.time.Instant.now();
        assertIsOk(postWithSig(body, sign(body)));
        var after = java.time.Instant.now();

        var start = windowStart(bindingId, from);
        assertFalse(start.isBefore(before.minusMillis(1)), () -> before + " > " + start);
        assertFalse(start.isAfter(after.plusMillis(1)), () -> start + " > " + after);
    }

    @Test
    void aMessageForAnotherPhoneNumberIsSkippedAndTheBindingsOwnIsDispatched() {
        var bindingId = seedBinding(APP_SECRET);
        var ours = uniqueSender();
        var theirs = uniqueSender();
        var otherId = uniqueWamid();
        var body = "{\"entry\":[{\"changes\":[" + change(PHONE_NUMBER_ID, textMessage(uniqueWamid(), ours, secondsAgo(0)))
                + "," + change(UNBOUND_PHONE_NUMBER_ID, textMessage(otherId, theirs, secondsAgo(0))) + "]}]}";

        var logged = loggedWhilePosting(body, 200, ours, otherId);

        assertEquals(java.util.List.of(
                skipped(otherId, UNBOUND_PHONE_NUMBER_ID, bindingId),
                served(ours)), logged);
        assertFalse(isRecorded(otherId));
    }

    // ── Two numbers under one Meta app share its callback URL, so one body can carry both ──

    private static final String OTHER_APP_SECRET = "whatsapp-other-app-secret-fixture";

    private static String changesBody(String... changes) {
        return "{\"entry\":[{\"changes\":[" + String.join(",", changes) + "]}]}";
    }

    private static String skipped(String wamid, String phoneNumberId, Long routedBindingId) {
        return "WhatsApp message " + wamid + " for phone number " + phoneNumberId
                + " skipped in a webhook for binding " + routedBindingId;
    }

    private static boolean hasWindowRow(Long bindingId, String from) {
        return commitInFreshTx(() -> WhatsAppConversationWindow.findRow(bindingId, from) != null);
    }

    /** The bindings whose agent holds a WhatsApp conversation with {@code peer}, polled: the dispatch thread opens it. */
    private static java.util.List<Long> bindingsConversingWith(String peer) {
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (true) {
            java.util.List<Long> ids = commitInFreshTx(() -> {
                java.util.List<models.Conversation> rows =
                        models.Conversation.find("channelType = ?1 and peerId = ?2", "whatsapp", peer).fetch();
                var out = new java.util.ArrayList<Long>();
                for (var c : rows) out.add(WhatsAppBinding.findByAgent(c.agent).id);
                return out;
            });
            if (!ids.isEmpty() || System.nanoTime() > deadline) return ids;
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return ids;
            }
        }
    }

    @Test
    void twoBindingsOfOneAppEachDispatchTheMessageForTheirOwnNumber() {
        var bindingA = seedBinding(APP_SECRET);
        var bindingB = seedBinding(OTHER_PHONE_NUMBER_ID, APP_SECRET, true, WhatsAppTransport.CLOUD_API);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var sentA = secondsAgo(60);
        var sentB = secondsAgo(120);
        var body = changesBody(change(PHONE_NUMBER_ID, textMessage(idA, a, sentA)),
                change(OTHER_PHONE_NUMBER_ID, textMessage(idB, b, sentB)));

        assertEquals(java.util.List.of(served(a), served(b)), loggedWhilePosting(body, 200, idA, idB, a, b));

        assertEquals(java.util.List.of(bindingA), bindingsConversingWith(a));
        assertEquals(java.util.List.of(bindingB), bindingsConversingWith(b));
        assertEquals(java.time.Instant.ofEpochSecond(sentA), windowStart(bindingA, a));
        assertEquals(java.time.Instant.ofEpochSecond(sentB), windowStart(bindingB, b));
        assertFalse(hasWindowRow(bindingA, b));
        assertFalse(hasWindowRow(bindingB, a));
        assertTrue(isRecorded(idA));
        assertTrue(isRecorded(idB));
    }

    @Test
    void aMessageForANumberOfAnotherAppIsSkippedAndTheRestOfTheBodyDispatched() {
        // The body's signature is APP_SECRET's, so a binding holding another app's secret cannot take a message from it.
        var bindingA = seedBinding(APP_SECRET);
        var bindingB = seedBinding(OTHER_PHONE_NUMBER_ID, OTHER_APP_SECRET, true, WhatsAppTransport.CLOUD_API);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var body = changesBody(change(PHONE_NUMBER_ID, textMessage(idA, a, secondsAgo(0))),
                change(OTHER_PHONE_NUMBER_ID, textMessage(idB, b, secondsAgo(0))));

        assertEquals(java.util.List.of(skipped(idB, OTHER_PHONE_NUMBER_ID, bindingA), served(a)),
                loggedWhilePosting(body, 200, idA, idB, a, b));

        assertEquals(java.util.List.of(bindingA), bindingsConversingWith(a));
        assertTrue(isRecorded(idA));
        assertFalse(isRecorded(idB));
        assertFalse(hasWindowRow(bindingB, b));
        assertFalse(hasWindowRow(bindingA, b));
    }

    @Test
    void messagesForUnboundDisabledWebOrSecretlessNumbersAreSkippedAndTheRestDispatched() {
        var bindingA = seedBinding(APP_SECRET);
        var bindingB = seedBinding(OTHER_PHONE_NUMBER_ID, APP_SECRET, true, WhatsAppTransport.CLOUD_API);
        var disabledNumber = "100000000000003";
        var webNumber = "100000000000004";
        var secretlessNumber = "100000000000005";
        var disabled = seedBinding(disabledNumber, APP_SECRET, false, WhatsAppTransport.CLOUD_API);
        var web = seedBinding(webNumber, APP_SECRET, true, WhatsAppTransport.WHATSAPP_WEB);
        var secretless = seedBinding(secretlessNumber, null, true, WhatsAppTransport.CLOUD_API);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var idUnbound = uniqueWamid();
        var idDisabled = uniqueWamid();
        var idWeb = uniqueWamid();
        var idSecretless = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var other = uniqueSender();
        var body = changesBody(change(PHONE_NUMBER_ID, textMessage(idA, a, secondsAgo(0))),
                change(UNBOUND_PHONE_NUMBER_ID, textMessage(idUnbound, other, secondsAgo(0))),
                change(disabledNumber, textMessage(idDisabled, other, secondsAgo(0))),
                change(webNumber, textMessage(idWeb, other, secondsAgo(0))),
                change(secretlessNumber, textMessage(idSecretless, other, secondsAgo(0))),
                change(OTHER_PHONE_NUMBER_ID, textMessage(idB, b, secondsAgo(0))));

        assertEquals(java.util.List.of(
                skipped(idUnbound, UNBOUND_PHONE_NUMBER_ID, bindingA),
                skipped(idDisabled, disabledNumber, bindingA),
                skipped(idWeb, webNumber, bindingA),
                skipped(idSecretless, secretlessNumber, bindingA),
                served(a),
                served(b)), loggedWhilePosting(body, 200, idA, idB, idUnbound, idDisabled, idWeb, idSecretless, a, b, other));

        assertEquals(java.util.List.of(bindingA), bindingsConversingWith(a));
        assertEquals(java.util.List.of(bindingB), bindingsConversingWith(b));
        assertEquals(java.util.List.of(true, true, false, false, false, false), java.util.List.of(
                isRecorded(idA), isRecorded(idB),
                isRecorded(idUnbound), isRecorded(idDisabled), isRecorded(idWeb), isRecorded(idSecretless)));
        assertEquals(java.util.List.of(false, false, false), java.util.List.of(
                hasWindowRow(disabled, other), hasWindowRow(web, other), hasWindowRow(secretless, other)));
    }

    @Test
    void aRedeliveredMessageForTheSecondBindingIsDroppedLikeTheFirstBindings() {
        seedBinding(APP_SECRET);
        var bindingB = seedBinding(OTHER_PHONE_NUMBER_ID, APP_SECRET, true, WhatsAppTransport.CLOUD_API);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var body = changesBody(change(PHONE_NUMBER_ID, textMessage(idA, a, secondsAgo(0))),
                change(OTHER_PHONE_NUMBER_ID, textMessage(idB, b, secondsAgo(0))));

        assertEquals(java.util.List.of(served(a), served(b)), loggedWhilePosting(body, 200, idA, idB, a, b));
        assertEquals(java.util.List.of(duplicate(idA), duplicate(idB)), loggedWhilePosting(body, 200, idA, idB, a, b));

        assertEquals(java.util.List.of(bindingB), bindingsConversingWith(b));
    }

    @Test
    void anIdTheSecondBindingRecordedEarlierIsDroppedBeforeItsAgeIsChecked() {
        seedBinding(APP_SECRET);
        var bindingB = seedBinding(OTHER_PHONE_NUMBER_ID, APP_SECRET, true, WhatsAppTransport.CLOUD_API);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        recordAccepted(idB);
        var body = changesBody(change(PHONE_NUMBER_ID, textMessage(idA, a, secondsAgo(0))),
                change(OTHER_PHONE_NUMBER_ID, textMessage(idB, b, secondsAgo(EIGHT_DAYS))));

        assertEquals(java.util.List.of(duplicate(idB), served(a)), loggedWhilePosting(body, 200, idA, idB, a, b));

        assertFalse(hasWindowRow(bindingB, b));
    }

    @Test
    void aStaleMessageForTheSecondBindingRefusesTheWholeBody() {
        var bindingA = seedBinding(APP_SECRET);
        var bindingB = seedBinding(OTHER_PHONE_NUMBER_ID, APP_SECRET, true, WhatsAppTransport.CLOUD_API);
        var idA = uniqueWamid();
        var idB = uniqueWamid();
        var a = uniqueSender();
        var b = uniqueSender();
        var body = changesBody(change(PHONE_NUMBER_ID, textMessage(idA, a, secondsAgo(0))),
                change(OTHER_PHONE_NUMBER_ID, textMessage(idB, b, secondsAgo(EIGHT_DAYS))));

        assertEquals(java.util.List.of(), loggedWhilePosting(body, 401, idA, idB, a, b));

        assertFalse(isRecorded(idA));
        assertFalse(isRecorded(idB));
        assertFalse(hasWindowRow(bindingA, a));
        assertFalse(hasWindowRow(bindingB, b));
    }
}
