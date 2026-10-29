import agents.AgentRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A turn sent through {@code POST /api/chat/send} that starts a subagent, in the foreground or the background, sees it run (JF-163). */
class ChatSendSubagentTest extends FunctionalTest {

    private static final String MODEL = "send-subagent-model";
    private static final String PARENT_ASK = "Delegate the report.";
    private static final String CHILD_TASK = "Write the quarterly report.";
    private static final String CHILD_REPLY = "CHILD_REPORT_READY";
    private static final String PARENT_FINAL = "The report is ready.";
    private static final Pattern RUN_ID = Pattern.compile("\"run_id\":\"(\\d+)\"");

    private HttpServer llmServer;
    private ExecutorService llmThreads;
    private String provider;
    private final CountDownLatch childAsked = new CountDownLatch(1);
    private final CountDownLatch releaseChild = new CountDownLatch(1);
    private volatile boolean asyncSpawn;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        provider = "send-subagent-" + UUID.randomUUID().toString().substring(0, 8);
        // A real port, because the request runs on Play's thread, where an HttpFactories binding does not reach.
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The parent's second round waits on the child's call, so the two must be answered concurrently.
        llmThreads = Executors.newVirtualThreadPerTaskExecutor();
        llmServer.setExecutor(llmThreads);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commitInFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Send\",\"contextWindow\":100000,\"maxTokens\":4096}]");
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
        releaseChild.countDown();
        llmServer.stop(0);
        llmThreads.shutdownNow();
        commitInFreshTx(() -> {
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
    void aSubagentRunInTheForegroundOfASentTurnRunsAndItsReplyReachesTheTurn() throws Exception {
        releaseChild.countDown();
        login();
        var agentId = seedAgent();

        var raw = send(agentId);
        var body = JsonParser.parseString(raw).getAsJsonObject();
        long conversationId = body.get("conversationId").getAsLong();
        assertEquals(JsonParser.parseString("""
                {"conversationId": %d, "response": "%s", "agentId": %d, "agentName": "%s"}"""
                .formatted(conversationId, PARENT_FINAL, agentId, agentName())), body,
                "a turn that does not yield answers its reply and nothing more");
        assertFalse(raw.contains(AgentRunner.YIELDED_RESPONSE), raw);
        assertTrue(childAsked.await(0, TimeUnit.SECONDS), "the child never reached the model");
        SubagentRun run = commitInFreshTx(() -> SubagentRun.find("parentConversation.id = ?1", conversationId).first());
        assertEquals(SubagentRun.Status.COMPLETED, run.status);
        assertEquals(CHILD_REPLY, run.outcome);
    }

    @Test
    void anAsyncSubagentStartedInASentTurnRunsAndResumesTheYieldedTurnWithItsReply() throws Exception {
        asyncSpawn = true;
        login();
        var agentId = seedAgent();

        var raw = send(agentId);
        assertTrue(childAsked.await(0, TimeUnit.SECONDS), "the child never reached the model before the parent yielded");
        var body = JsonParser.parseString(raw).getAsJsonObject();
        long conversationId = body.get("conversationId").getAsLong();
        SubagentRun awaited = commitInFreshTx(() -> SubagentRun.find("parentConversation.id = ?1", conversationId).first());
        assertEquals(JsonParser.parseString("""
                {"conversationId": %d, "response": null, "agentId": %d, "agentName": "%s",
                 "status": "waiting", "waitingOnRunIds": [%d]}"""
                .formatted(conversationId, agentId, agentName(), awaited.id)), body,
                "the turn suspends on subagent_yield and the request answers that it is waiting on the run");
        assertFalse(raw.contains(AgentRunner.YIELDED_RESPONSE), raw);
        releaseChild.countDown();

        var last = awaitLastMessage(conversationId, PARENT_FINAL, 30_000);
        assertEquals(MessageRole.ASSISTANT.value, last.role);

        SubagentRun run = commitInFreshTx(() -> SubagentRun.find("parentConversation.id = ?1", conversationId).first());
        assertEquals(SubagentRun.Status.COMPLETED, run.status);
        assertEquals(CHILD_REPLY, run.outcome);
        assertTrue(run.yielded, "the parent yielded on the run before the child answered");

        List<Message> announces = commitInFreshTx(() -> Message.find("conversation.id = ?1 AND messageKind = ?2",
                conversationId, SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE).fetch());
        assertEquals(1, announces.size());
        var announce = announces.getFirst();
        assertEquals(MessageRole.USER.value, announce.role, "a yielded parent reads the announce as its next input");
        assertEquals("Subagent completed (report): " + CHILD_REPLY, announce.content);
    }

    private String agentName() {
        return "send-subagent-" + provider;
    }

    private Long seedAgent() {
        return commitInFreshTx(() -> {
            var agent = AgentService.create(agentName(), provider, MODEL);
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
    }

    private String send(Long agentId) {
        var sent = POST("/api/chat/send", "application/json",
                "{\"agentId\": %d, \"message\": \"%s\"}".formatted(agentId, PARENT_ASK));
        assertIsOk(sent);
        return getContent(sent);
    }

    /** Routes each model call by the messages it carries; the order the turn makes them in is the test. */
    private void answer(HttpExchange exchange) throws IOException {
        var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        var messages = request.getAsJsonArray("messages");
        var userText = new StringBuilder();
        for (var m : messages) {
            if ("user".equals(m.getAsJsonObject().get("role").getAsString())) userText.append(textOf(m.getAsJsonObject()));
        }
        var last = messages.get(messages.size() - 1).getAsJsonObject();
        if (!userText.toString().contains(PARENT_ASK)) {
            childAsked.countDown();
            LlmReplyFixture.send(exchange, reply(await(releaseChild, 30) ? CHILD_REPLY : "the test never released the child"));
        } else if ("tool".equals(last.get("role").getAsString()) && !asyncSpawn) {
            var result = textOf(last);
            LlmReplyFixture.send(exchange, reply(result.contains(CHILD_REPLY) ? PARENT_FINAL
                    : "the spawn returned no child reply: " + result));
        } else if ("tool".equals(last.get("role").getAsString())) {
            // The yield waits for the child's call, so the parent always yields on a child that is running.
            await(childAsked, 10);
            var runId = RUN_ID.matcher(textOf(last));
            LlmReplyFixture.send(exchange, toolCall("call_yield", "subagent_yield",
                    Map.of("runId", runId.find() ? runId.group(1) : "no run id in: " + textOf(last))));
        } else if (userText.toString().contains("Subagent ")) {
            LlmReplyFixture.send(exchange, reply(userText.toString().contains(CHILD_REPLY) ? PARENT_FINAL
                    : "the announce carried no child reply: " + userText));
        } else {
            var spawn = new java.util.LinkedHashMap<String, Object>(Map.of("task", CHILD_TASK, "label", "report"));
            if (asyncSpawn) spawn.put("async", true);
            LlmReplyFixture.send(exchange, toolCall("call_spawn", "subagent_spawn", spawn));
        }
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

    private static boolean await(CountDownLatch latch, int seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        function.addProperty("arguments", utils.GsonHolder.GSON.toJson(arguments));
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

    private static Message awaitLastMessage(long conversationId, String content, long timeoutMillis)
            throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMillis;
        Message last = null;
        while (System.currentTimeMillis() < deadline) {
            last = commitInFreshTx(() -> Message.find("conversation.id = ?1 ORDER BY id DESC", conversationId).first());
            if (last != null && content.equals(last.content)) return last;
            Thread.sleep(100);
        }
        fail("the conversation never ended on \"" + content + "\"; its last message was: "
                + (last == null ? "none" : last.role + " \"" + last.content + "\""));
        return last;
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
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
