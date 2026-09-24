import agents.AgentRunner;
import agents.TurnRouting;
import com.google.gson.JsonParser;
import llm.LlmResilience;
import llm.ProviderRegistry;
import llm.routing.ModelRouter;
import llm.routing.RouteDecision.Target;
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
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import slash.Commands;
import utils.CircuitBreakers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1222 end to end: a conversation on {@code router/auto} streams through the model the router
 * picked, records the route beside the model that actually answered, fails over when that model
 * refuses before saying anything, and is accepted wherever a model is chosen.
 *
 * <p>This is the only class that writes the process-global {@code router.*} keys; every other router
 * test passes its policy explicitly.
 */
class ModelRouterTurnTest extends UnitTest {

    private static final String SSE_REPLY = """
            data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}

            data: [DONE]

            """;

    private com.sun.net.httpserver.HttpServer llmServer;
    private String subscription;
    private String perToken;
    private final AtomicInteger subscriptionCalls = new AtomicInteger();
    private final AtomicInteger perTokenCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        var id = UUID.randomUUID().toString().substring(0, 8);
        subscription = "rt-sub-" + id;
        perToken = "rt-pt-" + id;
    }

    @AfterEach
    void tearDown() {
        if (llmServer != null) llmServer.stop(0);
        for (var c : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(c));
        for (var name : List.of(subscription, perToken)) {
            SubscriptionUsage.clearForTest(name);
            CircuitBreakers.remove(LlmResilience.breakerName(name));
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models", ".paymentModality"}) {
                ConfigService.delete("provider." + name + suffix);
            }
        }
    }

    @Test
    void aRoutedTurnStreamsFromTheChosenModelAndRecordsItsRoute() throws Exception {
        startServer(200, 200);
        configure();
        var agent = persistAgent();
        var convo = ConversationService.create(agent, "web", "u-routed");
        commit();

        var harness = streamAndAwait(agent, convo, "hello there");

        assertNull(harness.error.get(), () -> "unexpected error: " + harness.error.get());
        assertEquals("from the subscription", harness.completed.get());
        assertEquals(1, subscriptionCalls.get());
        assertEquals(0, perTokenCalls.get(), "the per-token model is only the fallback");
        assertTrue(harness.statuses.stream().anyMatch(s -> s.startsWith("{\"route\":") && s.contains(subscription)
                        && s.contains("\"thinkingMode\":\"low\"")),
                "the chosen model, and the low effort a chat prompt gets, precede the reply: " + harness.statuses);

        var usage = lastAssistantUsage(convo);
        assertEquals(subscription, usage.get("modelProvider").getAsString());
        assertEquals("test-model", usage.get("modelId").getAsString());
        var route = usage.getAsJsonObject("route");
        assertEquals("chat", route.get("class").getAsString());
        assertFalse(route.has("failover"));
        assertEquals("low", route.get("thinkingMode").getAsString(), "a reload shows the effort the reply ran at");

        var prior = TurnRouting.readPriorTurn(convo.id, null);
        assertEquals(TaskClass.CHAT, prior.taskClass(), "the next turn reads the route back for stickiness");
        assertEquals(new Target(subscription, "test-model"), prior.target());
        assertEquals(12, prior.promptTokens());
    }

    @Test
    void aModelThatRefusesBeforeAnyTokenHandsTheTurnToTheFallback() throws Exception {
        startServer(402, 200);
        configure();
        var agent = persistAgent();
        var convo = ConversationService.create(agent, "web", "u-failover");
        commit();

        var harness = streamAndAwait(agent, convo, "hello there");

        assertNull(harness.error.get(), () -> "the fallback should have answered: " + harness.error.get());
        assertEquals("from per-token", harness.completed.get());
        assertEquals(1, subscriptionCalls.get());
        assertEquals(1, perTokenCalls.get());
        assertTrue(harness.statuses.stream().anyMatch(s -> s.contains("\"failover\":true") && s.contains(perToken)
                        && s.contains("\"thinkingMode\":\"low\"")),
                "the switch is announced so the badge can say so: " + harness.statuses);

        var usage = lastAssistantUsage(convo);
        assertEquals(perToken, usage.get("modelProvider").getAsString(),
                "cost is attributed to the model that answered, not the one that refused");
        assertTrue(usage.getAsJsonObject("route").get("failover").getAsBoolean());
        assertTrue(SubscriptionUsage.isMarkedUnavailable(subscription, "test-model"),
                "a 402 benches the provider so the next turn does not try it first again");
    }

    @Test
    void aRouterWithNothingToRouteToFailsTheTurnWithAnActionableError() throws Exception {
        startServer(200, 200);
        configure();
        for (var c : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(c));
        var agent = persistAgent();
        var convo = ConversationService.create(agent, "web", "u-unrouted");
        commit();

        var harness = streamAndAwait(agent, convo, "hello there");

        assertNotNull(harness.error.get());
        assertEquals(TurnRouting.ROUTER_UNAVAILABLE_ERROR, harness.error.get().getMessage());
        assertEquals(0, subscriptionCalls.get() + perTokenCalls.get(), "no model is guessed at");
    }

    @Test
    void theRouterIsAcceptedWhereverAModelIsChosen() throws Exception {
        startServer(200, 200);
        configure();
        var agent = persistAgent();
        var convo = ConversationService.create(agent, "web", "u-surfaces");

        var model = ModelRouter.findModel(ModelRouter.PROVIDER, ModelRouter.MODEL_ID);
        assertTrue(model.isPresent());
        assertEquals(ModelRouter.DISPLAY_NAME, model.get().name());
        assertTrue(AgentService.configuredModelKeys().contains("router:auto"), "a router agent stays enabled");
        assertTrue(AgentService.isProviderConfigured("router", "auto"));
        assertNull(ConversationService.thinkingOverrideRejection("router", "auto", "high"));
        assertNotNull(ConversationService.thinkingOverrideRejection("router", "auto", "max"),
                "a level no listed model accepts is still refused");

        var switched = Commands.performModelSwitch(agent, convo, "router/auto");
        assertTrue(switched.startsWith("Switched this conversation to `router/auto`"), switched);

        assertNotNull(ConfigService.setWithSideEffects("provider.router.baseUrl", "http://127.0.0.1:1/v1"),
                "no real provider may take the router's name");
        assertNotNull(ConfigService.setWithSideEffects("router.fast.models", "[]"));

        for (var c : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(c));
        assertTrue(ModelRouter.findModel(ModelRouter.PROVIDER, ModelRouter.MODEL_ID).isEmpty(),
                "with no chat list the router is not offered");
    }

    @Test
    void aCallOutsideATurnResolvesTheRouterToItsChatModel() throws Exception {
        startServer(200, 200);
        configure();
        assertEquals(new Target(subscription, "test-model"), ModelRouter.concrete("router", "auto"));
        assertEquals(new Target("some-provider", "some-model"), ModelRouter.concrete("some-provider", "some-model"));
        assertNull(ModelRouter.concrete(null, "auto"));

        for (var c : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(c));
        assertNull(ModelRouter.concrete("router", "auto"), "an unconfigured router has no model to offer");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static final class Harness {
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final AtomicReference<String> completed = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch terminated = new CountDownLatch(1);
    }

    private Harness streamAndAwait(Agent agent, Conversation convo, String text) throws InterruptedException {
        var h = new Harness();
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {},
                h.statuses::add,
                _ -> {},
                content -> { h.completed.set(content); h.terminated.countDown(); },
                error -> { h.error.set(error); h.terminated.countDown(); },
                h.terminated::countDown);
        AgentRunner.runStreaming(agent, convo.id, convo.channelType, convo.peerId, text,
                new AtomicBoolean(false), cb, null);
        assertTrue(h.terminated.await(60, TimeUnit.SECONDS), "the turn must reach a terminal callback");
        return h;
    }

    /** The subscription provider answers at {@code /sub}, the per-token one at {@code /pt}. */
    private void startServer(int subscriptionStatus, int perTokenStatus) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/sub/chat/completions",
                exchange -> answer(exchange, subscriptionStatus, "from the subscription", subscriptionCalls));
        llmServer.createContext("/pt/chat/completions",
                exchange -> answer(exchange, perTokenStatus, "from per-token", perTokenCalls));
        llmServer.start();
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status, String content,
                               AtomicInteger calls) throws java.io.IOException {
        calls.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] bytes;
        if (status == 200) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            bytes = SSE_REPLY.formatted(content).getBytes();
        } else {
            bytes = "{\"error\":{\"message\":\"this model uses extra usage only\"}}".getBytes();
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void configure() {
        var port = llmServer.getAddress().getPort();
        var models = "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096,"
                + "\"supportsThinking\":true,\"thinkingLevels\":[\"low\",\"high\"]}]";
        ConfigService.set("provider." + subscription + ".baseUrl", "http://127.0.0.1:" + port + "/sub");
        ConfigService.set("provider." + subscription + ".apiKey", "sk-test");
        ConfigService.set("provider." + subscription + ".models", models);
        ConfigService.set("provider." + subscription + ".paymentModality", "SUBSCRIPTION");
        ConfigService.set("provider." + perToken + ".baseUrl", "http://127.0.0.1:" + port + "/pt");
        ConfigService.set("provider." + perToken + ".apiKey", "sk-test");
        ConfigService.set("provider." + perToken + ".models", models);
        for (var i = 0; i < 50 && (ProviderRegistry.get(subscription) == null || ProviderRegistry.get(perToken) == null); i++) {
            ProviderRegistry.refresh();
        }
        assertNotNull(ProviderRegistry.get(subscription));
        assertNotNull(ProviderRegistry.get(perToken));
        // Listed per-token first: the router must still try the subscription before it.
        ConfigService.set(RouterPolicy.modelsKey(TaskClass.CHAT),
                "[{\"provider\":\"%s\",\"model\":\"test-model\"},{\"provider\":\"%s\",\"model\":\"test-model\"}]"
                        .formatted(perToken, subscription));
    }

    private Agent persistAgent() {
        var agent = new Agent();
        agent.name = "rt-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = ModelRouter.PROVIDER;
        agent.modelId = ModelRouter.MODEL_ID;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static com.google.gson.JsonObject lastAssistantUsage(Conversation convo) {
        JPA.em().clear();
        Message assistant = Message.find("conversation.id = ?1 AND role = ?2 ORDER BY createdAt DESC",
                convo.id, MessageRole.ASSISTANT.value).first();
        assertNotNull(assistant, "the reply must be persisted");
        assertNotNull(assistant.usageJson, "the reply must carry its usage record");
        return JsonParser.parseString(assistant.usageJson).getAsJsonObject();
    }
}
