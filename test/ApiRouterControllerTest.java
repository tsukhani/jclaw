import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiRouterController.CloudUsageResponse;
import controllers.ApiRouterController.UsageBucket;
import llm.routing.ClassifierHealth;
import llm.routing.RouterPolicy;
import llm.routing.SubscriptionUsage.Bucket;
import llm.routing.SubscriptionUsage.Range;
import llm.routing.SubscriptionUsage.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.ConfigService;
import services.NotificationBus;
import services.Tx;
import services.decision.JevApi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1449: {@code GET /api/router/usage/ollama-cloud}. Only the refusals go over HTTP: a valid
 * range reads ollama.com with whatever {@code ollama-cloud} key another class has set, and a Play
 * request thread is out of reach of a canned transport. {@code SubscriptionUsageTest} covers the read.
 */
class ApiRouterControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";

    @BeforeEach
    void login() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(TEST_PASSWORD)));
    }

    @AfterEach
    void logout() {
        AuthFixture.clearAdminPassword();
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
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    private JsonElement statusAlert() {
        var response = GET("/api/router/status");
        assertIsOk(response);
        var alert = JsonParser.parseString(getContent(response)).getAsJsonObject().get("classifierAlert");
        return alert == null ? JsonNull.INSTANCE : alert;
    }

    @Test
    void theStatusCarriesTheClassifiersAlertOnlyOnceItHasFailedThreeTurnsInARow() {
        var classifier = "arc-" + UUID.randomUUID().toString().substring(0, 8);
        commitInFreshTx(() -> {
            ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, classifier);
            ConfigService.set(RouterPolicy.CLASSIFIER_MODEL, "m");
            return null;
        });
        try {
            ClassifierHealth.failed(classifier + "/m", "first");
            ClassifierHealth.failed(classifier + "/m", "second");
            assertEquals(JsonNull.INSTANCE, statusAlert());

            ClassifierHealth.failed(classifier + "/m", "HTTP 410 from ollama-cloud: gone");
            var alert = ClassifierHealth.alertFor(classifier + "/m");
            var expected = new JsonObject();
            expected.addProperty("classifier", classifier + "/m");
            expected.addProperty("consecutiveFailures", 3);
            expected.addProperty("since", alert.since());
            expected.addProperty("lastError", "HTTP 410 from ollama-cloud: gone");
            assertEquals(expected, statusAlert());

            ClassifierHealth.succeeded(classifier + "/m");
            assertEquals(JsonNull.INSTANCE, statusAlert());
        } finally {
            commitInFreshTx(() -> {
                ConfigService.delete(RouterPolicy.CLASSIFIER_MODEL);
                ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
                return null;
            });
        }
    }

    @Test
    void savingAndClearingTheClassifierInSettingsTellsClientsTheAlertOfTheClassifierNowRouting() {
        var jev = RouterPolicy.JEV + "/" + JevApi.MODEL;
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var off = NotificationBus.subscribeEvents((type, data) -> {
            if (ClassifierHealth.BUS_EVENT.equals(type)) heard.add(data);
        });
        try {
            assertIsOk(POST("/api/config", "application/json",
                    "{\"key\":\"" + RouterPolicy.CLASSIFIER_PROVIDER + "\",\"value\":\"" + RouterPolicy.JEV + "\"}"));
            assertIsOk(POST("/api/config", "application/json",
                    "{\"key\":\"" + RouterPolicy.CLASSIFIER_MODEL + "\",\"value\":\"" + JevApi.MODEL + "\"}"));
            assertIsOk(DELETE("/api/config/" + RouterPolicy.CLASSIFIER_MODEL));
            // A request's transaction can commit after its response is written, and the event waits for that commit.
            for (var deadline = System.nanoTime() + 5_000_000_000L; heard.size() < 3 && System.nanoTime() < deadline; ) {
                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
            }
        } finally {
            off.run();
            commitInFreshTx(() -> {
                ConfigService.delete(RouterPolicy.CLASSIFIER_MODEL);
                ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
                return null;
            });
        }
        assertEquals(List.of(Map.of(), Map.of("classifier", jev), Map.of()), heard,
                "a provider alone routes nothing, the pair routes as JEV, and clearing the model routes nothing again");
    }

    @Test
    void aRangeOllamaWouldRefuseIsA400() {
        for (var url : List.of("/api/router/usage/ollama-cloud", "/api/router/usage/ollama-cloud?range=1h",
                "/api/router/usage/ollama-cloud?range=7D")) {
            var response = GET(url);
            assertStatus(400, response);
            assertTrue(getContent(response).contains("range must be 24h, 7d, or 30d"), () -> url + ": " + getContent(response));
        }
    }

    @Test
    void noReadingIsAnEmptySnapshotForTheRequestedRange() {
        var view = CloudUsageResponse.of("ollama-cloud", Range.MONTH, null);
        assertEquals(new CloudUsageResponse("ollama-cloud", "30d", 0, List.of(), null), view);
    }

    @Test
    void aReadingCarriesItsRangeTotalBucketsAndFetchTime() {
        var fetchedAt = Instant.parse("2026-10-15T13:17:00Z");
        var snapshot = new Snapshot(Map.of(), "24h", 9, List.of(
                new Bucket(Instant.parse("2026-10-15T11:00:00Z"), Instant.parse("2026-10-15T12:00:00Z"), 7, false),
                new Bucket(Instant.parse("2026-10-15T12:00:00Z"), Instant.parse("2026-10-15T12:30:00Z"), 2, true)),
                fetchedAt);
        var view = CloudUsageResponse.of("ollama-cloud", Range.DAY, snapshot);
        assertEquals("24h", view.range());
        assertEquals(9, view.total());
        assertEquals(List.of(
                new UsageBucket("2026-10-15T11:00:00Z", "2026-10-15T12:00:00Z", 7, false),
                new UsageBucket("2026-10-15T12:00:00Z", "2026-10-15T12:30:00Z", 2, true)), view.buckets());
        assertEquals("2026-10-15T13:17:00Z", view.fetchedAt());
    }
}
