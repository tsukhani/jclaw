import channels.WhatsAppUsage;
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

import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Functional tests for {@code WhatsAppUsageController} (JCLAW-1412): the monthly reply count
 * behind {@code /api/channels/whatsapp/bindings/{id}/usage}, with Graph stubbed via
 * {@link WhatsAppUsage#installForTest}.
 */
class WhatsAppUsageControllerTest extends FunctionalTest {

    private static final String TOKEN = "tok-SECRET-1412";
    private static final String DISPLAY = "+1 555-000-1111";

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicReference<String> readArgs = new AtomicReference<>();
    private final AtomicReference<WhatsAppUsage.Usage> answer = new AtomicReference<>();

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        answer.set(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 1240, 240));
        WhatsAppUsage.installForTest((phoneNumberId, accessToken, displayNumber) -> {
            reads.incrementAndGet();
            readArgs.set(phoneNumberId + "|" + accessToken + "|" + displayNumber);
            return answer.get();
        });
    }

    @AfterEach
    void teardown() {
        WhatsAppUsage.clearForTest();
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

    private Long seedBinding(WhatsAppTransport transport, String phoneNumberId, String accessToken,
                             String displayNumber) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "wa-usage-agent-" + SEQ.incrementAndGet();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var b = new WhatsAppBinding();
            b.agent = agent;
            b.transport = transport;
            b.phoneNumberId = phoneNumberId;
            b.accessToken = accessToken;
            b.displayPhoneNumber = displayNumber;
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    private Long seedCloudBinding() {
        return seedBinding(WhatsAppTransport.CLOUD_API, "111", TOKEN, DISPLAY);
    }

    private static String path(Long id) {
        return "/api/channels/whatsapp/bindings/" + id + "/usage";
    }

    @Test
    void requiresAuth() {
        var id = seedCloudBinding();
        assertEquals(401, GET(path(id)).status.intValue());
        assertEquals(0, reads.get());
    }

    @Test
    void operatorReadsTheCount() {
        var id = seedCloudBinding();
        login();

        var response = GET(path(id));
        assertIsOk(response);
        var content = getContent(response);
        var json = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(id.longValue(), json.get("bindingId").getAsLong());
        assertEquals("COUNTED", json.get("state").getAsString());
        assertEquals(2026, json.get("year").getAsInt());
        assertEquals(10, json.get("month").getAsInt());
        assertEquals(1240, json.get("replies").getAsLong());
        assertEquals(240, json.get("billed").getAsLong());
        assertEquals(1000, json.get("allowance").getAsInt());
        assertFalse(json.has("reason") && !json.get("reason").isJsonNull());
        assertFalse(content.contains(TOKEN));
        assertEquals("111|" + TOKEN + "|" + DISPLAY, readArgs.get());
    }

    @Test
    void unknownCarriesTheReason() {
        answer.set(new WhatsAppUsage.Unknown(YearMonth.of(2026, 10), "Invalid parameter"));
        var id = seedCloudBinding();
        login();

        var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
        assertEquals("UNKNOWN", json.get("state").getAsString());
        assertEquals("Invalid parameter", json.get("reason").getAsString());
        assertEquals(1000, json.get("allowance").getAsInt());
    }

    @Test
    void asksMetaOnEveryRequest() {
        var id = seedCloudBinding();
        login();

        assertIsOk(GET(path(id)));
        assertIsOk(GET(path(id)));
        assertEquals(2, reads.get());
    }

    @Test
    void unknownBindingIs404() {
        login();
        assertEquals(404, GET(path(999_999L)).status.intValue());
        assertEquals(0, reads.get());
    }

    @Test
    void whatsAppWebBindingIsNotApplicableAndNeverCallsMeta() {
        var id = seedBinding(WhatsAppTransport.WHATSAPP_WEB, null, null, null);
        login();

        var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
        assertEquals("NOT_APPLICABLE", json.get("state").getAsString());
        assertEquals(0, reads.get());
    }

    @Test
    void cloudBindingMissingACredentialOrDisplayNumberIsNotApplicable() {
        login();
        for (var id : new Long[]{
                seedBinding(WhatsAppTransport.CLOUD_API, "111", null, DISPLAY),
                seedBinding(WhatsAppTransport.CLOUD_API, null, TOKEN, DISPLAY),
                seedBinding(WhatsAppTransport.CLOUD_API, "112", TOKEN, " ")}) {
            var json = JsonParser.parseString(getContent(GET(path(id)))).getAsJsonObject();
            assertEquals("NOT_APPLICABLE", json.get("state").getAsString(), "binding " + id);
        }
        assertEquals(0, reads.get());
    }
}
