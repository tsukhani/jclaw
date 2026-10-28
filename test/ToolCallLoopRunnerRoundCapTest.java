import agents.AgentExecutionSink;
import agents.AgentRunner;
import agents.ConversationSink;
import agents.ToolCallLoopRunner;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmProvider;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import llm.OpenAiProvider;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConversationService;
import utils.CircuitBreakers;
import utils.LatencyTrace;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A turn that uses every tool round: one more call, offered no tools, asks the model what it did,
 * what is left and what needs a decision, and its answer is the reply with a fixed line after it.
 * The cap is passed to the loop rather than set through {@code chat.maxToolRounds}, which every
 * concurrently running class reads.
 */
class ToolCallLoopRunnerRoundCapTest extends UnitTest {

    private static final String MODEL = "test-model";
    private static final String SYSTEM = "You are terse.";
    private static final String QUESTION = "Audit every file.";
    private static final String TOOL = "jf128_probe";
    private static final String CLOSING = "Checked two files. Left: the rest. Decide: keep going?";
    private static final String NUDGE = "You have used every tool round this turn allows, so no more tools can run. "
            + "Tell me what you did, what is left to do, and anything I need to decide. Write it as markdown.";
    private static final String NOTE = "\n\n*[This turn stopped at the tool-round limit.]*";
    private static final String FIXED_REPLY =
            "I reached the maximum number of tool execution rounds. Please try a simpler request.";

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> providers = new ArrayList<>();

    @AfterEach
    void teardown() {
        servers.forEach(s -> s.stop(0));
        providers.forEach(name -> CircuitBreakers.remove(LlmResilience.breakerName(name)));
    }

    // ─── sync loop ───────────────────────────────────────────────────────────

    @Test
    void aSyncTurnThatUsesEveryRoundEndsWithTheModelsClosingAnswer() throws Exception {
        var llm = serve(toolCall("r1"), toolCall("r2"), text(CLOSING));

        var outcome = runSyncLoop(provider(llm), null, 2);

        assertEquals(CLOSING + NOTE, outcome.content());
        assertFalse(outcome.truncated());
        assertEquals(3, llm.bodies().size(), "two tool rounds, then the closing call");
        assertTrue(offersTools(llm.bodies().get(0)) && offersTools(llm.bodies().get(1)));
        assertFalse(offersTools(llm.bodies().get(2)), "the closing call offers no tools");
        assertEquals(List.of("system:" + SYSTEM, "user:" + QUESTION, "assistant->r1", "tool<-r1",
                "assistant->r2", "tool<-r2", "user:" + NUDGE), sent(llm.bodies().get(2)));
    }

    @Test
    void aSyncTurnThatAnswersOnItsLastAllowedRoundMakesNoClosingCall() throws Exception {
        var llm = serve(toolCall("r1"), text("Finished."));

        var outcome = runSyncLoop(provider(llm), null, 2);

        assertEquals("Finished.", outcome.content());
        assertEquals(2, llm.bodies().size());
        assertTrue(offersTools(llm.bodies().get(1)));
    }

    @Test
    void aSyncClosingCallThatFailsEndsWithTheFixedReply() throws Exception {
        var llm = serve(toolCall("r1"), clientError());

        var outcome = runSyncLoop(provider(llm), null, 1);

        assertEquals(FIXED_REPLY, outcome.content());
        assertEquals(2, llm.bodies().size());
    }

    @Test
    void aSyncClosingCallThatComesBackEmptyEndsWithTheFixedReply() throws Exception {
        var llm = serve(toolCall("r1"), text(""));

        var outcome = runSyncLoop(provider(llm), null, 1);

        assertEquals(FIXED_REPLY, outcome.content());
        assertEquals(2, llm.bodies().size(), "no fallback is configured, so one closing call");
    }

    @Test
    void aSyncClosingCallThatStillAsksForAToolEndsWithTheFixedReply() throws Exception {
        var llm = serve(toolCall("r1"), toolCall("r2"));

        var outcome = runSyncLoop(provider(llm), null, 1);

        assertEquals(FIXED_REPLY, outcome.content());
        assertEquals(2, llm.bodies().size());
    }

    @Test
    void aSyncClosingCallTheFallbackAnswersWhenThePrimaryFails() throws Exception {
        var primary = serve(toolCall("r1"), clientError());
        var secondary = serve(text(CLOSING));

        var outcome = runSyncLoop(provider(primary), provider(secondary), 1);

        assertEquals(CLOSING + NOTE, outcome.content());
        assertEquals(2, primary.bodies().size());
        assertEquals(1, secondary.bodies().size());
        assertFalse(offersTools(secondary.bodies().getFirst()), "the fallback's closing call offers no tools either");
    }

