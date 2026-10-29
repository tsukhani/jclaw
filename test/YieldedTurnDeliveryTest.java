import agents.AgentRunner;
import agents.QueueDrainOrchestrator;
import channels.ChannelTransport;
import channels.TelegramChannel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentBinding;
import models.Conversation;
import models.SubagentRun;
import models.TelegramBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.Tx;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** A turn that suspends on {@code subagent_yield} sends nothing to its channel; the resumed turn replies later (JF-166). */
class YieldedTurnDeliveryTest extends UnitTest {

    private static final String MODEL = "deliver-yield-model";
    private static final String ASK = "Check on the report.";
    private static final String REPLY = "Here is your answer.";
    private static final String PEER = "4343";

    private HttpServer llmServer;
    private String provider;
    private Long agentId;
    private volatile Long yieldOn;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        provider = "deliver-yield-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commit(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Deliver\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        // refresh() returns at once while another class's refresh runs, and that one may predate this commit.
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) {
            ProviderRegistry.refresh();
            if (ProviderRegistry.get(provider) == null) Thread.sleep(20);
        }
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
        agentId = commit(() -> {
            var agent = AgentService.create(provider, provider, MODEL);
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
    }

    @AfterEach
    void tearDown() {
        try {
            if (llmServer != null) llmServer.stop(0);
            commit(() -> {
                for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                    ConfigService.delete("provider." + provider + suffix);
                }
                return null;
            });
            ProviderRegistry.refresh();
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void aYieldedTurnForABoundAgentSendsNothing() throws Exception {
        yieldOn = seedRunningRun(conversation("telegram", PEER));
        var sent = new CopyOnWriteArrayList<String>();

        onFreshThread(() -> AgentRunner.processInboundForAgent(agent(), "telegram", PEER, ASK,
                (peer, text) -> sent.add(text)));

        assertEquals(List.of(), sent);
        assertTrue(yielded(yieldOn), "the turn suspended on the run");
    }

    @Test
    void aYieldedWebhookTurnSendsNothing() throws Exception {
        commit(() -> {
            var binding = new AgentBinding();
            binding.agent = Agent.findById(agentId);
            binding.channelType = "slack";
            binding.peerId = "C-yield";
            binding.save();
            return null;
        });
        yieldOn = seedRunningRun(conversation("slack", "C-yield"));
        var sent = new CopyOnWriteArrayList<String>();

        onFreshThread(() -> AgentRunner.processWebhookMessage("slack", "C-yield", ASK,
                (peer, text) -> sent.add(text), peer -> sent.add("no route for " + peer)));

        assertEquals(List.of(), sent);
        assertTrue(yielded(yieldOn), "the turn suspended on the run");
    }

    @Test
    void aYieldedQueuedTurnSendsNothingToItsChannel() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                yieldOn = seedRunningRun(conversationId);

                runQueued(conversationId);
                // The drain thread sends only after it frees the queue: 500 ms lets a send that was coming arrive.
                Thread.sleep(500);

                assertEquals(0, telegram.countRequests("sendMessage"));
                assertTrue(yielded(yieldOn), "the turn suspended on the run");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void aQueuedTurnThatDoesNotYieldSendsItsReply() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                runQueued(conversation("telegram", PEER));
                awaitRequest(telegram, "sendMessage");

                assertEquals(1, telegram.countRequests("sendMessage"));
                assertTrue(telegram.requests().getLast().body().contains(REPLY), telegram.requests().getLast().body());
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void aResumedTurnThatYieldsAgainSendsNothing() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                var deliver = AgentRunner.class.getDeclaredMethod("deliverResumed",
                        Agent.class, Conversation.class, AgentRunner.RunResult.class);
                deliver.setAccessible(true);
                Conversation chat = commit(() -> Conversation.findById(conversationId));

                deliver.invoke(null, agent(), chat, new AgentRunner.RunResult(AgentRunner.YIELDED_RESPONSE, chat, false));
                assertEquals(0, telegram.countRequests("sendMessage"), "a yielded resume has no reply to send");

                deliver.invoke(null, agent(), chat, new AgentRunner.RunResult(REPLY, chat, false));
                assertEquals(1, telegram.countRequests("sendMessage"), "a resume with a reply sends it");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    /** Runs {@code ASK} the way a queue drain does, on a conversation this test owns, and waits for it to end. */
    private void runQueued(Long conversationId) throws InterruptedException {
        var message = new ConversationQueue.QueuedMessage(ASK, "telegram", PEER, agent(), false, List.of());
        long generation = ConversationQueue.tryAcquireOwnership(conversationId, message, null);
        assertNotEquals(ConversationQueue.NOT_ACQUIRED, generation, "test precondition: the conversation is free");
        QueueDrainOrchestrator.runOwned(conversationId, generation, List.of(message));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (ConversationQueue.isBusy(conversationId) && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(ConversationQueue.isBusy(conversationId), "the queued turn ended");
    }

    /** Waits, up to 10 s, for {@code method} to reach the mock: the drain thread sends it after freeing the queue. */
    private static void awaitRequest(MockTelegramServer telegram, String method) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (telegram.countRequests(method) == 0 && System.nanoTime() < deadline) Thread.sleep(20);
    }

    private String bindTelegram(MockTelegramServer telegram) {
        var token = "deliver-yield-" + System.nanoTime();
        commit(() -> {
            var binding = new TelegramBinding();
            binding.agent = Agent.findById(agentId);
            binding.botToken = token;
            binding.telegramUserId = PEER;
            binding.transport = ChannelTransport.POLLING;
            binding.enabled = true;
            binding.save();
            return null;
        });
        telegram.start();
        TelegramChannel.installForTest(token, telegram.telegramUrl());
        return token;
    }

    private Agent agent() {
        return commit(() -> Agent.findById(agentId));
    }

    private Long conversation(String channelType, String peerId) {
        return commit(() -> ConversationService.findOrCreate(Agent.findById(agentId), channelType, peerId).id);
    }

    /** A run this agent started from {@code conversationId}, still running. */
    private Long seedRunningRun(Long conversationId) {
        return commit(() -> {
            Agent agent = Agent.findById(agentId);
            Conversation parent = Conversation.findById(conversationId);
            var child = AgentService.create(provider + "-child", provider, MODEL);
            child.parentAgent = agent;
            child.save();
            var childChat = ConversationService.create(child, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
            childChat.parentConversation = parent;
            childChat.save();
            var run = new SubagentRun();
            run.parentAgent = agent;
            run.childAgent = child;
            run.parentConversation = parent;
            run.childConversation = childChat;
            run.status = SubagentRun.Status.RUNNING;
            run.save();
            return run.id;
        });
    }

    private boolean yielded(Long runId) {
        return commit(() -> ((SubagentRun) SubagentRun.findById(runId)).yielded);
    }

    private void answer(HttpExchange exchange) throws IOException {
        var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        var messages = request.getAsJsonArray("messages");
        var lastRole = messages.get(messages.size() - 1).getAsJsonObject().get("role").getAsString();
        var runId = yieldOn;
        if (runId == null) {
            LlmReplyFixture.send(exchange, reply(REPLY));
        } else if ("tool".equals(lastRole)) {
            LlmReplyFixture.send(exchange, reply("the turn went on past its yield"));
        } else {
            LlmReplyFixture.send(exchange, yieldCall(runId));
        }
    }

    private static String reply(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return completion(message, "stop");
    }

    private static String yieldCall(long runId) {
        var function = new JsonObject();
        function.addProperty("name", "subagent_yield");
        function.addProperty("arguments", "{\"runId\":\"" + runId + "\"}");
        var call = new JsonObject();
        call.addProperty("id", "call_yield");
        call.addProperty("type", "function");
        call.add("function", function);
        var calls = new JsonArray();
        calls.add(call);
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "");
        message.add("tool_calls", calls);
        return completion(message, "tool_calls");
    }

    private static String completion(JsonObject message, String finishReason) {
        var choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason);
        var choices = new JsonArray();
        choices.add(choice);
        var usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("completion_tokens", 5);
        var completion = new JsonObject();
        completion.add("choices", choices);
        completion.add("usage", usage);
        return completion.toString();
    }

    /** Runs {@code body} on a thread with no transaction, as a channel's inbound turn does. */
    private static void onFreshThread(Runnable body) throws InterruptedException {
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the turn ends within 30s");
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static <T> T commit(Supplier<T> block) {
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
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
