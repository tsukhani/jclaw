import agents.AgentExecutionSink;
import agents.AgentRunner;
import agents.ConversationSink;
import agents.ToolCallLoopRunner;
import agents.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import llm.OpenAiProvider;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.SubagentRegistry;
import services.Tx;
import utils.LatencyTrace;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streaming-loop + audio-retry branch coverage for
 * {@link agents.ToolCallLoopRunner} (JCLAW-307).
 *
 * <p>{@code ToolCallLoopRunnerEdgeCasesTest} already covers the
 * synchronous {@code callWithToolLoop} happy and error paths via the
 * full {@link AgentRunner#run} pipeline. This sibling targets the two
 * remaining uncovered blocks: the sync audio-retry path (JCLAW-165)
 * via direct reflective invocation of {@code callWithToolLoop} with a
 * synthetic {@code audioBearers} list, and
 * {@code handleToolCallsStreaming} via direct reflective invocation
 * against a real {@link OpenAiProvider} pointed at a
 * {@link MockWebServer}. The JCLAW-300 streaming-failover edge stays
 * out of scope here.
 */
class ToolCallLoopRunnerStreamingTest extends UnitTest {

    // Sync-loop audio-retry harness uses a plain HttpServer matching the
    // existing edge-cases test's pattern. Streaming tests use MockWebServer
    // because SSE chunks fit its enqueue model cleanly.
    private HttpServer syncLlmServer;
    private int syncPort;
    private MockWebServer streamingServer;
    private List<ToolRegistry.Tool> originalTools;

    @BeforeEach
    void setup() throws Exception {
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        SubagentRegistry.clear();
        originalTools = ToolRegistry.listTools();
    }

    @AfterEach
    void teardown() {
        if (syncLlmServer != null) {
            syncLlmServer.stop(0);
            syncLlmServer = null;
        }
        if (streamingServer != null) {
            streamingServer.close();
            streamingServer = null;
        }
        if (originalTools != null) {
            ToolRegistry.publish(originalTools);
        }
        SubagentRegistry.clear();
        ConfigService.delete("chat.maxToolRounds");
    }

    // Sync loop: audio-format rejection retry then success.
    // Covers callWithToolLoop lines 121-141, 154-156.
    @Test
    void audioFormatRejectionRetriesAndSucceeds() throws Exception {
        var callCount = new AtomicInteger(0);
        startSyncLlmServer(exchange -> {
            int n = callCount.getAndIncrement();
            int status = (n == 0) ? 400 : 200;
            String body = (n == 0)
                    ? "{\"error\":{\"message\":\"unsupported_format: audio not accepted\"}}"
                    : """
                        {"choices":[{"index":0,"message":{"role":"assistant","content":"Got it via transcript."},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var provider = openAiProviderForSyncServer("audio-retry");
        var agent = persistAgent("audio-success-agent");
        var convo = persistConversation(agent);
        long msgId = seedUserMessageWithAudioTranscript(convo, "spoken transcript");

        var bearers = List.of(
                new agents.VisionAudioAssembler.AudioBearer(1, msgId, List.of(attachmentIdFor(msgId))));

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var outcome = invokeCallWithToolLoop(
                agent, convo, convo.id,
                List.of(ChatMessage.system("sys"), ChatMessage.user("describe this audio")),
                List.of(),
                provider, null, bearers);

        assertEquals("Got it via transcript.", outcome.content(),
                "Loop should rewrite messages and succeed on the retry");
        assertEquals(2, callCount.get(),
                "Loop should issue exactly two LLM calls - one rejected, one retried");
    }

    // Sync loop: audio-format rejection but no transcript available.
    // Covers lines 124, 129-133.
    @Test
    void audioFormatRejectionWithoutTranscriptReturnsErrorEnvelope() throws Exception {
        var callCount = new AtomicInteger(0);
        startSyncLlmServer(exchange -> {
            callCount.incrementAndGet();
            var body = "{\"error\":{\"message\":\"unsupported_format: audio not accepted\"}}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var provider = openAiProviderForSyncServer("audio-no-transcript");
        var agent = persistAgent("audio-no-transcript-agent");
        var convo = persistConversation(agent);
        long msgId = seedUserMessageWithAudioTranscript(convo, null);

        var bearers = List.of(
                new agents.VisionAudioAssembler.AudioBearer(1, msgId, List.of(attachmentIdFor(msgId))));

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var outcome = invokeCallWithToolLoop(
                agent, convo, convo.id,
                List.of(ChatMessage.system("sys"), ChatMessage.user("describe this audio")),
                List.of(),
                provider, null, bearers);

        assertTrue(outcome.content().contains("audio attachment couldn't be transcribed"),
                "Should return the deterministic no-transcript envelope, got: " + outcome.content());
        assertEquals(1, callCount.get(),
                "Loop must NOT retry when no transcript is available");
    }

    // Sync loop: non-audio-format LLM exception with audio bearers present.
    // Covers lines 143-148.
    @Test
    void nonAudioFormatLlmExceptionWithAudioBearersLogsErrorAndReturnsEnvelope() throws Exception {
        startSyncLlmServer(exchange -> {
            var body = "{\"error\":{\"message\":\"upstream gateway exploded\"}}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var provider = openAiProviderForSyncServer("non-audio-exc");
        var agent = persistAgent("non-audio-exc-agent");
        var convo = persistConversation(agent);
        long msgId = seedUserMessageWithAudioTranscript(convo, "transcript present");
        var bearers = List.of(
                new agents.VisionAudioAssembler.AudioBearer(1, msgId, List.of(attachmentIdFor(msgId))));

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var outcome = invokeCallWithToolLoop(
                agent, convo, convo.id,
                List.of(ChatMessage.system("sys"), ChatMessage.user("hi")),
                List.of(),
                provider, null, bearers);

        assertTrue(outcome.content().contains("encountered an error"),
                "Should return the generic-error envelope, got: " + outcome.content());
    }

    // Streaming: round >= maxToolRounds at entry skips dispatch.
    // Covers handleToolCallsStreaming lines 262-263.
    @Test
    void streamingReturnsMaxRoundsEnvelopeWhenRoundAtCap() throws Exception {
        ConfigService.set("chat.maxToolRounds", "2");
        startStreamingServer();
        var provider = openAiProviderForStreamingServer("max-rounds-cap");
        var agent = persistAgent("max-rounds-stream-agent");
        var convo = persistConversation(agent);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var cb = recordingCallbacks();
        var trace = new LatencyTrace();
        var turnUsage = new LlmProvider.TurnUsage();
        var collectedImages = new ArrayList<String>();

        String result = invokeHandleToolCallsStreaming(agent, convo, convo.id,
                new ArrayList<>(List.of(ChatMessage.user("ping"))), List.of(),
                List.of(toolCall("any", "noop")), "",
                provider, cb, "low", 2,
                new AtomicBoolean(false), trace, turnUsage, collectedImages, "web",
                new ConversationSink(convo));

        assertTrue(result.contains("maximum number of tool execution rounds"),
                "Streaming must short-circuit at the round cap, got: " + result);
        assertEquals(0, streamingServer.getRequestCount(),
                "No LLM HTTP call should be issued when the cap is hit at entry");
    }

    // Streaming: isCancelled.get() at entry preserves priorContent.
    // Covers handleToolCallsStreaming lines 265-266.
    @Test
    void streamingShortCircuitsOnEntryWhenAlreadyCancelled() throws Exception {
        startStreamingServer();
        var provider = openAiProviderForStreamingServer("entry-cancel");
        var agent = persistAgent("entry-cancel-agent");
        var convo = persistConversation(agent);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var cb = recordingCallbacks();
        var trace = new LatencyTrace();
        var turnUsage = new LlmProvider.TurnUsage();
        var collectedImages = new ArrayList<String>();
        var cancelled = new AtomicBoolean(true);

        String result = invokeHandleToolCallsStreaming(agent, convo, convo.id,
                new ArrayList<>(List.of(ChatMessage.user("ping"))), List.of(),
                List.of(toolCall("a", "noop")), "prior content",
                provider, cb, "low", 0, cancelled, trace, turnUsage, collectedImages, "web",
                new ConversationSink(convo));

        assertEquals("prior content", result,
                "Cancelled-at-entry must preserve priorContent");
        assertEquals(0, streamingServer.getRequestCount(),
                "No LLM HTTP call should be issued when cancellation flag is set at entry");
    }

    // Streaming: tool round + continuation streams a final answer.
    // Covers lines 268-313, 328, 351 false branch, 407-409.
    @Test
    void streamingHappyPathReturnsSynthesisContent() throws Exception {
        streamingServer = new MockWebServer();
        streamingServer.start();
        streamingServer.enqueue(sseResponse("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Final answer."},"finish_reason":"stop"}]}

                data: [DONE]

                """));

        var provider = openAiProviderForStreamingServer("happy-stream");
        var agent = persistAgent("happy-stream-agent");
        var convo = persistConversation(agent);

        var newTools = new ArrayList<>(originalTools);
        newTools.add(simpleTool("noop_stream", true, "ok"));
        ToolRegistry.publish(newTools);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var cb = recordingCallbacks();
        var trace = new LatencyTrace();
        var turnUsage = new LlmProvider.TurnUsage();
        var collectedImages = new ArrayList<String>();

        String result = invokeHandleToolCallsStreaming(agent, convo, convo.id,
                new ArrayList<>(List.of(ChatMessage.user("hi"))), List.of(),
                List.of(toolCall("tc1", "noop_stream")), "",
                provider, cb, "low", 0, new AtomicBoolean(false), trace, turnUsage,
                collectedImages, "web", new ConversationSink(convo));

        assertTrue(result.contains("Final answer."),
                "Streaming should surface the final synthesis content, got: " + result);
        assertEquals(1, streamingServer.getRequestCount(),
                "One continuation LLM call should be issued after the tool round");
    }

    // Streaming: truncation mid-tool-call. Covers lines 334-344.
    @Test
    void streamingTruncationWithPendingToolCallsReturnsTruncationHint() throws Exception {
        streamingServer = new MockWebServer();
        streamingServer.start();
        streamingServer.enqueue(sseResponse("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Partial here..."}}]}

                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"trunc","type":"function","function":{"name":"noop_stream","arguments":"{\\"action\\":\\"now"}}]}}]}

                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """));

        var provider = openAiProviderForStreamingServer("trunc-stream");
        var agent = persistAgent("trunc-stream-agent");
        var convo = persistConversation(agent);

        var newTools = new ArrayList<>(originalTools);
        newTools.add(simpleTool("noop_stream", true, "ok"));
        ToolRegistry.publish(newTools);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var cb = recordingCallbacks();
        var trace = new LatencyTrace();
        var turnUsage = new LlmProvider.TurnUsage();
        var collectedImages = new ArrayList<String>();

        String result = invokeHandleToolCallsStreaming(agent, convo, convo.id,
                new ArrayList<>(List.of(ChatMessage.user("hi"))), List.of(),
                List.of(toolCall("tc1", "noop_stream")), "",
                provider, cb, "low", 0, new AtomicBoolean(false), trace, turnUsage,
                collectedImages, "web", new ConversationSink(convo));

        assertTrue(result.contains("truncated") || result.contains("smaller steps"),
                "Streaming truncation guard should surface the hint, got: " + result);
    }

    // Streaming: empty continuation + retry with nudge returns content.
    // Covers lines 361-388/390.
    @Test
    void streamingEmptyContinuationRetriesWithNudgeAndReturnsContent() throws Exception {
        streamingServer = new MockWebServer();
        streamingServer.start();
        streamingServer.enqueue(sseResponse("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

                data: [DONE]

                """));
        streamingServer.enqueue(sseResponse("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"After nudge: here is the synthesis."},"finish_reason":"stop"}]}

                data: [DONE]

                """));

        var provider = openAiProviderForStreamingServer("empty-retry");
        var agent = persistAgent("empty-retry-agent");
        var convo = persistConversation(agent);

        var newTools = new ArrayList<>(originalTools);
        newTools.add(simpleTool("noop_stream", true, "ok"));
        ToolRegistry.publish(newTools);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var cb = recordingCallbacks();
        var trace = new LatencyTrace();
        var turnUsage = new LlmProvider.TurnUsage();
        var collectedImages = new ArrayList<String>();

        String result = invokeHandleToolCallsStreaming(agent, convo, convo.id,
                new ArrayList<>(List.of(ChatMessage.user("hi"))), List.of(),
                List.of(toolCall("tc1", "noop_stream")), "",
                provider, cb, "low", 0, new AtomicBoolean(false), trace, turnUsage,
                collectedImages, "web", new ConversationSink(convo));

        assertTrue(result.contains("After nudge"),
                "Retry-with-nudge should return the second call's content, got: " + result);
        assertEquals(2, streamingServer.getRequestCount(),
                "Empty continuation must trigger exactly one retry");
    }

    // Streaming: empty continuation + empty retry emits diagnostic fallback.
    // Covers lines 394-404.
    @Test
    void streamingEmptyContinuationAndEmptyRetryEmitsDiagnosticFallback() throws Exception {
        streamingServer = new MockWebServer();
        streamingServer.start();
        var emptyBody = """
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        streamingServer.enqueue(sseResponse(emptyBody));
        streamingServer.enqueue(sseResponse(emptyBody));

        var provider = openAiProviderForStreamingServer("empty-twice");
        var agent = persistAgent("empty-twice-agent");
        var convo = persistConversation(agent);

        var newTools = new ArrayList<>(originalTools);
        newTools.add(simpleTool("noop_stream", true, "ok"));
        ToolRegistry.publish(newTools);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var cb = recordingCallbacks();
        var trace = new LatencyTrace();
        var turnUsage = new LlmProvider.TurnUsage();
        var collectedImages = new ArrayList<String>();

        String result = invokeHandleToolCallsStreaming(agent, convo, convo.id,
                new ArrayList<>(List.of(ChatMessage.user("hi"))), List.of(),
                List.of(toolCall("tc1", "noop_stream")), "",
                provider, cb, "low", 0, new AtomicBoolean(false), trace, turnUsage,
                collectedImages, "web", new ConversationSink(convo));

        assertTrue(result.contains("no synthesis after tool calls"),
                "Empty-then-empty must emit the diagnostic fallback, got: " + result);
        assertEquals(2, streamingServer.getRequestCount(),
                "Empty-then-empty path must hit the LLM exactly twice (round + retry)");
    }

    // Sync loop: full pipeline returns YIELDED_RESPONSE when the tool emits
    // the YIELD sentinel. Covers callWithToolLoop lines 210-213.
    @Test
    void syncLoopReturnsYieldedResponseWhenToolEmitsSentinel() throws Exception {
        var callCount = new AtomicInteger(0);
        startSyncLlmServer(exchange -> {
            int n = callCount.getAndIncrement();
            String body;
            if (n == 0) {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"Yielding now.",
                    "tool_calls":[{"id":"yc","type":"function","function":{"name":"yielder","arguments":"{}"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            } else {
                body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"should not reach"},"finish_reason":"stop"}]}""";
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureSyncProvider();

        var newTools = new ArrayList<>(originalTools);
        newTools.add(new ToolRegistry.Tool() {
            @Override public String name() { return "yielder"; }
            @Override public String description() { return "Returns the YIELD sentinel"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                return tools.SubagentYieldTool.YIELD_SENTINEL_PREFIX + ",\"runId\":42}";
            }
        });
        ToolRegistry.publish(newTools);

        var agent = persistAgent("yield-loop-agent");
        var convo = persistConversation(agent);

        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Please yield");

        assertEquals(AgentRunner.YIELDED_RESPONSE, result.response(),
                "Sync loop must return YIELDED_RESPONSE when the yield sentinel is detected");
        assertEquals(1, callCount.get(),
                "Loop should NOT issue a second LLM call after the yield sentinel");
    }

    // === Helpers ===

    private Agent persistAgent(String name) {
        return Tx.run(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "test-provider";
            agent.modelId = "test-model";
            agent.enabled = true;
            agent.save();
            return agent;
        });
    }

    private Conversation persistConversation(Agent agent) {
        return Tx.run(() -> ConversationService.create(agent, "web", "user1"));
    }

    private long seedUserMessageWithAudioTranscript(Conversation convo, String transcript) {
        return Tx.run(() -> {
            var msg = new models.Message();
            msg.conversation = convo;
            msg.role = models.MessageRole.USER.value;
            msg.content = "[audio attachment]";
            msg.save();

            var att = new models.MessageAttachment();
            att.message = msg;
            att.uuid = java.util.UUID.randomUUID().toString();
            att.originalFilename = "speech.ogg";
            att.storagePath = "/tmp/test-speech.ogg";
            att.mimeType = "audio/ogg";
            att.sizeBytes = 1024L;
            att.kind = "audio";
            att.transcript = transcript;
            att.save();
            return msg.id;
        });
    }

    private long attachmentIdFor(long messageId) {
        return Tx.run(() -> {
            var att = (models.MessageAttachment) models.MessageAttachment.find(
                    "message.id = ?1", messageId).first();
            return att.id;
        });
    }

    private void startSyncLlmServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        syncLlmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        syncLlmServer.createContext("/chat/completions", handler);
        syncLlmServer.start();
        syncPort = syncLlmServer.getAddress().getPort();
    }

    private void configureSyncProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + syncPort);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    private OpenAiProvider openAiProviderForSyncServer(String name) {
        var baseUrl = "http://127.0.0.1:" + syncPort;
        var config = new ProviderConfig(name, baseUrl, "sk-test",
                List.of(new ModelInfo("test-model", "Test", 100000, 4096, false)));
        return new OpenAiProvider(config);
    }

    private void startStreamingServer() throws Exception {
        streamingServer = new MockWebServer();
        streamingServer.start();
    }

    private OpenAiProvider openAiProviderForStreamingServer(String name) {
        var baseUrl = streamingServer.url("/").toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        var config = new ProviderConfig(name, baseUrl, "sk-test",
                List.of(new ModelInfo("test-model", "Test", 100000, 4096, false)));
        return new OpenAiProvider(config);
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build();
    }

    private static ToolCall toolCall(String id, String name) {
        return new ToolCall(id, "function", new FunctionCall(name, "{}"));
    }

    private static ToolRegistry.Tool simpleTool(String name, boolean parallelSafe, String returnValue) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Simple stream test tool"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public boolean parallelSafe() { return parallelSafe; }
            @Override public String execute(String argsJson, Agent agent) { return returnValue; }
        };
    }

    private static AgentRunner.StreamingCallbacks recordingCallbacks() {
        Consumer<Conversation> noopConv = _ -> {};
        Consumer<String> noopStr = _ -> {};
        Consumer<AgentRunner.ToolCallEvent> noopTc = _ -> {};
        Consumer<Exception> noopExc = _ -> {};
        Runnable noopRun = () -> {};
        return new AgentRunner.StreamingCallbacks(
                noopConv, noopStr, noopStr, noopStr, noopTc, noopStr, noopExc, noopRun);
    }

    private static ToolCallLoopRunner.LoopOutcome invokeCallWithToolLoop(
            Agent agent, Conversation conversation, Long conversationId,
            List<ChatMessage> messages, List<ToolDef> tools,
            LlmProvider primary, LlmProvider secondary,
            List<agents.VisionAudioAssembler.AudioBearer> audioBearers) throws Exception {

        Method m = ToolCallLoopRunner.class.getDeclaredMethod(
                "callWithToolLoop",
                Agent.class, Conversation.class, Long.class,
                List.class, List.class, LlmProvider.class, LlmProvider.class,
                List.class, AgentExecutionSink.class, Long.class);  // JCLAW-414: trailing taskRunId
        m.setAccessible(true);

        var sink = new ConversationSink(conversation);
        var resultRef = new AtomicReference<ToolCallLoopRunner.LoopOutcome>();
        var errorRef = new AtomicReference<Exception>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                resultRef.set((ToolCallLoopRunner.LoopOutcome) m.invoke(null,
                        agent, conversation, conversationId,
                        new ArrayList<>(messages), tools, primary, secondary,
                        audioBearers, sink, (Long) null));  // JCLAW-414: taskRunId null (chat path)
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        t.join(30_000);
        if (errorRef.get() != null) {
            var e = errorRef.get();
            if (e instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
        return resultRef.get();
    }

    @SuppressWarnings("java:S107")
    private static String invokeHandleToolCallsStreaming(Agent agent, Conversation conversation,
                                                          Long conversationId,
                                                          List<ChatMessage> messages, List<ToolDef> tools,
                                                          List<ToolCall> toolCalls, String priorContent,
                                                          LlmProvider provider,
                                                          AgentRunner.StreamingCallbacks cb,
                                                          String thinkingMode,
                                                          int round, AtomicBoolean isCancelled,
                                                          LatencyTrace trace,
                                                          LlmProvider.TurnUsage turnUsage,
                                                          List<String> collectedImages,
                                                          String channelType,
                                                          AgentExecutionSink sink) throws Exception {

        Method m = ToolCallLoopRunner.class.getDeclaredMethod(
                "handleToolCallsStreaming",
                Agent.class, Conversation.class, Long.class,
                List.class, List.class, List.class, String.class,
                LlmProvider.class, AgentRunner.StreamingCallbacks.class, String.class,
                int.class, AtomicBoolean.class, LatencyTrace.class,
                LlmProvider.TurnUsage.class, List.class, String.class,
                AgentExecutionSink.class);
        m.setAccessible(true);

        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                resultRef.set((String) m.invoke(null,
                        agent, conversation, conversationId, messages, tools, toolCalls,
                        priorContent, provider, cb, thinkingMode, round, isCancelled,
                        trace, turnUsage, collectedImages, channelType, sink));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        t.join(30_000);
        if (errorRef.get() != null) {
            var e = errorRef.get();
            if (e instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
        return resultRef.get();
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
