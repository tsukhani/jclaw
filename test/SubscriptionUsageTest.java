import llm.LlmProvider.LlmException;
import llm.LlmTypes.ProviderConfig;
import llm.ProviderRegistry;
import llm.routing.SubscriptionUsage;
import llm.routing.SubscriptionUsage.Range;
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
import services.EventLogger;
import utils.AppClock;
import utils.HttpFactories;
import utils.LlmErrorTemplates.Failure;
import utils.LlmErrorTemplates.Remedy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1222: reading Ollama Cloud's quota windows, and benching what fails for exhausted credit.
 * JCLAW-1449: reading the request history the same endpoint answers with since the windows went.
 */
class SubscriptionUsageTest extends UnitTest {

    /** The body ollama.com returned for a Max account on 2026-09-17, trimmed of per-model detail. */
    private static final String LIVE_BODY = """
            {"activity":{"cost":"0.00000","period":{"type":"last_4_weeks","starting_at":"2026-08-24T00:00:00Z",\
            "ending_at":"2026-09-17T14:27:59.206747913Z"},"models":[]},\
            "limits":{"session":{"usage":0.017,"models":[{"name":"glm-5.3-flash","request_count":120}]},\
            "weekly":{"usage":0.631,"models":[{"name":"kimi-k3","request_count":1556}]}}}""";

    /** The body ollama.com returned on 2026-10-15: request counts only, no {@code limits}. */
    private static final String HISTORY_BODY = """
            {"range":"7d","scope":"self","granularity":"day","from":"2026-10-08T00:00:00Z",\
            "until":"2026-10-15T13:16:46.941696596Z","totals":{"request_count":8341},"buckets":[\
            {"from":"2026-10-08T00:00:00Z","until":"2026-10-09T00:00:00Z","request_count":952},\
            {"from":"2026-10-09T00:00:00Z","until":"2026-10-10T00:00:00Z","request_count":1137},\
            {"from":"2026-10-10T00:00:00Z","until":"2026-10-11T00:00:00Z","request_count":1352},\
            {"from":"2026-10-11T00:00:00Z","until":"2026-10-12T00:00:00Z","request_count":698},\
            {"from":"2026-10-12T00:00:00Z","until":"2026-10-13T00:00:00Z","request_count":457},\
            {"from":"2026-10-13T00:00:00Z","until":"2026-10-14T00:00:00Z","request_count":1430},\
            {"from":"2026-10-14T00:00:00Z","until":"2026-10-15T00:00:00Z","request_count":1533},\
            {"from":"2026-10-15T00:00:00Z","until":"2026-10-15T13:16:46.941696596Z","partial":true,"request_count":782}]}""";

