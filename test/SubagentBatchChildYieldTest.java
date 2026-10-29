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
import services.Tx;
import tools.SubagentSpawnTool;
import tools.SubagentYieldTool;
import utils.CircuitBreakers;
import utils.GsonHolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-169: a single-run {@code subagent_yield} on a {@code tasks[]} batch child, which posts no announce and resumes
 * nothing, block-awaits it and returns its outcome inline instead of suspending the turn for good.
 */
class SubagentBatchChildYieldTest extends UnitTest {

    private static final String MODEL = "batch-yield-model";
    private static final String PARENT_ASK = "Research both topics.";
    private static final String TASK_A = "Research topic alpha.";
    private static final String TASK_B = "Research topic beta.";
    private static final String REPLY_A = "ALPHA_REPORT";
    private static final String REPLY_B = "BETA_REPORT";
    private static final String SAW = "saw: ";
    private static final String FINISHED_NOTE = "Nothing is waiting to be collected. The runs in finished have ended "
            + "and keep their reports: pass the ids of any whose report you have not seen as runIds to read it. "
            + "Calling subagent_yield with all=true again will not change that.";

    private HttpServer llmServer;
    private ExecutorService llmThreads;
    private String provider;
    private final List<String> spawnedRunIds = new CopyOnWriteArrayList<>();
    /** The conversation whose yield child A's answer waits on, so the yield always meets a running child. */
    private volatile Long gatedConversation;
    /** Set when child A's wait for the yield ran out; the model thread cannot fail the test itself. */
    private final AtomicReference<String> holdFailure = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        provider = "batch-yield-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The children call the model while the parent's turn is still waiting on them.
        llmThreads = Executors.newVirtualThreadPerTaskExecutor();
        llmServer.setExecutor(llmThreads);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        inFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Batch\",\"contextWindow\":100000,\"maxTokens\":4096}]");
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
    void aSingleYieldOnABatchChildReturnsItsOutcomeAndTheTurnGoesOn() throws Exception {
        var parent = createAgent("batch-yield-turn-" + provider);
        var conv = ConversationService.create(parent, "web", "u-batch-yield-turn");
        commitAndReopen();
        gatedConversation = conv.id;

        var result = onVirtualThread(() -> AgentRunner.run(Tx.run(() -> (Agent) Agent.findById(parent.id)),
                Tx.run(() -> (Conversation) Conversation.findById(conv.id)), PARENT_ASK));

        assertNull(holdFailure.get());
        assertEquals(2, spawnedRunIds.size(), "the batch started both children: " + spawnedRunIds);
        var runA = spawnedRunIds.get(0);
        var runB = spawnedRunIds.get(1);
        assertFalse(result.yielded(), "the turn did not suspend");
        assertEquals(SAW + outcome(runA, REPLY_A), result.response(),
                "the yield returned the child's outcome inline, and the turn answered from it");
        JPA.em().clear();
        SubagentRun a = SubagentRun.findById(Long.parseLong(runA));
        assertEquals(SubagentRun.Status.COMPLETED, a.status);
        assertEquals(REPLY_A, a.outcome);
        assertFalse(a.yielded, "nothing will resume the turn, so the run is not marked yielded");
        assertEquals(0, Message.count("conversation.id = ?1 AND messageKind = ?2",
                conv.id, SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE), "a batch child posts no announce");

        // Collected once inline, the child is reported as collected by the batch forms, and nothing waits.
        assertEquals("{\"results\":[" + outcome(runA, REPLY_A) + "],\"count\":1}",
                yieldIn(parent.id, conv.id, "{\"runIds\":[\"" + runA + "\"]}"),
                "runIds reads the collected child's saved report back");
        assertEquals("{\"results\":[" + outcome(runB, REPLY_B) + "],\"count\":1}",
                yieldIn(parent.id, conv.id, "{\"all\":true}"),
                "all collects only the child the single yield left");
        assertEquals("{\"results\":[],\"count\":0,\"finished\":[{\"run_id\":\"" + runB + "\",\"status\":\"COMPLETED\"},"
                + "{\"run_id\":\"" + runA + "\",\"status\":\"COMPLETED\"}],\"note\":\"" + FINISHED_NOTE + "\"}",
                yieldIn(parent.id, conv.id, "{\"all\":true}"), "neither child is returned twice");
    }

    @Test
    void aSingleYieldOnAFinishedBatchChildReturnsItsOutcomeOnceAndAllDoesNotReturnItAgain() throws Exception {
        var parent = createAgent("batch-yield-finished-" + provider);
        var conv = ConversationService.create(parent, "web", "u-batch-yield-finished");
        commitAndReopen();

        var spawned = JsonParser.parseString(onVirtualThread(() -> ToolContext.withConversation(conv.id,
                () -> new SubagentSpawnTool().execute(GsonHolder.GSON.toJson(Map.of("tasks", List.of(TASK_A))),
                        Tx.run(() -> (Agent) Agent.findById(parent.id))))))
                .getAsJsonObject();
        var runA = spawned.getAsJsonArray("run_ids").get(0).getAsString();
        awaitFinished(Long.parseLong(runA));

        // Ahead of the status check: a finished child whose outcome is still held is collected, not answered already_terminal.
        assertEquals(outcome(runA, REPLY_A), yieldIn(parent.id, conv.id, "{\"runId\":\"" + runA + "\"}"));
        assertEquals("{\"results\":[],\"count\":0,\"finished\":[{\"run_id\":\"" + runA + "\",\"status\":\"COMPLETED\"}],"
                + "\"note\":\"" + FINISHED_NOTE + "\"}", yieldIn(parent.id, conv.id, "{\"all\":true}"),
                "the child the single yield collected is not returned again");
    }

    @Test
    void aSingleYieldOnABatchChildAlreadyCollectedReportsItsOutcomeAndDoesNotSuspend() throws Exception {
        var parent = createAgent("batch-yield-again-" + provider);
        var conv = ConversationService.create(parent, "web", "u-batch-yield-again");
        commitAndReopen();

        var spawned = JsonParser.parseString(onVirtualThread(() -> ToolContext.withConversation(conv.id,
                () -> new SubagentSpawnTool().execute(GsonHolder.GSON.toJson(Map.of("tasks", List.of(TASK_A))),
                        Tx.run(() -> (Agent) Agent.findById(parent.id))))))
                .getAsJsonObject();
        var runA = spawned.getAsJsonArray("run_ids").get(0).getAsString();
        assertEquals("{\"results\":[" + outcome(runA, REPLY_A) + "],\"count\":1}",
                yieldIn(parent.id, conv.id, "{\"runIds\":[\"" + runA + "\"]}"));

        JPA.em().clear();
        SubagentRun a = SubagentRun.findById(Long.parseLong(runA));
        assertEquals("{\"action\":\"already_terminal\",\"runId\":\"" + runA + "\",\"status\":\"COMPLETED\","
                + "\"reply\":\"" + REPLY_A + "\",\"conversation_id\":\"" + a.childConversation.id + "\"}",
                yieldIn(parent.id, conv.id, "{\"runId\":\"" + runA + "\"}"));
        JPA.em().clear();
        assertFalse(((SubagentRun) SubagentRun.findById(Long.parseLong(runA))).yielded);
    }

    private static void awaitFinished(long runId) throws InterruptedException {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            SubagentRun run = inFreshTx(() -> SubagentRun.findById(runId));
            if (run.status != SubagentRun.Status.RUNNING) return;
            Thread.sleep(20);
        }
        fail("run " + runId + " did not finish within 10s");
    }

    private static String outcome(String runId, String reply) {
        return "{\"run_id\":\"" + runId + "\",\"reply\":\"" + reply + "\",\"status\":\"COMPLETED\"}";
    }

    private Agent createAgent(String name) {
        var agent = AgentService.create(name, provider, MODEL);
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** Routes each model call by what it carries: a child's task, the spawn's result, or the yield's. */
    private void answer(HttpExchange exchange) throws IOException {
        var messages = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("messages");
        var userText = new StringBuilder();
        for (var m : messages) {
            if ("user".equals(m.getAsJsonObject().get("role").getAsString())) userText.append(textOf(m.getAsJsonObject()));
        }
        var last = messages.get(messages.size() - 1).getAsJsonObject();
        if (userText.toString().contains(TASK_A)) {
            var gate = gatedConversation;
            if (gate != null) awaitTheYieldOnChildA(gate);
            LlmReplyFixture.send(exchange, reply(REPLY_A));
        } else if (userText.toString().contains(TASK_B)) {
            LlmReplyFixture.send(exchange, reply(REPLY_B));
        } else if (!"tool".equals(last.get("role").getAsString())) {
            LlmReplyFixture.send(exchange, toolCall("call_spawn", "subagent_spawn", Map.of("tasks", List.of(TASK_A, TASK_B))));
        } else if (textOf(last).contains("\"run_ids\"")) {
            for (var id : JsonParser.parseString(textOf(last)).getAsJsonObject().getAsJsonArray("run_ids")) {
                spawnedRunIds.add(id.getAsString());
            }
            LlmReplyFixture.send(exchange, toolCall("call_yield", "subagent_yield", Map.of("runId", spawnedRunIds.getFirst())));
        } else {
            LlmReplyFixture.send(exchange, reply(SAW + textOf(last)));
        }
    }

    /** Until the parent's yield on child A has taken hold: claimed its outcome (fixed) or marked it yielded (unfixed). */
    private void awaitTheYieldOnChildA(Long conversationId) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!spawnedRunIds.isEmpty()) {
                var runA = Long.parseLong(spawnedRunIds.getFirst());
                var claimed = !ToolContext.withConversation(conversationId, SubagentSpawnTool::outstandingForCurrentScope)
                        .contains(runA);
                if (claimed || Tx.run(() -> ((SubagentRun) SubagentRun.findById(runA)).yielded)) return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        holdFailure.set("the parent's yield on child A never took hold within 10s");
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

    private static String toolCall(String id, String name, Map<String, Object> arguments) {
        var function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", GsonHolder.GSON.toJson(arguments));
        var call = new JsonObject();
        call.addProperty("id", id);
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

    private String yieldIn(Long parentId, Long conversationId, String argsJson) throws Exception {
        return onVirtualThread(() -> ToolContext.withConversation(conversationId,
                () -> new SubagentYieldTool().execute(argsJson, Tx.run(() -> (Agent) Agent.findById(parentId)))));
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
