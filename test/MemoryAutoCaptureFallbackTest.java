import llm.LlmProvider;
import llm.LlmProvider.LlmException;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import llm.ProviderRegistry;
import memory.MemoryAutoCapture;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.util.List;

/**
 * JCLAW-1193: memory auto-capture follows the agent's fallback the way the turn does, and
 * stands aside while a provider's breaker is probing so the user's turn is the probe.
 *
 * <p>Provider names are unique to this class: the breaker registry is process-global and
 * play1 runs test classes concurrently.
 */
class MemoryAutoCaptureFallbackTest extends UnitTest {

    private static final String UNROUTABLE = "http://memory-fallback.invalid/v1";
    private static final List<ChatMessage> MSGS = List.of(ChatMessage.user("Extract memories from: nothing."));

    private MockWebServer fallbackServer;

    @AfterEach
    void tearDown() {
        if (fallbackServer != null) fallbackServer.close();
    }

    @Test
    void anExtractionFailsOverToTheAgentsFallbackWithItsOwnModel() throws Exception {
        var primaryName = "jclaw1193-primary";
        try {
            LlmResilience.breakerFor(primaryName).trip();
            fallbackServer = new MockWebServer();
            fallbackServer.start();
            fallbackServer.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json").body(completionBody("[]")).build());
            var ctx = new MemoryAutoCapture.ExtractContext(
                    LlmProvider.forConfig(new ProviderConfig(primaryName, UNROUTABLE, "k", List.of())),
                    "primary-model", "web",
                    new LlmProvider.Fallback(openAiPointingAt(fallbackServer, "jclaw1193-fallback"), "fb-model"));

            assertEquals("[]", MemoryAutoCapture.chatText(ctx, MSGS, 64));
            assertEquals(1, fallbackServer.getRequestCount());
            var sent = fallbackServer.takeRequest().getBody().utf8();
            assertTrue(sent.contains("\"model\":\"fb-model\""), () -> "fallback request: " + sent);
        } finally {
            CircuitBreakers.remove(LlmResilience.breakerName(primaryName));
            CircuitBreakers.remove(LlmResilience.breakerName("jclaw1193-fallback"));
        }
    }

    @Test
    void withoutAFallbackTheExtractionFailsFast() {
        var primaryName = "jclaw1193-alone";
        try {
            LlmResilience.breakerFor(primaryName).trip();
            var ctx = new MemoryAutoCapture.ExtractContext(
                    LlmProvider.forConfig(new ProviderConfig(primaryName, UNROUTABLE, "k", List.of())),
                    "primary-model", "web", null);
            assertThrows(LlmException.class, () -> MemoryAutoCapture.chatText(ctx, MSGS, 64));
        } finally {
            CircuitBreakers.remove(LlmResilience.breakerName(primaryName));
        }
    }

    @Test
    void anAutoCaptureOverrideIsTheWholeChoice() {
        var agent = new Agent();
        agent.name = "jclaw1193-override";
        agent.modelProvider = "jclaw1193-primary";
        agent.modelId = "m";
        agent.fallbackProvider = "jclaw1193-fallback";
        agent.fallbackModelId = "fb-model";
        agent.memoryAutocaptureProvider = "jclaw1193-cheap";
        agent.memoryAutocaptureModel = "cheap-model";
        assertNull(MemoryAutoCapture.resolveFallback(agent),
                "an operator who pointed extraction at a specific provider did not ask for it to move");
    }

    @Test
    void theAgentsFallbackResolvesFromTheRegistry() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        ConfigService.set("provider.jclaw1193-registry.baseUrl", "https://jclaw1193.invalid/v1");
        ConfigService.set("provider.jclaw1193-registry.apiKey", "k");
        ProviderRegistry.refresh();

        var agent = new Agent();
        agent.name = "jclaw1193-resolves";
        agent.modelProvider = "jclaw1193-primary";
        agent.modelId = "m";
        agent.fallbackProvider = "jclaw1193-registry";
        agent.fallbackModelId = "fb-model";

        var fallback = MemoryAutoCapture.resolveFallback(agent);
        assertNotNull(fallback);
        assertEquals("jclaw1193-registry", fallback.provider().config().name());
        assertEquals("fb-model", fallback.modelId());

        agent.fallbackProvider = "jclaw1193-never-configured";
        assertNull(MemoryAutoCapture.resolveFallback(agent), "a fallback that lost its configuration is none");
    }

    @Test
    void captureStandsAsideWhileTheProviderIsProbing() {
        var name = "jclaw1193-probe";
        var breaker = CircuitBreakers.get(LlmResilience.breakerName(name), CircuitBreaker.Config.of(10, 0.5, 1, 0L));
        try {
            var provider = LlmProvider.forConfig(new ProviderConfig(name, UNROUTABLE, "k", List.of()));
            assertFalse(MemoryAutoCapture.providerIsProbing(provider), "closed: nothing to stand aside for");
            breaker.trip();
            assertFalse(MemoryAutoCapture.providerIsProbing(provider), "open: the extraction is refused, not deferred");
            assertTrue(breaker.allowRequest(), "cooldown 0: the first ask moves it to HALF_OPEN");
            assertTrue(MemoryAutoCapture.providerIsProbing(provider), "probing: the user's turn is the probe");
            breaker.reset();
            assertFalse(MemoryAutoCapture.providerIsProbing(provider));
        } finally {
            CircuitBreakers.remove(LlmResilience.breakerName(name));
        }
        assertFalse(MemoryAutoCapture.providerIsProbing(
                LlmProvider.forConfig(new ProviderConfig("jclaw1193-unminted", UNROUTABLE, "k", List.of()))),
                "a provider with no breaker yet is not probing");
    }

    private static OpenAiProvider openAiPointingAt(MockWebServer server, String name) {
        var baseUrl = server.url("/").toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return new OpenAiProvider(new ProviderConfig(name, baseUrl, "sk-test",
                List.of(new ModelInfo("fb-model", "Fallback", 100000, 4096, false))));
    }

    private static String completionBody(String content) {
        return "{\"id\":\"c1\",\"model\":\"fb-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
