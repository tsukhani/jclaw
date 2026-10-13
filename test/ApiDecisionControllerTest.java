import com.google.gson.JsonParser;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.ConfigService;
import services.Tx;
import services.decision.LayaDecision;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The operator's read of {@code GET /api/decision/laya} (JCLAW-1447), against a server that answers
 * {@code /health}: the stored address is the one dialed, the stored key is sent, and each field of the
 * server's answer lands in its own field of the route's.
 */
class ApiDecisionControllerTest extends FunctionalTest {

    private static final String KEY = "laya-route-key-0123";

    @BeforeEach
    void setup() {
        // LayaDecisionTest reads the two keys this class stores under the same lock.
        JevBreakerTestSync.acquire();
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void teardown() {
        try {
            commitInFreshTx(() -> {
                ConfigService.delete(LayaDecision.BASE_URL_KEY);
                ConfigService.delete(LayaDecision.API_KEY);
            });
            clearCookies();
        } finally {
            JevBreakerTestSync.release();
        }
    }

    private void login() {
        // The cookie jar is JVM-global; a bearer response a concurrent class left in it would make this an agent.
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static void commitInFreshTx(Runnable block) {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                Tx.run(block);
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
    }

    @Test
    void layaReportsWhatTheStoredServerAnswersSendingItTheStoredKey() throws Exception {
        login();
        try (var server = new MockWebServer()) {
            server.start();
            var health = new Buffer().writeUtf8("{\"status\":\"ok\",\"loaded\":[\"english\"],\"device\":\"mps\"}");
            server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .body(health).build());
            var address = server.url("/").toString();
            commitInFreshTx(() -> {
                ConfigService.set(LayaDecision.BASE_URL_KEY, address);
                ConfigService.set(LayaDecision.API_KEY, KEY);
            });

            var resp = GET("/api/decision/laya");

            assertIsOk(resp);
            var body = getContent(resp);
            var json = JsonParser.parseString(body).getAsJsonObject();
            assertEquals(Set.of("baseUrl", "reachable", "error", "loaded", "device"), json.keySet(), body);
            assertEquals(address.substring(0, address.length() - 1), json.get("baseUrl").getAsString());
            assertTrue(json.get("reachable").getAsBoolean(), body);
            assertTrue(json.get("error").isJsonNull(), body);
            assertEquals("[\"english\"]", json.get("loaded").toString());
            assertEquals("mps", json.get("device").getAsString());
            assertFalse(body.contains(KEY), "the answer carries no key");

            var recorded = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(recorded, "the stored address was dialed");
            assertEquals("GET", recorded.getMethod());
            assertEquals("/health", recorded.getUrl().encodedPath());
            assertEquals("Bearer " + KEY, recorded.getHeaders().get("Authorization"));
        }
    }
}
