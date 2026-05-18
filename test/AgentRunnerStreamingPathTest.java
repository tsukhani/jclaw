import agents.AgentRunner;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-318: residual coverage for {@link AgentRunner#runStreaming} —
 * the SPA / Telegram-streaming entrypoint that the rest of the
 * AgentRunner test suite leaves mostly bare. Existing
 * {@code ToolCallLoopRunnerStreamingTest} hits the tool-continuation
 * branches by reflectively invoking {@code handleToolCallsStreaming},
 * but the prologue of {@code streamLlmLoop} (provider resolve,
 * conversation queue acquire, audio-bearer empty path, round-1
 * accumulator wiring) only runs through {@code runStreaming}.
 *
 * <p>Each test drives a complete {@code runStreaming} call against an
 * embedded {@code HttpServer} mocking the LLM and awaits the
 * terminal callback ({@code onComplete} / {@code onError}) via a
 * {@link CountDownLatch}, mirroring the existing
 * {@code ChatStreamSseTest} contract.
 */
class AgentRunnerStreamingPathTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        // 200ms settle — same pattern as AgentRunnerCoreTest.
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

    // ─── No-provider envelope on streaming path ─────────────────────────

    @Test
    void runStreamingEmitsErrorWhenNoProviderConfigured() throws Exception {
        // streamLlmLoop's "primary == null" guard at the provider-resolve
        // boundary must surface a normal-shaped onError, not bubble an
        // exception out of the virtual thread (which would leak to the
        // VT's default uncaught handler and never reach the SSE caller).
        var agent = persistAgent("stream-no-provider", "missing", "model");
        var convo = persistConversation(agent, "web", "u-no-provider");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, convo, "hi");

        assertTrue(harness.terminated.await(30, TimeUnit.SECONDS),
                "runStreaming must reach a terminal callback within 30s");
        assertNotNull(harness.error.get(),
                "no-provider on the streaming path must invoke onError");
        assertTrue(harness.error.get().getMessage().contains("No LLM provider"),
                "error message must surface the 'No LLM provider' diagnostic, got: "
                        + harness.error.get().getMessage());
    }

    // ─── Conversation-not-found error envelope ──────────────────────────

    @Test
    void runStreamingEmitsErrorWhenConversationIdDoesNotExist() throws Exception {
        // resolveConversationAndAcquireQueue's findById returns null for a
        // stale conversation id (UI tab open for hours, conversation
        // deleted server-side). Contract: onError fires with a "Conversation
        // not found" payload — no LLM call, no queue acquire, no user
        // message persisted.
        var agent = persistAgent("stream-stale-conv", "missing", "model");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, /* conversationId */ 99999L,
                "web", "u-stale", "hi");

        assertTrue(harness.terminated.await(30, TimeUnit.SECONDS),
                "runStreaming must terminate within 30s on stale-conv path");
        assertNotNull(harness.error.get(),
                "stale conversation id must surface as onError");
        assertTrue(harness.error.get().getMessage().contains("Conversation not found"),
                "onError payload must explain the stale-conv condition, got: "
                        + harness.error.get().getMessage());
        // Critical: no init callback fires when the conversation is missing —
        // resolveConversationAndAcquireQueue bails before cb.onInit.
        assertNull(harness.initConvo.get(),
                "onInit must NOT fire when the conversation cannot be resolved");
    }

    // ─── Queue-busy canned response ─────────────────────────────────────

    @Test
    void runStreamingEmitsQueuedCannedResponseWhenQueueIsBusy() throws Exception {
        // The streaming entry's tryAcquire short-circuit (line 664 of
        // AgentRunner) fires when another turn already owns the
        // conversation queue. Contract: onInit fires with the resolved
        // conversation (so the SPA can render the assistant placeholder
        // for the queued message), then onComplete fires with the canned
        // "queued" string — no LLM call is issued.
        var agent = persistAgent("stream-queue-busy", "missing", "model");
        var convo = persistConversation(agent, "web", "u-queue-busy");

        // Manually grab the queue with a sentinel inbound — mimics a real
        // in-flight turn holding the lock.
        var blocker = new services.ConversationQueue.QueuedMessage(
                "blocker", "web", "u-queue-busy", agent);
        assertTrue(services.ConversationQueue.tryAcquire(convo.id, blocker),
                "test precondition: queue must be acquirable");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        try {
            var harness = streamAndAwait(agent, convo, "second message");

            assertTrue(harness.terminated.await(30, TimeUnit.SECONDS),
                    "runStreaming must terminate within 30s on queue-busy path");
            assertNotNull(harness.completed.get(),
                    "queue-busy path must reach onComplete with the canned response");
            assertTrue(harness.completed.get().contains("queued"),
                    "queue-busy onComplete payload must contain 'queued', got: "
                            + harness.completed.get());
            assertNotNull(harness.initConvo.get(),
                    "onInit must fire with the resolved conversation even on queue-busy");
        } finally {
            // Defensive: release so subsequent tests in the suite don't
            // see a stuck queue.
            services.ConversationQueue.releaseOwnership(convo.id);
        }
    }

    // ─── Happy streaming path ───────────────────────────────────────────

    @Test
    void runStreamingDeliversTokensAndPersistsAssistantMessageOnHappyPath() throws Exception {
        // The headline streamLlmLoop happy path: a single-chunk SSE
        // response is forwarded to onToken, the round folds into the
        // turn-level usage, onComplete fires with the full content, and
        // the assistant message is persisted to the conversation.
        startSseServer("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Hello "}}]}

                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"world!"},"finish_reason":"stop"}]}

                data: [DONE]

                """);
        configureProvider();
        var agent = persistAgent("stream-happy", "test-provider", "test-model");
        var convo = persistConversation(agent, "web", "u-happy");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, convo, "say hi");

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS),
                "runStreaming must terminate within 60s on the streaming happy path");
        assertNull(harness.error.get(),
                "happy path must not invoke onError; got error: "
                        + (harness.error.get() != null ? harness.error.get().getMessage() : "(none)"));
        assertNotNull(harness.completed.get(),
                "happy path must reach onComplete");
        assertEquals("Hello world!", harness.completed.get(),
                "onComplete payload must equal the concatenated SSE deltas");
        // onToken must have fired at least once per content chunk.
        assertTrue(harness.tokens.size() >= 2,
                "streaming must emit one onToken per content delta, got: " + harness.tokens);

        // Persistence side: the assistant reply must be in the DB by the
        // time onComplete settles (see the JCLAW-100 ordering comment in
        // streamLlmLoop). Reload through ConversationService to dodge any
        // stale-EM snapshot.
        JPA.em().clear();
        var rows = ConversationService.loadRecentMessages(
                ConversationService.findById(convo.id));
        assertTrue(rows.size() >= 2,
                "expected user + assistant rows on happy path, got " + rows.size());
        Message lastAssistant = null;
        for (var m : rows) {
            if (MessageRole.ASSISTANT.value.equals(m.role)) lastAssistant = m;
        }
        assertNotNull(lastAssistant,
                "assistant row must be persisted by the time onComplete returns");
        assertEquals("Hello world!", lastAssistant.content,
                "persisted assistant content must match the streamed payload");
    }

    // ─── Truncated reply (finish_reason=length, no tool calls) ──────────

    @Test
    void runStreamingFlagsTruncatedWhenFinishReasonIsLengthAndNoToolCallsArePresent() throws Exception {
        // JCLAW-291: empty-toolCalls + finish_reason=length is the silent
        // truncation path. streamLlmLoop must set replyTruncated and pass
        // it through to the persisted assistant row so the UI can render
        // the "truncated" marker.
        startSseServer("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"The reply gets cut off here"},"finish_reason":"length"}]}

                data: [DONE]

                """);
        configureProvider();
        var agent = persistAgent("stream-trunc-empty", "test-provider", "test-model");
        var convo = persistConversation(agent, "web", "u-trunc-empty");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, convo, "write a long answer");

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS),
                "runStreaming must terminate within 60s on the truncated-reply path");
        assertNotNull(harness.completed.get(),
                "truncated reply must still reach onComplete (with the partial content)");
        assertTrue(harness.completed.get().contains("cut off"),
                "onComplete payload must contain the partial content, got: "
                        + harness.completed.get());

        JPA.em().clear();
        var rows = ConversationService.loadRecentMessages(
                ConversationService.findById(convo.id));
        assertFalse(rows.isEmpty(),
                "assistant row must be persisted even on truncated reply");
        Message lastAssistant = null;
        for (var m : rows) {
            if (MessageRole.ASSISTANT.value.equals(m.role)) lastAssistant = m;
        }
        assertNotNull(lastAssistant,
                "assistant row must be persisted by the time onComplete returns");
        // The persisted assistant message must carry truncated=true so
        // the UI can render a marker without re-introspecting the
        // provider response.
        assertTrue(lastAssistant.truncated,
                "persisted assistant Message must carry truncated=true on length finish_reason");
    }

    // ─── runStreaming on web conversation with conversationId=null ──────

    @Test
    void runStreamingCreatesNewWebConversationWhenConversationIdIsNull() throws Exception {
        // The web channel branch at line 650-651 of AgentRunner forks
        // ConversationService.create vs findOrCreate based on the
        // channelType. A null conversationId on a "web" channel triggers
        // the create path — pin that branch via runStreaming directly.
        startSseServer("""
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"fresh"},"finish_reason":"stop"}]}

                data: [DONE]

                """);
        configureProvider();
        var agent = persistAgent("stream-fresh-conv", "test-provider", "test-model");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, /* conversationId */ null,
                "web", "u-fresh", "hello");

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS),
                "runStreaming must terminate within 60s on the fresh-conv path");
        assertNull(harness.error.get(),
                "fresh-conv create must not error; got: "
                        + (harness.error.get() != null ? harness.error.get().getMessage() : "(none)"));
        assertNotNull(harness.initConvo.get(),
                "onInit must surface the freshly-created conversation");
        assertNotNull(harness.initConvo.get().id,
                "freshly-created conversation must be persisted with a non-null id");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /** Container for the streaming-callback observations. */
    private static class Harness {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<String> reasoning = new CopyOnWriteArrayList<>();
        final AtomicReference<Conversation> initConvo = new AtomicReference<>();
        final AtomicReference<String> completed = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final CountDownLatch terminated = new CountDownLatch(1);
    }

    private Harness streamAndAwait(Agent agent, Conversation convo, String text) {
        return streamAndAwait(agent, convo != null ? convo.id : null,
                convo != null ? convo.channelType : "web",
                convo != null ? convo.peerId : "u-default",
                text);
    }

    private Harness streamAndAwait(Agent agent, Long conversationId,
                                    String channelType, String peerId, String text) {
        var h = new Harness();
        var cb = new AgentRunner.StreamingCallbacks(
                h.initConvo::set,
                h.tokens::add,
                h.reasoning::add,
                _ -> {},
                _ -> {},
                content -> { h.completed.set(content); h.terminated.countDown(); },
                error -> { h.error.set(error); h.terminated.countDown(); },
                () -> { h.cancelled.set(true); h.terminated.countDown(); }
        );
        AgentRunner.runStreaming(agent, conversationId, channelType, peerId, text,
                new AtomicBoolean(false), cb, null);
        return h;
    }

    private Agent persistAgent(String name, String provider, String model) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private Conversation persistConversation(Agent agent, String channelType, String peerId) {
        return ConversationService.create(agent, channelType, peerId);
    }

    private void startSseServer(String sseBody) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            var bytes = sseBody.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
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
}
