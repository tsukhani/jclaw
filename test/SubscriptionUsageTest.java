import llm.LlmProvider.LlmException;
import llm.LlmTypes.ProviderConfig;
import llm.ProviderRegistry;
import llm.routing.SubscriptionUsage;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import utils.AppClock;
import utils.HttpFactories;
import utils.LlmErrorTemplates.Failure;
import utils.LlmErrorTemplates.Remedy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** JCLAW-1222: reading Ollama Cloud's quota windows, and benching what fails for exhausted credit. */
class SubscriptionUsageTest extends UnitTest {

    /** The body ollama.com returned for a Max account on 2026-09-17, trimmed of per-model detail. */
    private static final String LIVE_BODY = """
            {"activity":{"cost":"0.00000","period":{"type":"last_4_weeks","starting_at":"2026-08-24T00:00:00Z",\
            "ending_at":"2026-09-17T14:27:59.206747913Z"},"models":[]},\
            "limits":{"session":{"usage":0.017,"models":[{"name":"glm-5.3-flash","request_count":120}]},\
            "weekly":{"usage":0.631,"models":[{"name":"kimi-k3","request_count":1556}]}}}""";

    private String provider;

    @BeforeEach
    void name() {
        provider = "su-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void clear() {
        SubscriptionUsage.clearForTest(provider);
    }

    private ProviderConfig ollamaCloud() {
        return new ProviderConfig(provider, "https://ollama.com/v1", "sk-secret", List.of());
    }

    @Test
    void theLiveResponseParsesIntoItsTwoWindows() {
        var windows = SubscriptionUsage.parseWindows(LIVE_BODY);
        assertEquals(2, windows.size());
        assertEquals(0.017, windows.get("session"), 1e-9);
        assertEquals(0.631, windows.get("weekly"), 1e-9);
    }

    @Test
    void anyWindowUnderLimitsCountsAndFractionsAreClamped() {
        var windows = SubscriptionUsage.parseWindows(
                "{\"limits\":{\"monthly\":{\"usage\":1.4},\"weekly\":{\"usage\":-0.2},\"note\":\"x\",\"session\":{}}}");
        assertEquals(1.0, windows.get("monthly"), 1e-9);
        assertEquals(0.0, windows.get("weekly"), 1e-9);
        assertEquals(2, windows.size(), "a window with no numeric usage is not a window");
    }

    @Test
    void anythingUnreadableParsesToNoWindows() {
        for (var body : new String[] {"", "[]", "not json", "{}", "{\"limits\":[]}", "{\"limits\":{\"weekly\":{\"usage\":\"63%\"}}}"}) {
            assertTrue(SubscriptionUsage.parseWindows(body).isEmpty(), () -> "parsed windows out of " + body);
        }
    }

    @Test
    void onlyOllamaHostsHaveAUsageSource() {
        assertTrue(SubscriptionUsage.hasUsageSource(ollamaCloud()));
        assertTrue(SubscriptionUsage.hasUsageSource(new ProviderConfig("x", "https://api.ollama.com/v1", "k", List.of())));
        assertFalse(SubscriptionUsage.hasUsageSource(new ProviderConfig("x", "http://localhost:11434/v1", "k", List.of())));
        assertFalse(SubscriptionUsage.hasUsageSource(new ProviderConfig("x", "https://notollama.com/v1", "k", List.of())));
        assertFalse(SubscriptionUsage.hasUsageSource(new ProviderConfig("x", "::not a url::", "k", List.of())));
    }

    @Test
    void refreshReadsTheUsageEndpointWithTheProviderKey() {
        var seen = new CopyOnWriteArrayList<String>();
        var snapshot = HttpFactories.callWith(canned(200, LIVE_BODY, seen), () -> SubscriptionUsage.refreshNow(ollamaCloud()));
        assertNotNull(snapshot);
        assertEquals(0.631, snapshot.pressure(), 1e-9);
        assertEquals("weekly", snapshot.fullestWindow());
        assertEquals(List.of("https://ollama.com/api/usage Bearer sk-secret"), seen);
        assertSame(snapshot, SubscriptionUsage.snapshots().get(provider), "the reading is kept for routing");
    }

