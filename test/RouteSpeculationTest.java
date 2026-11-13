import agents.AgentRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import llm.TokenUsageEstimator;
import llm.routing.ModelRouter;
import llm.routing.RouterPolicy;
import llm.routing.SubscriptionUsage;
import llm.routing.TaskClass;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import utils.AppClock;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.LatencyStats;
import utils.LatencyTrace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-377: a routed turn starts its model call on the previous turn's model while the router decides, keeps it when the
 * router picks that model again, and otherwise discards it unseen and unrun. Three stub providers stand in for the
 * network: the previous turn's model, the model the router may pick instead, and the router's classifier, each held
 * for a known time. Whether a turn speculated is read off the wire: its model request arrives before the classifier
 * has answered.
 *
 * <p>Writes the process-global {@code router.*} keys, so each test holds {@link JevBreakerTestSync}, as
 * {@code ModelRouterTurnTest} does.
 */
class RouteSpeculationTest extends UnitTest {

    private static final String MODEL = "test-model";
    private static final long HOLD_MS = 800;
    private static final String CHAT_LOW = "chat low";

    private interface Responder {
        void respond(HttpExchange exchange, JsonObject request) throws Exception;
    }

    /** One stub provider: what it was asked, when, and how it answers. */
    private static final class Stub {
        final String name;
        final List<JsonObject> requests = new CopyOnWriteArrayList<>();
        final List<Long> arrivals = new CopyOnWriteArrayList<>();
        volatile Responder responder = (exchange, _) -> exchange.close();

        Stub(String name) {
            this.name = name;
        }

        long lastArrival() {
            return arrivals.getLast();
        }
    }

