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
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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
    /** When set, each model call opens {@code reached} and answers once {@code release} opens. */
    private volatile Hold hold;

    private record Hold(CountDownLatch reached, CountDownLatch release) {
        Hold() {
            this(new CountDownLatch(1), new CountDownLatch(1));
        }
    }

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

    // --- JF-197: a turn stopped before its reply sends nothing to its destination ---

    @Test
    void aStoppedResumeSendsNothingToItsChannel() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                Conversation chat = commit(() -> Conversation.findById(conversationId));

                stopWhileTheModelIsCalled(conversationId, () -> AgentRunner.resumeAndDeliver(agent(), chat));

                assertEquals(0, telegram.countRequests("sendMessage"), "the stopped resume's reply is not sent");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void aStoppedInboundTurnForABoundAgentSendsNothing() throws Exception {
        var conversationId = conversation("telegram", PEER);
        var sent = new CopyOnWriteArrayList<String>();

        stopWhileTheModelIsCalled(conversationId, () -> AgentRunner.processInboundForAgent(agent(), "telegram", PEER,
                ASK, (peer, text) -> sent.add(text)));

        assertEquals(List.of(), sent);
    }

    @Test
    void aStoppedWebhookTurnSendsNothing() throws Exception {
        commit(() -> {
            var binding = new AgentBinding();
            binding.agent = Agent.findById(agentId);
            binding.channelType = "slack";
            binding.peerId = "C-stopped";
            binding.save();
            return null;
        });
        var conversationId = conversation("slack", "C-stopped");
        var sent = new CopyOnWriteArrayList<String>();

        stopWhileTheModelIsCalled(conversationId, () -> AgentRunner.processWebhookMessage("slack", "C-stopped", ASK,
                (peer, text) -> sent.add(text), peer -> sent.add("no route for " + peer)));

        assertEquals(List.of(), sent);
    }

    @Test
    void aStoppedQueuedTurnSendsNothingToItsChannel() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                hold = new Hold();
                var message = new ConversationQueue.QueuedMessage(ASK, "telegram", PEER, agent(), false, List.of());
                long generation = ConversationQueue.tryAcquireOwnership(conversationId, message, null);
                assertNotEquals(ConversationQueue.NOT_ACQUIRED, generation, "test precondition: the conversation is free");
                String agentName = agent().name;
                EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
                    QueueDrainOrchestrator.runOwned(conversationId, generation, List.of(message));
                    try {
                        assertTrue(hold.reached().await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
                        assertTrue(ConversationQueue.stop(conversationId), "the queued turn holds a flag of its own");
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    } finally {
                        hold.release().countDown();
                    }
                    awaitLogged(seen, "Response generated");
                });
                // The drain thread sends just after its reply is generated: 500 ms lets a send that was coming arrive.
                Thread.sleep(500);

                assertEquals(0, telegram.countRequests("sendMessage"), "the stopped turn's reply is not sent");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    // --- JF-197b: the operator's stop tells the person on the channel, in place of the reply ---

    private static boolean stopFromChat(Long conversationId, String channelType) {
        return ConversationQueue.stopOperatorTurn(conversationId, channelType) == ConversationQueue.OperatorStop.STOPPED;
    }

    @Test
    void anOperatorStopOfAnInboundTurnForABoundAgentSendsTheNoticeAlone() throws Exception {
        var conversationId = conversation("telegram", PEER);
        var sent = new CopyOnWriteArrayList<String>();

        stopWhileTheModelIsCalled(conversationId, () -> AgentRunner.processInboundForAgent(agent(), "telegram", PEER,
                ASK, (peer, text) -> sent.add(peer + ": " + text)), id -> stopFromChat(id, "telegram"));
        awaitSent(sent);

        assertEquals(List.of(PEER + ": " + AgentRunner.STOP_NOTICE), sent);
    }

    @Test
    void anOperatorStopOfAWebhookTurnSendsTheNoticeAlone() throws Exception {
        commit(() -> {
            var binding = new AgentBinding();
            binding.agent = Agent.findById(agentId);
            binding.channelType = "slack";
            binding.peerId = "C-noticed";
            binding.save();
            return null;
        });
        var conversationId = conversation("slack", "C-noticed");
        var sent = new CopyOnWriteArrayList<String>();

        stopWhileTheModelIsCalled(conversationId, () -> AgentRunner.processWebhookMessage("slack", "C-noticed", ASK,
                (peer, text) -> sent.add(peer + ": " + text), peer -> sent.add("no route for " + peer)),
                id -> stopFromChat(id, "slack"));
        awaitSent(sent);

        assertEquals(List.of("C-noticed: " + AgentRunner.STOP_NOTICE), sent);
    }

    @Test
    void anOperatorStopOfAResumeSendsTheNoticeToItsChannel() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                // The yielded run's announce: the request the resumed turn answers.
                Conversation chat = commit(() -> {
                    Conversation found = Conversation.findById(conversationId);
                    ConversationService.appendUserMessage(found, "[subagent result] the report is ready");
                    return found;
                });

                stopWhileTheModelIsCalled(conversationId, () -> AgentRunner.resumeAndDeliver(agent(), chat),
                        id -> stopFromChat(id, "telegram"));
                awaitRequest(telegram, "sendMessage");
                Thread.sleep(300);

                assertEquals(List.of(AgentRunner.STOP_NOTICE), sentTexts(telegram));
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void anOperatorStopOfAQueuedChannelTurnSendsTheNoticeAlone() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                hold = new Hold();
                var message = new ConversationQueue.QueuedMessage(ASK, "telegram", PEER, agent(), false, List.of());
                long generation = ConversationQueue.tryAcquireOwnership(conversationId, message, null);
                assertNotEquals(ConversationQueue.NOT_ACQUIRED, generation, "test precondition: the conversation is free");
                QueueDrainOrchestrator.runOwned(conversationId, generation, List.of(message));
                try {
                    assertTrue(hold.reached().await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
                    assertTrue(stopFromChat(conversationId, "telegram"), "the queued turn holds a flag of its own");
                } finally {
                    hold.release().countDown();
                }
                awaitRequest(telegram, "sendMessage");

                assertEquals(List.of(AgentRunner.STOP_NOTICE), sentTexts(telegram));
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void aStopSentOnTheChannelOfAQueuedChannelTurnSendsItNothingMore() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                hold = new Hold();
                var message = new ConversationQueue.QueuedMessage(ASK, "telegram", PEER, agent(), false, List.of());
                long generation = ConversationQueue.tryAcquireOwnership(conversationId, message, null);
                assertNotEquals(ConversationQueue.NOT_ACQUIRED, generation, "test precondition: the conversation is free");
                String agentName = agent().name;
                EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
                    QueueDrainOrchestrator.runOwned(conversationId, generation, List.of(message));
                    try {
                        assertTrue(hold.reached().await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
                        // The person's own /stop: its "Stopped." is their answer.
                        assertEquals(ConversationQueue.OperatorStop.STOPPED,
                                ConversationQueue.stopOperatorTurn(conversationId, "telegram", false));
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    } finally {
                        hold.release().countDown();
                    }
                    awaitLogged(seen, "Response generated");
                });
                // The drain thread sends just after its reply is generated: 500 ms lets a send that was coming arrive.
                Thread.sleep(500);

                assertEquals(List.of(), sentTexts(telegram), "neither the reply nor a notice");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void aStopWhileTheSyncProloguesTransactionHoldsTheUnsavedMessageStillSendsTheNoticeOnce() throws Exception {
        try (var telegram = new MockTelegramServer()) {
            var token = bindTelegram(telegram);
            try {
                var conversationId = conversation("telegram", PEER);
                var agent = agent();
                long generation = ConversationQueue.tryAcquireOwnership(conversationId,
                        new ConversationQueue.QueuedMessage("running", "telegram", PEER, agent), null);
                assertFalse(ConversationQueue.tryAcquire(conversationId,
                        new ConversationQueue.QueuedMessage(ASK, "telegram", PEER, agent)));
                var handed = ConversationQueue.drain(conversationId, generation);
                var inPrologue = new CountDownLatch(1);
                var stopped = new CountDownLatch(1);
                var stop = new AtomicReference<ConversationQueue.OperatorStop>();
                // Logged inside the prologue's transaction, after its USER row was written and before it commits.
                EventLogger.captureMatchingForTest(e -> {
                    if (agent.name.equals(e.agentId()) && e.message().startsWith("Calling ")) {
                        inPrologue.countDown();
                        try {
                            stopped.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return false;
                }, _ -> {
                    QueueDrainOrchestrator.runOwned(conversationId, generation, handed);
                    try {
                        assertTrue(inPrologue.await(30, TimeUnit.SECONDS), "test precondition: the prologue ran");
                        var stopper = Thread.ofPlatform().start(() ->
                                stop.set(ConversationQueue.stopOperatorTurn(conversationId, "telegram")));
                        stopper.join(10_000);
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    } finally {
                        stopped.countDown();
                    }
                });
                awaitRequest(telegram, "sendMessage");
                // The drained turn ends at its next checkpoint: 500 ms lets a second notice that was coming arrive.
                Thread.sleep(500);

                assertEquals(ConversationQueue.OperatorStop.STOPPED, stop.get());
                assertEquals(List.of(AgentRunner.STOP_NOTICE), sentTexts(telegram));
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    /** The stop sends its notice off its own thread: waits, up to 10 s, for it. */
    private static void awaitSent(List<String> sent) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sent.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
        Thread.sleep(300);
    }

    private static List<String> sentTexts(MockTelegramServer telegram) {
        return telegram.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .map(r -> JsonParser.parseString(r.body()).getAsJsonObject().get("text").getAsString())
                .toList();
    }

    /** Runs {@code turn} on its own thread, stops it once it calls the model, then lets the call answer and waits. */
    private void stopWhileTheModelIsCalled(Long conversationId, Runnable turn) throws InterruptedException {
        stopWhileTheModelIsCalled(conversationId, turn, ConversationQueue::stop);
    }

    /** {@link #stopWhileTheModelIsCalled(Long, Runnable)} with {@code stop} as the stop, true when it stopped a turn. */
    private void stopWhileTheModelIsCalled(Long conversationId, Runnable turn, Predicate<Long> stop)
            throws InterruptedException {
        hold = new Hold();
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                turn.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        try {
            assertTrue(hold.reached().await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
            assertTrue(stop.test(conversationId), "the turn holds a flag of its own");
        } finally {
            hold.release().countDown();
        }
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the stopped turn ends within 30s");
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static void awaitLogged(List<EventLogger.Captured> seen, String prefix) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (seen.stream().noneMatch(e -> e.message().startsWith(prefix)) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(seen.stream().anyMatch(e -> e.message().startsWith(prefix)), "never logged: " + prefix);
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
        var held = hold;
        if (held != null) {
            held.reached().countDown();
            try {
                held.release().await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
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
