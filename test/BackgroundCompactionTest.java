import agents.AgentRunner;
import agents.CompactionGate;
import agents.ContextWindowManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.SessionCompaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.SessionCompactor;
import services.Tx;
import slash.Commands;
import utils.CircuitBreakers;
import utils.LatencyStats;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-367: automatic compaction runs between turns. A reply whose next turn comes near the trigger has the summary
 * written after it, so the next turn starts compacted; a turn past the trigger whose request the window still takes is
 * sent as it is beside the summary call; a conversation runs one compaction at a time, and a failed one leaves it as it
 * was. The provider stub answers turns at once and holds summary calls until {@link #summaryGate} opens, on a 1,000,000
 * token window whose trigger each test sets with {@link SessionCompactor#TRIGGER_TOKENS_KEY}.
 */
class BackgroundCompactionTest extends UnitTest {

    private static final String MODEL = "jf367-model";
    private static final String SECTION = "### Goals and constraints";

    private HttpServer llmServer;
    private ExecutorService serverThreads;
    private String provider;
    private String channel;
    private final List<JsonObject> turns = new CopyOnWriteArrayList<>();
    private final List<JsonObject> summaries = new CopyOnWriteArrayList<>();
    private final List<Long> conversations = new CopyOnWriteArrayList<>();
    private final AtomicInteger reportedPrompt = new AtomicInteger(10);
    private final AtomicBoolean failSummaries = new AtomicBoolean();
    private final AtomicInteger summariesInFlight = new AtomicInteger();
    private final AtomicInteger mostSummariesAtOnce = new AtomicInteger();
    private volatile CountDownLatch summaryGate = new CountDownLatch(0);

    @BeforeEach
    void setUp() throws Exception {
        CompactionTriggerSync.acquire();
        ToolRegistrySync.publishForTest(List.of());
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        var id = UUID.randomUUID().toString().substring(0, 8);
        provider = "jf367-" + id;
        channel = "jf367-" + id;
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.setExecutor(serverThreads);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var request = JsonParser.parseString(body).getAsJsonObject();
            if (!body.contains(SECTION)) {
                turns.add(request);
                LlmReplyFixture.send(exchange, reply("Reply " + turns.size(), reportedPrompt.get()));
                return;
            }
            summaries.add(request);
            mostSummariesAtOnce.accumulateAndGet(summariesInFlight.incrementAndGet(), Math::max);
            try {
                if (!summaryGate.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("the gate never opened");
                if (failSummaries.get()) {
                    var error = "{\"error\":{\"message\":\"bad request\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, error.length);
                    exchange.getResponseBody().write(error);
                    exchange.close();
                } else {
                    LlmReplyFixture.send(exchange, reply("SUMMARY " + summaries.size(), 10));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                summariesInFlight.decrementAndGet();
            }
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"JF-367\",\"contextWindow\":1000000,\"maxTokens\":4096}]");
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider, "1.0");
        llm.ProviderRegistry.refresh();
    }

    @AfterEach
    void tearDown() {
        try {
            summaryGate.countDown();
            for (var id : conversations) awaitCompaction(id);
            if (llmServer != null) llmServer.stop(0);
            if (serverThreads != null) serverThreads.shutdownNow();
            for (var key : List.of("baseUrl", "apiKey", "models")) ConfigService.delete("provider." + provider + "." + key);
            ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider);
            llm.ProviderRegistry.refresh();
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
        } finally {
            try {
                ToolRegistrySync.release();
            } finally {
                CompactionTriggerSync.release();
            }
        }
    }

    // ─── After a reply ──────────────────────────────────────────────────

    @Test
    void aReplyThatBringsTheChatNearTheTriggerHasItsSummaryStoredBeforeTheNextTurn() throws Exception {
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        // Short of the trigger when the turn starts, within the margin once its reply is stored.
        int trigger = nextTurnTokens(agent, convo.id) * 100 / 95;
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(trigger));
        reportedPrompt.set(trigger);
        summaryGate = new CountDownLatch(1);

        run(agent, convo, "Next?");
        reportedPrompt.set(10);
        assertTrue(openGateAndAwait(convo.id).compacted());

        assertEquals(1, turns.size());
        assertEquals(1, summaries.size(), "the summary is written after the reply");
        assertFalse(systemPrompt(turns.getFirst()).contains(SessionCompactor.PRIOR_SUMMARY_HEADER),
                "the turn itself went uncompacted: it was short of the trigger");
        var stored = latestCompaction(convo.id);
        assertEquals("SUMMARY 1", stored.summary);

        long rewriteBefore = prologueRewrite("sum_ms");
        long countBefore = prologueRewrite("count");
        run(agent, convo, "And then?");

        assertEquals(2, turns.size());
        assertEquals(1, summaries.size(), "the next turn writes no summary of its own");
        assertTrue(systemPrompt(turns.get(1)).endsWith(SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n"
                + SessionCompactor.segmentLabel(stored) + "\n\nSUMMARY 1"), "the next turn starts on the stored summary");
        assertEquals(countBefore + 1, prologueRewrite("count"));
        long rewriteMs = prologueRewrite("sum_ms") - rewriteBefore;
        assertTrue(rewriteMs < 50, "the next turn's prologue_rewrite took " + rewriteMs + " ms");
    }

    @Test
    void aStreamedReplyThatBringsTheChatNearTheTriggerHasItsSummaryStoredBeforeTheNextTurn() throws Exception {
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        int trigger = nextTurnTokens(agent, convo.id) * 100 / 95;
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(trigger));
        reportedPrompt.set(trigger);
        summaryGate = new CountDownLatch(1);

        var started = new AtomicReference<CompletableFuture<SessionCompactor.CompactionResult>>();
        assertEquals("Reply 1", stream(agent, convo, "Next?", () -> started.set(CompactionGate.runningForTest(convo.id))));
        reportedPrompt.set(10);
        assertNotNull(started.get(), "the compaction is running when the reply completes");
        summaryGate.countDown();
        assertTrue(started.get().get(30, TimeUnit.SECONDS).compacted());

        assertEquals(1, summaries.size());
        assertEquals("SUMMARY 1", latestCompaction(convo.id).summary);
    }

    @Test
    void aReplyWellShortOfTheTriggerStartsNoCompaction() throws Exception {
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        // The margin starts at 90% of the trigger; this chat sits at about 80%.
        int trigger = nextTurnTokens(agent, convo.id) * 100 / 80;
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(trigger));
        reportedPrompt.set(trigger * 80 / 100);

        run(agent, convo, "Next?");

        assertNull(CompactionGate.runningForTest(convo.id));
        assertEquals(0, summaries.size());
        assertEquals(0L, compactionCount(convo.id));
    }

    @Test
    void aLargeLastCallWhoseNextTurnIsFarFromTheTriggerStoresNoSummary() throws Exception {
        // A final call past the trigger, as big tool results make one, while the next request, its history pruned, is not.
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        int trigger = nextTurnTokens(agent, convo.id) * 2;
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(trigger));
        reportedPrompt.set(trigger);

        run(agent, convo, "Next?");
        awaitCompaction(convo.id);

        assertEquals(0, summaries.size());
        assertEquals(0L, compactionCount(convo.id));
    }

    // ─── A turn past the trigger ────────────────────────────────────────

    @Test
    void aTurnPastTheTriggerWhoseRequestFitsIsSentWithoutWaitingAndTheNextTurnStartsNoSecondSummary() throws Exception {
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(nextTurnTokens(agent, convo.id) / 2));
        summaryGate = new CountDownLatch(1);

        assertEquals("Reply 1", run(agent, convo, "Next?").response());
        var running = CompactionGate.runningForTest(convo.id);
        assertNotNull(running, "the summary is still being written");
        assertFalse(running.isDone());
        assertEquals("Reply 2", run(agent, convo, "And then?").response());
        assertFalse(running.isDone(), "the second turn did not wait for it either");

        summaryGate.countDown();
        assertTrue(running.get(30, TimeUnit.SECONDS).compacted());
        awaitCompaction(convo.id);

        assertEquals(1, summaries.size(), "two turns in quick succession start one summary");
        assertEquals(1L, compactionCount(convo.id));
        for (var turn : turns) {
            assertFalse(systemPrompt(turn).contains(SessionCompactor.PRIOR_SUMMARY_HEADER), "both went uncompacted");
        }
        run(agent, convo, "After that?");
        assertTrue(systemPrompt(turns.getLast()).endsWith("SUMMARY 1"), "the turn after it carries the summary");
        assertEquals(1, summaries.size());
    }

    @Test
    void aFailedBackgroundSummaryLeavesTheConversationAsItWasAndTheNextTurnTriesAgain() throws Exception {
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(nextTurnTokens(agent, convo.id) / 2));
        failSummaries.set(true);
        summaryGate = new CountDownLatch(1);

        run(agent, convo, "Next?");
        var failed = openGateAndAwait(convo.id);

        assertFalse(failed.compacted());
        assertEquals("llm error", failed.skipReason());
        assertEquals(0L, compactionCount(convo.id));
        assertNull(Tx.run(() -> ((Conversation) Conversation.findById(convo.id)).compactionSince),
                "the floor did not move");

        failSummaries.set(false);
        summaryGate = new CountDownLatch(1);
        int before = summaries.size();
        run(agent, convo, "And then?");
        var retried = openGateAndAwait(convo.id);

        assertTrue(retried.compacted(), "the next turn past the trigger started another");
        assertEquals(before + 1, summaries.size());
        assertEquals(1L, compactionCount(convo.id));
        assertEquals("SUMMARY " + summaries.size(), latestCompaction(convo.id).summary);
    }

    @Test
    void slashCompactWaitsForTheRunningSummaryRatherThanRunningBesideIt() throws Exception {
        var agent = createAgent();
        var convo = conversation(agent);
        seed(convo, 26, 1200);
        commit();
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, String.valueOf(nextTurnTokens(agent, convo.id) / 2));
        summaryGate = new CountDownLatch(1);
        run(agent, convo, "Next?");
        var running = CompactionGate.runningForTest(convo.id);
        assertNotNull(running);

        awaitSummaries(1);

        var compactResult = new AtomicReference<Commands.Result>();
        var slash = Thread.ofVirtual().start(() -> compactResult.set(Commands.execute(Commands.Command.COMPACT,
                Tx.run(() -> (Agent) Agent.findById(agent.id)), channel, convo.peerId,
                Tx.run(() -> (Conversation) Conversation.findById(convo.id)), null, true)));
        slash.join(500);
        assertTrue(slash.isAlive(), "/compact waits while the background summary runs");
        assertEquals(1, summaries.size(), "and sends nothing meanwhile");

        summaryGate.countDown();
        slash.join(30_000);
        assertFalse(slash.isAlive());

        assertEquals(1, mostSummariesAtOnce.get(), "never two summary calls on the conversation at once");
        assertTrue(compactResult.get().responseText().startsWith("> Conversation Compacted"),
                compactResult.get().responseText());
        assertEquals(2L, compactionCount(convo.id), "the background summary, then /compact's on what it left");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Agent createAgent() {
        var agent = new Agent();
        agent.name = "jf367-agent";
        agent.modelProvider = provider;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private Conversation conversation(Agent agent) {
        var convo = ConversationService.create(agent, channel, "u-jf367");
        conversations.add(convo.id);
        return convo;
    }

    /** The request the conversation's next turn starts from, as the gate counts it. */
    private int nextTurnTokens(Agent agent, Long conversationId) {
        var turn = CompactionGate.nextTurnRequest(agent, conversationId);
        assertNotNull(turn);
        return ContextWindowManager.adjustedPromptTokens(provider, MODEL,
                TokenUsageEstimator.estimateChatRequest(MODEL, turn.messages(), turn.tools()));
    }

    private static SessionCompactor.CompactionResult awaitCompaction(Long conversationId) {
        var running = CompactionGate.runningForTest(conversationId);
        if (running == null) return null;
        try {
            return running.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the compaction did not end", e);
        }
    }

    /** Opens {@link #summaryGate} on the compaction the last turn left running, and returns how it ended. */
    private SessionCompactor.CompactionResult openGateAndAwait(Long conversationId) throws Exception {
        var running = CompactionGate.runningForTest(conversationId);
        assertNotNull(running, "the turn left a compaction running");
        summaryGate.countDown();
        return running.get(30, TimeUnit.SECONDS);
    }

    private void awaitSummaries(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (summaries.size() < count && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(count, summaries.size(), "summary calls that reached the provider");
    }

    private long prologueRewrite(String field) {
        var stats = LatencyStats.snapshot().getAsJsonObject(channel);
        if (stats == null || !stats.has("prologue_rewrite")) return 0;
        return stats.getAsJsonObject("prologue_rewrite").get(field).getAsLong();
    }

    private static String systemPrompt(JsonObject request) {
        return request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
    }

    private static SessionCompaction latestCompaction(Long conversationId) {
        JPA.em().clear();
        var latest = Tx.run(() -> SessionCompaction.findLatest(Conversation.findById(conversationId)));
        assertNotNull(latest, "a summary was stored");
        return latest;
    }

    private static long compactionCount(Long conversationId) {
        JPA.em().clear();
        return Tx.run(() -> SessionCompaction.count("conversation.id = ?1", conversationId));
    }

    private static AgentRunner.RunResult run(Agent agent, Conversation convo, String message) throws Exception {
        var result = new AtomicReference<AgentRunner.RunResult>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convo.id));
                result.set(AgentRunner.run(a, c, message));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the turn must finish within 60 s");
        if (error.get() != null) throw error.get();
        return result.get();
    }

    /** A streamed turn's completed reply; {@code onComplete} runs inside the completion callback. */
    private String stream(Agent agent, Conversation convo, String message, Runnable onComplete) throws Exception {
        var completed = new AtomicReference<String>();
        var failed = new AtomicReference<Exception>();
        var done = new CountDownLatch(1);
        var cb = new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { },
                content -> {
                    onComplete.run();
                    completed.set(content);
                    done.countDown();
                },
                error -> {
                    failed.set(error);
                    done.countDown();
                },
                done::countDown);
        AgentRunner.runStreaming(agent, convo.id, channel, convo.peerId, message, new AtomicBoolean(false), cb, null);
        assertTrue(done.await(60, TimeUnit.SECONDS), "the streamed turn must end within 60 s");
        if (failed.get() != null) throw failed.get();
        return completed.get();
    }

    private static String reply(String content, int promptTokens) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":5}}""".formatted(content, promptTokens);
    }

    /** Alternating user and assistant rows of {@code chars} characters of ledger prose, near 4 characters a token. */
    private static void seed(Conversation convo, int rows, int chars) {
        Tx.run(() -> {
            var managed = (Conversation) Conversation.findById(convo.id);
            for (int r = 0; r < rows; r++) {
                var sb = new StringBuilder(chars + 120);
                sb.append("Row ").append(r).append('.');
                for (int i = 0; sb.length() < chars; i++) {
                    sb.append(" Note ").append(i).append(": the ledger for account ").append(r * 10_000 + i)
                            .append(" lists invoices, refunds and adjustments in order.");
                }
                if (r % 2 == 0) ConversationService.appendUserMessage(managed, sb.toString());
                else ConversationService.appendAssistantMessage(managed, sb.toString(), null);
            }
        });
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
