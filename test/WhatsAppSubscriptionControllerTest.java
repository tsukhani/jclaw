import channels.WhatsAppSubscription;
import com.google.gson.JsonParser;
import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.Tx;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Functional tests for {@code WhatsAppSubscriptionController} (JCLAW-1410): the
 * subscription read and the Subscribe write behind
 * {@code /api/channels/whatsapp/bindings/{id}/subscription}, with Graph stubbed via
 * {@link WhatsAppSubscription#installForTest}.
 */
class WhatsAppSubscriptionControllerTest extends FunctionalTest {

    private static final String TOKEN = "tok-SECRET-1410";

    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicInteger subscribes = new AtomicInteger();
    private final AtomicReference<WhatsAppSubscription.State> checkAnswer = new AtomicReference<>();
    private final AtomicReference<WhatsAppSubscription.SubscribeResult> subscribeAnswer = new AtomicReference<>();

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        checkAnswer.set(new WhatsAppSubscription.NotSubscribed("222", "444"));
        subscribeAnswer.set(new WhatsAppSubscription.Done("222"));
        WhatsAppSubscription.installForTest(
                (phoneNumberId, accessToken) -> {
                    checks.incrementAndGet();
                    return checkAnswer.get();
                },
                (phoneNumberId, accessToken) -> {
                    subscribes.incrementAndGet();
                    return subscribeAnswer.get();
                });
    }

    @AfterEach
    void teardown() {
        WhatsAppSubscription.clearForTest();
    }

    private void login() {
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
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

    private Long seedBinding(WhatsAppTransport transport, String phoneNumberId, String accessToken) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "wa-sub-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = transport;
            b.phoneNumberId = phoneNumberId;
            b.accessToken = accessToken;
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    private Long seedCloudBinding() {
        return seedBinding(WhatsAppTransport.CLOUD_API, "111", TOKEN);
    }

    private static String path(Long id) {
        return "/api/channels/whatsapp/bindings/" + id + "/subscription";
    }

    // ===== Auth =====

    @Test
    void bothRoutesRequireAuth() {
        var id = seedCloudBinding();
        assertEquals(401, GET(path(id)).status.intValue());
        assertEquals(401, POST(path(id), "application/json", "{}").status.intValue());
        assertEquals(0, checks.get() + subscribes.get());
    }

    // ===== GET =====

    @Test
    void readAnswersNotSubscribedWithIds() {
        var id = seedCloudBinding();
        login();

        var response = GET(path(id));
        assertIsOk(response);
        var content = getContent(response);
        var json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(id.longValue(), json.get("bindingId").getAsLong());
        assertEquals("NOT_SUBSCRIBED", json.get("state").getAsString());
        assertEquals("222", json.get("wabaId").getAsString());
        assertEquals("444", json.get("appId").getAsString());
        assertFalse(content.contains(TOKEN));
        assertEquals(1, checks.get());
    }

    @Test
    void readAnswersSubscribed() {
        checkAnswer.set(new WhatsAppSubscription.Subscribed("222", "444"));
        var id = seedCloudBinding();
        login();

        var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
        assertEquals("SUBSCRIBED", json.get("state").getAsString());
        assertEquals("222", json.get("wabaId").getAsString());
        assertEquals("444", json.get("appId").getAsString());
    }

    @Test
    void readAnswersUnknownWithReason() {
        checkAnswer.set(new WhatsAppSubscription.Unknown("(#200) Permissions error"));
        var id = seedCloudBinding();
        login();

        var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
        assertEquals("UNKNOWN", json.get("state").getAsString());
        assertEquals("(#200) Permissions error", json.get("reason").getAsString());
    }

    @Test
    void readAsksMetaOnEveryRequest() {
        var id = seedCloudBinding();
        login();

        assertIsOk(GET(path(id)));
        assertIsOk(GET(path(id)));
        assertEquals(2, checks.get());
    }

    @Test
    void unknownBindingIs404OnBothRoutes() {
        login();
        assertEquals(404, GET(path(999_999L)).status.intValue());
        assertEquals(404, POST(path(999_999L), "application/json", "{}").status.intValue());
        assertEquals(0, checks.get() + subscribes.get());
    }

    @Test
    void whatsAppWebBindingIsNotApplicableAndNeverCallsMeta() {
        var id = seedBinding(WhatsAppTransport.WHATSAPP_WEB, null, null);
        login();

        var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
        assertEquals("NOT_APPLICABLE", json.get("state").getAsString());
        assertEquals(400, POST(path(id), "application/json", "{}").status.intValue());
        assertEquals(0, checks.get() + subscribes.get());
    }

    @Test
    void cloudBindingWithoutTokenIsNotApplicableAndNeverCallsMeta() {
        var id = seedBinding(WhatsAppTransport.CLOUD_API, "111", null);
        login();

        var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
        assertEquals("NOT_APPLICABLE", json.get("state").getAsString());
        assertEquals(400, POST(path(id), "application/json", "{}").status.intValue());
        assertEquals(0, checks.get() + subscribes.get());
    }

    // ===== POST =====

    @Test
    void successfulSubscribeAnswersTheStateReadBack() {
        var id = seedCloudBinding();
        checkAnswer.set(new WhatsAppSubscription.Subscribed("222", "444"));
        login();

        var response = POST(path(id), "application/json", "{}");
        assertIsOk(response);
        var content = getContent(response);
        var json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals("SUBSCRIBED", json.get("state").getAsString());
        assertEquals("222", json.get("wabaId").getAsString());
        assertFalse(content.contains(TOKEN));
        assertEquals(1, subscribes.get());
        assertEquals(1, checks.get());
    }

    @Test
    void refusedSubscribeAnswers422WithMetasMessage() {
        subscribeAnswer.set(new WhatsAppSubscription.Failed("(#200) Permissions error"));
        var id = seedCloudBinding();
        login();

        var response = POST(path(id), "application/json", "{}");
        assertEquals(422, response.status.intValue());
        var content = getContent(response);
        var json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals("cloud_api_subscribe_failed", json.get("code").getAsString());
        assertTrue(json.get("message").getAsString().contains("(#200) Permissions error"));
        assertFalse(content.contains(TOKEN));
        assertEquals(0, checks.get());
    }
}
