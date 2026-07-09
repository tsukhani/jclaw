import channels.ChannelTransport;
import channels.TelegramBotIdentityTestHooks;
import channels.TelegramChannel;
import channels.TelegramWebhookRateLimiter;
import controllers.WebhookTelegramController;
import models.Agent;
import models.TelegramBinding;
import models.TelegramTopicBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
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
    private static final String BOT_TOKEN = "999:bot-fixture";

    private MockTelegramServer server;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        // M1: clear the static per-binding rate-limit counters so a flood test
        // doesn't bleed into unrelated cases (and vice-versa). The default
        // ceiling (60/60s) is permissive enough that other single-request
        // cases never trip the limiter regardless.
        TelegramWebhookRateLimiter.resetForTest();
        // JCLAW-371: the dispatch path now resolves the bot identity via getMe.
        // Redirect that call to an embedded mock so no test hits api.telegram.org;
        // the rejection-branch tests below never spawn the downstream agent path,
        // but the identity resolve still fires before the gate.
        server = new MockTelegramServer();
        server.start();
        server.respondWith("getMe", 200,
                "{\"ok\":true,\"result\":{\"id\":999,\"is_bot\":true,"
                        + "\"first_name\":\"Fixture Bot\",\"username\":\"fixture_bot\"}}");
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
        TelegramBotIdentityTestHooks.clear(BOT_TOKEN);
    }

    @AfterEach
    void teardown() {
        if (server != null) server.close();
        TelegramChannel.clearForTest(BOT_TOKEN);
        TelegramBotIdentityTestHooks.clear(BOT_TOKEN);
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
            b.webhookBaseUrl = "https://example.com/tg";
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

    // ===== JCLAW-387 B1: forwarded-message coalescing lane =====

    @Test
    void acceptsForwardedMessageFromOwner() {
        // A forwarded message from the binding owner passes the access gate and
        // is routed through the forward-coalesce lane (which buffers it for the
        // idle window). The webhook returns 200 immediately — dispatch happens
        // off-thread after the window, so we assert ingress acceptance here.
        var bindingId = seedBinding(true);
        var body = "{"
                + "\"update_id\":1,"
                + "\"message\":{"
                + "  \"message_id\":1,"
                + "  \"date\":1700000100,"
                + "  \"forward_date\":1700000000,"
                + "  \"chat\":{\"id\":42,\"type\":\"private\"},"
                + "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Owner\"},"
                + "  \"text\":\"a forwarded note\""
                + "}"
                + "}";
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, body);
        assertEquals(200, response.status.intValue());
    }

    @Test
    void forwardDetectionMatchesWebhookShapedUpdate() {
        // The controller derives isForward from the same raw SDK Update it passes
        // to parseUpdate; assert that detection seam directly against the shape a
        // webhook body deserializes into (forward_date set vs absent).
        var fwdMsg = new org.telegram.telegrambots.meta.api.objects.message.Message();
        fwdMsg.setMessageId(1);
        fwdMsg.setText("forwarded");
        fwdMsg.setForwardDate(1700000000);
        var fwdUpdate = new org.telegram.telegrambots.meta.api.objects.Update();
        fwdUpdate.setMessage(fwdMsg);
        assertTrue(channels.TelegramPollingRunner.isForward(fwdUpdate),
                "a forwarded webhook message must be detected as a forward");

        var normalMsg = new org.telegram.telegrambots.meta.api.objects.message.Message();
        normalMsg.setMessageId(2);
        normalMsg.setText("typed");
        var normalUpdate = new org.telegram.telegrambots.meta.api.objects.Update();
        normalUpdate.setMessage(normalMsg);
        assertFalse(channels.TelegramPollingRunner.isForward(normalUpdate),
                "a normal typed webhook message must NOT be detected as a forward");
    }

    @Test
    void silentlyIgnoresGroupMessageWithoutMention() {
        // JCLAW-371: a group message that does NOT address the bot is silently
        // ignored even when it comes from the binding owner — group access is
        // mention-gated, not owner-gated. Controller returns 200 (no retry) and
        // never reaches the agent dispatch path.
        var bindingId = seedBinding(true);
        var body = "{"
                + "\"update_id\":1,"
                + "\"message\":{"
                + "  \"message_id\":1,"
                + "  \"date\":1700000000,"
                + "  \"chat\":{\"id\":-100,\"type\":\"group\"},"
                + "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Owner\"},"
                + "  \"text\":\"just chatting, not addressing the bot\""
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

    // ===== M1 webhook ingress hardening =====

    @Test
    void floodIsRateLimitedWith429BeforeSecretCheck() {
        // M1: with a small rate-limit ceiling, a flood for one binding starts
        // returning 429 once the window count is exceeded. The requests carry a
        // WRONG header secret on purpose — a 429 (not 401) proves the rate-limit
        // runs BEFORE verifySecret.
        play.Play.configuration.setProperty("telegram.webhook.rate-limit.max", "3");
        play.Play.configuration.setProperty("telegram.webhook.rate-limit.window-seconds", "60");
        try {
            var bindingId = seedBinding(true);
            // First `max` (3) requests: wrong secret → 401 (rate limit not yet hit).
            for (int i = 0; i < 3; i++) {
                var r = postWithSecretHeader(bindingId, SECRET, "wrong-header", "{}");
                assertEquals(401, r.status.intValue(),
                        "request " + (i + 1) + " is under the limit, so the wrong secret yields 401");
            }
            // The 4th exceeds the window → 429, even though the secret is still wrong.
            var flooded = postWithSecretHeader(bindingId, SECRET, "wrong-header", "{}");
            assertEquals(429, flooded.status.intValue(),
                    "exceeding the rate-limit window returns 429 before the secret check");
        } finally {
            play.Play.configuration.remove("telegram.webhook.rate-limit.max");
            play.Play.configuration.remove("telegram.webhook.rate-limit.window-seconds");
        }
    }

    @Test
    void oversizedBodyReturns413() {
        // M1: a body larger than the configured max-body-bytes is rejected with
        // 413 before parsing. Valid secrets are supplied so the only thing that
        // can stop the request is the size guard.
        play.Play.configuration.setProperty("telegram.webhook.max-body-bytes", "8");
        try {
            var bindingId = seedBinding(true);
            var oversized = "{\"update_id\":1234567890}"; // > 8 bytes
            var response = postWithSecretHeader(bindingId, SECRET, SECRET, oversized);
            assertEquals(413, response.status.intValue());
        } finally {
            play.Play.configuration.remove("telegram.webhook.max-body-bytes");
        }
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

    // ===== JCLAW-375: inbound message_reaction handling =====

    @Test
    void reactionUpdateWithNotifyOffReturns200WithoutDispatch() {
        // JCLAW-375: the handler is wired into the webhook dispatch path. With
        // notify=off the reaction is parsed but gated out — no agent spawn, no
        // network. Controller still returns 200 so Telegram stops retrying.
        play.Play.configuration.setProperty("telegram.reactions.notify", "off");
        try {
            var bindingId = seedBinding(true);
            var body = reactionBody("private", 100, 42, BOUND_USER_ID, "👍"); // 👍
            var response = postWithSecretHeader(bindingId, SECRET, SECRET, body);
            assertEquals(200, response.status.intValue());
        } finally {
            play.Play.configuration.remove("telegram.reactions.notify");
        }
    }

    @Test
    void reactionUpdateInGroupSuppressedUnderDefaultOwnPolicy() {
        // Default policy is 'own', which suppresses group reactions (the reacted
        // message's author is unattributable from the update). Parsed, gated out,
        // 200, no agent dispatch.
        play.Play.configuration.remove("telegram.reactions.notify"); // → default 'own'
        var bindingId = seedBinding(true);
        var body = reactionBody("supergroup", -100, 42, BOUND_USER_ID, "👍");
        var response = postWithSecretHeader(bindingId, SECRET, SECRET, body);
        assertEquals(200, response.status.intValue());
    }

    private static String reactionBody(String chatType, long chatId, int messageId,
                                       String reactorId, String emoji) {
        return "{"
                + "\"update_id\":1,"
                + "\"message_reaction\":{"
                + "  \"chat\":{\"id\":" + chatId + ",\"type\":\"" + chatType + "\"},"
                + "  \"message_id\":" + messageId + ","
                + "  \"user\":{\"id\":" + reactorId + ",\"is_bot\":false,\"first_name\":\"Owner\"},"
                + "  \"date\":1700000000,"
                + "  \"old_reaction\":[],"
                + "  \"new_reaction\":[{\"type\":\"emoji\",\"emoji\":\"" + emoji + "\"}]"
                + "}}";
    }

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

    // ===== JCLAW-377: per-topic agent routing at the dispatch site =====
    //
    // The accepted-message dispatch path spawns a virtual thread that streams
    // through TelegramChannel against api.telegram.org, so we can't assert on a
    // full 200-dispatch here without leaking network. Instead we exercise the
    // private helper resolveTopicAgent(token, chatId, threadId, defaultAgent) —
    // the exact call processMessage() makes before handing off to
    // AgentRunner.processInboundForAgentStreaming. Invoked reflectively so no
    // production-only test seam is added.

    private static Agent invokeResolveTopicAgent(String token, String chatId,
                                                 Integer threadId, Agent defaultAgent) {
        try {
            var m = WebhookTelegramController.class.getDeclaredMethod(
                    "resolveTopicAgent", String.class, String.class, Integer.class, Agent.class);
            m.setAccessible(true);
            return (Agent) m.invoke(null, token, chatId, threadId, defaultAgent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Seed a (chatId, threadId) override row mapping the topic to {@code overrideAgent}. */
    private void seedTopicOverride(Long bindingId, String chatId, Integer threadId, String overrideAgentName) {
        commitInFreshTx(() -> {
            var overrideAgent = new Agent();
            overrideAgent.name = overrideAgentName;
            overrideAgent.modelProvider = "openrouter";
            overrideAgent.modelId = "gpt-4.1";
            overrideAgent.enabled = true;
            overrideAgent.save();

            var t = new TelegramTopicBinding();
            t.binding = TelegramBinding.findById(bindingId);
            t.chatId = chatId;
            t.threadId = threadId;
            t.agent = overrideAgent;
            t.save();
            return null;
        });
    }

    @Test
    void resolveTopicAgentRoutesMappedTopicToOverrideAgent() {
        var bindingId = seedBinding(true);
        seedTopicOverride(bindingId, "-100377", 7, "webhook-topic-override");

        Agent defaultAgent = commitInFreshTx(() ->
                ((TelegramBinding) TelegramBinding.findById(bindingId)).agent);
        Agent resolved = invokeResolveTopicAgent(BOT_TOKEN, "-100377", 7, defaultAgent);

        assertEquals("webhook-topic-override", resolved.name,
                "a mapped (chatId, threadId) must route to the per-topic override agent");
    }

    @Test
    void resolveTopicAgentFallsBackToDefaultForUnmappedTopicAndDm() {
        var bindingId = seedBinding(true);
        seedTopicOverride(bindingId, "-100377", 7, "webhook-fallback-override");

        Agent defaultAgent = commitInFreshTx(() ->
                ((TelegramBinding) TelegramBinding.findById(bindingId)).agent);

        // Same chat, different (unmapped) topic → default.
        Agent unmapped = invokeResolveTopicAgent(BOT_TOKEN, "-100377", 99, defaultAgent);
        assertEquals("tg-webhook-agent", unmapped.name,
                "an unmapped topic must fall back to the binding default agent");

        // DM / non-topic message (null threadId) → default, even with an override on the chat.
        Agent dm = invokeResolveTopicAgent(BOT_TOKEN, "-100377", null, defaultAgent);
        assertEquals("tg-webhook-agent", dm.name,
                "a non-topic message (null threadId) must use the binding default agent");
    }
}
