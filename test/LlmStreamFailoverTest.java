import llm.LlmProvider;
import llm.LlmProvider.LlmException;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreakers;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-1182: the fourth link of the streaming chain — an open breaker on the primary starts the
 * stream on the secondary instead of surfacing an error, which is what the synchronous path has
 * done since JCLAW-300.
 *
 * <p>Only the breaker-open case fails over, and the tests say so both ways: it refuses before any
 * token exists, so the switch is invisible; a stream the breaker admitted owns its own outcome
 * however it ends, because re-answering would duplicate what the user already saw.
 *
 * <p>Each test mints its own provider names so the breakers are its own registry entries — play1
 * runs test classes concurrently — and removes them afterwards.
 */
class LlmStreamFailoverTest extends UnitTest {

    /** Enough failures to blow the default 50% rate over the default 20-call minimum. */
    private static final int FAILURES_TO_OPEN = 20;

    private final List<MockWebServer> servers = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    @AfterEach
    void teardown() {
        servers.forEach(MockWebServer::close);
        names.forEach(name -> CircuitBreakers.remove(LlmResilience.breakerName(name)));
    }

    @Test
    void anOpenPrimaryStartsTheStreamOnTheSecondary() throws Exception {
        var primary = streamingProvider("jclaw1182-open-primary", "from-primary");
        var secondary = streamingProvider("jclaw1182-open-secondary", "from-secondary");
        openByFailures("jclaw1182-open-primary");

        var accumulator = stream(primary, secondary);

        assertNull(accumulator.error(), "the user sees no error when a secondary is configured");
        assertEquals("from-secondary", accumulator.content());
        assertEquals(0, server(primary).getRequestCount(), "an open primary is never dialled");
        assertEquals(1, server(secondary).getRequestCount());
    }

    @Test
    void anOperatorTripOfThePrimaryReachesTheSecondary() throws Exception {
        var primary = streamingProvider("jclaw1182-trip-primary", "from-primary");
        var secondary = streamingProvider("jclaw1182-trip-secondary", "from-secondary");
        // A trip raises ManuallyIsolated rather than a plain ServerError, and that has to route
        // too — it is what makes trip/reset a live failover drill (JCLAW-1170).
        LlmResilience.breakerFor("jclaw1182-trip-primary").trip();

        var accumulator = stream(primary, secondary);

        assertNull(accumulator.error());
        assertEquals("from-secondary", accumulator.content());
        assertEquals(0, server(primary).getRequestCount());
        // JCLAW-1190: the fallback streams its own model, not the primary's.
        var sent = server(secondary).takeRequest().getBody().utf8();
        assertTrue(sent.contains("\"model\":\"x-fallback\""), () -> "fallback request: " + sent);
    }

    @Test
    void anOpenPrimaryWithNoSecondaryStillFailsFast() throws Exception {
        var primary = streamingProvider("jclaw1182-alone", "from-primary");
        LlmResilience.breakerFor("jclaw1182-alone").trip();

        var accumulator = stream(primary, null);

        assertInstanceOf(LlmException.ManuallyIsolated.class, accumulator.error());
        assertEquals(0, server(primary).getRequestCount(), "nothing reached the wire");
    }

    @Test
    void anOpenSecondaryIsHonouredRatherThanLoopedInto() throws Exception {
        var primary = streamingProvider("jclaw1182-both-primary", "from-primary");
        var secondary = streamingProvider("jclaw1182-both-secondary", "from-secondary");
        openByFailures("jclaw1182-both-primary");
        openByFailures("jclaw1182-both-secondary");

        var accumulator = stream(primary, secondary);

        // JCLAW-1188: a refusal has its own class, so a retry-on-5xx rule can leave it alone.
        var error = assertInstanceOf(LlmException.BreakerOpen.class, accumulator.error());
        assertTrue(error.getMessage().contains("jclaw1182-both-secondary"),
                "the failure the user sees is the secondary's, so the hop happened exactly once");
        assertEquals(0, server(primary).getRequestCount());
        assertEquals(0, server(secondary).getRequestCount());
    }

    @Test
    void aHealthyPrimaryKeepsTheStreamAndLeavesTheSecondaryAlone() throws Exception {
        var primary = streamingProvider("jclaw1182-healthy-primary", "from-primary");
        var secondary = streamingProvider("jclaw1182-healthy-secondary", "from-secondary");

        var accumulator = stream(primary, secondary);

        assertNull(accumulator.error());
        assertEquals("from-primary", accumulator.content(), "no switch, so no duplicated output");
        assertEquals(1, server(primary).getRequestCount());
        assertEquals(0, server(secondary).getRequestCount());
    }

    @Test
    void anAdmittedStreamThatFailsIsNotReAnsweredOnTheSecondary() throws Exception {
        var primary = provider("jclaw1182-broken-primary",
                new MockResponse.Builder().code(500).body("upstream is unwell").build());
        var secondary = streamingProvider("jclaw1182-broken-secondary", "from-secondary");

        var accumulator = stream(primary, secondary);

        assertInstanceOf(LlmException.ServerError.class, accumulator.error(),
                "a provider the breaker admitted owns its own outcome");
        assertEquals(0, server(secondary).getRequestCount(),
                "failing over here is what would duplicate output once a stream has started");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private LlmProvider.StreamAccumulator stream(LlmProvider primary, @Nullable LlmProvider secondary)
            throws InterruptedException {
        var fallback = secondary == null ? null : new LlmProvider.Fallback(secondary, "x-fallback");
        var accumulator = LlmProvider.chatStreamAccumulateWithFailover(
                primary, fallback, "x", List.of(ChatMessage.user("hi")), null,
                _ -> { }, _ -> { }, null, null, null);
        assertTrue(accumulator.awaitCompletion(10_000), "the stream should settle well inside 10s");
        return accumulator;
    }

    /** A provider whose one reply is an SSE stream of {@code content}, so a test can tell which answered. */
    private OpenAiProvider streamingProvider(String name, String content) throws Exception {
        return provider(name, new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse(content))
                .build());
    }

    /** A provider on its own MockWebServer, primed with one reply. The server is torn down with the test. */
    private OpenAiProvider provider(String name, MockResponse reply) throws Exception {
        var server = new MockWebServer();
        server.start();
        servers.add(server);
        names.add(name);
        server.enqueue(reply);
        return new OpenAiProvider(new ProviderConfig(name, server.url("/v1").toString(), "sk-test", List.of()));
    }

    private MockWebServer server(LlmProvider provider) {
        return servers.get(names.indexOf(provider.config().name()));
    }

    /** Open a breaker the way an outage does, so the failure class is a plain ServerError. */
    private static void openByFailures(String providerName) {
        var breaker = LlmResilience.breakerFor(providerName);
        for (var i = 0; i < FAILURES_TO_OPEN; i++) breaker.recordFailure();
        assertFalse(breaker.allowRequest(), "the fixture must actually have opened the breaker");
    }

    private static String sse(String content) {
        return """
                data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":"stop"}]}

                data: [DONE]

                """.formatted(content);
    }
}
