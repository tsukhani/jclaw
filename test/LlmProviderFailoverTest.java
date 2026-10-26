import llm.LlmProvider;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.SocketEffect;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-300: pin {@link LlmProvider#chatWithFailover} — the primary→
 * secondary fallback path the JCLAW-299 AgentRunner extraction carved
 * out as deferred work. The ticket described this as "streaming"; the
 * sync loop ({@link agents.ToolCallLoopRunner#callWithToolLoop}) calls its
 * streamed twin, {@code chatStreamedWithFailover}, and the streaming path got its own, narrower entry in
 * JCLAW-1182 — see {@code LlmStreamFailoverTest}. Tests target the
 * static utility directly so its three branches are exercised without
 * standing up the loop scaffolding.
 *
 * <p>Three branches in {@code chatWithFailover}:
 * <ol>
 *   <li>primary succeeds → return primary's response, no log emitted</li>
 *   <li>primary throws {@link LlmProvider.LlmException} + secondary
 *       non-null → log warn, return secondary's response</li>
 *   <li>primary throws {@code LlmException} + secondary null →
 *       rethrow (no failover available)</li>
 * </ol>
 *
 * <p>The fourth conceptual branch — "primary throws non-LlmException
 * propagates" — is not tested here because LlmProvider is a sealed
 * class with no test seam to force a non-LlmException out of the chat
 * path; the catch block is narrow ({@code catch (LlmException)}) so
 * the branch is structurally trivial.
 *
 * <p>LlmProvider is sealed to the four concrete providers (final
 * subclasses), so the stub pattern is "real OpenAiProvider pointed at
 * a MockWebServer" — same pattern
 * {@code ToolCallLoopRunnerStreamingTest} uses.
 */
class LlmProviderFailoverTest extends UnitTest {

    private MockWebServer primaryServer;
    private MockWebServer secondaryServer;

    @AfterEach
    void teardown() {
        if (primaryServer != null) {
            primaryServer.close();
            primaryServer = null;
        }
        if (secondaryServer != null) {
            secondaryServer.close();
            secondaryServer = null;
        }
    }

    @Test
    void primarySucceedsReturnsPrimaryResponseAndDoesNotCallSecondary() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        primaryServer.enqueue(jsonResponse(200, completionBody("from-primary", "ok")));

        secondaryServer = new MockWebServer();
        secondaryServer.start();
        // No secondary response enqueued — if the code calls it the test fails
        // because MockWebServer returns a default 404 / connection error.

        var primary = openAiPointingAt(primaryServer, "primary");
        var secondary = openAiPointingAt(secondaryServer, "secondary");

        ChatResponse result = LlmProvider.chatWithFailover(
                primary, new LlmProvider.Fallback(secondary, "fallback-model"),
                "test-model", List.of(), List.of(), null, null, "web");

        assertEquals("from-primary", result.id(),
                "happy path must return the primary's response");
        assertEquals(1, primaryServer.getRequestCount(), "primary called exactly once");
        assertEquals(0, secondaryServer.getRequestCount(),
                "secondary must not be called when primary succeeds");
    }

    @Test
    void primaryThrowsLlmExceptionAndSecondarySucceedsReturnsSecondaryResponse() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        // 5xx triggers the retry inside executeWithRetry; after retries
        // exhaust, the call exits with an LlmException (RuntimeException
        // subclass) which is what chatWithFailover catches.
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic\"}"));
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic\"}"));
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic\"}"));
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic\"}"));

        secondaryServer = new MockWebServer();
        secondaryServer.start();
        secondaryServer.enqueue(jsonResponse(200, completionBody("from-secondary", "ok")));

        var primary = openAiPointingAt(primaryServer, "primary");
        var secondary = openAiPointingAt(secondaryServer, "secondary");

        ChatResponse result = LlmProvider.chatWithFailover(
                primary, new LlmProvider.Fallback(secondary, "fallback-model"),
                "test-model", List.of(), List.of(), null, null, "web");

        assertEquals("from-secondary", result.id(),
                "failover must return the secondary's response on LlmException");
        assertTrue(primaryServer.getRequestCount() >= 1,
                "primary contacted at least once before failover");
        assertEquals(1, secondaryServer.getRequestCount(),
                "secondary called exactly once on failover");
        // JCLAW-1190: the fallback is asked for its own model, not the primary's — the live
        // drill that motivated this sent a model id the fallback had never heard of.
        var sent = secondaryServer.takeRequest().getBody().utf8();
        assertTrue(sent.contains("\"model\":\"fallback-model\""), () -> "fallback request: " + sent);
    }

    @Test
    void aFallbackThatResolvesToThePrimaryIsNoFallback() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        for (var i = 0; i < 4; i++) {
            primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic\"}"));
        }
        var primary = openAiPointingAt(primaryServer, "primary");
        // A conversation-level provider override can make the effective primary the agent's own
        // fallback provider; covering for itself would just fail the same way twice.
        var self = new LlmProvider.Fallback(openAiPointingAt(primaryServer, "primary"), "other-model");

        assertThrows(LlmProvider.LlmException.class, () -> LlmProvider.chatWithFailover(
                primary, self, "test-model", List.of(), List.of(), null, null, "web"));
        assertEquals(4, primaryServer.getRequestCount(), "one retry loop, no second pass on the same provider");
    }

    @Test
    void primaryThrowsLlmExceptionAndSecondaryIsNullRethrowsLlmException() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic and no secondary\"}"));
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic and no secondary\"}"));
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic and no secondary\"}"));
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic and no secondary\"}"));

        var primary = openAiPointingAt(primaryServer, "primary");

        assertThrows(LlmProvider.LlmException.class, () ->
                LlmProvider.chatWithFailover(
                        primary, null, "test-model", List.of(), List.of(), null, null, "web"),
                "chatWithFailover must rethrow LlmException when secondary is null");
        assertTrue(primaryServer.getRequestCount() >= 1, "primary contacted before rethrow");
    }

    @Test
    void aTimeoutOnThePrimaryFailsOverAtOnceInsteadOfRetrying() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        secondaryServer = new MockWebServer();
        secondaryServer.start();
        secondaryServer.enqueue(jsonResponse(200, completionBody("from-secondary", "ok")));
        var primaryName = "timeout-primary-" + System.nanoTime();
        var secondaryName = "timeout-secondary-" + System.nanoTime();
        var primary = openAiPointingAt(primaryServer, primaryName);
        var secondary = openAiPointingAt(secondaryServer, secondaryName);
        var attempts = new AtomicInteger();
        try {
            var result = HttpFactories.callWith(timingOut(primaryServer.getPort(), attempts),
                    () -> LlmProvider.chatWithFailover(primary, new LlmProvider.Fallback(secondary, "fallback-model"),
                            "test-model", List.of(), List.of(), null, null, "web"));

            assertEquals("from-secondary", result.id());
            assertEquals(1, attempts.get(), "a timeout is not retried on a provider a fallback covers");
        } finally {
            CircuitBreakers.remove(primaryName);
            CircuitBreakers.remove(secondaryName);
        }
    }

    @Test
    void aTimeoutWithNoFallbackIsStillRetried() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        var primaryName = "timeout-alone-" + System.nanoTime();
        var primary = openAiPointingAt(primaryServer, primaryName);
        var attempts = new AtomicInteger();
        try {
            assertThrows(LlmProvider.LlmException.class, () -> HttpFactories.callWith(
                    timingOut(primaryServer.getPort(), attempts),
                    () -> LlmProvider.chatWithFailover(primary, null, "test-model", List.of(), List.of(), null, null, "web")));
            assertEquals(4, attempts.get(), "with nowhere to fail over to, the retries are the only recourse");
        } finally {
            CircuitBreakers.remove(primaryName);
        }
    }

    @Test
    void aStreamedCallAssemblesTheReplyWithItsToolCalls() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        primaryServer.enqueue(sseResponse(LlmReplyFixture.sse("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"t1","type":"function","function":{"name":"datetime","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}]}""")));
        var name = "streamed-" + System.nanoTime();
        try {
            var result = LlmProvider.chatStreamedWithFailover(openAiPointingAt(primaryServer, name), null,
                    "test-model", List.of(), List.of(), null, null, "web");

            var choice = result.choices().getFirst();
            assertEquals("tool_calls", choice.finishReason());
            assertNull(choice.message().content(), "a tool-call reply without text carries no content, as a sync one does");
            assertEquals("datetime", choice.message().toolCalls().getFirst().function().name());
        } finally {
            CircuitBreakers.remove(name);
        }
    }

    @Test
    void aStreamedCallRetriesAServerErrorAndThenAnswers() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        primaryServer.enqueue(jsonResponse(500, "{\"error\":\"primary synthetic\"}"));
        primaryServer.enqueue(sseResponse(LlmReplyFixture.sse(completionBody("second-try", "answered"))));
        var name = "streamed-retry-" + System.nanoTime();
        try {
            var result = LlmProvider.chatStreamedWithFailover(openAiPointingAt(primaryServer, name), null,
                    "test-model", List.of(), List.of(), null, null, "web");

            assertEquals("answered", result.choices().getFirst().message().content());
            assertEquals(2, primaryServer.getRequestCount());
        } finally {
            CircuitBreakers.remove(name);
        }
    }

    @Test
    void aBrokenStreamIsRetriedAndThenFailsOver() throws Exception {
        // A canned transport cannot reach the stream's own thread, so the primary's server breaks the stream itself.
        primaryServer = new MockWebServer();
        primaryServer.start();
        for (var i = 0; i < 4; i++) {
            primaryServer.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(": keepalive\n\n".repeat(2000))
                    .onResponseBody(new SocketEffect.CloseSocket())
                    .build());
        }
        secondaryServer = new MockWebServer();
        secondaryServer.start();
        secondaryServer.enqueue(sseResponse(LlmReplyFixture.sse(completionBody("from-secondary", "fallback answer"))));
        var primaryName = "streamed-broken-" + System.nanoTime();
        var secondaryName = "streamed-fallback-" + System.nanoTime();
        try {
            var result = LlmProvider.chatStreamedWithFailover(openAiPointingAt(primaryServer, primaryName),
                    new LlmProvider.Fallback(openAiPointingAt(secondaryServer, secondaryName), "fallback-model"),
                    "test-model", List.of(), List.of(), null, null, "web");

            assertEquals("fallback answer", result.choices().getFirst().message().content());
            assertEquals(4, primaryServer.getRequestCount(), "a broken stream is retried like a failed sync call");
        } finally {
            CircuitBreakers.remove(primaryName);
            CircuitBreakers.remove(secondaryName);
        }
    }

    @Test
    void aStreamThatCarriedNoChoiceReadsAsNoResponse() throws Exception {
        primaryServer = new MockWebServer();
        primaryServer.start();
        primaryServer.enqueue(sseResponse(LlmReplyFixture.sse("{\"choices\":[]}")));
        var name = "streamed-empty-" + System.nanoTime();
        try {
            var result = LlmProvider.chatStreamedWithFailover(openAiPointingAt(primaryServer, name), null,
                    "test-model", List.of(), List.of(), null, null, "web");

            assertTrue(result.choices().isEmpty(), "the sync loop reports this as no response: " + result);
        } finally {
            CircuitBreakers.remove(name);
        }
    }

    // === Helpers ===

    private OpenAiProvider openAiPointingAt(MockWebServer server, String name) {
        var baseUrl = server.url("/").toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        var config = new ProviderConfig(name, baseUrl, "sk-test",
                List.of(new ModelInfo("test-model", "Test", 100000, 4096, false)));
        return new OpenAiProvider(config);
    }

    /** A transport whose calls to {@code port} time out at once, counting each attempt; other ports pass through. */
    private static OkHttpClient timingOut(int port, AtomicInteger attempts) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().url().port() != port) return chain.proceed(chain.request());
            attempts.incrementAndGet();
            throw new SocketTimeoutException("timeout");
        }).build();
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build();
    }

    private static MockResponse jsonResponse(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    /**
     * Minimal valid OpenAI-format chat completion JSON. The {@code id} field
     * carries the marker tag the test asserts on; the rest is just enough
     * structure to deserialize into {@link ChatResponse} without NPE.
     */
    private static String completionBody(String idTag, String content) {
        return ("{"
                + "\"id\":\"" + idTag + "\","
                + "\"model\":\"test-model\","
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}"
                + "}");
    }
}
