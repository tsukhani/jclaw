import agents.CompactionGate;
import agents.ContextWindowManager;
import agents.UsageMetricsBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmProvider;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.Usage;
import llm.ProviderRegistry;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.CompactionProgress;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import slash.Commands;
import utils.AppClock;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JF-422: the compaction card's before and after counts are in the provider's units, as the chat's meter counts the
 * conversation: from the last reply's provider-reported prompt when the trigger can count from it, else the adjusted
 * estimate, on the conversation's own model; and a compaction that stored nothing sends its reason on the skipped
 * frame. Covers the inline gate and {@code /compact}.
 */
class CompactionCardTokensTest extends UnitTest {

    /** Not an OpenAI model, so counted on the cl100k_base fallback and scaled by the provider's multiplier. */
    private static final String MODEL = "card-model";
    /** What a summary model that only ever answers with nothing leaves: every try, then giving up. */
    private static final String EMPTY_REPLIES_REASON =
            "The summary failed on 4 tries (last: an empty reply). The older messages are kept as they are.";
    private static final String SUMMARY_MODEL = "card-summary";
    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final String REPLY = "Here is the plan for the week.";
    private static final List<ChatMessage> ASKED = List.of(ChatMessage.system("SP"),
            ChatMessage.user("Plan a week in Kyoto for two."));