    private HttpServer server;
    private ExecutorService handlers;
    private Stub previous;
    private Stub other;
    private Stub classifier;
    private volatile String classifierAnswer = CHAT_LOW;
    private volatile long classifierHoldMs;
    private final AtomicLong classifierAnsweredAt = new AtomicLong();
    private final CountDownLatch aborted = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        JevBreakerTestSync.acquire();
        var id = UUID.randomUUID().toString().substring(0, 8);
        previous = new Stub("rs-prev-" + id);
        other = new Stub("rs-other-" + id);
        classifier = new Stub("rs-cls-" + id);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The default executor runs one exchange at a time, which would queue the model's request behind the
        // classifier's held one and serialize exactly the two waits under test.
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        for (var stub : List.of(previous, other, classifier)) {
            server.createContext("/" + stub.name + "/chat/completions", exchange -> {
                stub.arrivals.add(System.nanoTime());
                try {
                    var request = JsonParser.parseString(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    stub.requests.add(request);
                    stub.responder.respond(exchange, request);
                } catch (Exception _) {
                    exchange.close();
                }
            });
        }
        classifier.responder = (exchange, _) -> {
            sleep(classifierHoldMs);
            var body = ("{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + MODEL + "\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + classifierAnswer
                    + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,"
                    + "\"total_tokens\":7}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            classifierAnsweredAt.set(System.nanoTime());
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        };
        server.start();
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) server.stop(0);
            if (handlers != null) handlers.shutdown();
            for (var c : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(c));
            for (var key : List.of(RouterPolicy.CLASSIFIER_PROVIDER, RouterPolicy.CLASSIFIER_MODEL, RouterPolicy.SPECULATE)) {
                ConfigService.delete(key);
            }
            for (var stub : List.of(previous, other, classifier)) {
                SubscriptionUsage.clearForTest(stub.name);
                CircuitBreakers.remove(LlmResilience.breakerName(stub.name));
                for (var suffix : new String[] {".baseUrl", ".apiKey", ".models", ".paymentModality"}) {
                    ConfigService.delete("provider." + stub.name + suffix);
                }
            }
            ProviderRegistry.refresh();
        } finally {
            JevBreakerTestSync.release();
        }
    }

    // ─── The router keeps the model ─────────────────────────────────────

    @Test
    void aConfirmedRouteDoesNotAddTheClassifiersWaitToTheFirstToken() throws Exception {
        configure(true);
        var agent = routerAgent();
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        assertEquals("the first reply", turn(agent, convo, "Describe the moon's orbit in a few lines").completed());

        classifierHoldMs = HOLD_MS;
        previous.responder = reply("the kept reply", HOLD_MS);
        var turn = turn(agent, convo, "Now describe the orbit of Mars the same way");

        assertNull(turn.error.get());
        assertEquals("the kept reply", turn.completed());
        assertEquals("the kept reply", turn.tokens.toString());
        assertTrue(previous.lastArrival() < classifierAnsweredAt.get(),
                "the call started before the classifier answered");
        assertTrue(turn.firstTokenMs() >= HOLD_MS && turn.firstTokenMs() < 2 * HOLD_MS - 300,
                "the first token waits for the model's %d ms, not for it and the classifier's %d ms: %d ms"
                        .formatted(HOLD_MS, HOLD_MS, turn.firstTokenMs()));
        assertEquals(2, previous.requests.size(), "one request a turn: the started call was kept");
        assertEquals(0, other.requests.size());
        assertEquals("low", previous.requests.getLast().get("reasoning_effort").getAsString());
        assertEquals(List.of(previous.name), routeStatuses(turn));

        var usage = lastAssistantUsage(convo);
        assertEquals(previous.name, usage.get("modelProvider").getAsString());
        assertEquals("chat", usage.getAsJsonObject("route").get("class").getAsString());
        assertFalse(usage.has("discarded"), "nothing was discarded");
    }

    @Test
    void withSpeculationOffTheFirstTokenWaitsForTheClassifierAsBefore() throws Exception {
        configure(true);
        ConfigService.set(RouterPolicy.SPECULATE, "off");
        var agent = routerAgent();
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        turn(agent, convo, "Describe the moon's orbit in a few lines");

        classifierHoldMs = HOLD_MS;
        previous.responder = reply("the routed reply", HOLD_MS);
        var turn = turn(agent, convo, "Now describe the orbit of Mars the same way");

        assertEquals("the routed reply", turn.completed());
        assertTrue(previous.lastArrival() > classifierAnsweredAt.get(), "the call waited for the route");
        assertTrue(turn.firstTokenMs() >= 2 * HOLD_MS - 100,
                "the two holds add up when the turn routes first: %d ms".formatted(turn.firstTokenMs()));
    }

    // ─── The router picks another model ─────────────────────────────────

    @Test
    void anotherRouteDiscardsTheStartedCallUnseenUnsavedAndUnrun() throws Exception {
        configure(true);
        var agent = routerAgent();
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        turn(agent, convo, "Describe the moon's orbit in a few lines");

        classifierHoldMs = 600;
        classifierAnswer = "reasoning high";
        previous.responder = speculativeTextThenToolCallThenHang();
        other.responder = reply("the chosen reply", 0);
        var turn = new AtomicReference<Turn>();
        var discards = EventLogger.captureMatchingForTest(
                e -> agent.name.equals(e.agentId()) && "router".equals(e.category()) && e.message().startsWith("Discarded"),
                _ -> turn.set(turnUnchecked(agent, convo, "Prove that there are infinitely many primes")));

        assertEquals("the chosen reply", turn.get().completed());
        assertEquals("the chosen reply", turn.get().tokens.toString(), "the discarded call's text never reached the reader");
        assertEquals("", turn.get().reasoning.toString());
        assertEquals(List.of(), turn.get().toolCalls, "the discarded call's tool call was not run");
        assertEquals(List.of(other.name), routeStatuses(turn.get()), "only the chosen route is announced");
        assertTrue(previous.lastArrival() < classifierAnsweredAt.get(), "the turn started on the previous model");
        assertEquals(2, previous.requests.size(), "the discarded call made no continuation round");
        assertEquals(1, other.requests.size());
        assertTrue(aborted.await(5, TimeUnit.SECONDS), "the discarded call's HTTP request was aborted");
        assertEquals(List.of("Discarded the call started on %s/%s while the router decided: the router chose %s/%s"
                .formatted(previous.name, MODEL, other.name, MODEL)), discards.stream().map(EventLogger.Captured::message).toList());

        JPA.em().clear();
        List<Message> rows = Message.find("conversation.id = ?1 ORDER BY createdAt", convo.id).fetch();
        assertEquals(List.of("user", "assistant", "user", "assistant"), rows.stream().map(m -> m.role).toList(),
                "no tool row: the tool call was never run");
        assertEquals("the chosen reply", rows.getLast().content);
        assertNull(rows.getLast().reasoning);

        var usage = lastAssistantUsage(convo);
        assertEquals(other.name, usage.get("modelProvider").getAsString());
        assertEquals(12, usage.get("prompt").getAsInt(), "the reply's own counts are the chosen model's");
        assertEquals(3, usage.get("completion").getAsInt());
        var discarded = usage.getAsJsonObject("discarded");
        assertEquals(previous.name, discarded.get("provider").getAsString());
        assertEquals(MODEL, discarded.get("model").getAsString());
        assertEquals("jtokkit", discarded.get("usageSource").getAsString(), "a cancelled call reports no usage of its own");
        assertTrue(discarded.get("prompt").getAsInt() > 0, discarded.toString());
        assertEquals(TokenUsageEstimator.estimateCompletion(MODEL, "speculative text", List.of(), "").tokens(),
                discarded.get("completion").getAsInt(), "the text the discarded call had streamed");
    }

    @Test
    void theSameModelAtAnotherThinkingLevelRestartsTheCall() throws Exception {
        configure(true);
        var agent = routerAgent();
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        turn(agent, convo, "Describe the moon's orbit in a few lines");

        classifierHoldMs = 400;
        classifierAnswer = "chat high";
        var low = reply("the low-effort reply", 0);
        var high = reply("the high-effort reply", 0);
        previous.responder = (exchange, request) ->
                ("high".equals(request.get("reasoning_effort").getAsString()) ? high : low).respond(exchange, request);
        var turn = turn(agent, convo, "Now describe the orbit of Mars the same way");

        assertEquals("the high-effort reply", turn.completed());
        assertEquals("the high-effort reply", turn.tokens.toString());
        assertEquals(List.of("low", "low", "high"),
                previous.requests.stream().map(r -> r.get("reasoning_effort").getAsString()).toList(),
                "started at the previous turn's effort, restarted at the route's");
        var usage = lastAssistantUsage(convo);
        assertEquals("high", usage.getAsJsonObject("route").get("thinkingMode").getAsString());
        assertEquals(previous.name, usage.getAsJsonObject("discarded").get("provider").getAsString());
    }

    // ─── Turns that route first, as before ──────────────────────────────

    @Test
    void theFirstTurnOfAConversationRoutesFirst() throws Exception {
        configure(true);
        var agent = routerAgent();
        var convo = conversation(agent);
        classifierHoldMs = 400;
        previous.responder = reply("the first reply", 0);

        assertEquals("the first reply", turn(agent, convo, "Describe the moon's orbit in a few lines").completed());
        assertTrue(previous.lastArrival() > classifierAnsweredAt.get(), "no previous route to start on");
        assertFalse(lastAssistantUsage(convo).has("discarded"));
    }

    @Test
    void theFirstTurnAfterAResetRoutesFirst() throws Exception {
        configure(true);
        var agent = routerAgent();
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        turn(agent, convo, "Describe the moon's orbit in a few lines");
        JPA.em().clear();
        Conversation reset = Objects.requireNonNull(ConversationService.findById(convo.id));
        reset.contextSince = AppClock.now();
        reset.save();
        commit();

        classifierHoldMs = 400;
        previous.responder = reply("the reply after the reset", 0);
        assertEquals("the reply after the reset", turn(agent, convo, "Describe the orbit of Mars in a few lines").completed());
        assertTrue(previous.lastArrival() > classifierAnsweredAt.get(), "a reset leaves no previous route");
    }

    @Test
    void aTurnOffTheRouterIsUnchanged() throws Exception {
        configure(true);
        var agent = persistAgent(previous.name, MODEL);
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        turn(agent, convo, "Describe the moon's orbit in a few lines");
        previous.responder = reply("the second reply", 0);
        var turn = turn(agent, convo, "Now describe the orbit of Mars the same way");

        assertEquals("the second reply", turn.completed());
        assertEquals(0, classifier.requests.size(), "no router, no classifier");
        assertEquals(2, previous.requests.size());
        assertEquals(List.of(), routeStatuses(turn));
        assertFalse(lastAssistantUsage(convo).has("route"));
    }

    @Test
    void aPerTokenProviderRoutesFirstUnlessTheOperatorAllowsSpeculation() throws Exception {
        configure(false);
        var agent = routerAgent();
        var convo = conversation(agent);
        previous.responder = reply("the first reply", 0);
        turn(agent, convo, "Describe the moon's orbit in a few lines");

        classifierHoldMs = 400;
        previous.responder = reply("the second reply", 0);
        assertEquals("the second reply", turn(agent, convo, "Now describe the orbit of Mars the same way").completed());
        assertTrue(previous.lastArrival() > classifierAnsweredAt.get(), "a discarded call would be billed");

        ConfigService.set(RouterPolicy.SPECULATE, "all");
        previous.responder = reply("the third reply", 0);
        assertEquals("the third reply", turn(agent, convo, "And then the orbit of Venus, please").completed());
        assertTrue(previous.lastArrival() < classifierAnsweredAt.get(), "router.speculate=all starts it at once");
        assertEquals(3, previous.requests.size());
    }

    // ─── The setting, the segments and the breaker ──────────────────────

    @Test
    void theSettingAcceptsItsThreeModesAndRefusesAnythingElse() {
        for (var mode : List.of("prepaid", "all", "off")) {
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.SPECULATE, mode), mode);
        }
        assertEquals("router.speculate must be prepaid, all or off.",
                RouterPolicy.rejectionFor(RouterPolicy.SPECULATE, "sometimes"));
        assertEquals("router.speculate must be prepaid, all or off.", RouterPolicy.rejectionFor(RouterPolicy.SPECULATE, null));
    }

    @Test
    void aKeptCallAndADiscardedOneEachRecordTheirOwnSegment() {
        var keptChannel = "rs-kept-" + System.nanoTime();
        var kept = LatencyTrace.forTurn(keptChannel, null);
        kept.recordRouteSpeculation(true, 377);
        kept.mark(LatencyTrace.PROLOGUE_DONE);
        kept.end();
        var discardedChannel = "rs-discarded-" + System.nanoTime();
        var discarded = LatencyTrace.forTurn(discardedChannel, null);
        discarded.recordRouteSpeculation(false, 412);
        discarded.mark(LatencyTrace.PROLOGUE_DONE);
        discarded.end();
        var plainChannel = "rs-plain-" + System.nanoTime();
        var plain = LatencyTrace.forTurn(plainChannel, null);
        plain.mark(LatencyTrace.PROLOGUE_DONE);
        plain.end();

        var stats = LatencyStats.snapshot();
        var hit = stats.getAsJsonObject(keptChannel).getAsJsonObject("route_speculation_hit");
        assertEquals(1L, hit.get("count").getAsLong());
        assertEquals(377L, hit.get("sum_ms").getAsLong());
        assertFalse(stats.getAsJsonObject(keptChannel).has("route_speculation_miss"));
        var miss = stats.getAsJsonObject(discardedChannel).getAsJsonObject("route_speculation_miss");
        assertEquals(1L, miss.get("count").getAsLong());
        assertEquals(412L, miss.get("sum_ms").getAsLong());
        assertFalse(stats.getAsJsonObject(discardedChannel).has("route_speculation_hit"));
        assertFalse(stats.getAsJsonObject(plainChannel).has("route_speculation_hit"));
        assertFalse(stats.getAsJsonObject(plainChannel).has("route_speculation_miss"));
    }

    @Test
    void aCancelledStreamChargesTheBreakerNothingAndHandsAProbeBack() {
        var closed = new CircuitBreaker(CircuitBreaker.Config.of(10, 0.5, 1, 60_000));
        LlmResilience.streamGuardForTest(closed, System::nanoTime, 0, 0).cancelled();
        assertEquals(0, closed.stats().samples(), "a cancel is no evidence about the provider");

        var clock = new AtomicLong(0);
        var probing = new CircuitBreaker(CircuitBreaker.Config.of(10, 0.5, 1, 1_000), clock::get);
        probing.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, probing.state());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertTrue(probing.allowRequest(), "test precondition: the probe takes the half-open permit");
        LlmResilience.streamGuardForTest(probing, clock::get, 0, 0).cancelled();
        assertEquals(CircuitBreaker.State.CLOSED, probing.state(), "the permit came back rather than wedging the breaker");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static final class Turn {
        final long startNanos = System.nanoTime();
        final AtomicLong firstTokenNanos = new AtomicLong();
        final StringBuffer tokens = new StringBuffer();
        final StringBuffer reasoning = new StringBuffer();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final List<AgentRunner.ToolCallEvent> toolCalls = new CopyOnWriteArrayList<>();
        final AtomicReference<String> completed = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        String completed() {
            return completed.get();
        }

        long firstTokenMs() {
            return TimeUnit.NANOSECONDS.toMillis(firstTokenNanos.get() - startNanos);
        }
    }

    private static Turn turn(Agent agent, Conversation convo, String text) throws InterruptedException {
        var t = new Turn();
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {},
                token -> {
                    t.firstTokenNanos.compareAndSet(0, System.nanoTime());
                    t.tokens.append(token);
                },
                t.reasoning::append,
                t.statuses::add,
                t.toolCalls::add,
                content -> {
                    t.completed.set(content);
                    t.done.countDown();
                },
                e -> {
                    t.error.set(e);
                    t.done.countDown();
                },
                t.done::countDown);
        AgentRunner.runStreaming(agent, convo.id, convo.channelType, convo.peerId, text, new AtomicBoolean(false), cb, null);
        assertTrue(t.done.await(60, TimeUnit.SECONDS), "the turn must reach a terminal callback");
        assertNull(t.error.get(), () -> "unexpected error: " + t.error.get());
        return t;
    }

    private static Turn turnUnchecked(Agent agent, Conversation convo, String text) {
        try {
            return turn(agent, convo, text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<String> routeStatuses(Turn turn) {
        return turn.statuses.stream().filter(s -> s.startsWith("{\"route\":"))
                .map(s -> JsonParser.parseString(s).getAsJsonObject().getAsJsonObject("route").get("provider").getAsString())
                .toList();
    }

    /** An SSE reply of {@code text}, its headers sent after {@code holdMs}. */
    private static Responder reply(String text, long holdMs) {
        return (exchange, _) -> {
            sleep(holdMs);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var os = exchange.getResponseBody()) {
                os.write(("data: {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"model\":\"x\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    /** Text and a tool call at once, then the stream held open with keepalives until the client goes away. */
    private Responder speculativeTextThenToolCallThenHang() {
        return (exchange, _) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var os = exchange.getResponseBody();
            try {
                os.write(("data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"model\":\"x\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"speculative text\"}}]}\n\n"
                        + "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"model\":\"x\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"datetime\",\"arguments\":\"{}\"}}]}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                for (int i = 0; i < 120; i++) {
                    sleep(50);
                    os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.close();
            } catch (IOException _) {
                aborted.countDown();
                exchange.close();
            }
        };
    }

    private void configure(boolean prepaid) {
        var port = server.getAddress().getPort();
        var models = "[{\"id\":\"" + MODEL + "\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096,"
                + "\"supportsThinking\":true,\"thinkingLevels\":[\"low\",\"high\"]}]";
        for (var stub : List.of(previous, other, classifier)) {
            ConfigService.set("provider." + stub.name + ".baseUrl", "http://127.0.0.1:" + port + "/" + stub.name);
            ConfigService.set("provider." + stub.name + ".apiKey", "sk-test");
            ConfigService.set("provider." + stub.name + ".models", models);
            if (prepaid && stub != classifier) ConfigService.set("provider." + stub.name + ".paymentModality", "SUBSCRIPTION");
        }
        for (var i = 0; i < 50 && List.of(previous, other, classifier).stream().anyMatch(s -> ProviderRegistry.get(s.name) == null); i++) {
            ProviderRegistry.refresh();
        }
        for (var stub : List.of(previous, other, classifier)) {
            assertNotNull(ProviderRegistry.get(stub.name), "test precondition: " + stub.name + " registers");
        }
        assertEquals(prepaid, ModelRouter.isPrepaid(Objects.requireNonNull(ProviderRegistry.get(previous.name)).config()));
        ConfigService.set(RouterPolicy.modelsKey(TaskClass.CHAT),
                "[{\"provider\":\"%s\",\"model\":\"%s\"}]".formatted(previous.name, MODEL));
        ConfigService.set(RouterPolicy.modelsKey(TaskClass.REASONING),
                "[{\"provider\":\"%s\",\"model\":\"%s\"}]".formatted(other.name, MODEL));
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, classifier.name);
        ConfigService.set(RouterPolicy.CLASSIFIER_MODEL, MODEL);
    }

    private static Agent routerAgent() {
        return persistAgent(ModelRouter.PROVIDER, ModelRouter.MODEL_ID);
    }

    private static Agent persistAgent(String provider, String modelId) {
        var agent = new Agent();
        agent.name = "rs-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = modelId;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private static Conversation conversation(Agent agent) {
        var convo = ConversationService.create(agent, "web", "u-" + UUID.randomUUID());
        commit();
        return convo;
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static JsonObject lastAssistantUsage(Conversation convo) {
        JPA.em().clear();
        Message assistant = Message.find("conversation.id = ?1 AND role = ?2 ORDER BY createdAt DESC",
                convo.id, MessageRole.ASSISTANT.value).first();
        assertNotNull(assistant, "the reply must be persisted");
        assertNotNull(assistant.usageJson, "the reply must carry its usage record");
        return JsonParser.parseString(assistant.usageJson).getAsJsonObject();
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
