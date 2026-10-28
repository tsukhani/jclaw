import agents.AgentRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import utils.CircuitBreakers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A turn's saved usage record carries its first model call's prompt and cache reads beside the last call's (JF-151). */
class FirstCallUsageTest extends UnitTest {

    private static final String MODEL = "first-call-model";
    private static final List<String> THREE_CALLS = List.of(
            toolCallReply("call_1", 1_000, 200),
            toolCallReply("call_2", 1_300, 1_000),
            finalReply("Three calls made.", 1_600, 1_300));

    private final AtomicInteger calls = new AtomicInteger();
    private volatile List<String> replies = THREE_CALLS;
    private com.sun.net.httpserver.HttpServer llmServer;
    private String provider;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        provider = "first-call-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        // The reply is chosen by how many tool results the request carries, so a stray call cannot shift the sequence.
        llmServer.createContext("/chat/completions", exchange -> {
            var request = JsonParser.parseString(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var toolResults = 0;
            for (var m : request.getAsJsonArray("messages")) {
                if ("tool".equals(m.getAsJsonObject().get("role").getAsString())) toolResults++;
            }
            calls.incrementAndGet();
            LlmReplyFixture.send(exchange, replies.get(Math.min(toolResults, replies.size() - 1)));
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"First call\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void tearDown() {
        try {
            if (llmServer != null) llmServer.stop(0);
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            ProviderRegistry.refresh();
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void aStreamedTurnOfThreeCallsSavesTheFirstCallsPromptAndCacheReads() throws Exception {
        var convo = seedConversation("u-first-streamed");

        var error = streamAndAwait(convo, "What time is it, twice?");

        assertNull(error, () -> "unexpected error: " + error);
        assertEquals(3, calls.get(), "two tool rounds and the answer");
        assertThreeCallUsage(lastAssistantUsage(convo));
    }

    @Test
    void aSyncTurnOfThreeCallsSavesTheFirstCallsPromptAndCacheReads() throws Exception {
        var convo = seedConversation("u-first-sync");

        assertEquals("Three calls made.", runSync(convo, "What time is it, twice?"));

        assertEquals(3, calls.get(), "two tool rounds and the answer");
        assertThreeCallUsage(lastAssistantUsage(convo));
    }

    @Test
    void aSingleCallTurnSavesItsOneCallAsBothFirstAndLast() throws Exception {
        replies = List.of(finalReply("One call made.", 700, 650));
        var convo = seedConversation("u-first-single");

        var error = streamAndAwait(convo, "Hello.");

        assertNull(error, () -> "unexpected error: " + error);
        assertEquals(1, calls.get());
        var usage = lastAssistantUsage(convo);
        assertEquals(700, usage.get("firstPrompt").getAsInt());
        assertEquals(650, usage.get("firstCached").getAsInt());
        assertEquals(700, usage.get("lastPrompt").getAsInt());
        assertEquals(650, usage.get("lastCached").getAsInt());
        assertEquals(700, usage.get("prompt").getAsInt());
        assertEquals(650, usage.get("cached").getAsInt());
    }

    private static void assertThreeCallUsage(JsonObject usage) {
        assertEquals(1_000, usage.get("firstPrompt").getAsInt(), usage::toString);
        assertEquals(200, usage.get("firstCached").getAsInt(), usage::toString);
        assertEquals(1_600, usage.get("lastPrompt").getAsInt(), usage::toString);
        assertEquals(1_300, usage.get("lastCached").getAsInt(), usage::toString);
        assertEquals(3_900, usage.get("prompt").getAsInt(), "every call's prompt still sums");
        assertEquals(2_500, usage.get("cached").getAsInt(), "every call's cache reads still sum");
    }

    private Conversation seedConversation(String peer) {
        var agent = new Agent();
        agent.name = "first-call-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        var convo = ConversationService.create(agent, "web", peer);
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    private static Exception streamAndAwait(Conversation convo, String text) throws InterruptedException {
        var error = new AtomicReference<Exception>();
        var terminated = new CountDownLatch(1);
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {},
                _ -> terminated.countDown(),
                e -> { error.set(e); terminated.countDown(); },
                terminated::countDown);
        AgentRunner.runStreaming(convo.agent, convo.id, convo.channelType, convo.peerId, text,
                new AtomicBoolean(false), cb, null);
        assertTrue(terminated.await(60, TimeUnit.SECONDS), "the turn must reach a terminal callback");
        return error.get();
    }

    /** {@link AgentRunner#run} on its own thread, so it commits its own rows. */
    private static String runSync(Conversation convo, String text) throws InterruptedException {
        var agentId = convo.agent.id;
        var convoId = convo.id;
        var reply = new AtomicReference<String>();
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> AgentService.findById(agentId));
                var c = Tx.run(() -> ConversationService.findById(convoId));
                reply.set(AgentRunner.run(Objects.requireNonNull(a), Objects.requireNonNull(c), text).response());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertTrue(thread.join(Duration.ofSeconds(60)), "the sync turn must finish");
        assertNull(error.get(), () -> "unexpected error: " + error.get());
        return reply.get();
    }

    private static JsonObject lastAssistantUsage(Conversation convo) {
        JPA.em().clear();
        Message assistant = Message.find("conversation.id = ?1 AND role = ?2 ORDER BY id DESC",
                convo.id, MessageRole.ASSISTANT.value).first();
        assertNotNull(assistant, "the reply must be persisted");
        assertNotNull(assistant.usageJson, "the reply must carry its usage record");
        return JsonParser.parseString(assistant.usageJson).getAsJsonObject();
    }

    private static String toolCallReply(String callId, int prompt, int cached) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Let me check.",
                "tool_calls":[{"id":"%s","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now\\"}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":%d,"completion_tokens":5,"total_tokens":%d,"prompt_tokens_details":{"cached_tokens":%d}}}"""
                .formatted(callId, prompt, prompt + 5, cached);
    }

    private static String finalReply(String content, int prompt, int cached) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":%d,"completion_tokens":9,"total_tokens":%d,"prompt_tokens_details":{"cached_tokens":%d}}}"""
                .formatted(content, prompt, prompt + 9, cached);
    }
}
