import org.junit.jupiter.api.*;
import play.test.*;
import models.*;
import play.db.jpa.JPA;
import services.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for the AgentRunner core execution loop: synchronous run(), provider
 * resolution, tool-call cycling, and queue interaction. Uses an embedded HTTP
 * server to mock the LLM provider — zero mocks, real H2 DB.
 */
public class AgentRunnerCoreTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
    }

    // --- Provider resolution ---

    @Test
    public void runReturnsErrorWhenNoProviderConfigured() {
        var agent = createAgent("no-provider-agent", "nonexistent", "model");
        var convo = ConversationService.create(agent, "web", "user1");

        var result = agents.AgentRunner.run(agent, convo, "Hello");

        assertNotNull(result);
        assertTrue(result.response().contains("No LLM provider configured"),
                "Should return provider error, got: " + result.response());

        // User message should still be persisted
        var messages = ConversationService.loadRecentMessages(convo);
        assertTrue(messages.size() >= 1, "User message should be persisted even on provider error");
    }

    // --- Simple completion (no tool calls) ---

    @Test
    public void runCompletesWithSimpleResponse() throws Exception {
        startLlmServer(simpleResponse("Hello, I am your assistant!"));
        configureProvider();

        var agent = createAgent("simple-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        // Commit so virtual thread transactions can see the rows
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Hi there");

        assertEquals("Hello, I am your assistant!", result.response());

        // Verify both user and assistant messages persisted
        JPA.em().clear();
        var messages = ConversationService.loadRecentMessages(
                ConversationService.findById(convo.id));
        assertTrue(messages.size() >= 2,
                "Should have user + assistant messages, got " + messages.size());
    }

    // --- Tool call loop ---

    @Test
    public void runExecutesToolCallAndContinues() throws Exception {
        // First LLM call returns a tool call, second returns final text
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        startLlmServer(exchange -> {
            String body;
            if (callCount.getAndIncrement() == 0) {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"Let me check.",
                    "tool_calls":[{"id":"call_1","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now\\"}"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            } else {
                body = simpleResponse("The current time is 2026-04-13T18:00:00Z.");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();
        // Register the datetime tool so execute() finds it
        new jobs.ToolRegistrationJob().doJob();

        var agent = createAgent("tool-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "What time is it?");

        assertEquals("The current time is 2026-04-13T18:00:00Z.", result.response());
        assertTrue(callCount.get() >= 2, "Should have made at least 2 LLM calls (initial + after tool)");
    }

    // --- Truncation handling ---

    @Test
    public void runHandlesTruncatedToolCall() throws Exception {
        // LLM returns a tool call with finish_reason=length (truncated)
        startLlmServer(exchange -> {
            var body = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"I was trying to help but got cut off.",
                "tool_calls":[{"id":"call_t","type":"function","function":{"name":"datetime","arguments":"{\\"acti"}}]},
                "finish_reason":"length"}],
                "usage":{"prompt_tokens":10,"completion_tokens":4096}}""";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("trunc-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Do something complex");

        // Should return the partial content, not execute the truncated tool
        assertNotNull(result.response());
        assertTrue(result.response().contains("I was trying to help"),
                "Should return the partial content, got: " + result.response());
    }

    // --- Max tool rounds ---

    @Test
    public void runStopsAfterMaxToolRounds() throws Exception {
        // LLM always returns a tool call — should stop after maxToolRounds
        ConfigService.set("chat.maxToolRounds", "2");

        startLlmServer(exchange -> {
            var body = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"call_%d","type":"function","function":{"name":"datetime","arguments":"{\\"action\\":\\"now\\"}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                    .formatted(System.nanoTime());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();
        new jobs.ToolRegistrationJob().doJob();

        var agent = createAgent("loop-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Loop forever");

        assertTrue(result.response().contains("maximum number of tool execution rounds"),
                "Should report max rounds reached, got: " + result.response());
    }

    // --- Conversation queuing ---

    @Test
    public void runQueuesWhenConversationBusy() throws Exception {
        var llmGate = new CountDownLatch(1);
        startLlmServer(exchange -> {
            try { llmGate.await(10, TimeUnit.SECONDS); } catch (InterruptedException _) {}
            var body = simpleResponse("First response");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("queue-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var agentId = agent.id;
        var convoId = convo.id;

        // Start first run (will block at LLM call)
        var firstResult = new AtomicReference<agents.AgentRunner.RunResult>();
        var t1 = Thread.ofVirtual().start(() -> {
            var a = Tx.run(() -> (Agent) Agent.findById(agentId));
            var c = Tx.run(() -> (Conversation) Conversation.findById(convoId));
            firstResult.set(agents.AgentRunner.run(a, c, "First message"));
        });

        Thread.sleep(300); // Let first run acquire the queue

        // Second run on a new virtual thread should be queued
        var a2 = Tx.run(() -> (Agent) Agent.findById(agentId));
        var c2 = Tx.run(() -> (Conversation) Conversation.findById(convoId));
        var secondResult = agents.AgentRunner.run(a2, c2, "Second message");

        assertTrue(secondResult.response().contains("queued"),
                "Second concurrent run should be queued, got: " + secondResult.response());

        llmGate.countDown();
        t1.join(10_000);
    }

    @Test
    public void runHandlesMaxTokensFinishReason() throws Exception {
        // JCLAW-76 ground: Bedrock / Anthropic return finish_reason="max_tokens"
        // when output is cut off (vs OpenAI's "length"). Both must be treated as
        // truncation — otherwise the incomplete tool-call JSON flows into Gson
        // and throws EOFException downstream.
        startLlmServer(exchange -> {
            var body = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Partial answer about the weather",
                "tool_calls":[{"id":"call_m","type":"function","function":{"name":"datetime","arguments":"{\\"act"}}]},
                "finish_reason":"max_tokens"}],
                "usage":{"prompt_tokens":10,"completion_tokens":4096}}""";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("max-tokens-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Tell me a long story");

        assertNotNull(result.response());
        assertTrue(result.response().contains("Partial answer"),
                "max_tokens finish_reason must behave like length — return partial, "
                        + "don't run truncated tool, got: " + result.response());
    }

    @Test
    public void runHandlesMalformedToolCallJson() throws Exception {
        // Tool-call with a valid finish_reason="tool_calls" but syntactically
        // broken JSON arguments. The runner must recover gracefully rather
        // than bubble a parse exception to the caller.
        startLlmServer(exchange -> {
            var body = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"bad","type":"function","function":{"name":"datetime","arguments":"{not valid json"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();
        new jobs.ToolRegistrationJob().doJob();

        var agent = createAgent("bad-json-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        // The assertion we care about: runOnVirtualThread does not throw.
        // Whatever response message the runner ends up with is less important
        // than the invariant that malformed JSON doesn't crash the loop.
        var result = runOnVirtualThread(agent, convo, "Do something");
        assertNotNull(result);
        assertNotNull(result.response());
    }

    // --- LLM error handling ---

    @Test
    public void runReturnsErrorOnLlmFailure() throws Exception {
        startLlmServer(exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        configureProvider();

        var agent = createAgent("error-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "user1");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Hello");

        assertTrue(result.response().contains("error") || result.response().contains("sorry"),
                "Should return error message, got: " + result.response());
    }

    // --- Helpers ---

    private Agent createAgent(String name, String provider, String model) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void startLlmServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", handler);
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private void startLlmServer(String staticResponse) throws Exception {
        startLlmServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, staticResponse.getBytes().length);
            exchange.getResponseBody().write(staticResponse.getBytes());
            exchange.close();
        });
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

    private agents.AgentRunner.RunResult runOnVirtualThread(Agent agent, Conversation convo, String message) throws Exception {
        var agentId = agent.id;
        var convoId = convo.id;
        var resultRef = new AtomicReference<agents.AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Exception>();

        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agentId));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                resultRef.set(agents.AgentRunner.run(a, c, message));
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
