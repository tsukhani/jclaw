import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;
import services.ConversationTurnStream;
import services.Tx;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * JF-201: {@code GET /api/conversations/{id}/stream}. The operator's session gets the open comment at once, then
 * what it missed of the turn in progress after the last id it names, then every frame live, each event's id and data
 * in one chunk; anyone else is refused.
 */
class ApiConversationStreamTest extends FunctionalTest {

    private static final String OPEN = ": open\n\n";
    private static final String INIT = "{\"type\":\"init\",\"conversationId\":1}";
    private static final String COMPLETE = "{\"type\":\"complete\",\"content\":\"done\"}";
    private static final String RESYNC = "{\"type\":\"resync\"}";

    private Map<String, Http.Cookie> session;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        session = null;
    }

    @Test
    void anAgentPrincipalIsRefused() {
        var conversation = seedConversation();
        var request = newRequest();
        request.headers.put("authorization",
                new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));

        assertStatus(403, GET(request, streamPath(conversation)));
        assertFalse(ConversationTurnStream.holdsState(conversation));
    }

    @Test
    void aRequestWithNoSessionIsRefused() {
        var conversation = seedConversation();

        assertStatus(401, GET(streamPath(conversation)));
    }

    @Test
    void aConversationThatDoesNotExistIsNotFound() {
        login();
        long missing = seedConversation() + 1_000;

        assertStatus(404, GET(streamPath(missing)));
        assertFalse(ConversationTurnStream.holdsState(missing));
    }

    @Test
    void anIdleConversationSendsTheOpenCommentAtOnceThenNothingUntilATurnStarts() {
        login();
        var conversation = seedConversation();
        var publisher = Thread.ofPlatform().start(() -> {
            awaitSubscribers(conversation, 1);
            sleepQuietly(300);
            var turn = ConversationTurnStream.begin(conversation, ConversationTurnStreamTest.generation());
            turn.publish(bytes(INIT));
            turn.end(bytes(COMPLETE));
        });

        var chunks = read(conversation, "", Map.of(), heard -> heard.getLast().contains(COMPLETE));

        assertEquals(3, chunks.size(), "the open comment, then the turn's two frames: " + chunks);
        assertEquals(OPEN, chunks.getFirst());
        var init = ConversationTurnStreamTest.parse(chunks.get(1));
        var complete = ConversationTurnStreamTest.parse(chunks.get(2));
        assertEquals(INIT, init.data());
        assertEquals("id: " + complete.id() + "\ndata: " + COMPLETE + "\n\n", chunks.get(2),
                "an event's id and its data come in one chunk");
        assertTrue(complete.id() > init.id());
        join(publisher);
        awaitNoSubscriber(conversation);
    }

    @Test
    void theTurnInProgressIsReplayedAfterTheLastEventIdParameterThenStreamedLive() {
        login();
        var conversation = seedConversation();
        var ids = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribeIds = ConversationTurnStream.subscribe(conversation, null, ids);
        var turn = ConversationTurnStream.begin(conversation, ConversationTurnStreamTest.generation());
        try {
            turn.publish(bytes(INIT));
            turn.publish(bytes(token("a")));
            turn.publish(bytes(token("b")));
            var seen = ids.awaitData(token("b"));
            var publisher = Thread.ofPlatform().start(() -> {
                awaitSubscribers(conversation, 2);
                turn.publish(bytes(token("c")));
                turn.end(bytes(COMPLETE));
            });

            var chunks = read(conversation, "lastEventId=" + seen.getFirst().id(), Map.of(),
                    heard -> heard.getLast().contains(COMPLETE));

            var all = ids.awaitData(COMPLETE);
            assertEquals(List.of(OPEN, event(all.get(1)), event(all.get(2)), event(all.get(3)), event(all.get(4))),
                    chunks);
            join(publisher);
        } finally {
            turn.end(bytes(COMPLETE));
            unsubscribeIds.run();
        }
        awaitNoSubscriber(conversation);
    }

    @Test
    void theLastEventIdHeaderWinsOverTheParameter() {
        login();
        var conversation = seedConversation();
        var ids = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribeIds = ConversationTurnStream.subscribe(conversation, null, ids);
        var turn = ConversationTurnStream.begin(conversation, ConversationTurnStreamTest.generation());
        try {
            turn.publish(bytes(INIT));
            turn.publish(bytes(token("a")));
            turn.publish(bytes(token("b")));
            var seen = ids.awaitData(token("b"));

            var chunks = read(conversation, "lastEventId=" + seen.getFirst().id(),
                    Map.of("last-event-id", String.valueOf(seen.get(1).id())),
                    heard -> heard.getLast().contains(token("b")));

            assertEquals(List.of(OPEN, event(seen.get(2))), chunks);
        } finally {
            turn.end(bytes(COMPLETE));
            unsubscribeIds.run();
        }
        awaitNoSubscriber(conversation);
    }

    @Test
    void aLastEventIdFromBeforeTheTurnGetsAResyncFirstAndOneThatIsNoNumberGetsTheWholeTurn() {
        login();
        var conversation = seedConversation();
        var ids = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribeIds = ConversationTurnStream.subscribe(conversation, null, ids);
        var turn = ConversationTurnStream.begin(conversation, ConversationTurnStreamTest.generation());
        try {
            turn.publish(bytes(INIT));
            turn.publish(bytes(token("a")));
            var seen = ids.awaitData(token("a"));

            var stale = read(conversation, "lastEventId=1", Map.of(), heard -> heard.getLast().contains(token("a")));
            var garbled = read(conversation, "lastEventId=abc", Map.of(), heard -> heard.getLast().contains(token("a")));

            assertEquals(4, stale.size(), "the open comment, a resync, then the turn's two frames: " + stale);
            assertEquals(RESYNC, ConversationTurnStreamTest.parse(stale.get(1)).data());
            assertEquals(List.of(event(seen.get(0)), event(seen.get(1))), stale.subList(2, 4));
            assertEquals(List.of(OPEN, event(seen.get(0)), event(seen.get(1))), garbled);
        } finally {
            turn.end(bytes(COMPLETE));
            unsubscribeIds.run();
        }
        awaitNoSubscriber(conversation);
    }

    @Test
    void aWebTurnsHarnessBridgeIsRegisteredWhileItStreamsAndClearedOnceItEnds() throws Exception {
        login();
        var provider = "stream-bridge-" + UUID.randomUUID().toString().substring(0, 8);
        var llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/chat/completions", exchange -> LlmReplyFixture.send(exchange, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"It is now."},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}"""));
        llm.start();
        try {
            inFreshTx(() -> {
                ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llm.getAddress().getPort());
                ConfigService.set("provider." + provider + ".apiKey", "sk-test");
                ConfigService.set("provider." + provider + ".models",
                        "[{\"id\":\"bridge-model\",\"name\":\"Bridge\",\"contextWindow\":100000,\"maxTokens\":4096}]");
                return null;
            });
            for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
            long agentId = inFreshTx(() -> {
                var agent = new Agent();
                agent.name = "bridge-agent-" + UUID.randomUUID().toString().substring(0, 8);
                agent.modelProvider = provider;
                agent.modelId = "bridge-model";
                agent.enabled = true;
                agent.save();
                return agent.id;
            });
            var conversationId = new AtomicReference<Long>();
            var registeredWhileStreaming = new AtomicReference<Boolean>();
            var request = newRequest();
            request.method = "POST";
            request.path = "/api/chat/stream";
            request.url = request.path;
            request.querystring = "";
            request.contentType = "application/json";
            request.body = new ByteArrayInputStream(("{\"agentId\": " + agentId + ", \"message\": \"What time is it?\"}")
                    .getBytes(StandardCharsets.UTF_8));
            request.cookies = session;
            var response = new Http.Response();
            response.out = new ByteArrayOutputStream();
            var chunks = new CopyOnWriteArrayList<String>();
            response.onWriteChunk(chunk -> {
                var text = new String((byte[]) chunk, StandardCharsets.UTF_8);
                chunks.add(text);
                if (!text.startsWith("data: ")) return;
                var frame = JsonParser.parseString(text.substring("data: ".length()).strip()).getAsJsonObject();
                var type = frame.get("type").getAsString();
                if ("init".equals(type)) conversationId.set(frame.get("conversationId").getAsLong());
                if ("token".equals(type)) {
                    registeredWhileStreaming.compareAndSet(null,
                            SubagentSpawnTool.chatCallbacksForTest(conversationId.get()) != null);
                }
            });

            makeRequest(request, response);

            assertEquals(200, response.status.intValue());
            assertNotNull(conversationId.get(), "the turn opened: " + chunks);
            assertEquals(Boolean.TRUE, registeredWhileStreaming.get(), "the bridge is registered once init is out");
            assertNull(SubagentSpawnTool.chatCallbacksForTest(conversationId.get()), "and gone once the turn ends");
        } finally {
            llm.stop(0);
            inFreshTx(() -> {
                for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                    ConfigService.delete("provider." + provider + suffix);
                }
                return null;
            });
            ProviderRegistry.refresh();
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
        }
    }

    // --- helpers ---

    private void login() {
        var response = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(response);
        session = response.cookies;
    }

    private static String streamPath(long conversationId) {
        return "/api/conversations/" + conversationId + "/stream";
    }

    /** The chunks the stream writes, until {@code done} holds for them; the handler's throw is the client leaving. */
    private List<String> read(long conversationId, String query, Map<String, String> headers,
                              Predicate<List<String>> done) {
        var request = newRequest();
        request.method = "GET";
        request.path = streamPath(conversationId);
        request.url = query.isEmpty() ? request.path : request.path + "?" + query;
        request.querystring = query;
        request.body = new ByteArrayInputStream(new byte[0]);
        request.cookies = session;
        headers.forEach((name, value) -> request.headers.put(name, new Http.Header(name, value)));
        var response = new Http.Response();
        response.out = new ByteArrayOutputStream();
        var chunks = new CopyOnWriteArrayList<String>();
        // A heartbeat comes every 15 s, so a stream that never sends what the test waits for still ends.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        response.onWriteChunk(chunk -> {
            chunks.add(new String((byte[]) chunk, StandardCharsets.UTF_8));
            if (done.test(chunks) || System.nanoTime() > deadline) {
                throw new IllegalStateException("client disconnected");
            }
        });

        makeRequest(request, response);

        assertEquals(200, response.status.intValue());
        return List.copyOf(chunks);
    }

    private static String event(ConversationTurnStreamTest.Event event) {
        return "id: " + event.id() + "\ndata: " + event.data() + "\n\n";
    }

    private static String token(String content) {
        return "{\"type\":\"token\",\"content\":\"" + content + "\"}";
    }

    private static byte[] bytes(String frame) {
        return frame.getBytes(StandardCharsets.UTF_8);
    }

    private static void awaitSubscribers(long conversationId, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ConversationTurnStream.subscriberCount(conversationId) < count && System.nanoTime() < deadline) {
            sleepQuietly(10);
        }
    }

    /** The stream's close hook unsubscribes it on the request's thread, after the handler threw. */
    private static void awaitNoSubscriber(long conversationId) {
        awaitCondition(() -> !ConversationTurnStream.holdsState(conversationId));
        assertFalse(ConversationTurnStream.holdsState(conversationId), "the closed stream left no state behind");
    }

    private static void awaitCondition(Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.get() && System.nanoTime() < deadline) sleepQuietly(10);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Committed on its own thread, since the request reads it in a transaction of its own. */
    private static long seedConversation() {
        return inFreshTx(() -> {
            var agent = new Agent();
            agent.name = "stream-agent-" + UUID.randomUUID().toString().substring(0, 8);
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var conversation = new Conversation();
            conversation.agent = agent;
            conversation.channelType = "web";
            conversation.peerId = "admin";
            conversation.save();
            return conversation.id;
        });
    }

    private static <T> T inFreshTx(Supplier<T> body) {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().start(() -> {
            try {
                result.set(Tx.run(body::get));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        join(thread);
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