    private static final String GUARD_OFF = "budget guard is off";

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
    void theRescopedResponseParsesIntoItsRequestHistory() {
        var seen = new CopyOnWriteArrayList<String>();
        var snapshot = HttpFactories.callWith(canned(200, HISTORY_BODY, seen), () -> SubscriptionUsage.refreshNow(ollamaCloud()));
        assertNotNull(snapshot);
        assertEquals(List.of("https://ollama.com/api/usage Bearer sk-secret"), seen, "the router's own read is unchanged");
        assertTrue(snapshot.windows().isEmpty());
        assertEquals(0.0, snapshot.pressure(), 1e-9, "no windows, so no pressure: the guard cannot fire");
        assertEquals("7d", snapshot.range());
        assertEquals(8341, snapshot.total());
        assertEquals(8, snapshot.buckets().size());
        var first = snapshot.buckets().getFirst();
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), first.from());
        assertEquals(Instant.parse("2026-10-09T00:00:00Z"), first.until());
        assertEquals(952, first.requestCount());
        assertFalse(first.partial());
        var last = snapshot.buckets().getLast();
        assertEquals(782, last.requestCount());
        assertTrue(last.partial(), "the running day is marked partial");
        assertEquals(Instant.parse("2026-10-15T13:16:46.941696596Z"), last.until());
    }

    @Test
    void aBodyCarryingLimitsStillFeedsTheGuardBesideItsHistory() {
        var body = "{\"limits\":{\"weekly\":{\"usage\":0.8}},\"range\":\"24h\",\"buckets\":["
                + "{\"from\":\"2026-10-15T12:00:00Z\",\"until\":\"2026-10-15T13:00:00Z\",\"request_count\":5}]}";
        var snapshot = HttpFactories.callWith(canned(200, body, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.refreshNow(ollamaCloud()));
        assertNotNull(snapshot);
        assertEquals(0.8, snapshot.pressure(), 1e-9);
        assertEquals("weekly", snapshot.fullestWindow());
        assertEquals(5, snapshot.total(), "with no totals the buckets are summed");
    }

    @Test
    void malformedBucketsAreSkippedRatherThanFailingTheRead() {
        var body = "{\"range\":\"7d\",\"totals\":{\"request_count\":3},\"buckets\":[1,{\"from\":\"x\"},"
                + "{\"from\":\"2026-10-14T00:00:00Z\",\"until\":\"2026-10-15T00:00:00Z\",\"request_count\":\"3\"},"
                + "{\"from\":\"2026-10-14T00:00:00Z\",\"until\":\"2026-10-15T00:00:00Z\",\"request_count\":3}]}";
        var snapshot = HttpFactories.callWith(canned(200, body, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK));
        assertNotNull(snapshot);
        assertEquals(1, snapshot.buckets().size());
        assertEquals(3, snapshot.total());
    }

    @Test
    void eachRangeIsItsOwnCacheEntryOnTheSameMinute() {
        var seen = new CopyOnWriteArrayList<String>();
        var client = canned(200, HISTORY_BODY, seen);
        HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.DAY));
        HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.DAY));
        HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.MONTH));
        HttpFactories.callWith(client, () -> SubscriptionUsage.fresh(ollamaCloud()));
        assertEquals(List.of(
                "https://ollama.com/api/usage?range=24h Bearer sk-secret",
                "https://ollama.com/api/usage?range=30d Bearer sk-secret",
                "https://ollama.com/api/usage Bearer sk-secret"), seen, "a range read inside its minute is served from the cache");

        var later = Clock.fixed(AppClock.now().plus(Duration.ofSeconds(61)), ZoneOffset.UTC);
        var reread = AppClock.callWith(later,
                () -> HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.DAY)));
        assertNotNull(reread);
        assertEquals("https://ollama.com/api/usage?range=24h Bearer sk-secret", seen.getLast());
        assertEquals(4, seen.size(), "a minute on, the range is read again");
    }

    @Test
    void aHistoryOlderThanAMinuteIsNotServedAfterAFailedReread() {
        HttpFactories.callWith(canned(200, HISTORY_BODY, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK));
        var later = Clock.fixed(AppClock.now().plus(Duration.ofSeconds(90)), ZoneOffset.UTC);
        var reread = AppClock.callWith(later, () -> HttpFactories.callWith(canned(503, "busy", new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK)));
        assertNull(reread, "the display never shows counts more than a minute old");
    }

    @Test
    void readingsWithoutLimitsWarnOncePerProcessAndTheirReturnIsLogged() {
        var events = EventLogger.captureForTest(() -> {
            for (var i = 0; i < 3; i++) {
                HttpFactories.callWith(canned(200, HISTORY_BODY, new CopyOnWriteArrayList<>()),
                        () -> SubscriptionUsage.refreshNow(ollamaCloud()));
            }
        });
        var warnings = events.stream().filter(e -> e.message().contains(GUARD_OFF)).toList();
        assertEquals(1, warnings.size(), () -> "warned once across three refreshes: " + events);
        assertEquals("WARN", warnings.getFirst().level());
        assertTrue(warnings.getFirst().message().contains(provider), () -> warnings.toString());
        assertTrue(warnings.getFirst().message().contains("402 and 429"), () -> warnings.toString());

        var recovery = EventLogger.captureForTest(() -> HttpFactories.callWith(canned(200, LIVE_BODY, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.refreshNow(ollamaCloud())));
        assertTrue(recovery.stream().anyMatch(e -> e.message().contains("budget guard is back on") && e.message().contains(provider)),
                () -> "the windows' return is not hidden by the earlier warning: " + recovery);
        assertEquals(0.631, SubscriptionUsage.snapshots().get(provider).pressure(), 1e-9);

        var again = EventLogger.captureForTest(() -> HttpFactories.callWith(canned(200, HISTORY_BODY, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.refreshNow(ollamaCloud())));
        assertTrue(again.stream().noneMatch(e -> e.message().contains(GUARD_OFF)), () -> "once per process: " + again);
    }

    @Test
    void aRangeReadWithoutLimitsDoesNotSpeakForTheGuard() {
        var events = EventLogger.captureForTest(() -> HttpFactories.callWith(canned(200, HISTORY_BODY, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.history(ollamaCloud(), Range.DAY)));
        assertTrue(events.stream().noneMatch(e -> e.message().contains(GUARD_OFF)), () -> events.toString());
    }

    @Test
    void everyFailureAnswersNoHistoryWithAWarning() {
        var failures = List.of(
                canned(401, "{\"error\":\"unauthorized\"}", new CopyOnWriteArrayList<>()),
                canned(429, "slow down", new CopyOnWriteArrayList<>()),
                canned(500, "oops", new CopyOnWriteArrayList<>()),
                throwing(new SocketTimeoutException("timeout")),
                canned(200, "{\"hello\":\"world\"}", new CopyOnWriteArrayList<>()),
                canned(200, "<html>not json</html>", new CopyOnWriteArrayList<>()));
        for (var client : failures) {
            SubscriptionUsage.clearForTest(provider);
            var events = EventLogger.captureForTest(() -> assertNull(
                    HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK))));
            assertEquals(1, events.stream().filter(e -> "WARN".equals(e.level())).count(), () -> events.toString());
        }
    }

    @Test
    void aKeyOkHttpRefusesInAHeaderAnswersNoHistoryWithAWarning() {
        var config = new ProviderConfig(provider, "https://ollama.com/v1", "fake-ollama-key-é", List.of());
        var events = EventLogger.captureForTest(() -> assertNull(HttpFactories.callWith(
                canned(200, HISTORY_BODY, new CopyOnWriteArrayList<>()), () -> SubscriptionUsage.history(config, Range.DAY))));
        assertEquals(1, events.stream().filter(e -> "WARN".equals(e.level())).count(), () -> events.toString());
    }

    @Test
    void aProviderOffOllamaComAnswersNoHistoryWithAWarningAndIsNotAsked() {
        var config = new ProviderConfig(provider, "http://127.0.0.1:1/v1", "fake-key", List.of());
        var seen = new CopyOnWriteArrayList<String>();
        var events = EventLogger.captureForTest(() -> assertNull(HttpFactories.callWith(
                canned(200, HISTORY_BODY, seen), () -> SubscriptionUsage.history(config, Range.DAY))));
        assertTrue(seen.isEmpty(), () -> "only ollama.com is asked: " + seen);
        assertEquals(1, events.stream().filter(e -> "WARN".equals(e.level())).count(), () -> events.toString());
    }

    @Test
    void aReadArrivingWhileItsRangeIsFetchedWaitsForThatFetch() throws InterruptedException {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Interceptor slow = chain -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("canned").body(ResponseBody.create(HISTORY_BODY, null)).build();
        };
        var client = new OkHttpClient.Builder().addInterceptor(slow).build();
        var first = new AtomicReference<SubscriptionUsage.Snapshot>();
        var second = new AtomicReference<SubscriptionUsage.Snapshot>();
        var a = Thread.ofPlatform().start(() -> first.set(
                HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK))));
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the first read reached the transport");
        var b = Thread.ofPlatform().start(() -> second.set(
                HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK))));
        b.join(Duration.ofMillis(300));
        release.countDown();
        a.join(Duration.ofSeconds(10));
        b.join(Duration.ofSeconds(10));
        assertNotNull(first.get());
        assertNotNull(second.get(), "a read during the fetch is answered from it, not as no reading");
        assertEquals(1, calls.get(), "one fetch serves both");
    }

    @Test
    void aReadWithoutWindowsLiftsAnEarlierReadingsPressure() {
        SubscriptionUsage.putSnapshotForTest(provider, java.util.Map.of("weekly", 0.97));
        HttpFactories.callWith(canned(200, HISTORY_BODY, new CopyOnWriteArrayList<>()),
                () -> SubscriptionUsage.refreshNow(ollamaCloud()));
        assertEquals(0.0, SubscriptionUsage.snapshots().get(provider).pressure(), 1e-9,
                "a reading from before the windows went must not keep the provider benched");
    }

    @Test
    void withTheGuardOffTheRouterAsksOnceAnHour() {
        var seen = new CopyOnWriteArrayList<String>();
        var client = canned(200, HISTORY_BODY, seen);
        var start = AppClock.now();
        for (var offset : List.of(Duration.ZERO, Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofSeconds(3599))) {
            readRouterAt(start.plus(offset), client);
        }
        assertEquals(1, seen.size(), () -> "a read with no windows stands for the hour: " + seen);
        readRouterAt(start.plus(Duration.ofHours(1)).plusSeconds(1), client);
        assertEquals(2, seen.size(), () -> "just past the hour the router asks again: " + seen);
    }

    @Test
    void withTheGuardOffACreditFailureDoesNotHastenTheRouterRead() {
        try {
            registerOnOllamaCom();
            var seen = new CopyOnWriteArrayList<String>();
            var client = canned(200, HISTORY_BODY, seen);
            var start = AppClock.now();
            readRouterAt(start, client);
            SubscriptionUsage.noteFailure(quotaFailure(provider, "kimi-k3", Remedy.RATE_LIMITED));
            readRouterAt(start.plus(Duration.ofMinutes(2)), client);
            SubscriptionUsage.noteFailure(quotaFailure(provider, "kimi-k3", Remedy.QUOTA_EXHAUSTED));
            readRouterAt(start.plus(Duration.ofMinutes(3)), client);
            assertEquals(1, seen.size(), () -> "a 429 or 402 cannot bring back windows the read lacks: " + seen);
            assertTrue(SubscriptionUsage.isMarkedUnavailable(provider, "kimi-k3"), "the 402 still benches the model");
            assertFalse(SubscriptionUsage.isMarkedUnavailable(provider, "glm-5.3"), "and only the model");
        } finally {
            unregister();
        }
    }

    @Test
    void whenWindowsReturnOnTheHourlyCheckTheMinuteTtlIsBack() {
        var seen = new CopyOnWriteArrayList<String>();
        var withWindows = canned(200, LIVE_BODY, seen);
        var start = AppClock.now();
        readRouterAt(start, canned(200, HISTORY_BODY, seen));
        readRouterAt(start.plus(Duration.ofMinutes(30)), withWindows);
        assertEquals(1, seen.size(), () -> "with the guard off nothing is asked within the hour: " + seen);

        var recheck = start.plus(Duration.ofHours(1)).plusSeconds(1);
        var events = EventLogger.captureForTest(() -> readRouterAt(recheck, withWindows));
        assertEquals(2, seen.size(), () -> seen.toString());
        assertTrue(events.stream().anyMatch(e -> e.message().contains("budget guard is back on")), () -> events.toString());
        readRouterAt(recheck.plusSeconds(30), withWindows);
        assertEquals(2, seen.size(), () -> seen.toString());
        readRouterAt(recheck.plusSeconds(61), withWindows);
        assertEquals(3, seen.size(), () -> "with windows back, a minute-old read is stale again: " + seen);
    }

    @Test
    void theRouterReadAndTheWeekReadShareOneFetchEitherWayRound() {
        var seen = new CopyOnWriteArrayList<String>();
        var client = canned(200, HISTORY_BODY, seen);
        var start = AppClock.now();
        readRouterAt(start, client);
        var week = weekAt(start.plusSeconds(30), client);
        assertNotNull(week, "the router's read answers the 7d view");
        assertEquals(8341, week.total());
        assertEquals(List.of("https://ollama.com/api/usage Bearer sk-secret"), seen);

        SubscriptionUsage.clearForTest(provider);
        seen.clear();
        weekAt(start, client);
        var router = readRouterAt(start.plusSeconds(30), client);
        assertNotNull(router, "the 7d read answers the router");
        assertEquals("7d", router.range());
        assertEquals(List.of("https://ollama.com/api/usage?range=7d Bearer sk-secret"), seen);
    }

    @Test
    void aWeekReadAMinuteAfterTheRouterReadFetchesThoughTheRouterEntryStandsForTheHour() {
        var seen = new CopyOnWriteArrayList<String>();
        var client = canned(200, HISTORY_BODY, seen);
        var start = AppClock.now();
        readRouterAt(start, client);
        var later = start.plusSeconds(61);
        var week = weekAt(later, client);
        assertNotNull(week, "the 7d view never shows history more than a minute old");
        assertEquals(later, week.fetchedAt());
        assertEquals(List.of("https://ollama.com/api/usage Bearer sk-secret",
                "https://ollama.com/api/usage?range=7d Bearer sk-secret"), seen);
    }

    @Test
    void aWeekReadThatFillsTheRouterEntryMovesTheGuardWithIt() {
        var seen = new CopyOnWriteArrayList<String>();
        var start = AppClock.now();
        var off = EventLogger.captureForTest(() -> weekAt(start, canned(200, HISTORY_BODY, seen)));
        assertTrue(off.stream().anyMatch(e -> e.message().contains(GUARD_OFF)),
                () -> "a 7d read without windows turns the guard off: " + off);

        var withWindows = "{\"limits\":{\"weekly\":{\"usage\":0.4}},\"range\":\"7d\",\"buckets\":["
                + "{\"from\":\"2026-10-14T00:00:00Z\",\"until\":\"2026-10-15T00:00:00Z\",\"request_count\":5}]}";
        var later = start.plusSeconds(61);
        var on = EventLogger.captureForTest(() -> weekAt(later, canned(200, withWindows, seen)));
        assertTrue(on.stream().anyMatch(e -> e.message().contains("budget guard is back on")),
                () -> "and one with windows turns it back on: " + on);
        assertEquals(0.4, SubscriptionUsage.snapshots().get(provider).pressure(), 1e-9);
        readRouterAt(later.plusSeconds(61), canned(200, LIVE_BODY, seen));
        assertEquals(3, seen.size(), () -> "the router's minute TTL is back: " + seen);
    }

    /** The router's read, synchronously: {@code current()} refreshes on a virtual thread the canned transport cannot reach. */
    private SubscriptionUsage.Snapshot readRouterAt(Instant at, OkHttpClient client) {
        return AppClock.callWith(Clock.fixed(at, ZoneOffset.UTC),
                () -> HttpFactories.callWith(client, () -> SubscriptionUsage.fresh(ollamaCloud())));
    }

    private SubscriptionUsage.Snapshot weekAt(Instant at, OkHttpClient client) {
        return AppClock.callWith(Clock.fixed(at, ZoneOffset.UTC),
                () -> HttpFactories.callWith(client, () -> SubscriptionUsage.history(ollamaCloud(), Range.WEEK)));
    }

    /** {@code noteFailure} acts on a usage source only for a provider the registry knows is on ollama.com. */
    private void registerOnOllamaCom() {
        ConfigService.set("provider." + provider + ".baseUrl", "https://ollama.com/v1");
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", "[{\"id\":\"kimi-k3\"},{\"id\":\"glm-5.3\"}]");
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "registered, or the test would pass without a usage source");
    }

    private void unregister() {
        for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
            ConfigService.delete("provider." + provider + suffix);
        }
        ProviderRegistry.refresh();
    }

    @Test
    void aProviderWithNoKeyAnswersNoHistoryWithAWarning() {
        var events = EventLogger.captureForTest(() -> assertNull(SubscriptionUsage.history(provider, Range.DAY)));
        assertTrue(events.stream().anyMatch(e -> "WARN".equals(e.level()) && e.message().contains("no API key")),
                () -> events.toString());
    }

    @Test
    void theAuthorizationValueNeverReachesTheLog() {
        var key = "fake-ollama-key-" + UUID.randomUUID();
        var config = new ProviderConfig(provider, "https://ollama.com/v1", key, List.of());
        var clients = List.of(
                canned(401, "{\"error\":\"bad token Bearer " + key + "\"}", new CopyOnWriteArrayList<>()),
                canned(500, "upstream echoed " + key, new CopyOnWriteArrayList<>()),
                canned(200, "{\"echo\":\"" + key + "\"}", new CopyOnWriteArrayList<>()),
                throwing(new IOException("connection reset while sending " + key)),
                throwing(new SocketTimeoutException("timeout after Authorization: Bearer " + key)));
        for (var client : clients) {
            SubscriptionUsage.clearForTest(provider);
            var events = EventLogger.captureForTest(() -> {
                HttpFactories.callWith(client, () -> SubscriptionUsage.refreshNow(config));
                HttpFactories.callWith(client, () -> SubscriptionUsage.history(config, Range.DAY));
            });
            assertFalse(events.isEmpty(), "each failure is logged");
            for (var e : events) {
                assertFalse(e.message().contains(key), () -> "the key reached the log: " + e.message());
                assertFalse(String.valueOf(e.details()).contains(key), () -> "the key reached the log: " + e.details());
            }
        }
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

    /** Fails every call with {@code failure}, as a timeout or a dropped connection does. */
    private static OkHttpClient throwing(IOException failure) {
        Interceptor fail = _ -> {
            throw failure;
        };
        return new OkHttpClient.Builder().addInterceptor(fail).build();
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
