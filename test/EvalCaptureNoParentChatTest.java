import agents.AgentRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import services.evals.EvalCapture;
import services.evals.EvalCase;
import services.evals.EvalScorer;
import services.evals.EvalSuite;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-158: a capture case runs through {@link AgentRunner#runForTask} on a conversation that is never saved, so a
 * tool its model calls must not stand the agent's latest chat in as the turn's own. Each test seeds a recent chat for
 * the agent and drives a real capture against a scripted model.
 */
class EvalCaptureNoParentChatTest extends UnitTest {

    private static final String CASE_INPUT = "JF158-CASE where are the paper's figures?";
    private static final String DONE = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    // Own provider name: breakers and the provider cache are process-global, keyed by name.
    private final String provider = "jf158-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<String> firstCall = new AtomicReference<>();
    private HttpServer llmServer;
    private Long agentId;
    private Long recentChatId;

    @BeforeEach
    void setup() throws Exception {
        ConfigService.clearCache();
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            var messages = body.getAsJsonArray("messages");
            LlmReplyFixture.send(exchange, isCaseOpening(messages) ? toolCallReply(firstCall.get()) : DONE);
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"agent-model\",\"name\":\"Agent\",\"contextWindow\":64000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();

        var agent = AgentService.create(provider + "-agent", provider, "agent-model");
        agent.enabled = true;
        agent.memoryAutocaptureEnabled = false;
        agent.save();
        agentId = agent.id;
        var recent = ConversationService.create(agent, "web", "admin");
        ConversationService.appendUserMessage(recent, "an unrelated chat the operator opened");
        recentChatId = recent.id;
        commit();
    }

    @AfterEach
    void teardown() {
        llmServer.stop(0);
        CircuitBreakers.remove(provider);
        for (var suffix : new String[] {"baseUrl", "apiKey", "models"}) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
    }

    @Test
    void anInlineSpawnInACaptureWritesNothingToTheAgentsRecentChat() throws Exception {
        firstCall.set("""
                {"id":"call_1","type":"function","function":{"name":"subagent_spawn",
                 "arguments":"{\\"task\\":\\"find the figures\\",\\"mode\\":\\"inline\\"}"}}""");
        long before = rowCount(recentChatId);

        var response = capture();

        assertEquals(List.of("subagent_spawn"), response.toolsCalled(), "control: the spawn ran");
        var result = JsonParser.parseString(response.toolResults().get("subagent_spawn").getFirst()).getAsJsonObject();
        assertEquals("COMPLETED", result.get("status").getAsString(), result.toString());
        assertEquals(before, rowCount(recentChatId), "the spawn wrote nothing into the agent's recent chat");
        assertEquals(1L, rowCount(recentChatId), "the seeded row alone");
        assertEquals("mode=\"inline\" ran as mode=\"session\": this turn has no conversation to embed the child into.",
                result.has("note") ? result.get("note").getAsString() : null);

        var runId = Long.valueOf(result.get("run_id").getAsString());
        var run = Tx.run(() -> {
            SubagentRun r = SubagentRun.findById(runId);
            return new Object[] {r.parentConversation, r.parentTaskRun,
                    r.childConversation.agent.parentAgent.id, r.childConversation.parentConversation};
        });
        assertNull(run[0], "a capture's run records no parent conversation");
        assertNull(run[1], "nor a task run: a capture has none");
        assertEquals(agentId, run[2], "control: the child is the capture agent's");
        assertNull(run[3], "the child's conversation hangs under no chat");
    }

    @Test
    void aSendWithNoDestinationInACaptureDeliversNothingToTheRecentChat() throws Exception {
        firstCall.set("""
                {"id":"call_1","type":"function","function":{"name":"message",
                 "arguments":"{\\"action\\":\\"send\\",\\"message\\":\\"the figures are in appendix B\\"}"}}""");
        long before = rowCount(recentChatId);

        var response = capture();

        assertEquals(List.of("message"), response.toolsCalled(), "control: the send ran");
        assertEquals("Error: no active conversation for agent '" + provider + "-agent'; pass explicit 'channel' and "
                + "'target' to send anyway.", response.toolResults().get("message").getFirst());
        assertEquals(before, rowCount(recentChatId), "nothing reached the agent's recent chat");
    }

    @Test
    void aWebSendWithNoTargetInACaptureDeliversNothingToTheRecentChat() throws Exception {
        firstCall.set("""
                {"id":"call_1","type":"function","function":{"name":"message",
                 "arguments":"{\\"action\\":\\"send\\",\\"message\\":\\"hi\\",\\"channel\\":\\"web\\"}"}}""");
        long before = rowCount(recentChatId);

        var response = capture();

        assertEquals("Error: this turn runs in no conversation, so a web message needs an explicit numeric "
                + "'target' conversation id.", response.toolResults().get("message").getFirst());
        assertEquals(before, rowCount(recentChatId));
    }

    private EvalScorer.Response capture() throws Exception {
        var suite = new EvalSuite("jf158-fixture", "fixture",
                List.of(new EvalCase("case", CASE_INPUT, "rubric", List.of())));
        var out = new AtomicReference<EvalCapture.Capture>();
        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            out.set(EvalCapture.run(suite, agent, 1,
                    (a, prompt, sink) -> AgentRunner.runForTask(a, prompt, sink).content()));
        });
        var response = out.get().responses().get("case");
        assertNull(response.error(), "the case ran: " + response.error());
        return response;
    }

    // The case's own first round: its prompt is there and no tool has answered yet. The child's turn carries neither.
    private static boolean isCaseOpening(JsonArray messages) {
        boolean hasCase = false;
        for (var m : messages) {
            var o = m.getAsJsonObject();
            if ("tool".equals(o.get("role").getAsString())) return false;
            var content = o.get("content");
            if (content != null && content.isJsonPrimitive() && content.getAsString().contains("JF158-CASE")) {
                hasCase = true;
            }
        }
        return hasCase;
    }

    private static String toolCallReply(String call) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[%s]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(call);
    }

    private static long rowCount(Long conversationId) {
        return Tx.run(() -> {
            JPA.em().clear();
            Conversation c = Conversation.findById(conversationId);
            return Message.count("conversation = ?1", c);
        });
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    // A capture runs its cases on threads with no ambient transaction; the test thread holds one.
    private static void onVirtualThread(ThrowingRunnable body) throws Exception {
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the capture should complete within 60s");
        switch (error.get()) {
            case null -> { }
            case Exception e -> throw e;
            case Error e -> throw e;
            default -> throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
