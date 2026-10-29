import agents.AgentRunner;
import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool;
import tools.SubagentYieldTool;
import utils.CircuitBreakers;
import utils.GsonHolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-168: a subagent's own turn never suspends. Its {@code async} spawns run in the foreground, a single-run
 * {@code subagent_yield} there waits only on a batch child, and a child turn that still ends on the yield marker is
 * recorded FAILED with the yields it installed undone.
 */
class SubagentTurnYieldTest extends UnitTest {

    private static final String MODEL = "turn-yield-model";
    private static final String CHILD_TASK = "Summarize the findings.";
    private static final String GRANDCHILD_TASK = "Collect the raw figures.";
    private static final String GRANDCHILD_REPLY = "RAW_FIGURES";
    private static final String HELD_TASK = "Draft the summary.";
    private static final String HELD_REPLY = "SUMMARY_DRAFT";
    private static final String BATCH_TASK = "Count the rows.";
    private static final String BATCH_REPLY = "ROW_COUNT";
    private static final String MARKER_TASK = "Answer with the marker.";
    private static final String CHILD_SAW = "child saw: ";
    private static final String FOREGROUND_NOTE = "async ran in the foreground: nobody reads a background result in a "
            + "subagent's turn. Relay the reply yourself; there is nothing to collect with subagent_yield. To run "
            + "several children in parallel here, pass them as one tasks[] batch.";
    private static final String SUSPENDED_REASON = "The subagent stopped its turn to wait for another run and gave no reply.";