    // ─── streaming continuation ──────────────────────────────────────────────

    @Test
    void aStreamedTurnThatUsesEveryRoundEndsWithTheModelsClosingAnswer() throws Exception {
        var llm = serve(toolCall("r2"), text(CLOSING));
        var tokens = new StringBuffer();

        var result = runContinuation(provider(llm), tokens, new AtomicBoolean(false), 0, 1);

        assertEquals(CLOSING + NOTE, result.content());
        assertFalse(result.truncated());
        assertEquals(CLOSING + NOTE, tokens.toString(), "the reader sees what is stored");
        assertEquals(2, llm.bodies().size(), "the continuation, then the closing call");
        assertTrue(offersTools(llm.bodies().get(0)));
        assertFalse(offersTools(llm.bodies().get(1)), "the closing call offers no tools");
        assertEquals(List.of("system:" + SYSTEM, "user:" + QUESTION, "assistant->r1", "tool<-r1", "user:" + NUDGE),
                sent(llm.bodies().get(1)), "the tool call left over at the cap is not run or sent");
    }

    @Test
    void aStreamedClosingAnswerCutOffAtTheOutputLimitIsFlaggedTruncated() throws Exception {
        var llm = serve(toolCall("r2"), text(CLOSING, "length"));

        var result = runContinuation(provider(llm), new StringBuffer(), new AtomicBoolean(false), 0, 1);

        assertEquals(CLOSING + NOTE, result.content());
        assertTrue(result.truncated(), "flagged as the sync loop flags it");
    }

    @Test
    void aStreamedContinuationThatAnswersBeforeTheCapMakesNoClosingCall() throws Exception {
        var llm = serve(text("Finished."));

        var result = runContinuation(provider(llm), new StringBuffer(), new AtomicBoolean(false), 0, 1);

        assertEquals("Finished.", result.content());
        assertEquals(1, llm.bodies().size());
    }

    @Test
    void aStreamedClosingCallThatFailsEndsWithTheFixedReply() throws Exception {
        var llm = serve(toolCall("r2"), clientError());

        var result = runContinuation(provider(llm), new StringBuffer(), new AtomicBoolean(false), 0, 1);

        assertEquals(FIXED_REPLY, result.content());
        assertEquals(2, llm.bodies().size());
    }

    @Test
    void aStreamedClosingCallThatComesBackEmptyEndsWithTheFixedReply() throws Exception {
        var llm = serve(toolCall("r2"), text(""));

        var result = runContinuation(provider(llm), new StringBuffer(), new AtomicBoolean(false), 0, 1);

        assertEquals(FIXED_REPLY, result.content());
        assertEquals(2, llm.bodies().size(), "no fallback is configured, so one closing call");
    }

    @Test
    void aCanceledTurnAtTheRoundCapMakesNoClosingCall() throws Exception {
        var llm = serve(text(CLOSING));

        var result = runContinuation(provider(llm), new StringBuffer(), new AtomicBoolean(true), 1, 1);

        assertEquals("prior content", result.content());
        assertEquals(0, llm.bodies().size());
    }

    // ─── replies ─────────────────────────────────────────────────────────────

    private record Reply(int status, String body) {}

    private static Reply text(String content) {
        return text(content, "stop");
    }

    private static Reply text(String content, String finishReason) {
        return new Reply(200, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(content, finishReason));
    }

