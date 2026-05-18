import agents.AgentRunner;
import agents.AgentExecutionSink;
import agents.ConversationSink;
import agents.ParallelToolExecutor;
import agents.ToolCallLoopRunner;
import agents.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.SubagentRegistry;
import services.Tx;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Edge-case characterization tests for
 * {@link agents.ToolCallLoopRunner#callWithToolLoop} (JCLAW-307).
 *
 * <p>{@code AgentRunnerCoreTest} covers the happy-path tool-call cycle,
 * the {@code maxToolRounds} cap, the truncation-with-pending-tool-calls
 * guard, the empty-toolCalls truncation flag, and the malformed-JSON
 * recovery. These tests fill the remaining branches: tool execution
 * exceptions, registered-tool args-validation failures, empty
 * {@code choices()} provider responses, the cancellation checkpoint
 * between rounds, the {@code yieldRequestedInLastRound} positive
 * branch, and the {@code yieldRequestedInLastRound} negative-shape
 * branches.
 *
 * <p>The JCLAW-300 streaming-failover edge is intentionally not
 * duplicated here.
 */
class ToolCallLoopRunnerEdgeCasesTest extends UnitTest {

    private HttpServer llmServer;
    private int port;
    private List<ToolRegistry.Tool> originalTools;

    @BeforeEach
    void setup() throws Exception {
        // Mirror AgentRunnerCoreTest's setup: a 200ms gap lets any prior
        // test's lingering VT activity (queue drain, EventLogger flush)
        // settle before this test resets the DB. Without it the
        // Fixtures.deleteDatabase() can race in-flight writes from the
        // previous test's tail.
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        SubagentRegistry.clear();
        originalTools = ToolRegistry.listTools();
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
        if (originalTools != null) {
            ToolRegistry.publish(originalTools);
        }
        SubagentRegistry.clear();
        // Reset config keys mutated by individual tests so siblings in the
        // same JVM don't inherit them.
        ConfigService.delete("chat.maxToolRounds");
    }

    // =========================================================
    // AC: Tool-error propagation
    //   A tool throwing an exception lands as a tool-result message
    //   carrying the error, and the loop continues to the next LLM round.
    // =========================================================

    @Test
    void toolExceptionSurfacesAsToolResultAndLoopContinues() throws Exception {
        // First LLM call: assistant requests the throwing tool.
        // Second LLM call: assistant supplies a final reply that paraphrases
        // the error the ParallelToolExecutor catches.
        var callCount = new AtomicInteger(0);
        var receivedToolResultText = new AtomicReference<String>();
        startLlmServer(exchange -> {
            int n = callCount.getAndIncrement();
            String body;
            if (n == 0) {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"Calling tool.",
                    "tool_calls":[{"id":"call_err","type":"function","function":{"name":"throwy_tool","arguments":"{}"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            } else {
                var reqBody = new String(exchange.getRequestBody().readAllBytes());
                receivedToolResultText.set(reqBody);
                body = simpleResponse("Tool failed but I recovered.");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var newTools = new ArrayList<>(originalTools);
        newTools.add(throwingTool("throwy_tool",
                new RuntimeException("simulated tool failure")));
        ToolRegistry.publish(newTools);

        var agent = createAgent("err-prop-agent");
        var convo = ConversationService.create(agent, "web", "user1");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Use the throwy tool");

        assertEquals("Tool failed but I recovered.", result.response(),
                "Loop should continue past the tool exception and emit final text");
        assertEquals(2, callCount.get(),
                "Loop should make 2 LLM calls — request, then continue past the error");

        var seenSecondCall = receivedToolResultText.get();
        assertNotNull(seenSecondCall, "Second LLM call should have been made");
        assertTrue(seenSecondCall.contains("Error executing tool"),
                "Tool-result message in second-round request should carry the error prefix, got: "
                        + seenSecondCall.substring(0, Math.min(seenSecondCall.length(), 500)));
    }

    // =========================================================
    // AC: Schema / args validation failure for a registered tool
    //   When a tool's args JSON is invalid (vs the truncated-mid-arg case),
    //   ToolRegistry.executeRich captures the parse error in the result
    //   without invoking the tool body, and the loop continues.
    // =========================================================

    @Test
    void invalidArgsJsonForRegisteredToolReturnsParseErrorAndContinues() throws Exception {
        var callCount = new AtomicInteger(0);
        var continuationBody = new AtomicReference<String>();
        startLlmServer(exchange -> {
            int n = callCount.getAndIncrement();
            String body;
            if (n == 0) {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"Calling tool.",
                    "tool_calls":[{"id":"call_bad","type":"function","function":{"name":"schema_tool","arguments":"{not json"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            } else {
                continuationBody.set(new String(exchange.getRequestBody().readAllBytes()));
                body = simpleResponse("Sorry, the input was malformed.");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var executionCount = new AtomicInteger(0);
        var newTools = new ArrayList<>(originalTools);
        newTools.add(new ToolRegistry.Tool() {
            @Override public String name() { return "schema_tool"; }
            @Override public String description() { return "A tool the LLM should call with valid args."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string")));
            }
            @Override public String execute(String argsJson, Agent agent) {
                executionCount.incrementAndGet();
                return "ok";
            }
        });
        ToolRegistry.publish(newTools);

        var agent = createAgent("schema-fail-agent");
        var convo = ConversationService.create(agent, "web", "user1");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Use schema_tool with bad args");

        assertEquals("Sorry, the input was malformed.", result.response());
        assertEquals(0, executionCount.get(),
                "Tool body must NOT execute when args JSON is malformed");
        var seen = continuationBody.get();
        assertNotNull(seen, "Continuation LLM call should be issued");
        assertTrue(seen.contains("malformed arguments"),
                "Continuation request must include the parse-error surface from ToolRegistry, got: "
                        + seen.substring(0, Math.min(seen.length(), 500)));
    }

    // =========================================================
    // AC: Retry-budget exhaustion via repeated tool failures
    //   When every round produces a tool error, the loop terminates
    //   at maxToolRounds with the deterministic failure envelope.
    // =========================================================

    @Test
    void repeatedToolFailuresTerminateWithMaxRoundsEnvelope() throws Exception {
        ConfigService.set("chat.maxToolRounds", "3");

        var llmCalls = new AtomicInteger(0);
        startLlmServer(exchange -> {
            llmCalls.incrementAndGet();
            var body = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"call_%d","type":"function","function":{"name":"always_throws","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                    .formatted(System.nanoTime());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var newTools = new ArrayList<>(originalTools);
        newTools.add(throwingTool("always_throws", new RuntimeException("nope")));
        ToolRegistry.publish(newTools);

        var agent = createAgent("retry-budget-agent");
        var convo = ConversationService.create(agent, "web", "user1");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Loop forever");

        assertTrue(result.response().contains("maximum number of tool execution rounds"),
                "Repeated tool failures must terminate with the deterministic max-rounds envelope, got: "
                        + result.response());
        assertEquals(3, llmCalls.get(),
                "Loop should issue exactly maxToolRounds (3) LLM calls before terminating");
    }

    // =========================================================
    // AC: Partial / truncated JSON tool arguments
    //   ToolRegistry must surface a parse-error tool result (the JCLAW-291
    //   anti-EOFException defence-in-depth). Distinct from the
    //   AgentRunnerCoreTest case where the loop merely doesn't crash; here
    //   we verify the user-facing parse-error hint actually appears in the
    //   continuation request.
    // =========================================================

    @Test
    void truncatedJsonArgsProduceParseErrorToolResult() throws Exception {
        var callCount = new AtomicInteger(0);
        var continuationBody = new AtomicReference<String>();
        startLlmServer(exchange -> {
            int n = callCount.getAndIncrement();
            String body;
            if (n == 0) {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                    "tool_calls":[{"id":"trunc","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            } else {
                continuationBody.set(new String(exchange.getRequestBody().readAllBytes()));
                body = simpleResponse("Tried, but the call was malformed.");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();
        new jobs.ToolRegistrationJob().doJob();

        var agent = createAgent("partial-json-agent");
        var convo = ConversationService.create(agent, "web", "user1");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Do it");

        assertNotNull(result.response());
        assertEquals("Tried, but the call was malformed.", result.response(),
                "Loop should continue past parse-error tool result");
        var seen = continuationBody.get();
        assertNotNull(seen, "Continuation LLM call should be made");
        assertTrue(seen.contains("malformed") || seen.contains("Error"),
                "Continuation request should carry the parse-error tool-result string");
    }

    // =========================================================
    // AC: Function-call vs tool-call dispatch
    //   Multiple parallel tool calls and a single tool call both route
    //   through executeToolsParallel and produce ordered tool-result
    //   history. Pins the contract that single-call dispatch (n==1 fast
    //   path) and multi-call dispatch (multi-thread path) produce
    //   equivalent history shapes.
    // =========================================================

    @Test
    void singleAndMultiCallDispatchProduceEquivalentToolResultHistory() throws Exception {
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var singleMessages = new ArrayList<ChatMessage>();
        invokeExecuteToolsParallel(List.of(
                toolCall("c1", "ok_tool")), agent, ids[1], singleMessages);

        var multiMessages = new ArrayList<ChatMessage>();
        invokeExecuteToolsParallel(List.of(
                toolCall("c2", "ok_tool"),
                toolCall("c3", "ok_tool_b")), agent, ids[1], multiMessages);

        assertEquals(1, singleMessages.size(),
                "single-call dispatch produces exactly one tool result");
        assertEquals(2, multiMessages.size(),
                "multi-call dispatch produces a tool result per call");

        assertEquals(MessageRole.TOOL.value, singleMessages.getFirst().role());
        assertEquals("c1", singleMessages.getFirst().toolCallId());

        assertEquals(MessageRole.TOOL.value, multiMessages.get(0).role());
        assertEquals(MessageRole.TOOL.value, multiMessages.get(1).role());
        assertEquals(List.of("c2", "c3"),
                multiMessages.stream().map(ChatMessage::toolCallId).toList(),
                "multi-call dispatch must preserve declared order in the committed history");
    }

    // =========================================================
    // AC: Empty assistant message between tool calls
    //   An assistant turn with empty string content + non-empty tool_calls
    //   must not be treated as terminator — the loop must run the tools
    //   and continue.
    // =========================================================

    @Test
    void emptyAssistantContentWithToolCallsContinuesLoop() throws Exception {
        var callCount = new AtomicInteger(0);
        startLlmServer(exchange -> {
            int n = callCount.getAndIncrement();
            String body;
            if (n == 0) {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                    "tool_calls":[{"id":"mid","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now\\"}"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            } else {
                body = simpleResponse("Continued past empty content.");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();
        new jobs.ToolRegistrationJob().doJob();

        var agent = createAgent("empty-mid-agent");
        var convo = ConversationService.create(agent, "web", "user1");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "What time is it?");

        assertEquals("Continued past empty content.", result.response(),
                "Empty content alongside tool_calls must not terminate the loop");
        assertEquals(2, callCount.get(),
                "Loop must round-trip through the tool and continue to the next LLM call");
    }

    // =========================================================
    // AC: Cancellation token honoured between tool calls
    //   A flipped SubagentRegistry cancel flag for a RUNNING SubagentRun
    //   pointing at this conversation must short-circuit the loop at the
    //   next checkpoint.
    // =========================================================

    @Test
    void subagentCancelFlagShortCircuitsLoopAtCheckpoint() throws Exception {
        var llmCalls = new AtomicInteger(0);
        startLlmServer(exchange -> {
            llmCalls.incrementAndGet();
            var body = simpleResponse("should never reach here");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parentAgent = createAgent("cancel-parent-agent");
        var childAgent = createAgent("cancel-child-agent");
        var parentConv = ConversationService.create(parentAgent, "web", "parent");
        var childConv = ConversationService.create(childAgent, "web", "child");

        Long runId = Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgent;
            run.parentConversation = parentConv;
            run.childConversation = childConv;
            run.status = SubagentRun.Status.RUNNING;
            run.save();
            return run.id;
        });
        assertNotNull(runId);

        var dummyFuture = java.util.concurrent.CompletableFuture.completedFuture(null);
        SubagentRegistry.register(runId, dummyFuture);
        // Flip the cancel flag WITHOUT calling kill(): production kill()
        // transitions the row to KILLED, which would cause AgentRunner's
        // checkSubagentCancel query (filtering on status=RUNNING) to return
        // null — masking the cancellation. The real production race we
        // want to characterize is "flag flipped, status still RUNNING in
        // the window between the registry write and the DB write."
        SubagentRegistry.cancelForTest(runId);
        assertTrue(SubagentRegistry.isCancelled(runId),
                "cancel flag must be set");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        try {
            runOnVirtualThread(childAgent, childConv, "any message");
        } catch (Exception _) {
            // RunCancelledException may bubble — the load-bearing
            // assertion is that no LLM call was attempted.
        }

        assertEquals(0, llmCalls.get(),
                "No LLM call should be issued after the cancel flag is flipped");
    }

    // =========================================================
    // AC: Empty choices() from provider
    //   When the LLM HTTP layer hands back a parseable response with an
    //   empty choices array, the loop emits the deterministic
    //   "No response received" envelope rather than NPE'ing.
    // =========================================================

    @Test
    void emptyChoicesArrayProducesDeterministicEnvelope() throws Exception {
        startLlmServer(exchange -> {
            var body = """
                {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":0}}""";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("empty-choices-agent");
        var convo = ConversationService.create(agent, "web", "user1");

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Anything");

        assertNotNull(result.response());
        assertTrue(result.response().contains("No response received"),
                "Empty choices array must produce the deterministic envelope, got: "
                        + result.response());
    }

    // =========================================================
    // yieldRequestedInLastRound: positive + negative shapes
    //   Direct reflection tests against the private helper to pin the
    //   sentinel-prefix contract independently of the loop wiring.
    // =========================================================

    @Test
    void yieldRequestedInLastRoundReturnsTrueOnSentinelPrefix() {
        var sentinel = tools.YieldToSubagentTool.YIELD_SENTINEL_PREFIX + "child-reply";
        var messages = List.of(
                ChatMessage.user("user msg"),
                ChatMessage.toolResult("tc1", "yield_to_subagent", sentinel));
        assertTrue(ToolCallLoopRunner.yieldRequestedInLastRound(messages, 1),
                "yield sentinel in a TOOL-role result must return true");
    }

    @Test
    void yieldRequestedInLastRoundIgnoresNonToolRoleAndNonSentinel() {
        var nonToolSentinel = List.of(
                ChatMessage.assistant(
                        tools.YieldToSubagentTool.YIELD_SENTINEL_PREFIX + "looks-like-yield",
                        null));
        assertFalse(ToolCallLoopRunner.yieldRequestedInLastRound(nonToolSentinel, 0),
                "sentinel text in assistant role must NOT trigger yield");

        var normalTool = List.of(
                ChatMessage.toolResult("tc1", "datetime", "2026-01-01T00:00:00Z"));
        assertFalse(ToolCallLoopRunner.yieldRequestedInLastRound(normalTool, 0),
                "regular tool result without the sentinel must NOT trigger yield");

        var empty = new ArrayList<ChatMessage>();
        assertFalse(ToolCallLoopRunner.yieldRequestedInLastRound(empty, 0),
                "empty message list must NOT trigger yield");
    }

    @Test
    void yieldRequestedInLastRoundHonorsFromIndexBoundary() {
        // The fromIndex argument scopes the scan to messages appended in
        // the just-finished round. A sentinel BEFORE fromIndex must NOT
        // trigger.
        var sentinel = tools.YieldToSubagentTool.YIELD_SENTINEL_PREFIX + "prior-round";
        var messages = List.of(
                ChatMessage.toolResult("old", "yield_to_subagent", sentinel),
                ChatMessage.user("subsequent user msg"));

        assertFalse(ToolCallLoopRunner.yieldRequestedInLastRound(messages, 1),
                "sentinel before fromIndex must NOT trigger yield");

        assertTrue(ToolCallLoopRunner.yieldRequestedInLastRound(messages, 0),
                "sentinel at/after fromIndex must trigger yield");
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static ToolCall toolCall(String id, String name) {
        return new ToolCall(id, "function", new FunctionCall(name, "{}"));
    }

    private long[] seedAgentAndConversation() {
        // Register the ok tools used by the dispatch test alongside the
        // prior tool set. parallelSafe=true so multi-call goes through
        // the per-call VT path.
        var newTools = new ArrayList<>(originalTools);
        newTools.add(simpleTool("ok_tool", true, "ok-a"));
        newTools.add(simpleTool("ok_tool_b", true, "ok-b"));
        ToolRegistry.publish(newTools);

        return Tx.run(() -> {
            var agent = new Agent();
            agent.name = "dispatch-test";
            agent.modelProvider = "test";
            agent.modelId = "test";
            agent.save();
            var conv = ConversationService.create(agent, "web", "tester");
            return new long[]{agent.id, conv.id};
        });
    }

    private void invokeExecuteToolsParallel(List<ToolCall> calls, Agent agent, long convId,
                                            List<ChatMessage> messages) {
        var conv = (Conversation) Tx.run(() -> ConversationService.findById(convId));
        AgentExecutionSink sink = new ConversationSink(conv);
        ParallelToolExecutor.executeToolsParallel(
                calls, agent, convId, messages, null, null, null, new AtomicBoolean(false), sink);
    }

    private static ToolRegistry.Tool throwingTool(String name, RuntimeException error) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "A tool that throws for testing."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) { throw error; }
        };
    }

    private static ToolRegistry.Tool simpleTool(String name, boolean parallelSafe, String returnValue) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Simple test tool"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public boolean parallelSafe() { return parallelSafe; }
            @Override public String execute(String argsJson, Agent agent) { return returnValue; }
        };
    }

    private Agent createAgent(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void startLlmServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", handler);
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private void configureProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    private static String simpleResponse(String content) {
        return """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(
                content.replace("\"", "\\\""));
    }

    private AgentRunner.RunResult runOnVirtualThread(Agent agent, Conversation convo, String message) throws Exception {
        var agentId = agent.id;
        var convoId = convo.id;
        var resultRef = new AtomicReference<AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Exception>();

        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agentId));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                resultRef.set(AgentRunner.run(a, c, message));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "AgentRunner.run should complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
