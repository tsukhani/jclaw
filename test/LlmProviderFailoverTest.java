import llm.LlmProvider;
import llm.OpenAiProvider;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-300: pin {@link LlmProvider#chatWithFailover} — the primary→
 * secondary fallback path the JCLAW-299 AgentRunner extraction carved
 * out as deferred work. The ticket described this as "streaming" but
 * the failover utility is actually called from
 * {@link agents.ToolCallLoopRunner#callWithToolLoop} (the synchronous
 * tool-call loop); the streaming path receives a secondary parameter
 * but doesn't currently use it for failover. Tests target the static
 * utility directly so its three branches are exercised without
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
                primary, secondary, "test-model", List.of(), List.of(), null, null, "web");

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
                primary, secondary, "test-model", List.of(), List.of(), null, null, "web");

        assertEquals("from-secondary", result.id(),
                "failover must return the secondary's response on LlmException");
        assertTrue(primaryServer.getRequestCount() >= 1,
                "primary contacted at least once before failover");
        assertEquals(1, secondaryServer.getRequestCount(),
                "secondary called exactly once on failover");
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

    // === Helpers ===

    private OpenAiProvider openAiPointingAt(MockWebServer server, String name) {
        var baseUrl = server.url("/").toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        var config = new ProviderConfig(name, baseUrl, "sk-test",
                List.of(new ModelInfo("test-model", "Test", 100000, 4096, false)));
        return new OpenAiProvider(config);
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