    private HttpServer llmServer;
    private ExecutorService llmThreads;
    private String provider;
    /** Whether the child sends its spawn beside a datetime call, so the spawn runs on a work-unit thread. */
    private volatile boolean besideAnotherCall;
    private final CountDownLatch releaseHeld = new CountDownLatch(1);
    /** Set when a held child's wait ran out; the model thread cannot fail the test itself. */
    private final AtomicReference<String> holdFailure = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        provider = "turn-yield-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A child's model call is answered while its parent's call is still open.
        llmThreads = Executors.newVirtualThreadPerTaskExecutor();
        llmServer.setExecutor(llmThreads);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        inFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Turn\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        // refresh() returns at once while another class's refresh runs, and that one may predate this commit.
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) {
            ProviderRegistry.refresh();
            if (ProviderRegistry.get(provider) == null) Thread.sleep(20);
        }
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void tearDown() {
        releaseHeld.countDown();
        llmServer.stop(0);
        llmThreads.shutdownNow();
        inFreshTx(() -> {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        ToolRegistrySync.release();
    }

    @Test
    void aSubagentsAsyncSpawnRunsInTheForegroundAndItsReplyReachesTheParent() throws Exception {
        childSpawnsAnAsyncGrandchild("session");
    }

    @Test
    void aSubagentsAsyncSpawnOnTheParallelToolPathRunsInTheForeground() throws Exception {
        besideAnotherCall = true;
        childSpawnsAnAsyncGrandchild("session");
    }

    @Test
    void anInlineSubagentsAsyncSpawnRunsInTheForeground() throws Exception {
        childSpawnsAnAsyncGrandchild("inline");
    }

    @Test
    void aSubagentsYieldOnARunningRunThatAnnouncesIsRefusedAndTheRunStillAnnounces() throws Exception {
        var agent = createAgent("turn-yield-announcing-" + provider);
        var conv = ConversationService.create(agent, "web", "u-turn-yield-announcing");
        commitAndReopen();
        // A top-level web-chat spawn: this run announces, and would resume a turn that yielded on it.
        var spawned = spawnIn(agent.id, conv.id, Map.of("task", HELD_TASK, "async", true, "label", "held"));
        var runId = JsonParser.parseString(spawned).getAsJsonObject().get("run_id").getAsLong();

        // An inline child running as the same agent shares the conversation, so only its run binding tells it apart.
        assertEquals("Error: a subagent cannot wait on run " + runId + " from its own turn, because nothing would "
                + "resume the turn. In a subagent's turn, subagent_yield waits only for a tasks[] batch child.",
                yieldAsSubagent(agent.id, conv.id, "{\"runId\":\"" + runId + "\"}"));
        JPA.em().clear();
        assertFalse(((SubagentRun) SubagentRun.findById(runId)).yielded, "a refused yield leaves the run as it was");

        releaseHeld.countDown();
        var announce = awaitAnnounce(conv.id);
        assertNull(holdFailure.get());
        assertEquals("Subagent completed (held): " + HELD_REPLY, announce.content, "the run's own announce still posts");
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.COMPLETED, run.status);
        assertEquals("{\"action\":\"already_terminal\",\"runId\":\"" + runId + "\",\"status\":\"COMPLETED\",\"reply\":\""
                + HELD_REPLY + "\",\"conversation_id\":\"" + run.childConversation.id + "\"}",
                yieldAsSubagent(agent.id, conv.id, "{\"runId\":\"" + runId + "\"}"), "a finished run answers with its result");
    }

    @Test
    void aSubagentsYieldOnItsBatchChildWaitsForItInline() throws Exception {
        var agent = createAgent("turn-yield-batch-" + provider);
        var conv = ConversationService.create(agent, "web", "u-turn-yield-batch");
        commitAndReopen();
        var spawned = spawnIn(agent.id, conv.id, Map.of("tasks", List.of(BATCH_TASK)));
        var runId = JsonParser.parseString(spawned).getAsJsonObject().getAsJsonArray("run_ids").get(0).getAsString();

        assertEquals("{\"run_id\":\"" + runId + "\",\"reply\":\"" + BATCH_REPLY + "\",\"status\":\"COMPLETED\"}",
                yieldAsSubagent(agent.id, conv.id, "{\"runId\":\"" + runId + "\"}"));
    }

    @Test
    void theBackstopUndoesTheYieldsADeadChildTurnInstalled() throws Exception {
        var agent = createAgent("turn-yield-undo-" + provider);
        var conv = ConversationService.create(agent, "web", "u-turn-yield-undo");
        var first = seedYieldedRun(agent, conv, "a");
        var second = seedYieldedRun(agent, conv, "b");
        commitAndReopen();
        var firstFuture = new CompletableFuture<Void>();
        var secondFuture = new CompletableFuture<Void>();
        SubagentRegistry.register(first, firstFuture);
        SubagentRegistry.register(second, secondFuture);
        try {
            assertTrue(SubagentRegistry.scheduleYieldTimeout(first, 1));
            assertTrue(SubagentRegistry.scheduleYieldTimeout(second, 1));

            var outcome = onVirtualThread(() -> SubagentSpawnTool.awaitFuture(CompletableFuture.completedFuture(
                    new AgentRunner.RunResult(AgentRunner.YIELDED_RESPONSE, null, false, List.of(first, second))),
                    30, 0, -System.nanoTime()));

            assertEquals(new SubagentSpawnTool.SyncRunOutcome("", SubagentRun.Status.FAILED, SUSPENDED_REASON,
                    SUSPENDED_REASON, false, false), outcome);
            JPA.em().clear();
            assertFalse(((SubagentRun) SubagentRun.findById(first)).yielded);
            assertFalse(((SubagentRun) SubagentRun.findById(second)).yielded);
            // Past the 1 s budget: a watchdog still armed would have failed the run's future by now.
            Thread.sleep(1_500);
            assertFalse(firstFuture.isDone(), "the first run's yield watchdog was disarmed");
            assertFalse(secondFuture.isDone(), "the second run's yield watchdog was disarmed");
        } finally {
            SubagentRegistry.unregister(first);
            SubagentRegistry.unregister(second);
        }
    }

    @Test
    void aSyncChildWhoseTurnEndsOnTheYieldMarkerIsRecordedFailed() throws Exception {
        var parent = createAgent("turn-yield-sync-marker-" + provider);
        var conv = ConversationService.create(parent, "web", "u-turn-yield-sync-marker");
        commitAndReopen();

        var spawned = spawnIn(parent.id, conv.id, Map.of("task", MARKER_TASK));

        var runId = JsonParser.parseString(spawned).getAsJsonObject().get("run_id").getAsString();
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(Long.parseLong(runId));
        var expected = new LinkedHashMap<String, Object>();
        expected.put("run_id", runId);
        expected.put("conversation_id", String.valueOf(run.childConversation.id));
        expected.put("reply", "");
        expected.put("status", "FAILED");
        expected.put("error", SUSPENDED_REASON);
        assertEquals(GsonHolder.GSON.toJson(expected, Map.class), spawned);
        assertEquals(SubagentRun.Status.FAILED, run.status);
        assertEquals(SUSPENDED_REASON, run.outcome);
    }

    @Test
    void anAsyncChildWhoseTurnEndsOnTheYieldMarkerIsRecordedFailedAndAnnouncedSo() throws Exception {
        var parent = createAgent("turn-yield-async-marker-" + provider);
        var conv = ConversationService.create(parent, "web", "u-turn-yield-async-marker");
        commitAndReopen();

        var spawned = spawnIn(parent.id, conv.id, Map.of("task", MARKER_TASK, "async", true, "label", "marker"));

        var runId = Long.parseLong(JsonParser.parseString(spawned).getAsJsonObject().get("run_id").getAsString());
        var announce = awaitAnnounce(conv.id);
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.FAILED, run.status);
        assertEquals(SUSPENDED_REASON, run.outcome);
        assertEquals("Subagent failed (marker): " + SUSPENDED_REASON, announce.content);
    }

    @Test
    void aBatchChildWhoseTurnEndsOnTheYieldMarkerIsCollectedFailed() throws Exception {
        var parent = createAgent("turn-yield-batch-marker-" + provider);
        var conv = ConversationService.create(parent, "web", "u-turn-yield-batch-marker");
        commitAndReopen();

        var spawned = spawnIn(parent.id, conv.id, Map.of("tasks", List.of(MARKER_TASK)));
        var runId = JsonParser.parseString(spawned).getAsJsonObject().getAsJsonArray("run_ids").get(0).getAsString();

        assertEquals("{\"results\":[{\"run_id\":\"" + runId + "\",\"reply\":\"\",\"status\":\"FAILED\",\"error\":\""
                + SUSPENDED_REASON + "\"}],\"count\":1}",
                onVirtualThread(() -> ToolContext.withConversation(conv.id, () -> new SubagentYieldTool().execute(
                        "{\"runIds\":[\"" + runId + "\"]}", Tx.run(() -> (Agent) Agent.findById(parent.id))))));
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(Long.parseLong(runId));
        assertEquals(SubagentRun.Status.FAILED, run.status);
        assertEquals(SUSPENDED_REASON, run.outcome);
    }

    /**
     * The parent runs itself as a child ({@code agentId}, so it may spawn again), and that child's real turn spawns
     * an {@code async} grandchild and answers from what the spawn returned.
     */
    private void childSpawnsAnAsyncGrandchild(String mode) throws Exception {
        var parent = createAgent("turn-yield-" + mode + "-" + provider);
        var conv = ConversationService.create(parent, "web", "u-turn-yield-" + mode);
        commitAndReopen();

        var spawned = spawnIn(parent.id, conv.id, Map.of("task", CHILD_TASK, "agentId", parent.id, "mode", mode));

        var childRunId = Long.parseLong(JsonParser.parseString(spawned).getAsJsonObject().get("run_id").getAsString());
        JPA.em().clear();
        SubagentRun child = SubagentRun.findById(childRunId);
        SubagentRun grandchild = SubagentRun.find("parentAgent.id = ?1 AND id <> ?2", parent.id, childRunId).first();
        assertNotNull(grandchild, "the child spawned a grandchild: " + spawned);
        var grandchildResult = new LinkedHashMap<String, Object>();
        grandchildResult.put("run_id", String.valueOf(grandchild.id));
        grandchildResult.put("conversation_id", String.valueOf(grandchild.childConversation.id));
        grandchildResult.put("reply", GRANDCHILD_REPLY);
        grandchildResult.put("status", "COMPLETED");
        grandchildResult.put("note", FOREGROUND_NOTE);
        var childReply = CHILD_SAW + GsonHolder.GSON.toJson(grandchildResult, Map.class);
        var expected = new LinkedHashMap<String, Object>();
        expected.put("run_id", String.valueOf(childRunId));
        expected.put("conversation_id", String.valueOf(child.childConversation.id));
        expected.put("reply", childReply);
        expected.put("status", "COMPLETED");
        assertEquals(GsonHolder.GSON.toJson(expected, Map.class), spawned,
                "the grandchild's spawn returned its reply in the foreground, and the child answered from it");
        assertEquals(SubagentRun.Status.COMPLETED, child.status);
        assertEquals(childReply, child.outcome);
        assertEquals(SubagentRun.Status.COMPLETED, grandchild.status);
        assertEquals(GRANDCHILD_REPLY, grandchild.outcome);
        assertFalse(grandchild.yielded);
        assertEquals(0, Message.count("conversation.id = ?1 AND messageKind = ?2",
                grandchild.parentConversation.id, SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE),
                "a foreground run posts no announce");
    }

    private Long seedYieldedRun(Agent parent, Conversation conv, String suffix) {
        var child = createAgent(parent.name + "-" + suffix);
        child.parentAgent = parent;
        child.save();
        var childConv = ConversationService.create(child, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = conv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = child;
        run.parentConversation = conv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.yielded = true;
        run.save();
        return run.id;
    }

    /** Calls subagent_yield in {@code conversationId} as a subagent's own turn does: on a thread bound to a run. */
    private String yieldAsSubagent(Long agentId, Long conversationId, String argsJson) throws Exception {
        long ownRun = -System.nanoTime();
        SubagentRegistry.register(ownRun, new CompletableFuture<Void>());
        try {
            return onVirtualThread(() -> SubagentRegistry.callAsRun(ownRun, () -> ToolContext.withConversation(
                    conversationId, () -> new SubagentYieldTool().execute(argsJson,
                            Tx.run(() -> (Agent) Agent.findById(agentId))))));
        } finally {
            SubagentRegistry.unregister(ownRun);
        }
    }

    private String spawnIn(Long parentId, Long conversationId, Map<String, Object> args) throws Exception {
        return onVirtualThread(() -> ToolContext.withConversation(conversationId,
                () -> new SubagentSpawnTool().execute(GsonHolder.GSON.toJson(args),
                        Tx.run(() -> (Agent) Agent.findById(parentId)))));
    }

    private Agent createAgent(String name) {
        var agent = AgentService.create(name, provider, MODEL);
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** Routes each model call by its conversation's task and the tool results that end it. */
    private void answer(HttpExchange exchange) throws IOException {
        var messages = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("messages");
        var userText = new StringBuilder();
        var calledBefore = false;
        for (var m : messages) {
            if ("user".equals(role(m.getAsJsonObject()))) userText.append(textOf(m.getAsJsonObject()));
            calledBefore |= m.getAsJsonObject().has("tool_calls");
        }
        var results = new LinkedHashMap<String, String>();
        for (var i = messages.size() - 1; i >= 0 && "tool".equals(role(messages.get(i).getAsJsonObject())); i--) {
            var m = messages.get(i).getAsJsonObject();
            results.put(m.get("tool_call_id").getAsString(), textOf(m));
        }
        var task = userText.toString();
        if (task.contains(MARKER_TASK)) {
            LlmReplyFixture.send(exchange, reply(AgentRunner.YIELDED_RESPONSE));
        } else if (task.contains(GRANDCHILD_TASK)) {
            LlmReplyFixture.send(exchange, reply(GRANDCHILD_REPLY));
        } else if (task.contains(BATCH_TASK)) {
            LlmReplyFixture.send(exchange, reply(BATCH_REPLY));
        } else if (task.contains(HELD_TASK)) {
            try {
                if (!releaseHeld.await(10, TimeUnit.SECONDS)) holdFailure.set("the test never released the held child");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            LlmReplyFixture.send(exchange, reply(HELD_REPLY));
        } else if (results.containsKey("call_spawn")) {
            LlmReplyFixture.send(exchange, reply(CHILD_SAW + results.get("call_spawn")));
        } else if (!calledBefore && task.contains(CHILD_TASK)) {
            LlmReplyFixture.send(exchange, toolCalls(new Call("call_spawn", "subagent_spawn",
                    Map.of("task", GRANDCHILD_TASK, "async", true))));
        } else {
            LlmReplyFixture.send(exchange, reply("UNEXPECTED: " + textOf(messages.get(messages.size() - 1).getAsJsonObject())));
        }
    }

    private Message awaitAnnounce(Long conversationId) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Message announce = inFreshTx(() -> Message.find("conversation.id = ?1 AND messageKind = ?2",
                    conversationId, SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE).first());
            if (announce != null) return announce;
            Thread.sleep(50);
        }
        fail("no announce posted within 30s");
        return null;
    }

    private record Call(String id, String name, Map<String, Object> arguments) {}

    /** The child's call, beside a datetime call when the test sends it down the parallel path. */
    private String toolCalls(Call call) {
        var all = new ArrayList<Call>(List.of(call));
        if (besideAnotherCall) all.add(new Call(call.id() + "_time", "datetime", Map.of("action", "now")));
        var calls = new JsonArray();
        for (var c : all) {
            var function = new JsonObject();
            function.addProperty("name", c.name());
            function.addProperty("arguments", GsonHolder.GSON.toJson(c.arguments()));
            var json = new JsonObject();
            json.addProperty("id", c.id());
            json.addProperty("type", "function");
            json.add("function", function);
            calls.add(json);
        }
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "");
        message.add("tool_calls", calls);
        return completion(message, "tool_calls");
    }

    private static String role(JsonObject message) {
        return message.get("role").getAsString();
    }

    private static String textOf(JsonObject message) {
        var content = message.get("content");
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        var text = new StringBuilder();
        for (var part : content.getAsJsonArray()) {
            var p = part.getAsJsonObject();
            if (p.has("text")) text.append(p.get("text").getAsString());
        }
        return text.toString();
    }

    private static String reply(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return completion(message, "stop");
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

    /** Runs {@code body} on its own thread, outside the test's transaction, as a turn's tool call runs; never waits past 60s. */
    private static <T> T onVirtualThread(Supplier<T> body) throws Exception {
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(body.get());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the call did not return within 60s");
        if (error.get() instanceof Exception e) throw e;
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static <T> T inFreshTx(Supplier<T> block) {
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
