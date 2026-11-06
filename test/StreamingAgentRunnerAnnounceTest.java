import agents.AgentRunner;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.NotificationBus;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A turn streamed to a channel or to voice tells a chat page open on its conversation of each commit that writes,
 * since that page has no stream of it; a turn streamed to the web tab does not, whatever the conversation's channel,
 * since the tab already shows its rows.
 */
class StreamingAgentRunnerAnnounceTest extends UnitTest {

    private static final String MODEL = "announce-stream-model";

    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer llmServer;
    private String provider;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        provider = "announce-stream-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One tool round, then the reply: chosen by whether the request carries a tool result yet.
        llmServer.createContext("/chat/completions", exchange -> {
            var request = JsonParser.parseString(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            boolean toolAnswered = false;
            for (var m : request.getAsJsonArray("messages")) {
                if ("tool".equals(m.getAsJsonObject().get("role").getAsString())) toolAnswered = true;
            }
            calls.incrementAndGet();
            LlmReplyFixture.send(exchange, toolAnswered ? FINAL_REPLY : TOOL_CALL_REPLY);
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Announce\",\"contextWindow\":100000,\"maxTokens\":4096}]");
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
    void aTurnStreamedToTelegramAnnouncesItsMessageItsToolRoundAndItsReply() throws Exception {
        var convo = seedConversation("telegram", "tg-announce");

        var heard = updatesWhile(convo.id, () -> assertNull(streamAndAwait(convo, "telegram", new AtomicBoolean())));

        assertEquals(List.of(update(convo.id), update(convo.id), update(convo.id)), heard);
        assertEquals(List.of("user", "assistant", "tool", "assistant"), roles(convo.id));
    }

    @Test
    void aTurnStreamedToVoiceAnnouncesItsMessageItsToolRoundAndItsReply() throws Exception {
        var convo = seedConversation("voice", "admin#voice-announce");

        var heard = updatesWhile(convo.id, () -> assertNull(streamAndAwait(convo, "voice", new AtomicBoolean())));

        assertEquals(List.of(update(convo.id), update(convo.id), update(convo.id)), heard);
        assertEquals(List.of("user", "assistant", "tool", "assistant"), roles(convo.id));
    }

    @Test
    void aTurnStreamedToTheWebTabIsNotAnnounced() throws Exception {
        var convo = seedConversation("web", "web-announce");

        var heard = updatesWhile(convo.id, () -> assertNull(streamAndAwait(convo, "web", new AtomicBoolean())));

        assertEquals(List.of(), heard, "the tab streaming the turn already shows its rows");
        assertEquals(List.of("user", "assistant", "tool", "assistant"), roles(convo.id));
    }

    @Test
    void aTurnTheWebTabStreamsInATelegramConversationIsNotAnnounced() throws Exception {
        var convo = seedConversation("telegram", "tg-from-the-tab");

        var heard = updatesWhile(convo.id, () -> assertNull(streamAndAwait(convo, "web", new AtomicBoolean())));

        assertEquals(List.of(), heard, "the surface decides, not the conversation's channel");
        assertEquals(List.of("user", "assistant", "tool", "assistant"), roles(convo.id));
    }

    @Test
    void aChannelTurnStoppedBeforeItsFirstWriteAnnouncesNothing() throws Exception {
        var convo = seedConversation("telegram", "tg-stopped");

        var heard = updatesWhile(convo.id, () -> assertNull(streamAndAwait(convo, "telegram", new AtomicBoolean(true))));

        assertEquals(List.of(), heard, "the stop refused the user message's commit, so nothing was written to announce");
        assertEquals(0, calls.get(), "the stopped turn never reached the model");
        // No user row, so the stop marker has no request to answer and is not written either.
        assertEquals(List.of(), roles(convo.id));
    }

    private static final String TOOL_CALL_REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"Let me check.",
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now\\"}"}}]},
            "finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""";

    private static final String FINAL_REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"It is now."},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}""";

    private interface TurnBody {
        void run() throws Exception;
    }

    /** The {@code conversation.updated} payloads published for {@code conversationId} while {@code body} runs. */
    private static List<Map<String, Object>> updatesWhile(Long conversationId, TurnBody body) throws Exception {
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = NotificationBus.subscribeEvents((type, data) -> {
            if (NotificationBus.BUS_CONVERSATION_UPDATED.equals(type) && conversationId.equals(data.get("conversationId"))) {
                heard.add(data);
            }
        });
        try {
            body.run();
        } finally {
            unsubscribe.run();
        }
        return List.copyOf(heard);
    }

    private static Map<String, Object> update(Long conversationId) {
        return Map.of("conversationId", conversationId);
    }

    private Conversation seedConversation(String channelType, String peer) {
        var agent = new Agent();
        agent.name = "announce-stream-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        var convo = ConversationService.create(agent, channelType, peer);
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    /** Streams {@code convo}'s turn to {@code surface} and returns its error, or {@code null} once it ends without one. */
    private static Exception streamAndAwait(Conversation convo, String surface, AtomicBoolean isCancelled)
            throws InterruptedException {
        var error = new AtomicReference<Exception>();
        var terminated = new CountDownLatch(1);
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {},
                _ -> terminated.countDown(),
                e -> { error.set(e); terminated.countDown(); },
                terminated::countDown);
        AgentRunner.runStreaming(convo.agent, convo.id, surface, convo.peerId, "What time is it?", isCancelled, cb, null);
        assertTrue(terminated.await(60, TimeUnit.SECONDS), "the turn must reach a terminal callback");
        awaitIdle(convo.id);
        return error.get();
    }

    /** The terminal callback runs after the release, but the turn's thread may still be in its finally. */
    private static void awaitIdle(Long conversationId) throws InterruptedException {
        for (int i = 0; i < 100 && ConversationQueue.isBusy(conversationId); i++) Thread.sleep(50);
        assertFalse(ConversationQueue.isBusy(conversationId), "the turn released its conversation");
    }

    private static List<String> roles(Long conversationId) {
        JPA.em().clear();
        List<Message> rows = Message.find("conversation.id = ?1 ORDER BY id", conversationId).fetch();
        var roles = new ArrayList<String>(rows.size());
        for (var m : rows) roles.add(m.role);
        return roles;
    }
}