    private HttpServer llmServer;
    private final AtomicReference<String> summaryText = new AtomicReference<>(SummaryFixture.asSummary("Canned summary of the earlier turns."));
    private final List<String> providers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        CompactionTriggerSync.acquire();
        // No tools, so the request /compact builds is the same each time this class reads it.
        ToolRegistrySync.publishForTest(List.of());
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        ConfigService.set("chat.compactionReserveTokens", "500");
        ConfigService.set("chat.compactionReserveTokensFloor", "500");
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var reply = new JsonObject();
            var message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", summaryText.get());
            var choice = new JsonObject();
            choice.addProperty("index", 0);
            choice.add("message", message);
            choice.addProperty("finish_reason", "stop");
            var choices = new com.google.gson.JsonArray();
            choices.add(choice);
            reply.add("choices", choices);
            var usage = new JsonObject();
            usage.addProperty("prompt_tokens", 10);
            usage.addProperty("completion_tokens", 5);
            reply.add("usage", usage);
            LlmReplyFixture.send(exchange, reply.toString());
        });
        llmServer.start();
    }

    @AfterEach
    void tearDown() {
        try {
            if (llmServer != null) llmServer.stop(0);
            for (var provider : providers) {
                for (var key : List.of("baseUrl", "apiKey", "models")) ConfigService.delete("provider." + provider + "." + key);
                ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider);
                CircuitBreakers.remove(LlmResilience.breakerName(provider));
            }
            ProviderRegistry.refresh();
            ConfigService.delete("chat.compactionReserveTokens");
            ConfigService.delete("chat.compactionReserveTokensFloor");
        } finally {
            try {
                ToolRegistrySync.release();
            } finally {
                CompactionTriggerSync.release();
            }
        }
    }

    // ─── the inline gate ─────────────────────────────────────────────────

    @Test
    void theGatesCardCountsBeforeAndAfterFromTheLastRepliesProviderCount() {
        var provider = provider(1.0);
        var agent = agent(provider);
        var conv = ConversationService.create(agent, "web", "card-anchor");
        seed(conv);
        int asked = tokens(ASKED);
        int prompt = 2 * asked;
        anchoredReply(conv, agent, prompt, 40);
        commitAndReopen();
        var current = turnPastTheWindow();
        var frames = new ArrayList<String>();

        var rebuilt = new AtomicReference<List<ChatMessage>>();
        ScopedValue.where(CompactionProgress.SINK, (Consumer<String>) frames::add).run(() -> rebuilt.set(
                CompactionGate.maybeCompactAndRebuild(agent, conv.id, Set.of(), ProviderRegistry.get(provider), current)));

        var start = frame(frames, "start");
        var done = frame(frames, "done");
        int expectedBefore = prompt + 40 + tokens(current) - asked - replyTokens();
        assertEquals(expectedBefore, start.get("tokensBefore").getAsInt(), frames.toString());
        assertEquals(expectedBefore, done.get("tokensBefore").getAsInt(), frames.toString());
        assertNotEquals(tokens(current), expectedBefore, "the provider's count, not the local estimate of the request");
        assertEquals(2 * tokens(rebuilt.get()), done.get("tokensAfter").getAsInt(),
                "the rebuilt request scaled by the reply's provider count over its estimate: " + frames);
    }

    @Test
    void withNoProviderCountTheGatesCardCountsTheAdjustedEstimate() {
        var provider = provider(1.5);
        var agent = agent(provider);
        var conv = ConversationService.create(agent, "web", "card-no-anchor");
        seed(conv);
        commitAndReopen();
        var current = turnPastTheWindow();
        var frames = new ArrayList<String>();

        var rebuilt = new AtomicReference<List<ChatMessage>>();
        ScopedValue.where(CompactionProgress.SINK, (Consumer<String>) frames::add).run(() -> rebuilt.set(
                CompactionGate.maybeCompactAndRebuild(agent, conv.id, Set.of(), ProviderRegistry.get(provider), current)));

        var done = frame(frames, "done");
        assertEquals((int) Math.ceil(tokens(current) * 1.5), done.get("tokensBefore").getAsInt(), frames.toString());
        assertEquals((int) Math.ceil(tokens(rebuilt.get()) * 1.5), done.get("tokensAfter").getAsInt(), frames.toString());
    }

    @Test
    void aGateCompactionThatStoresNoSummarySendsItsReasonOnTheSkippedFrame() {
        summaryText.set("");
        var provider = provider(1.0);
        var agent = agent(provider);
        var conv = ConversationService.create(agent, "web", "card-skip");
        seed(conv);
        commitAndReopen();
        var current = turnPastTheWindow();
        var frames = new ArrayList<String>();

        ScopedValue.where(CompactionProgress.SINK, (Consumer<String>) frames::add).run(() ->
                CompactionGate.maybeCompactAndRebuild(agent, conv.id, Set.of(), ProviderRegistry.get(provider), current));

        assertEquals("start", phase(frames.getFirst()), frames.toString());
        assertEquals("{\"compaction\":{\"phase\":\"skipped\",\"reason\":\"" + EMPTY_REPLIES_REASON + "\"}}", frames.getLast());
    }

    // ─── /compact ────────────────────────────────────────────────────────

    @Test
    void slashCompactCountsBeforeAndAfterFromTheLastRepliesProviderCount() throws Exception {
        var provider = provider(1.0);
        var agent = agent(provider);
        var conv = ConversationService.create(agent, "web", "card-slash-anchor");
        seed(conv);
        int asked = tokens(ASKED);
        int prompt = 2 * asked;
        anchoredReply(conv, agent, prompt, 40);
        commitAndReopen();
        int next = tokens(CompactionGate.nextTurnRequest(agent, conv.id));
        var frames = new CopyOnWriteArrayList<String>();

        var result = slashCompact(agent, conv, frames::add);
        var outcome = result.compaction();

        assertNotNull(outcome);
        int expectedBefore = prompt + 40 + next - asked - replyTokens();
        assertEquals(expectedBefore, outcome.tokensBefore());
        assertNotEquals(next, expectedBefore, "the provider's count, not the local estimate of the request");
        int after = afterCompaction(agent, conv, result.responseText());
        assertEquals(2 * after, outcome.tokensAfter());
        var done = frame(frames, "done");
        assertEquals(outcome.tokensBefore(), done.get("tokensBefore").getAsInt());
        assertEquals(outcome.tokensAfter(), done.get("tokensAfter").getAsInt());
    }

    @Test
    void slashCompactCountsOnTheConversationsModelNotTheSummaryModel() throws Exception {
        var provider = provider(1.5);
        var summarizer = provider(2.0);
        var agent = agent(provider);
        agent.compactionProvider = summarizer;
        agent.compactionModel = SUMMARY_MODEL;
        agent.save();
        var conv = ConversationService.create(agent, "web", "card-slash-model");
        seed(conv);
        commitAndReopen();
        int next = tokens(CompactionGate.nextTurnRequest(agent, conv.id));

        var result = slashCompact(agent, conv, null);
        var outcome = result.compaction();

        assertNotNull(outcome);
        assertEquals((int) Math.ceil(next * 1.5), outcome.tokensBefore(),
                "the conversation's provider's multiplier, 1.5, not the summary provider's 2.0 or none");
        int after = afterCompaction(agent, conv, result.responseText());
        assertEquals((int) Math.ceil(after * 1.5), outcome.tokensAfter());
    }

    @Test
    void aSlashCompactThatStoresNoSummarySendsItsReasonAndAnswersNoCounts() throws Exception {
        summaryText.set("");
        var provider = provider(1.0);
        var agent = agent(provider);
        var conv = ConversationService.create(agent, "web", "card-slash-skip");
        seed(conv);
        commitAndReopen();
        var frames = new CopyOnWriteArrayList<String>();

        var result = slashCompact(agent, conv, frames::add);

        assertNull(result.compaction());
        assertEquals("start", phase(frames.getFirst()), frames.toString());
        assertEquals("{\"compaction\":{\"phase\":\"skipped\",\"reason\":\"" + EMPTY_REPLIES_REASON + "\"}}", frames.getLast());
    }

    // ─── the skipped frame ───────────────────────────────────────────────

    @Test
    void theSkippedFrameCarriesAnyReasonAsValidJsonAndLeavesANullOneOut() throws Exception {
        var frames = new ArrayList<String>();
        var withReason = announced(frames);
        withReason.skipped("The summary model said \"no\".\nOlder turns remain.");
        var withNone = announced(frames);
        withNone.skipped(null);

        assertEquals(List.of("{\"compaction\":{\"phase\":\"start\",\"tokensBefore\":1200,\"rows\":3}}",
                "{\"compaction\":{\"phase\":\"skipped\",\"reason\":\"The summary model said \\\"no\\\".\\nOlder turns remain.\"}}",
                "{\"compaction\":{\"phase\":\"start\",\"tokensBefore\":1200,\"rows\":3}}",
                "{\"compaction\":{\"phase\":\"skipped\"}}"), frames);
        assertEquals("The summary model said \"no\".\nOlder turns remain.", JsonParser.parseString(frames.get(1))
                .getAsJsonObject().getAsJsonObject("compaction").get("reason").getAsString());
    }

    @Test
    void aSkipWithNoStartBeforeItSendsNothing() {
        var frames = new ArrayList<String>();
        var progress = ScopedValue.where(CompactionProgress.SINK, (Consumer<String>) frames::add)
                .call(() -> CompactionProgress.open(1200));

        progress.skipped(services.SessionCompactor.NO_CUT_REASON);

        assertEquals(List.of(), frames);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** A provider no other class names, serving {@link #MODEL} and {@link #SUMMARY_MODEL} from the stub, with its multiplier pinned. */
    private String provider(double multiplier) {
        var provider = "card-" + UUID.randomUUID().toString().substring(0, 8);
        providers.add(provider);
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", ("[{\"id\":\"%s\",\"name\":\"Card\",\"contextWindow\":2000,"
                + "\"maxTokens\":1024},{\"id\":\"%s\",\"name\":\"Card summary\",\"contextWindow\":2000,\"maxTokens\":1024}]")
                .formatted(MODEL, SUMMARY_MODEL));
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider, String.valueOf(multiplier));
        for (int i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider));
        return provider;
    }

    private static Agent agent(String provider) {
        var agent = new Agent();
        agent.name = "card-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** 25 rows of about 150 tokens each, a second apart, so every summary call plans past the last one's floor. */
    private static void seed(Conversation conv) {
        for (int i = 0; i < 25; i++) {
            var role = i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT;
            var content = "turn " + i + " " + "padding ".repeat(150);
            AppClock.runWith(Clock.fixed(T0.plusSeconds(i), ZoneOffset.UTC),
                    () -> ConversationService.appendMessage(conv, role, content, null, null, null));
        }
    }

    /** The reply after the seeded rows, its final call counted by the provider at {@code prompt} and {@code completion}, locally as {@link #ASKED}. */
    private static void anchoredReply(Conversation conv, Agent agent, int prompt, int completion) {
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(LlmProvider.StreamAccumulator.builder()
                .usage(new Usage(prompt, completion, prompt + completion, 0, 0, 0))
                .promptTokenEstimate(TokenUsageEstimator.estimateChatRequest(MODEL, ASKED, List.of())).build());
        var usage = UsageMetricsBuilder.buildUsageJson(turn, new ModelInfo(MODEL, MODEL, 2000, 1024, false),
                System.currentTimeMillis(), agent, conv);
        AppClock.runWith(Clock.fixed(T0.plusSeconds(25), ZoneOffset.UTC),
                () -> ConversationService.appendAssistantMessage(conv, REPLY, null, usage, null));
    }

    /** A request past the 1,500-token window budget, so the gate compacts it inline. */
    private static List<ChatMessage> turnPastTheWindow() {
        var big = ("Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt "
                + "ut labore et dolore magna aliqua. ").repeat(120);
        var current = List.of(ChatMessage.system("SP"), ChatMessage.user(big));
        assertTrue(tokens(current) > 1500, "the request must pass the window budget: " + tokens(current));
        return current;
    }

    private static int tokens(List<ChatMessage> messages) {
        return TokenUsageEstimator.estimateChatRequest(MODEL, messages, List.of()).promptTokens();
    }

    private static int tokens(services.SessionCompactor.@org.jspecify.annotations.Nullable TurnRequest turn) {
        assertNotNull(turn);
        return TokenUsageEstimator.estimateChatRequest(MODEL, turn.messages(), turn.tools()).promptTokens();
    }

    /**
     * The request the next turn sends once {@code /compact}, on its own thread, has stored its summary, as it stood when
     * {@code /compact} counted it: before it saved its reply, {@code ack}.
     */
    private static int afterCompaction(Agent agent, Conversation conv, String ack) {
        commitAndReopen();
        models.Message newest = models.Message.find("conversation.id = ?1 ORDER BY createdAt DESC, id DESC", conv.id).first();
        assertNotNull(newest);
        assertEquals(ack, newest.content);
        newest.delete();
        commitAndReopen();
        return tokens(CompactionGate.nextTurnRequest(agent, conv.id));
    }

    /** The stored reply as a later request's estimate counts it. */
    private static int replyTokens() {
        return TokenUsageEstimator.estimateMessage(MODEL, ChatMessage.assistant(REPLY)).tokens();
    }

    /** A tracker whose compaction has announced itself, as the compactor does once it finds a cut. */
    private static CompactionProgress announced(List<String> frames) throws Exception {
        var progress = ScopedValue.where(CompactionProgress.SINK, (Consumer<String>) frames::add)
                .call(() -> CompactionProgress.open(1200));
        var started = CompactionProgress.class.getDeclaredMethod("started", int.class);
        started.setAccessible(true);
        progress.around(() -> {
            try {
                return started.invoke(null, 3);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        return progress;
    }

    private static JsonObject frame(List<String> frames, String phase) {
        return frames.stream().filter(f -> phase.equals(phase(f))).reduce((first, second) -> second)
                .map(f -> JsonParser.parseString(f).getAsJsonObject().getAsJsonObject("compaction"))
                .orElseThrow(() -> new AssertionError("no " + phase + " frame in " + frames));
    }

    private static String phase(String frame) {
        return JsonParser.parseString(frame).getAsJsonObject().getAsJsonObject("compaction").get("phase").getAsString();
    }

    /** {@code /compact} as web chat runs it, on a thread with no transaction of its own, {@code sink} bound when given. */
    private static Commands.Result slashCompact(Agent agent, Conversation conv,
                                                @org.jspecify.annotations.Nullable Consumer<String> sink)
            throws Exception {
        var result = new AtomicReference<Commands.Result>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var fresh = Tx.run(() -> (Agent) Agent.findById(agent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(conv.id));
                result.set(sink == null
                        ? Commands.execute(Commands.Command.COMPACT, fresh, "web", c.peerId, c, null, true)
                        : ScopedValue.where(CompactionProgress.SINK, sink).call(() ->
                                Commands.execute(Commands.Command.COMPACT, fresh, "web", c.peerId, c, null, true)));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "/compact must finish within 60 s");
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().clear();
        JPA.em().getTransaction().begin();
    }
}