    @Test
    void aFailedRefreshKeepsTheLastGoodReading() {
        HttpFactories.callWith(canned(200, LIVE_BODY, new CopyOnWriteArrayList<>()), () -> SubscriptionUsage.refreshNow(ollamaCloud()));
        var failed = HttpFactories.callWith(canned(404, "not found", new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.refreshNow(ollamaCloud()));
        assertNull(failed);
        assertEquals(0.631, SubscriptionUsage.snapshots().get(provider).pressure(), 1e-9);
    }

    @Test
    void freshDoesNotRefetchInsideTheTtl() {
        SubscriptionUsage.putSnapshotForTest(provider, java.util.Map.of("weekly", 0.4));
        var seen = new CopyOnWriteArrayList<String>();
        var snapshot = HttpFactories.callWith(canned(200, LIVE_BODY, seen), () -> SubscriptionUsage.fresh(ollamaCloud()));
        assertEquals(0.4, snapshot.pressure(), 1e-9);
        assertTrue(seen.isEmpty(), "a minute-old reading is still current");
    }

    @Test
    void exhaustedCreditBenchesAProviderWithoutAUsageSourceForAWhile() {
        SubscriptionUsage.noteFailure(quotaFailure(provider, "m1", Remedy.QUOTA_EXHAUSTED));
        assertTrue(SubscriptionUsage.isMarkedUnavailable(provider, "m1"));
        assertTrue(SubscriptionUsage.isMarkedUnavailable(provider, "another-model"),
                "without a usage API the whole account is presumed out of credit");

        var later = Clock.fixed(AppClock.now().plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        assertFalse(AppClock.callWith(later, () -> SubscriptionUsage.isMarkedUnavailable(provider, "m1")),
                "the bench lifts once the cooldown passes");
    }

    @Test
    void onAnOllamaHostOnlyTheRefusedModelIsBenched() {
        ConfigService.set("provider." + provider + ".baseUrl", "https://ollama.com/v1");
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", "[{\"id\":\"kimi-k3\"},{\"id\":\"glm-5.3\"}]");
        try {
            for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
            SubscriptionUsage.noteFailure(quotaFailure(provider, "kimi-k3", Remedy.QUOTA_EXHAUSTED));
            assertTrue(SubscriptionUsage.isMarkedUnavailable(provider, "kimi-k3"));
            assertFalse(SubscriptionUsage.isMarkedUnavailable(provider, "glm-5.3"),
                    "Ollama refuses a model its plan tier excludes while the plan itself has headroom");
        } finally {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
        }
    }

    @Test
    void failuresThatSayNothingAboutCreditBenchNothing() {
        SubscriptionUsage.noteFailure(quotaFailure(provider, "m1", Remedy.RATE_LIMITED));
        SubscriptionUsage.noteFailure(quotaFailure(provider, "m1", Remedy.MODEL_NOT_FOUND));
        SubscriptionUsage.noteFailure(new LlmException("no failure record"));
        SubscriptionUsage.noteFailure(new IllegalStateException("not an LLM failure"));
        assertFalse(SubscriptionUsage.isMarkedUnavailable(provider, "m1"));
    }

    private static LlmException quotaFailure(String provider, String model, Remedy remedy) {
        return new LlmException.ClientError("HTTP 402 from " + provider,
                new Failure(remedy, provider, model, null, null));
    }

    /** Answers every call with {@code code}/{@code body}, recording each request's URL and Authorization header. */
    private static OkHttpClient canned(int code, String body, List<String> seen) {
        Interceptor canned = chain -> {
            seen.add(chain.request().url() + " " + chain.request().header("Authorization"));
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }
}
