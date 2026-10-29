import agents.CompactionGate;
import agents.ContextWindowManager;
import agents.MessageHydrator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import llm.TokenUsageEstimator;
import llm.routing.ModelRouter;
import llm.routing.RouteDecision;
import llm.routing.RouteDecision.Target;
import llm.routing.RoutedTurn;
import llm.routing.RouterPolicy;
import llm.routing.SubscriptionUsage;
import llm.routing.TaskClass;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import models.SessionCompaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import slash.Commands;
import utils.AppClock;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-160: an agent whose configured model is the router writes every compaction summary with the router's summarize
 * class, mid-chat and through {@code /compact}, failing over to that class's next candidate, while an agent or a
 * conversation on a fixed model keeps its own. Three stub providers: {@code a} serves the chat and coding classes,
 * {@code b} then {@code c} the summarize class.
 *
 * <p>Writes the process-global {@code router.*} model lists, so it holds {@link JevBreakerTestSync} as
 * {@code ModelRouterTurnTest} does, then {@link CompactionTriggerSync} for the trigger and history-limit keys.
 */
class CompactionSummaryModelTest extends UnitTest {

    private static final String SUMMARY_SECTION = "### Goals and constraints";

    private HttpServer llmServer;
    private String a;
    private String b;
    private String c;
    private final Map<String, List<JsonObject>> requests = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        JevBreakerTestSync.acquire();
        CompactionTriggerSync.acquire();
        var id = UUID.randomUUID().toString().substring(0, 8);
        a = "jf160-a-" + id;
        b = "jf160-b-" + id;
        c = "jf160-c-" + id;
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (var provider : List.of(a, b, c)) {
            requests.put(provider, new CopyOnWriteArrayList<>());
            llmServer.createContext("/" + provider + "/chat/completions", exchange -> {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.get(provider).add(JsonParser.parseString(body).getAsJsonObject());
                int status = statuses.getOrDefault(provider, 200);
                if (status == 200) {
                    LlmReplyFixture.send(exchange, text("SUMMARY FROM " + provider));
                    return;
                }
                var error = "{\"error\":{\"message\":\"bad request\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
            });
        }
        llmServer.start();
    }

    @AfterEach
    void tearDown() {
        try {
            if (llmServer != null) llmServer.stop(0);
            for (var taskClass : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(taskClass));
            for (var provider : List.of(a, b, c)) {
                for (var key : List.of("baseUrl", "apiKey", "models")) ConfigService.delete("provider." + provider + "." + key);
                ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider);
                SubscriptionUsage.clearForTest(provider);
                CircuitBreakers.remove(LlmResilience.breakerName(provider));
            }
            ProviderRegistry.refresh();
        } finally {
            try {
                CompactionTriggerSync.release();
            } finally {
                JevBreakerTestSync.release();
            }
        }
    }

    // ─── Mid-chat, through the gate ─────────────────────────────────────

    @Test
    void aRouterAgentsMidChatSummaryGoesToTheSummarizeClassNotTheTurnsModel() {
        configure(1_000_000);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-gate");
        seed(conv, 25, i -> "turn " + i);
        commit();

        var rebuilt = compactDuringACodingTurn(agent, conv, List.of(ChatMessage.system("SP"), ChatMessage.user("hi")));

        assertEquals(0, requests.get(a).size(), "the coding turn's own model writes no summary");
        assertEquals(List.of(b + "-model"), models(b));
        assertEquals(0, requests.get(c).size(), "the next candidate waits for a failure");
        assertTrue(((String) rebuilt.messages().getFirst().content()).endsWith("SUMMARY FROM " + b));
        assertEquals(List.of(b + "/" + b + "-model"), storedModels(conv.id));
    }

    @Test
    void aFixedModelAgentSummarizesWithItsOwnModelWhateverTheRouterOffers() {
        configure(1_000_000);
        var agent = new Agent();
        agent.name = "jf160-fixed-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = a;
        agent.modelId = a + "-model";
        agent.enabled = true;
        agent.save();
        var conv = ConversationService.create(agent, "web", "u-jf160-fixed");
        seed(conv, 25, i -> "turn " + i);
        commit();

        CompactionGate.maybeCompactAndRebuild(agent, conv.id, Set.of(), ProviderRegistry.get(a),
                hydration(List.of(ChatMessage.system("SP"), ChatMessage.user("hi"))), List.of(), 1_000_000);

        assertEquals(List.of(a + "-model"), models(a));
        assertEquals(0, requests.get(b).size() + requests.get(c).size());
        assertEquals(List.of(a + "/" + a + "-model"), storedModels(conv.id));
    }

    @Test
    void aTurnShapedRequestTooLargeForTheSummaryModelGoesAsTheTranscript() {
        // b's 40,000-token window takes neither the ~60,000-token system prompt nor its turn-shaped request.
        configure(40_000);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-fit");
        seed(conv, 25, i -> "turn " + i);
        commit();
        var system = ChatMessage.system(bigText(60_000));

        compactDuringACodingTurn(agent, conv, turnOf(system, 25));

        var sent = messages(b);
        assertEquals(List.of("system", "user"), roles(sent));
        assertTrue(content(sent, 0).contains(SUMMARY_SECTION), "the instruction is the transcript's system prompt");
        assertTrue(content(sent, 1).startsWith("[USER] turn 0"), content(sent, 1));
    }

    @Test
    void aTurnShapedRequestTheSummaryModelTakesKeepsTheTurnsPrefix() {
        configure(1_000_000);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-fits");
        seed(conv, 25, i -> "turn " + i);
        commit();
        var system = ChatMessage.system(bigText(60_000));

        compactDuringACodingTurn(agent, conv, turnOf(system, 25));

        var sent = messages(b);
        assertEquals(system.content(), content(sent, 0));
        assertEquals("turn 0", content(sent, 1));
        assertEquals("user", roles(sent).getLast());
        assertTrue(content(sent, sent.size() - 1).contains(SUMMARY_SECTION), "the instruction comes last");
    }

    // ─── /compact ────────────────────────────────────────────────────────

    @Test
    void slashCompactOnARouterAgentUsesTheSummarizeClassNotTheChatClass() throws Exception {
        configure(1_000_000);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-slash");
        seed(conv, 12, i -> "turn " + i);
        commit();

        var result = slashCompact(agent, conv);

        assertEquals(0, requests.get(a).size(), "the chat class writes no summary");
        assertEquals(List.of(b + "-model"), models(b));
        assertEquals(0, requests.get(c).size());
        assertTrue(result.responseText().startsWith("> Conversation Compacted"), result.responseText());
        assertEquals(List.of(b + "/" + b + "-model"), storedModels(conv.id));
    }

    @Test
    void aFailingFirstSummarizeCandidateHandsTheSummaryToTheNext() throws Exception {
        configure(1_000_000);
        statuses.put(b, 400);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-failover");
        seed(conv, 12, i -> "turn " + i);
        commit();

        var result = slashCompact(agent, conv);

        assertEquals(List.of(b + "-model"), models(b));
        assertEquals(List.of(c + "-model"), models(c));
        assertEquals(0, requests.get(a).size());
        assertEquals(requests.get(b).getFirst().get("messages"), requests.get(c).getFirst().get("messages"),
                "the next candidate gets the same request");
        assertTrue(result.responseText().startsWith("> Conversation Compacted"), result.responseText());
        assertEquals(List.of(c + "/" + c + "-model"), storedModels(conv.id), "the row names the model that wrote it");
    }

    @Test
    void aConversationOverriddenToAFixedModelSummarizesWithThatModel() throws Exception {
        configure(1_000_000);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-override");
        conv.modelProviderOverride = c;
        conv.modelIdOverride = c + "-model";
        conv.save();
        seed(conv, 12, i -> "turn " + i);
        commit();

        slashCompact(agent, conv);

        assertEquals(List.of(c + "-model"), models(c));
        assertEquals(0, requests.get(a).size() + requests.get(b).size());
        assertEquals(List.of(c + "/" + c + "-model"), storedModels(conv.id));
    }

    @Test
    void aCatchUpIsChunkedByTheSummaryModelsWindow() throws Exception {
        // b's 40,000-token window bounds a call at 20,000 tokens, six of these ~3,000-token rows; the turn's
        // 1,000,000-token model would take the history limit's twelve.
        configure(40_000);
        var agent = routerAgent();
        var conv = ConversationService.create(agent, "web", "u-jf160-chunks");
        var tick = new AtomicLong(Instant.now().minusSeconds(60).toEpochMilli());
        AppClock.runWith(new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.incrementAndGet()); }
        }, () -> seed(conv, 30, i -> "Row " + i + " " + "ledger ".repeat(1_700)));
        ConfigService.set(ConversationService.KEY_GLOBAL_MAX, "12");
        commit();

        slashCompact(agent, conv);

        // /compact keeps 4: rows 0-5, 6-11, 12-17 and 18-23 in chunks of six, then 24-25.
        var inputs = new ArrayList<String>();
        for (var request : requests.get(b)) inputs.add(content(request.getAsJsonArray("messages"), 1));
        assertEquals(5, inputs.size());
        assertTrue(inputs.getFirst().contains("[USER] Row 4 ") && inputs.getFirst().contains("[ASSISTANT] Row 5 ")
                && !inputs.getFirst().contains("Row 6 "), "the first chunk ends at row 5");
        assertEquals(0, requests.get(a).size());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /** Providers a, b and c, one model each; {@code bWindow} is b's context window. a serves chat and coding, b then c summarize. */
    private void configure(int bWindow) {
        var port = llmServer.getAddress().getPort();
        for (var provider : List.of(a, b, c)) {
            int window = provider.equals(b) ? bWindow : 1_000_000;
            ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + port + "/" + provider);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models", ("[{\"id\":\"%s-model\",\"name\":\"%s\","
                    + "\"contextWindow\":%d,\"maxTokens\":4096}]").formatted(provider, provider, window));
            ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider, "1.0");
        }
        for (var i = 0; i < 50 && (ProviderRegistry.get(a) == null || ProviderRegistry.get(b) == null
                || ProviderRegistry.get(c) == null); i++) {
            ProviderRegistry.refresh();
        }
        assertNotNull(ProviderRegistry.get(a));
        assertNotNull(ProviderRegistry.get(b));
        assertNotNull(ProviderRegistry.get(c));
        ConfigService.set(RouterPolicy.modelsKey(TaskClass.CHAT), candidates(a));
        ConfigService.set(RouterPolicy.modelsKey(TaskClass.CODING), candidates(a));
        ConfigService.set(RouterPolicy.modelsKey(TaskClass.SUMMARIZE), candidates(b, c));
    }

    private static String candidates(String... providers) {
        var out = new ArrayList<String>();
        for (var p : providers) out.add("{\"provider\":\"%s\",\"model\":\"%s-model\"}".formatted(p, p));
        return "[" + String.join(",", out) + "]";
    }

    private Agent routerAgent() {
        var agent = new Agent();
        agent.name = "jf160-router-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = ModelRouter.PROVIDER;
        agent.modelId = ModelRouter.MODEL_ID;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** The gate as a turn the router sent to a's coding model runs it, with a reserve that passes any trigger. */
    private MessageHydrator.Hydration compactDuringACodingTurn(Agent agent, Conversation conv, List<ChatMessage> turn) {
        var decision = new RouteDecision(TaskClass.CODING, TaskClass.CODING.defaultEffort(), new Target(a, a + "-model"),
                null, List.of("test"), List.of(), false, false, false, null);
        return RoutedTurn.callWith(decision, conv, () -> CompactionGate.maybeCompactAndRebuild(agent, conv.id,
                Set.of(), ProviderRegistry.get(a), hydration(turn), List.of(), 1_000_000));
    }

    private static MessageHydrator.Hydration hydration(List<ChatMessage> messages) {
        return new MessageHydrator.Hydration(messages, List.of(), List.of(), List.of());
    }

    /** {@code system} then the {@code rows} seeded rows, as the turn loads them. */
    private static List<ChatMessage> turnOf(ChatMessage system, int rows) {
        var out = new ArrayList<ChatMessage>();
        out.add(system);
        for (int i = 0; i < rows; i++) {
            out.add(i % 2 == 0 ? ChatMessage.user("turn " + i) : ChatMessage.assistant("turn " + i));
        }
        return out;
    }

    private static String bigText(int tokens) {
        var unit = " alpha bravo charlie delta echo foxtrot golf hotel india juliet";
        return unit.repeat(tokens / TokenUsageEstimator.estimateText("any-model", unit).tokens());
    }

    private static void seed(Conversation conv, int rows, java.util.function.IntFunction<String> content) {
        Tx.run(() -> {
            var managed = (Conversation) Conversation.findById(conv.id);
            for (int i = 0; i < rows; i++) {
                ConversationService.appendMessage(managed, i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                        content.apply(i), null, null, null);
            }
        });
    }

    /** {@code /compact} as web chat runs it, on a thread with no transaction of its own. */
    private static Commands.Result slashCompact(Agent agent, Conversation conv) throws Exception {
        var result = new AtomicReference<Commands.Result>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var fresh = Tx.run(() -> (Agent) Agent.findById(agent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(conv.id));
                result.set(Commands.execute(Commands.Command.COMPACT, fresh, "web", c.peerId, c, null, true));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "/compact must finish within 60 s");
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private List<String> models(String provider) {
        var out = new ArrayList<String>();
        for (var request : requests.get(provider)) out.add(request.get("model").getAsString());
        return out;
    }

    private JsonArray messages(String provider) {
        assertEquals(1, requests.get(provider).size(), provider + " gets the one summary call");
        return requests.get(provider).getFirst().getAsJsonArray("messages");
    }

    private static List<String> roles(JsonArray messages) {
        var out = new ArrayList<String>();
        for (var m : messages) out.add(m.getAsJsonObject().get("role").getAsString());
        return out;
    }

    private static String content(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("content").getAsString();
    }

    private static List<String> storedModels(Long conversationId) {
        JPA.em().clear();
        return Tx.run(() -> {
            var out = new ArrayList<String>();
            for (Object o : SessionCompaction.find("conversation.id = ?1 ORDER BY id", conversationId).fetch()) {
                out.add(((SessionCompaction) o).model);
            }
            return out;
        });
    }

    private static String text(String content) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(content);
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
