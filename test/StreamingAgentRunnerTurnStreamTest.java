import agents.AgentRunner;
import agents.RunCancelledException;
import agents.TurnBroadcast;
import agents.TurnFrames;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.ConversationTurnStream;
import services.SubagentRegistry;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-200: what the agent runners publish to a conversation's watchers. A streamed turn, whichever surface streams it,
 * publishes every frame its own stream gets, from {@code init} to one terminal, and a turn that never acquired the
 * conversation publishes nothing. A background turn publishes its start, each round's tool frames and its end, never
 * a token, and its terminal precedes the next drained turn's first frame.
 */
class StreamingAgentRunnerTurnStreamTest extends UnitTest {

    private static final String MODEL = "turn-stream-model";
    private static final String REPLY = "It is now.";
    private static final String QUEUED = "Your message has been queued and will be processed shortly.";
    private static final byte[] NONE = new byte[0];
    private static final String MARK = "{\"type\":\"status\",\"content\":\"marker turn\"}";

    private final AtomicInteger calls = new AtomicInteger();
    private volatile Responder responder;
    private volatile int toolRounds;
    private HttpServer llmServer;
    private String provider;

    private interface Responder {
        void respond(HttpExchange exchange, JsonObject request) throws Exception;
    }

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        toolRounds = 1;
        responder = this::toolRoundsThenReply;
        provider = "turn-stream-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            try {
                responder.respond(exchange, JsonParser.parseString(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (Exception e) {
                exchange.close();
            }
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Turn stream\",\"contextWindow\":100000,\"maxTokens\":4096}]");
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

    // --- streamed turns ---

    @Test
    void twoWatchersOfAWebTurnGetEveryFrameItsTabGets() throws Exception {
        watchersGetEveryFrameOfAStreamedTurn("web");
    }

    @Test
    void twoWatchersOfATelegramTurnGetEveryFrameItsStreamGets() throws Exception {
        watchersGetEveryFrameOfAStreamedTurn("telegram");
    }

    @Test
    void twoWatchersOfAVoiceTurnGetEveryFrameItsStreamGets() throws Exception {
        watchersGetEveryFrameOfAStreamedTurn("voice");
    }

    private void watchersGetEveryFrameOfAStreamedTurn(String surface) throws Exception {
        var convo = seedConversation(surface, surface + "-watched-" + UUID.randomUUID());
        var first = new ConversationTurnStreamTest.RecordingSink();
        var second = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribeFirst = ConversationTurnStream.subscribe(convo.id, null, first);
        var unsubscribeSecond = ConversationTurnStream.subscribe(convo.id, null, second);
        var tab = new Tab(convo.agent);

        streamAndAwait(convo, surface, new AtomicBoolean(), tab);

        var expected = tab.frames();
        assertEquals(json(TurnFrames.complete(REPLY)), expected.getLast(), "test precondition: the tab got the reply");
        var heard = first.awaitData(expected.getLast());
        assertEquals(normalized(expected), normalized(ConversationTurnStreamTest.data(heard)));
        assertEquals("init", type(heard.getFirst().data()));
        assertEquals(json(TurnFrames.complete(REPLY)), heard.getLast().data());
        for (int i = 1; i < heard.size(); i++) assertTrue(heard.get(i).id() > heard.get(i - 1).id());
        assertEquals(heard, second.awaitData(expected.getLast()));
        unsubscribeFirst.run();
        unsubscribeSecond.run();
        assertFalse(ConversationTurnStream.holdsState(convo.id), "the ended turn and its gone watchers leave nothing");
    }

    @Test
    void aTabWhoseCompleteThrowsLeavesItsWatchersOneTerminal() throws Exception {
        var convo = seedConversation("web", "web-throwing-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var tab = new Tab(convo.agent) {
            @Override
            void complete(String content) {
                super.complete(content);
                throw new IllegalStateException("the tab's write failed");
            }
        };

        streamAndAwait(convo, "web", new AtomicBoolean(), tab);
        assertTrue(tab.errored.await(10, TimeUnit.SECONDS), "the throw re-entered onError");
        assertEquals("the tab's write failed", tab.error.get().getMessage());
        marker(convo.id);

        var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
        assertEquals(json(TurnFrames.complete(REPLY)), heard.get(heard.size() - 2));
        assertEquals(1, heard.stream().filter(StreamingAgentRunnerTurnStreamTest::isTerminal).count(),
                "one terminal, the complete: " + heard);
        unsubscribe.run();
    }

    @Test
    void aTurnQueuedBehindAnotherPublishesNothing() throws Exception {
        var convo = seedConversation("web", "web-queued-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var held = ConversationQueue.tryAcquireOwnership(convo.id,
                new ConversationQueue.QueuedMessage("holding", "web", convo.peerId, convo.agent), null);
        assertTrue(held >= 0, "test precondition: the test holds the conversation");
        var tab = new Tab(convo.agent);
        try {
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, "What time is it?",
                    new AtomicBoolean(), tab.callbacks(), null);
            assertTrue(tab.ended.await(30, TimeUnit.SECONDS));
            assertEquals(json(TurnFrames.complete(QUEUED)), tab.frames().getLast());
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
        marker(convo.id);

        assertEquals(List.of(MARK), ConversationTurnStreamTest.data(sink.awaitData(MARK)));
        assertEquals(0, calls.get(), "the queued turn never reached the model");
        unsubscribe.run();
    }

    @Test
    void aTurnOnAConversationThatIsGonePublishesNothing() throws Exception {
        var convo = seedConversation("web", "web-gone-" + UUID.randomUUID());
        long missing = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(missing, null, sink);
        var tab = new Tab(convo.agent);

        AgentRunner.runStreaming(convo.agent, missing, "web", convo.peerId, "What time is it?",
                new AtomicBoolean(), tab.callbacks(), null);
        assertTrue(tab.ended.await(30, TimeUnit.SECONDS));
        assertNotNull(tab.error.get());
        marker(missing);

        assertEquals(List.of(MARK), ConversationTurnStreamTest.data(sink.awaitData(MARK)));
        unsubscribe.run();
        assertFalse(ConversationTurnStream.holdsState(missing));
    }

    @Test
    void aTokenThatArrivesAfterTheCancelFlagFlipsEndsTheWatchersTurnInsteadOfReachingThem() throws Exception {
        var rest = new CountDownLatch(1);
        var end = new CountDownLatch(1);
        responder = holdAfterFirstToken(rest, end);
        var convo = seedConversation("web", "web-cancelled-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var cancelled = new AtomicBoolean();
        var tab = new Tab(convo.agent);
        try {
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, "Say hello",
                    cancelled, tab.callbacks(), null);
            awaitToken(sink, "Hel");
            cancelled.set(true);
            rest.countDown();
            tab.awaitToken("lo");
            assertEquals(1, tab.ended.getCount(), "test precondition: the runner has not looked at its flag yet");
            var heard = ConversationTurnStreamTest.data(sink.awaitData(STOPPED));
            assertEquals(List.of("init", "token", "stopped"), heard.stream().map(StreamingAgentRunnerTurnStreamTest::type).toList());
            assertEquals("Hel", field(heard.get(1), "content"));
            end.countDown();
            assertTrue(tab.ended.await(15, TimeUnit.SECONDS), "the runner notices the flag once the stream ends");
        } finally {
            rest.countDown();
            end.countDown();
        }
        awaitIdle(convo.id);
        marker(convo.id);

        var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
        assertEquals(List.of(STOPPED, MARK), heard.subList(2, heard.size()), "the runner's own cancel adds nothing");
        unsubscribe.run();
    }

    @Test
    void anOperatorStopEndsTheWatchersTurnAtOnce() throws Exception {
        var rest = new CountDownLatch(1);
        responder = holdAfterFirstToken(rest, new CountDownLatch(0));
        var convo = seedConversation("web", "web-operator-stop-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var tab = new Tab(convo.agent);
        try {
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, "Say hello",
                    new AtomicBoolean(), tab.callbacks(), null);
            awaitToken(sink, "Hel");
            var stopped = new AtomicBoolean();
            var stopping = Thread.ofVirtual().start(() -> stopped.set(ConversationQueue.stop(convo.id)));
            stopping.join(10_000);
            assertTrue(stopped.get(), "the stop found the turn");

            var heard = ConversationTurnStreamTest.data(sink.awaitData(STOPPED));
            assertEquals(List.of("init", "token", "stopped"), heard.stream().map(StreamingAgentRunnerTurnStreamTest::type).toList());
            assertEquals(1, tab.ended.getCount(), "the watchers heard before the runner looked at its flag");
            assertNull(ConversationTurnStream.current(convo.id));
        } finally {
            rest.countDown();
        }
        assertTrue(tab.ended.await(15, TimeUnit.SECONDS));
        awaitIdle(convo.id);
        marker(convo.id);
        assertEquals(List.of(STOPPED, MARK), ConversationTurnStreamTest.data(sink.awaitData(MARK)).subList(2, 4));
        unsubscribe.run();
    }

    @Test
    void aStreamedTurnsReasoningReachesItsWatchersAsItsTabGetsIt() throws Exception {
        toolRounds = 0;
        responder = (exchange, request) -> LlmReplyFixture.send(exchange, """
                {"choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"Checking the clock.",
                "content":"It is now."},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}""");
        var convo = seedConversation("web", "web-reasoning-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var tab = new Tab(convo.agent);

        streamAndAwait(convo, "web", new AtomicBoolean(), tab);

        var expected = tab.frames();
        assertTrue(expected.contains(new String(TurnFrames.chunk(NONE, TurnFrames.Chunk.REASONING, "Checking the clock.",
                NONE), StandardCharsets.UTF_8)), "test precondition: the tab got the reasoning: " + expected);
        assertEquals(normalized(expected), normalized(ConversationTurnStreamTest.data(sink.awaitData(expected.getLast()))));
        unsubscribe.run();
    }

    @Test
    void aStreamedTurnThatFailsAfterInitEndsItsWatchersOnTheErrorItsTabGets() throws Exception {
        responder = (exchange, request) -> {
            var body = "{\"error\":{\"message\":\"model is busy\",\"type\":\"invalid_request_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        };
        var convo = seedConversation("web", "web-failing-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var tab = new Tab(convo.agent);

        var error = streamAndAwait(convo, "web", new AtomicBoolean(), tab);

        assertNotNull(error, "test precondition: the turn failed");
        var heard = ConversationTurnStreamTest.data(sink.awaitData(json(TurnFrames.error(error))));
        assertEquals("init", type(heard.getFirst()));
        assertEquals(json(TurnFrames.error(error)), heard.getLast());
        unsubscribe.run();
    }

    // --- background turns ---

    @Test
    void aBackgroundTurnPublishesItsStartItsToolRoundAndItsReplyButNoToken() throws Exception {
        var convo = seedConversation("web", "web-background-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);

        var result = runInBackground(convo, "What time is it?");

        assertEquals(REPLY, result.response());
        var heard = ConversationTurnStreamTest.data(sink.awaitData(json(TurnFrames.complete(REPLY))));
        assertEquals(List.of("init", "status", "tool_call", "complete"), heard.stream().map(StreamingAgentRunnerTurnStreamTest::type).toList());
        assertEquals(json(TurnFrames.init(convo.agent, convo)), heard.get(0));
        assertEquals(json(TurnFrames.status("Using tool: datetime")), heard.get(1));
        assertEquals("datetime", field(heard.get(2), "name"));
        unsubscribe.run();
        assertFalse(ConversationTurnStream.holdsState(convo.id));
    }

    @Test
    void aLongToolOnlyTurnSendsEachRoundsFramesOnce() throws Exception {
        toolRounds = 5;
        var convo = seedConversation("web", "web-rounds-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);

        runInBackground(convo, "What time is it?");

        var heard = ConversationTurnStreamTest.data(sink.awaitData(json(TurnFrames.complete(REPLY))));
        var expectedTypes = new ArrayList<String>();
        expectedTypes.add("init");
        for (int i = 0; i < 5; i++) {
            expectedTypes.add("status");
            expectedTypes.add("tool_call");
        }
        expectedTypes.add("complete");
        assertEquals(expectedTypes, heard.stream().map(StreamingAgentRunnerTurnStreamTest::type).toList());
        assertEquals(List.of("call_0", "call_1", "call_2", "call_3", "call_4"), heard.stream()
                .filter(f -> "tool_call".equals(type(f))).map(f -> field(f, "id")).toList());
        unsubscribe.run();
    }

    @Test
    void aStreamedTurnsTerminalIsPublishedBeforeTheDrainedNextTurnsFirstFrame() throws Exception {
        var hold = new CountDownLatch(1);
        var firstRequest = new CountDownLatch(1);
        responder = (exchange, request) -> {
            firstRequest.countDown();
            hold.await(30, TimeUnit.SECONDS);
            toolRoundsThenReply(exchange, request);
        };
        var convo = seedConversation("web", "web-drained-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var streamed = new Tab(convo.agent);
        var queued = new Tab(convo.agent);
        try {
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, "What time is it?",
                    new AtomicBoolean(), streamed.callbacks(), null);
            assertTrue(firstRequest.await(30, TimeUnit.SECONDS));
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, "And now?",
                    new AtomicBoolean(), queued.callbacks(), null);
            assertTrue(queued.ended.await(30, TimeUnit.SECONDS));
            assertEquals(json(TurnFrames.complete(QUEUED)), queued.frames().getLast());
        } finally {
            hold.countDown();
        }
        assertTrue(streamed.ended.await(30, TimeUnit.SECONDS));

        var heard = ConversationTurnStreamTest.data(sink.awaitUntil(
                events -> events.stream().filter(e -> "complete".equals(type(e.data()))).count() == 2,
                "heard both turns end"));
        awaitIdle(convo.id);
        var types = heard.stream().map(StreamingAgentRunnerTurnStreamTest::type).toList();
        int firstComplete = types.indexOf("complete");
        int secondInit = types.lastIndexOf("init");
        assertEquals(0, types.indexOf("init"));
        assertTrue(firstComplete < secondInit, "the streamed turn ended before the drained turn began: " + types);
        assertEquals(normalized(streamed.frames()), normalized(heard.subList(0, firstComplete + 1)));
        assertEquals(List.of("init", "status", "tool_call", "complete"), types.subList(secondInit, types.size()),
                "the drained turn is a background turn: its start, its tool round and its end, no token");
        unsubscribe.run();
    }

    @Test
    void anInlineChildPublishesItsToolRoundIntoItsParentsOpenTurnAndLeavesItOpen() throws Exception {
        var convo = seedConversation("web", "web-inline-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var parent = ConversationTurnStream.begin(convo.id, ConversationTurnStreamTest.generation());
        var result = new AtomicReference<AgentRunner.RunResult>();
        var child = Thread.ofVirtual().start(() ->
                result.set(AgentRunner.runInParentConversation(convo.agent, convo, "What time is it?")));
        child.join(60_000);

        assertEquals(REPLY, result.get().response());
        assertTrue(parent.isOpen(), "the child's end is not its parent's");
        assertSame(parent, ConversationTurnStream.current(convo.id));
        parent.end(MARK.getBytes(StandardCharsets.UTF_8));
        var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
        assertEquals(3, heard.size(), "the child's status and tool call, then the parent's own end: " + heard);
        assertEquals(json(TurnFrames.status("Using tool: datetime")), heard.get(0));
        assertEquals("datetime", field(heard.get(1), "name"));
        assertEquals(MARK, heard.get(2));
        unsubscribe.run();
    }

    @Test
    void aSyncTurnStoppedAtItsCancelCheckpointEndsItsWatchersAsStopped() throws Exception {
        var convo = seedConversation("web", "web-killed-" + UUID.randomUUID());
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var thrown = new AtomicReference<Throwable>();
        var run = Thread.ofVirtual().start(() -> {
            try {
                SubagentRegistry.runAs(new SubagentRegistry.RunScope(-1L, new AtomicBoolean(true)),
                        () -> AgentRunner.run(convo.agent, convo, "What time is it?"));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        run.join(60_000);

        assertInstanceOf(RunCancelledException.class, thrown.get());
        awaitIdle(convo.id);
        marker(convo.id);
        assertEquals(List.of(STOPPED, MARK), ConversationTurnStreamTest.data(sink.awaitData(MARK)));
        assertEquals(0, calls.get(), "the checkpoint comes before the model");
        unsubscribe.run();
    }

    @Test
    void aSyncTurnThatThrowsEndsItsWatchersOnItsClassifiedError() throws Exception {
        var gone = seedConversation("web", "web-vanished-" + UUID.randomUUID());
        var convo = new Conversation();
        convo.id = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        convo.agent = gone.agent;
        convo.channelType = "web";
        convo.peerId = gone.peerId;
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(convo.id, null, sink);
        var thrown = new AtomicReference<Throwable>();
        var run = Thread.ofVirtual().start(() -> {
            try {
                AgentRunner.run(convo.agent, convo, "What time is it?");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        run.join(60_000);

        assertInstanceOf(RuntimeException.class, thrown.get(), "test precondition: the turn threw");
        var expected = json(TurnFrames.error(thrown.get()));
        assertEquals(List.of(expected), ConversationTurnStreamTest.data(sink.awaitData(expected)));
        ConversationQueue.forgetForTest(convo.id);
        unsubscribe.run();
    }

    // --- the broadcast's own rules ---

    @Test
    void aBridgeRelaysWhatItGetsToTheOpenTurnsWatchersWithoutEndingIt() {
        long conversation = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var turn = ConversationTurnStream.begin(conversation, ConversationTurnStreamTest.generation());
        var tab = new Tab(new Agent());
        var bridge = TurnBroadcast.alsoPublishing(conversation, tab.callbacks());
        var toolCall = new AgentRunner.ToolCallEvent(null, "edit_file", "harness", "{}", "", null, null, List.of());

        bridge.onToken().accept("harness says");
        bridge.onReasoning().accept("thinking");
        bridge.onStatus().accept("Subagent still working");
        bridge.onToolCall().accept(toolCall);

        assertTrue(turn.isOpen());
        turn.end(MARK.getBytes(StandardCharsets.UTF_8));
        var expected = new ArrayList<>(tab.frames());
        expected.add(MARK);
        assertEquals(4, tab.frames().size(), "the tab got each one too");
        assertEquals(normalized(expected), normalized(ConversationTurnStreamTest.data(sink.awaitData(MARK))));
        unsubscribe.run();
    }

    @Test
    void theFirstTokenOfATurnCarriesTheChatStreamsTimestampAndLaterOnesDoNot() {
        long conversation = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var broadcast = TurnBroadcast.owning(conversation, ConversationTurnStreamTest.generation());

        broadcast.token("It ");
        broadcast.token("is");
        broadcast.complete(REPLY);

        var heard = ConversationTurnStreamTest.data(sink.awaitData(json(TurnFrames.complete(REPLY))));
        var first = JsonParser.parseString(heard.get(0)).getAsJsonObject();
        assertEquals(Set.of("type", "content", "timestamp"), first.keySet());
        assertEquals("It ", first.get("content").getAsString());
        assertDoesNotThrow(() -> Instant.parse(first.get("timestamp").getAsString()));
        assertEquals(tokenFrame("is"), heard.get(1));
        unsubscribe.run();
    }

    @Test
    void aJoiningBroadcastAddsToTheOpenTurnAndNeverEndsIt() {
        long conversation = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var parent = ConversationTurnStream.begin(conversation, ConversationTurnStreamTest.generation());
        var child = TurnBroadcast.joining(conversation);

        child.status("Using tool: datetime");
        child.complete("the child's reply");
        child.error(new IllegalStateException("child failed"));
        child.stopped();
        assertTrue(parent.isOpen(), "the child's end is not the parent turn's");
        parent.end(MARK.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(json(TurnFrames.status("Using tool: datetime")), MARK),
                ConversationTurnStreamTest.data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    @Test
    void aJoiningBroadcastWithNoTurnOpenPublishesNothingAndKeepsNoState() {
        long conversation = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        var child = TurnBroadcast.joining(conversation);

        child.status("Using tool: datetime");
        child.complete("reply");

        assertFalse(ConversationTurnStream.holdsState(conversation));
    }

    @Test
    void aSyncTurnEndsByHowItEnded() {
        long conversation = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var failure = new IllegalStateException("provider down");

        TurnBroadcast.owning(conversation, ConversationTurnStreamTest.generation()).endWith(new AgentRunner.RunResult(REPLY, null), null);
        TurnBroadcast.owning(conversation, ConversationTurnStreamTest.generation()).endWith(new AgentRunner.RunResult(AgentRunner.YIELDED_RESPONSE, null), null);
        TurnBroadcast.owning(conversation, ConversationTurnStreamTest.generation()).endWith(null, new RunCancelledException(7L));
        TurnBroadcast.owning(conversation, ConversationTurnStreamTest.generation()).endWith(null, failure);
        TurnBroadcast.owning(conversation, ConversationTurnStreamTest.generation()).endWith(null, null);
        marker(conversation);

        assertEquals(List.of(json(TurnFrames.complete(REPLY)), json(TurnFrames.complete("")), "{\"type\":\"stopped\"}",
                        json(TurnFrames.error(failure)),
                        json(TurnFrames.error(new IllegalStateException("the turn ended without a reply"))), MARK),
                ConversationTurnStreamTest.data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    // --- the frames themselves ---

    @Test
    void theChatStreamsDeltaFramesAreTheBytesItAlwaysSent() {
        var data = "data: ".getBytes(StandardCharsets.UTF_8);
        var end = "\n\n".getBytes(StandardCharsets.UTF_8);

        assertEquals("data: {\"type\":\"token\",\"content\":\"a \\\"b\\\"\\n<c> é\"}\n\n",
                new String(TurnFrames.chunk(data, TurnFrames.Chunk.TOKEN, "a \"b\"\n<c> é", end), StandardCharsets.UTF_8));
        assertEquals("data: {\"type\":\"reasoning\",\"content\":\"\"}\n\n",
                new String(TurnFrames.chunk(data, TurnFrames.Chunk.REASONING, "", end), StandardCharsets.UTF_8));
        assertEquals("{\"type\":\"token\",\"content\":\"x\"}",
                new String(TurnFrames.chunk(NONE, TurnFrames.Chunk.TOKEN, "x", NONE), StandardCharsets.UTF_8));
    }

    @Test
    void aToolCallFrameCarriesItsResultAsATreeAndLeavesOutWhatItLacks() {
        var frame = JsonParser.parseString(json(TurnFrames.toolCall(new AgentRunner.ToolCallEvent(
                null, "web_search", "search", "{\"q\":\"x\"}", "two results", "{\"provider\":\"exa\"}", null,
                List.of())))).getAsJsonObject();

        assertEquals(JsonParser.parseString("""
                {"type":"tool_call","name":"web_search","icon":"search","arguments":"{\\"q\\":\\"x\\"}",
                 "resultText":"two results","resultStructured":{"provider":"exa"}}"""), frame);
    }

    // --- helpers ---

    /** A turn after the test's, newer than any generation the queue issues, whose one frame closes what a test waits on. */
    private static void marker(long conversationId) {
        ConversationTurnStream.begin(conversationId, Long.MAX_VALUE).end(MARK.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(Map<String, Object> frame) {
        return new String(TurnFrames.json(frame), StandardCharsets.UTF_8);
    }

    private static final String STOPPED = "{\"type\":\"stopped\"}";

    /** Frames as JSON trees, the first token's timestamp replaced: the tab and the broadcast each stamp their own. */
    private static List<JsonElement> normalized(List<String> frames) {
        var trees = new ArrayList<JsonElement>();
        for (var frame : frames) {
            var tree = JsonParser.parseString(frame).getAsJsonObject();
            if (tree.has("timestamp")) {
                assertDoesNotThrow(() -> Instant.parse(tree.get("timestamp").getAsString()));
                tree.addProperty("timestamp", "stamped");
            }
            trees.add(tree);
        }
        return trees;
    }

    private static void awaitToken(ConversationTurnStreamTest.RecordingSink sink, String content) {
        sink.awaitUntil(events -> events.stream().anyMatch(e -> "token".equals(type(e.data()))
                && content.equals(field(e.data(), "content"))), "heard the token " + content);
    }

    /**
     * A streamed reply whose first token goes out at once, its second once {@code rest} opens, and its end once
     * {@code end} does: until then the runner waits on the stream and looks at its cancel flag only every 5 s.
     */
    private static Responder holdAfterFirstToken(CountDownLatch rest, CountDownLatch end) {
        return (exchange, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(deltaChunk("Hel"));
                out.flush();
                rest.await(30, TimeUnit.SECONDS);
                out.write(deltaChunk("lo"));
                out.flush();
                end.await(30, TimeUnit.SECONDS);
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static String tokenFrame(String content) {
        return new String(TurnFrames.chunk(NONE, TurnFrames.Chunk.TOKEN, content, NONE), StandardCharsets.UTF_8);
    }

    private static String type(String frame) {
        return field(frame, "type");
    }

    private static String field(String frame, String name) {
        var value = JsonParser.parseString(frame).getAsJsonObject().get(name);
        return value == null ? null : value.getAsString();
    }

    private static boolean isTerminal(String frame) {
        var type = type(frame);
        return "complete".equals(type) || "error".equals(type) || "stopped".equals(type);
    }

    private static byte[] deltaChunk(String content) {
        return ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A tool call until {@link #toolRounds} results follow the latest user message, then the reply. */
    private void toolRoundsThenReply(HttpExchange exchange, JsonObject request) throws Exception {
        int answered = 0;
        for (var m : request.getAsJsonArray("messages")) {
            var role = m.getAsJsonObject().get("role").getAsString();
            if ("user".equals(role)) answered = 0;
            else if ("tool".equals(role)) answered++;
        }
        LlmReplyFixture.send(exchange, answered < toolRounds ? toolCallReply("call_" + answered) : FINAL_REPLY);
    }

    private static String toolCallReply(String id) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"%s","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now\\"}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""".formatted(id);
    }

    private static final String FINAL_REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"It is now."},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}""";

    private Conversation seedConversation(String channelType, String peer) {
        var agent = new Agent();
        agent.name = "turn-stream-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        var convo = ConversationService.create(agent, channelType, peer);
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    private static AgentRunner.RunResult runInBackground(Conversation convo, String message) throws Exception {
        var result = new AtomicReference<AgentRunner.RunResult>();
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(AgentRunner.run(convo.agent, convo, message));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.join(60_000);
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertNotNull(result.get(), "the background turn must finish");
        awaitIdle(convo.id);
        return result.get();
    }

    /** Streams {@code convo}'s turn to {@code surface} and returns its error, or {@code null} once it ends without one. */
    private static Exception streamAndAwait(Conversation convo, String surface, AtomicBoolean isCancelled, Tab tab)
            throws InterruptedException {
        AgentRunner.runStreaming(convo.agent, convo.id, surface, convo.peerId, "What time is it?", isCancelled,
                tab.callbacks(), null);
        assertTrue(tab.ended.await(60, TimeUnit.SECONDS), "the turn must reach a terminal callback");
        awaitIdle(convo.id);
        return tab.error.get();
    }

    /** The terminal callback runs after the release, but the turn's thread may still be in its finally. */
    private static void awaitIdle(Long conversationId) throws InterruptedException {
        for (int i = 0; i < 100 && ConversationQueue.isBusy(conversationId); i++) Thread.sleep(50);
        assertFalse(ConversationQueue.isBusy(conversationId), "the turn released its conversation");
        for (int i = 0; i < 100 && ConversationTurnStream.current(conversationId) != null; i++) Thread.sleep(20);
    }

    /** The turn's own stream: records each frame it gets as the JSON the chat stream's builders make of it. */
    static class Tab {
        private final Agent agent;
        private final List<String> frames = new ArrayList<>(); // guarded by this
        private final StringBuilder tokens = new StringBuilder(); // guarded by this
        final CountDownLatch ended = new CountDownLatch(1);
        final CountDownLatch errored = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<>();

        Tab(Agent agent) {
            this.agent = agent;
        }

        AgentRunner.StreamingCallbacks callbacks() {
            return new AgentRunner.StreamingCallbacks(
                    conversation -> record(json(TurnFrames.init(agent, conversation))),
                    token -> {
                        boolean first;
                        synchronized (this) {
                            first = tokens.isEmpty();
                            tokens.append(token);
                            notifyAll();
                        }
                        record(first ? json(TurnFrames.firstToken(token)) : tokenFrame(token));
                    },
                    reasoning -> record(new String(TurnFrames.chunk(NONE, TurnFrames.Chunk.REASONING, reasoning, NONE),
                            StandardCharsets.UTF_8)),
                    status -> record(json(TurnFrames.status(status))),
                    toolCall -> record(json(TurnFrames.toolCall(toolCall))),
                    this::complete,
                    e -> {
                        error.set(e);
                        errored.countDown();
                        ended.countDown();
                    },
                    ended::countDown);
        }

        void complete(String content) {
            record(json(TurnFrames.complete(content)));
            ended.countDown();
        }

        synchronized void record(String frame) {
            frames.add(frame);
        }

        synchronized List<String> frames() {
            return List.copyOf(frames);
        }

        synchronized void awaitToken(String suffix) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!tokens.toString().endsWith(suffix)) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                assertTrue(remainingMs > 0, "the stream never delivered " + suffix + "; got " + tokens);
                wait(remainingMs);
            }
        }
    }
}