    private static Reply toolCall(String id) {
        return new Reply(200, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"%s",\
                "type":"function","function":{"name":"%s","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(id, TOOL));
    }

    private static Reply clientError() {
        return new Reply(400, """
                {"error":{"message":"Invalid value for 'temperature'.","type":"invalid_request_error",\
                "param":"temperature","code":"invalid_value"}}""");
    }

    private static boolean offersTools(String body) {
        return JsonParser.parseString(body).getAsJsonObject().has("tools");
    }

    /** Each message as {@code role:content}, a tool-call turn as {@code assistant->ids}, a result as {@code tool<-id}. */
    private static List<String> sent(String body) {
        var out = new ArrayList<String>();
        for (var element : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages")) {
            var m = element.getAsJsonObject();
            var role = m.get("role").getAsString();
            var calls = m.get("tool_calls");
            if (calls != null && calls.isJsonArray() && !calls.getAsJsonArray().isEmpty()) {
                var ids = new ArrayList<String>();
                calls.getAsJsonArray().forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
                out.add(role + "->" + String.join(",", ids));
            } else if (MessageRole.TOOL.value.equals(role)) {
                out.add("tool<-" + m.get("tool_call_id").getAsString());
            } else {
                var content = m.get("content");
                out.add(role + ":" + (content != null && content.isJsonPrimitive() ? content.getAsString() : ""));
            }
        }
        return out;
    }

    /** A chat endpoint answering each request with the next scripted reply, recording what it was sent. */
    private record ScriptedLlm(HttpServer server, List<String> bodies) {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private ScriptedLlm serve(Reply... replies) throws IOException {
        var bodies = new CopyOnWriteArrayList<String>();
        var next = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var reply = replies[Math.min(next.getAndIncrement(), replies.length - 1)];
            if (reply.status() == 200) {
                LlmReplyFixture.send(exchange, reply.body());
                return;
            }
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return new ScriptedLlm(server, bodies);
    }

    private OpenAiProvider provider(ScriptedLlm llm) {
        var name = "jf128-" + System.nanoTime();
        providers.add(name);
        return new OpenAiProvider(new ProviderConfig(name, llm.baseUrl(), "sk-test",
                List.of(new ModelInfo(MODEL, "Test", 100_000, 4096, false))));
    }

    // ─── drivers ─────────────────────────────────────────────────────────────

    private static List<ToolDef> tools() {
        return List.of(ToolDef.of(TOOL, "Probe one file.", Map.of("type", "object", "properties", Map.of())));
    }

    private static List<ChatMessage> history() {
        return List.of(ChatMessage.system(SYSTEM), ChatMessage.user(QUESTION));
    }

    private static Conversation conversation(LlmProvider provider) {
        var agent = new Agent();
        agent.name = "jf128-agent-" + System.nanoTime();
        agent.modelProvider = provider.config().name();
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        var convo = ConversationService.create(agent, "web", "jf128-peer-" + System.nanoTime());
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    private ToolCallLoopRunner.LoopOutcome runSyncLoop(LlmProvider primary, LlmProvider secondary, int maxRounds)
            throws Exception {
        var convo = conversation(primary);
        var fallback = secondary == null ? null : new LlmProvider.Fallback(secondary, MODEL);
        Method m = ToolCallLoopRunner.class.getDeclaredMethod("callWithToolLoop",
                Agent.class, Conversation.class, Long.class, List.class, List.class, LlmProvider.class,
                LlmProvider.Fallback.class, List.class, List.class, AgentExecutionSink.class, Long.class, int.class);
        m.setAccessible(true);
        return onVirtualThread(() -> (ToolCallLoopRunner.LoopOutcome) m.invoke(null, convo.agent, convo, convo.id,
                new ArrayList<>(history()), tools(), primary, fallback, List.of(), List.of(),
                new ConversationSink(convo), null, maxRounds));
    }

    /** One streamed tool round for call {@code r1}, then whatever the scripted model answers. */
    private ToolCallLoopRunner.LoopOutcome runContinuation(LlmProvider primary, StringBuffer tokens,
                                                           AtomicBoolean cancelled, int round, int maxRounds)
            throws Exception {
        var convo = conversation(primary);
        AgentRunner.StreamingCallbacks cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, tokens::append, _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {}, () -> {});
        var ctx = new ToolCallLoopRunner.StreamingTurnContext(convo.agent, convo, convo.id, tools(), primary, null,
                cb, null, cancelled, new LatencyTrace(), new LlmProvider.TurnUsage(), new ArrayList<>(), "web",
                new ConversationSink(convo));
        Method m = ToolCallLoopRunner.class.getDeclaredMethod("handleToolCallsStreaming",
                ToolCallLoopRunner.StreamingTurnContext.class, List.class, List.class, String.class, int.class,
                int.class);
        m.setAccessible(true);
        var priorContent = cancelled.get() ? "prior content" : "";
        return onVirtualThread(() -> (ToolCallLoopRunner.LoopOutcome) m.invoke(null, ctx, history(),
                List.of(new ToolCall("r1", "function", new FunctionCall(TOOL, "{}"))), priorContent, round,
                maxRounds));
    }

    private interface Invocation<T> {
        T call() throws Exception;
    }

    private static <T> T onVirtualThread(Invocation<T> body) throws Exception {
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(body.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the loop must return within 60 s");
        var e = error.get();
        if (e instanceof InvocationTargetException ite && ite.getCause() instanceof RuntimeException re) throw re;
        if (e != null) throw e;
        return result.get();
    }
}
