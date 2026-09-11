import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.HttpAttributes;
import llm.LlmProvider.LlmException;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.telemetry.OtelRuntime;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1169: the per-provider circuit breaker on the streaming chat path, and the
 * inter-chunk gap that makes a stalled stream visible to it. JCLAW-1181: the separate
 * budget before the first chunk, which is what bounds a stream that never starts.
 *
 * <p>The wire-level tests mint their own provider name so the breaker is their own registry
 * entry — play1 runs test classes concurrently — and remove it afterwards. The stall tests
 * drive a {@link LlmResilience.StreamGuard} over a caller-supplied monotonic source instead,
 * because a real stall is thirty seconds long and a test is not.
 */
class LlmStreamBreakerTest extends UnitTest {

    /** Arbitrary but non-zero: nanoTime's origin is arbitrary, and 0 must not read as "unstarted". */
    private static final long CLOCK_ORIGIN = 4_000_000_000L;

    private static final long SECOND = 1_000_000_000L;

    /** The llm.breaker.first-chunk-seconds default, spelled out so the boundary cases read as minutes. */
    private static final long FIRST_CHUNK_BUDGET_MS = 600_000L;

    private static final String SSE = """
            data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Hel"}}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    // ─── The two terminal outcomes, over a real stream ───────────────────

    @Test
    void aCleanStreamRecordsASuccess() throws Exception {
        var name = "jclaw1169-clean";
        try (var server = openServer()) {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(SSE)
                    .build());

            assertNull(streamAndAwait(name, server.url("/v1").toString()), "a served stream does not error");

            var stats = LlmResilience.breakerFor(name).stats();
            assertEquals(1, stats.samples());
            assertEquals(0, stats.failures());
            assertEquals(0, stats.slowCalls(), "chunks arriving back to back are not a stall");
        } finally {
            forget(name);
        }
    }

    @Test
    void aStreamThatErrorsRecordsAFailure() throws Exception {
        var name = "jclaw1169-error";
        try (var server = openServer()) {
            server.enqueue(new MockResponse.Builder().code(500).body("upstream is unwell").build());

            var error = streamAndAwait(name, server.url("/v1").toString());
            assertInstanceOf(LlmException.ServerError.class, error);

            var stats = LlmResilience.breakerFor(name).stats();
            assertEquals(1, stats.samples());
            assertEquals(1, stats.failures());
        } finally {
            forget(name);
        }
    }

    @Test
    void anOpenBreakerFailsTheStreamBeforeTheWire() throws Exception {
        var name = "jclaw1169-open";
        try {
            LlmResilience.breakerFor(name).trip();

            // The base URL is unroutable, so a stream that reached the transport would come back
            // as a Transport failure and would leave a sample behind. Neither happens.
            var error = streamAndAwait(name, "http://llm-stream-breaker.invalid/v1");

            assertInstanceOf(LlmException.ServerError.class, error,
                    "callers retry and fail over on LlmException, so the fail-fast must be one");
            assertEquals(0, LlmResilience.breakerFor(name).stats().samples(),
                    "a rejected stream never reached the provider, so it says nothing about it");
        } finally {
            forget(name);
        }
    }

    // ─── The stall ───────────────────────────────────────────────────────

    @Test
    void aStreamStalledMidWayIsASlowCallEvenThoughItFinished() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);

        guard.chunk();
        clock.addAndGet(2 * SECOND);
        guard.chunk();
        clock.addAndGet(45 * SECOND);   // the stall
        guard.chunk();
        clock.addAndGet(SECOND);
        guard.succeeded();

        var stats = breaker.stats();
        assertEquals(1, stats.samples());
        assertEquals(0, stats.failures(), "the stream did finish — a slow call is not a failure");
        assertEquals(1, stats.slowCalls(), "the worst gap is what a stall shows up in, not the total");
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(),
                "and it counts toward opening the breaker");
    }

    @Test
    void aLongButSteadyStreamIsNotASlowCall() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);

        // Ten minutes of generation, a chunk every five seconds: the case an end-to-end
        // slow-call threshold cannot tell apart from the stall above.
        guard.chunk();
        for (var i = 0; i < 120; i++) {
            clock.addAndGet(5 * SECOND);
            guard.chunk();
        }
        guard.succeeded();

        assertEquals(0, breaker.stats().slowCalls());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void aSlowFirstTokenIsNotAStall() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);

        // A cold local model loading weights. Nothing has been read off the socket either, so
        // this is the case OkHttp's read timeout already covers; charging it would break
        // streaming on every slow machine.
        clock.addAndGet(120 * SECOND);
        assertFalse(guard.checkDeadlines(), "the gap is armed by the first chunk, not by the dispatch");
        guard.chunk();
        guard.succeeded();

        assertEquals(0, breaker.stats().slowCalls());
    }

    @Test
    void aStreamStillHangingIsChargedBeforeItEnds() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);

        guard.chunk();
        clock.addAndGet(20 * SECOND);
        assertFalse(guard.checkDeadlines(), "still inside the budget");

        clock.addAndGet(20 * SECOND);
        assertTrue(guard.checkDeadlines(), "a stream that may never terminate still has to reach the breaker");
        assertEquals(1, breaker.stats().slowCalls());

        // The transport gives up an hour later. The outcome was already recorded, and a second
        // one would count the same stream twice.
        clock.addAndGet(3600 * SECOND);
        guard.succeeded();
        assertEquals(1, breaker.stats().samples());
    }

    @Test
    void aStalledStreamReleasesItsCallerToo() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);
        var releasedAfterMs = new AtomicLong(-1L);
        guard.onAbandoned(releasedAfterMs::set);

        guard.chunk();
        clock.addAndGet(40 * SECOND);
        assertTrue(guard.checkDeadlines());

        // JCLAW-1183: the charge alone leaves the transport parked on a socket nothing will
        // reclaim, whether or not tokens reached the screen first.
        assertEquals(40_000L, releasedAfterMs.get());
        assertEquals(1, breaker.stats().slowCalls(), "still a slow call, not a failure");
        assertEquals(0, breaker.stats().failures());
    }

    @Test
    void aStalledProbeDoesNotStrandItsHalfOpenPermit() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        // Cooldown 0, so the only thing between OPEN and a fresh probe is a reported outcome.
        var breaker = new CircuitBreaker(CircuitBreaker.Config.of(10, 0.5, 1, 0L)
                .withHalfOpenPermits(2)
                .withSlowCalls(30_000L, 0.5));
        breaker.trip();

        assertTrue(breaker.allowRequest());
        var probe = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);
        assertTrue(breaker.allowRequest());
        assertFalse(breaker.allowRequest(), "both probe permits are out");

        probe.chunk();
        clock.addAndGet(60 * SECOND);
        assertTrue(probe.checkDeadlines());

        assertEquals(CircuitBreaker.State.OPEN, breaker.state(), "a degraded probe re-opens it");
        assertTrue(breaker.allowRequest(),
                "reporting the stall is what stops two hung probes wedging the breaker shut for good");
    }

    // ─── The stream that never starts (JCLAW-1181) ───────────────────────

    @Test
    void aStreamThatNeverProducesAFirstChunkIsChargedAndItsCallerReleased() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);
        var releasedAfterMs = new AtomicLong(-1L);
        guard.onAbandoned(releasedAfterMs::set);

        clock.addAndGet(9 * 60 * SECOND);
        assertFalse(guard.checkDeadlines(), "a cold local model loading weights is inside the budget");
        assertEquals(0, breaker.stats().samples());

        clock.addAndGet(2 * 60 * SECOND);
        assertTrue(guard.checkDeadlines(), "eleven minutes of silence is a dead stream, not a slow start");
        assertEquals(11 * 60_000L, releasedAfterMs.get(),
                "charging the breaker does not unpark the caller — the release is what ends the turn");

        var stats = breaker.stats();
        assertEquals(1, stats.samples());
        assertEquals(1, stats.failures(), "a stream that delivered nothing did not succeed slowly");
        assertEquals(0, stats.slowCalls());
    }

    @Test
    void anAbandonedStreamThatLaterFinishesIsStillOneOutcome() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);
        var releases = new AtomicLong();
        guard.onAbandoned(_ -> releases.incrementAndGet());

        clock.addAndGet(11 * 60 * SECOND);
        assertTrue(guard.checkDeadlines());

        // The provider wakes up an hour later. The permit was handed back at the abandonment;
        // a second outcome would count the same stream twice and strand the next probe.
        clock.addAndGet(3600 * SECOND);
        guard.chunk();
        guard.succeeded();
        assertFalse(guard.checkDeadlines());

        assertEquals(1, breaker.stats().samples());
        assertEquals(1, releases.get());
    }

    @Test
    void aStreamWithNoReleaseInstalledIsLeftToTheTransport() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, FIRST_CHUNK_BUDGET_MS);

        clock.addAndGet(60 * 60 * SECOND);
        assertFalse(guard.checkDeadlines(),
                "recording an outcome with nobody to release would trade a hang for a quieter hang");
        assertEquals(0, breaker.stats().samples());
    }

    @Test
    void theFirstChunkBudgetIsOffWhenItIsZero() {
        var clock = new AtomicLong(CLOCK_ORIGIN);
        var breaker = stallingBreaker();
        var guard = LlmResilience.streamGuardForTest(breaker, clock::get, 0L);
        var released = new AtomicBoolean();
        guard.onAbandoned(_ -> released.set(true));

        clock.addAndGet(60 * 60 * SECOND);
        assertFalse(guard.checkDeadlines());
        assertFalse(released.get());
        assertEquals(0, breaker.stats().samples());
    }

    // ─── The span the breaker sits in front of ───────────────────────────

    @Test
    void theBreakerLeavesTheSpanNestingIntact() throws Exception {
        var name = "jclaw1169-spans";
        TelemetryTestSync.acquire();
        try (var server = openServer()) {
            OtelRuntime.init();
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(SSE)
                    .build());
            var url = server.url("/v1").toString();

            var spans = OtelRuntime.captureForTest(() -> {
                try {
                    assertNull(streamAndAwait(name, url));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });

            var chat = one(spans, "chat x");
            var http = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT
                            && "POST".equals(s.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no http client span in " + names(spans)));
            assertEquals(chat.getSpanContext().getSpanId(), http.getParentSpanContext().getSpanId(),
                    "the guard runs on the dispatching thread and the stream still inherits the wrap");
            assertEquals(1, LlmResilience.breakerFor(name).stats().samples());
        } finally {
            TelemetryTestSync.release();
            forget(name);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** Stream one turn against {@code baseUrl} and return the error it ended with, or null. */
    private static Exception streamAndAwait(String providerName, String baseUrl) throws InterruptedException {
        var provider = new OpenAiProvider(new ProviderConfig(providerName, baseUrl, "sk-test", List.of()));
        var error = new AtomicReference<Exception>();
        var done = new CountDownLatch(1);
        provider.chatStream("x", List.of(ChatMessage.user("hi")), null,
                chunk -> { },
                done::countDown,
                e -> { error.set(e); done.countDown(); },
                null, null, null);
        assertTrue(done.await(10, TimeUnit.SECONDS), "the stream should settle well inside 10s");
        return error.get();
    }

    private static MockWebServer openServer() throws Exception {
        var server = new MockWebServer();
        server.start();
        return server;
    }

    /** One slow call out of one opens it, so a single stall is visible in the state. */
    private static CircuitBreaker stallingBreaker() {
        return new CircuitBreaker(CircuitBreaker.Config.of(10, 0.5, 1, 60_000L)
                .withSlowCalls(30_000L, 0.5));
    }

    private static SpanData one(List<SpanData> spans, String name) {
        var matches = spans.stream().filter(s -> s.getName().equals(name)).toList();
        assertEquals(1, matches.size(), () -> name + " in " + names(spans));
        return matches.getFirst();
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }

    private static void forget(String providerName) {
        CircuitBreakers.remove(LlmResilience.breakerName(providerName));
    }
}
