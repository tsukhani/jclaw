import com.google.gson.JsonParser;
import llm.routing.RouterPolicy;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.ConfigService;
import services.Tx;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The operator's read of {@code GET /api/decision/laya} (JCLAW-1447), against a server that answers
 * {@code /health}: the stored address is the one dialed, the stored key is sent, and each field of the
 * server's answer lands in its own field of the route's. And, through {@code POST /api/config}, the selections the
 * cards stored are gone and an Ollama classifier is checked against the server's list (JF-135).
 */
class ApiDecisionControllerTest extends FunctionalTest {

    private static final String KEY = "laya-route-key-0123";

    @BeforeEach
    void setup() {
        // LayaDecisionTest reads the Laya keys this class stores under the same lock, and ModelRouterTurnTest the rest.
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

    @Test
    void aRetiredModelSelectionIsRefusedAsNoDecisionProviderSetting() {
        // JF-135: no card stores a selection any more.
        login();
        var resp = POST("/api/config", "application/json", "{\"key\":\"decision.ollama.models\",\"value\":\"[\\\"tev1\\\"]\"}");
        assertEquals(403, resp.status.intValue(), getContent(resp));
        assertEquals("decision.ollama.models is not a decision-provider setting; use one of decision.jev.apiKey, "
                + "decision.ollama.baseUrl, decision.ollama.keepAlive, decision.laya.baseUrl, decision.laya.apiKey, "
                + "decision.baseten.apiKey.", message(resp));
        commitInFreshTx(() -> assertNull(ConfigService.get("decision.ollama.models"), "nothing was stored"));
    }

    @Test
    void anOllamaClassifierIsCheckedAgainstWhatTheServerListsNow() throws Exception {
        // JF-135. Both classifier halves are stored under the lock, which ModelRouterTurnTest's turns also take.
        login();
        try (var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (!request.getUrl().encodedPath().equals("/api/tags")) return new MockResponse.Builder().code(404).build();
                    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                            .body("{\"models\":[{\"name\":\"tev1:latest\",\"capabilities\":[\"decision\"]},"
                                    + "{\"name\":\"nimble:latest\",\"capabilities\":[\"decision\"]}]}").build();
                }
            });
            server.start();
            var address = server.url("/").toString().replaceAll("/$", "");
            commitInFreshTx(() -> {
                ConfigService.set(OllamaDecision.BASE_URL_KEY, address);
                ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, OllamaDecision.PROVIDER);
            });
            try {
                var unlisted = postClassifierModel("clef:cloud");
                assertEquals(403, unlisted.status.intValue(), getContent(unlisted));
                assertEquals("router.classifier.model must be a decision model on the Ollama server at " + address
                        + ": tev1:latest, nimble:latest.", message(unlisted));

                var listed = postClassifierModel("nimble:latest");
                assertIsOk(listed);
                commitInFreshTx(() -> assertEquals("nimble:latest", ConfigService.get(RouterPolicy.CLASSIFIER_MODEL)));

                commitInFreshTx(() -> ConfigService.set(OllamaDecision.BASE_URL_KEY, "http://127.0.0.1:1"));
                var down = postClassifierModel("tev1:latest");
                assertEquals(403, down.status.intValue(), getContent(down));
                assertEquals("router.classifier.model could not be checked against the Ollama server at "
                        + "http://127.0.0.1:1: not reachable (ConnectException).", message(down));
                commitInFreshTx(() -> assertEquals("nimble:latest", ConfigService.get(RouterPolicy.CLASSIFIER_MODEL),
                        "the refused write left the model as it was"));
            } finally {
                commitInFreshTx(() -> {
                    ConfigService.delete(RouterPolicy.CLASSIFIER_MODEL);
                    ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
                    ConfigService.delete(OllamaDecision.BASE_URL_KEY);
                });
            }
        }
    }

    private Http.Response postClassifierModel(String model) {
        return POST("/api/config", "application/json",
                "{\"key\":\"" + RouterPolicy.CLASSIFIER_MODEL + "\",\"value\":\"" + model + "\"}");
    }

    private static String message(Http.Response resp) {
        return JsonParser.parseString(getContent(resp)).getAsJsonObject().get("message").getAsString();
    }
}
