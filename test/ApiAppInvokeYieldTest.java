import agents.AgentRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** What {@code POST /api/apps/<slug>/invoke} answers when the app's turn calls {@code subagent_yield} (JF-166). */
class ApiAppInvokeYieldTest extends FunctionalTest {

    private static final String MODEL = "invoke-yield-model";
    private static final String APP_ASK = "Check on the report.";
    private static final String APP_REPLY = "Here is your answer.";

    private HttpServer llmServer;
    private String provider;
    private Long agentId;
    private String slug;
    private Path appDir;
    private volatile Long yieldOn;
    private volatile String refusal;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        provider = "invoke-yield-" + UUID.randomUUID().toString().substring(0, 8);
        // A real port, because the request runs on Play's thread, where an HttpFactories binding does not reach.
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commitInFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Invoke\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        // refresh() returns at once while another class's refresh runs, and that one may predate this commit.
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) {
            ProviderRegistry.refresh();
            if (ProviderRegistry.get(provider) == null) Thread.sleep(20);
        }
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");

        agentId = commitInFreshTx(() -> {
            var agent = AgentService.create(provider, provider, MODEL);
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
        slug = "jclaw-test-invoke-yield-" + UUID.randomUUID().toString().substring(0, 8);
        appDir = Play.getFile("public/apps").toPath().resolve(slug);
        Files.createDirectories(appDir);
        Files.writeString(appDir.resolve("app.json"),
                "{\"name\":\"Yield\",\"version\":\"1.0.0\",\"agent\":\"" + agentId + "\"}");
        Files.writeString(appDir.resolve("index.html"), "<html></html>");
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            if (llmServer != null) llmServer.stop(0);
            commitInFreshTx(() -> {
                for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                    ConfigService.delete("provider." + provider + suffix);
                }
                return null;
            });
            ProviderRegistry.refresh();
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
            if (appDir != null && Files.exists(appDir)) {
                try (var walk = Files.walk(appDir)) {
                    for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void aYieldOnARunTheAgentStartedFromAnotherChatIsRefusedAndTheTurnReplies() {
        long runId = seedRunningRunFromWebChat();
        yieldOn = runId;
        refusal = "Error: run " + runId + " was not started from this conversation; "
                + "a turn can wait only on a run its own conversation started.";

        var raw = invoke();
        long conversationId = latestAppConversationId();
        assertEquals(JsonParser.parseString("""
                {"conversationId": %d, "response": "%s", "files": []}""".formatted(conversationId, APP_REPLY)),
                JsonParser.parseString(raw), "the model read the refusal and replied");
        assertFalse(raw.contains(AgentRunner.YIELDED_RESPONSE), raw);
        SubagentRun run = commitInFreshTx(() -> SubagentRun.findById(runId));
        assertFalse(run.yielded, "the web chat's run is not waited on");
    }

    @Test
    void aTurnThatDoesNotYieldAnswersItsReplyWithNoStatus() {
        var raw = invoke();
        long conversationId = latestAppConversationId();
        assertEquals(JsonParser.parseString("""
                {"conversationId": %d, "response": "%s", "files": []}""".formatted(conversationId, APP_REPLY)),
                JsonParser.parseString(raw));
        assertFalse(raw.contains(AgentRunner.YIELDED_RESPONSE), raw);
    }

    private String invoke() {
        var req = newRequest();
        req.headers.put("referer", new Http.Header("referer", "http://localhost/apps/" + slug + "/"));
        req.headers.put("sec-fetch-site", new Http.Header("sec-fetch-site", "same-origin"));
        var response = POST(req, "/api/apps/" + slug + "/invoke", Map.of("message", APP_ASK), Map.of());
        assertIsOk(response);
        return getContent(response);
    }

    private long seedRunningRunFromWebChat() {
        return commitInFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            var webChat = ConversationService.create(agent, "web", "admin");
            var child = AgentService.create(provider + "-child", provider, MODEL);
            child.parentAgent = agent;
            child.save();
            var childChat = ConversationService.create(child, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
            childChat.parentConversation = webChat;
            childChat.save();
            var run = new SubagentRun();
            run.parentAgent = agent;
            run.childAgent = child;
            run.parentConversation = webChat;
            run.childConversation = childChat;
            run.status = SubagentRun.Status.RUNNING;
            run.save();
            return run.id;
        });
    }

    private long latestAppConversationId() {
        Conversation latest = commitInFreshTx(() ->
                Conversation.find("channelType = ?1 and peerId = ?2 order by id desc", "app", slug).first());
        return latest.id;
    }

    private void answer(HttpExchange exchange) throws IOException {
        var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        var messages = request.getAsJsonArray("messages");
        var lastRole = messages.get(messages.size() - 1).getAsJsonObject().get("role").getAsString();
        var runId = yieldOn;
        if (runId == null) {
            LlmReplyFixture.send(exchange, reply(APP_REPLY));
        } else if ("tool".equals(lastRole)) {
            var result = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
            LlmReplyFixture.send(exchange, reply(result.equals(refusal) ? APP_REPLY : "the yield returned: " + result));
        } else {
            LlmReplyFixture.send(exchange, yieldCall(runId));
        }
    }

    private static String reply(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return completion(message, "stop");
    }

    private static String yieldCall(long runId) {
        var function = new JsonObject();
        function.addProperty("name", "subagent_yield");
        function.addProperty("arguments", "{\"runId\":\"" + runId + "\"}");
        var call = new JsonObject();
        call.addProperty("id", "call_yield");
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
