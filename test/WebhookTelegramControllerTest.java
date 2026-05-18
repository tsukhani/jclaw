import channels.ChannelTransport;
import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import play.test.*;
import services.EventLogger;
import services.Tx;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Happy-path and edge coverage for
 * {@link controllers.WebhookTelegramController} (JCLAW-313). Sister to
 * {@code WebhookControllerTest}, which owns the negative gates (unknown
 * binding, bad path secret, missing header). This file covers the cases
 * the negative file can't reach because it doesn't inject the
 * {@code x-telegram-bot-api-secret-token} header:
 *
 * <ul>
 *   <li>Valid path-secret + valid secret-token header → 200 with body
 *       parse paths (text, non-message update, malformed JSON).</li>
 *   <li>Peer-mismatch (signature passes, but {@code from.id} doesn't
 *       match the binding's user) — silent drop, 200 to keep Telegram
 *       from retrying.</li>
 * </ul>
 */
class WebhookTelegramControllerTest extends FunctionalTest {

    private static final String SECRET = "telegram-secret-fixture";
    private static final String BOUND_USER_ID = "42";

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

    private Long seedBinding(boolean enabled) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "tg-webhook-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = "999:bot-fixture";
            b.telegramUserId = BOUND_USER_ID;
            b.transport = ChannelTransport.WEBHOOK;
            b.webhookSecret = SECRET;
            b.webhookUrl = "https://example.com/tg";
            b.enabled = enabled;
            b.save();
            return b.id;
        });
    }

    private static play.mvc.Http.Response postWithSecretHeader(Long bindingId, String pathSecret,
                                                                String headerSecret, String body) {
        var req = newRequest();
        req.method = "POST";
        req.contentType = "application/json";
        var url = "/api/webhooks/telegram/" + bindingId + "/" + pathSecret;
        req.url = url;
        req.path = url;
        req.querystring = "";
        req.body = new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        if (headerSecret != null) {
            req.headers.put("x-telegram-bot-api-secret-token",
                    new play.mvc.Http.Header("x-telegram-bot-api-secret-token", headerSecret));
        }
        return makeRequest(req);
    }

    // ===== Path secret + header secret pairing =====

    @Test
    void acceptsValidPathAndHeaderSecretWithEmptyUpdate() {
        // Both secrets match; the body parses as {} which produces a
        // non-message update — controller calls ok() without dispatch.
        var bindingId = seedBinding(true);
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, "{}");
        assertEquals(200, response.status.intValue());
    }

    @Test
    void rejectsWrongHeaderSecretEvenWithCorrectPath() {
        // Path passes, header fails — must 401.
        var bindingId = seedBinding(true);
        var response = postWithSecretHeader(bindingId, SECRET, "wrong-header", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void rejectsCorrectHeaderWithWrongPath() {
        // Path fails first, header never compared.
        var bindingId = seedBinding(true);
        var response = postWithSecretHeader(bindingId, "wrong-path", SECRET, "{}");
        assertEquals(401, response.status.intValue());
    }

    // ===== Body parse fallthroughs =====

    @Test
    void parsesEditedMessageAsNonMessageUpdate() {
        // Telegram delivers edited_message and other update types we
        // currently don't dispatch — parseUpdate returns null and the
        // controller short-circuits to ok().
        var bindingId = seedBinding(true);
        var body = "{\"update_id\":1,\"edited_message\":{}}";
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, body);
        assertEquals(200, response.status.intValue());
    }

    @Test
    void rejectsPeerMismatchSilently() {
        // The message is well-formed but from a different Telegram user
        // than the binding is locked to. Controller logs + returns 200 so
        // Telegram doesn't retry, but no agent invocation happens.
        var bindingId = seedBinding(true);
        var body = "{"
                + "\"update_id\":1,"
                + "\"message\":{"
                + "  \"message_id\":1,"
                + "  \"date\":1700000000,"
                + "  \"chat\":{\"id\":100,\"type\":\"private\"},"
                + "  \"from\":{\"id\":9999999,\"is_bot\":false,\"first_name\":\"Imposter\"},"
                + "  \"text\":\"hello\""
                + "}"
                + "}";
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, body);
        assertEquals(200, response.status.intValue());
    }

    @Test
    void disabledBindingShortCircuitsBeforeSignatureCheck() {
        // Disabled bindings must not reach signature verification (a
        // tampered request still gets 200; Telegram stops retrying).
        // This is the JCLAW-89 "operator pause" path.
        var bindingId = seedBinding(false);
        // Wrong header on purpose: disabled-binding short-circuit must
        // win over signature gate. Controller returns 200.
        var response = postWithSecretHeader(bindingId, SECRET, "anything", "{}");
        assertEquals(200, response.status.intValue());
    }

    @Test
    void swallowsMalformedJsonWithoutErroringTheResponse() {
        // The controller catches JsonParser exceptions and still hits
        // ok() at the end — Telegram never sees a 500 even when the
        // payload is junk. Important to keep retry storms from forming.
        var bindingId = seedBinding(true);
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, "{not-json");
        assertEquals(200, response.status.intValue());
    }

    @Test
    void unknownBindingTakesPriorityOverHeaderCheck() {
        // Even with a valid-looking secret-token header, an unknown
        // bindingId returns 404 (the missing-row check runs first).
        var response = postWithSecretHeader(99999999L, SECRET, SECRET, "{}");
        assertEquals(404, response.status.intValue());
    }

    // ===== Callback query (inline keyboard) — peer mismatch only =====
    //
    // The accepted-callback path spawns a virtual thread that calls
    // TelegramCallbackDispatcher.dispatch(), which routes through the live
    // OkHttp-backed TelegramChannel against api.telegram.org. That leaks
    // socket activity into the shared test JVM and destabilises later
    // suite-mates. We only cover the peer-mismatch branch here, which
    // returns 200 BEFORE the dispatcher is invoked. The accepted-callback
    // happy path is covered end-to-end by TelegramChannelTest with a
    // MockTelegramServer harness.

    @Test
    void silentlyDropsCallbackQueryFromOtherUser() {
        // JCLAW-109: callback parsed successfully, signature passes, but
        // from.id != binding.telegramUserId. The controller logs a
        // peer-mismatch warning and returns 200 — never reaches the
        // dispatcher.
        var bindingId = seedBinding(true);
        var body = "{"
                + "\"update_id\":1,"
                + "\"callback_query\":{"
                + "  \"id\":\"cb-2\","
                + "  \"chat_instance\":\"ci-2\","
                + "  \"from\":{\"id\":9999999,\"is_bot\":false,\"first_name\":\"Imposter\"},"
                + "  \"data\":\"action:ok\","
                + "  \"message\":{\"message_id\":5,\"date\":1700000000,"
                + "    \"chat\":{\"id\":100,\"type\":\"private\"},"
                + "    \"from\":{\"id\":9999999,\"is_bot\":false,\"first_name\":\"Imposter\"}}"
                + "}}";
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, body);
        assertEquals(200, response.status.intValue());
    }
}
