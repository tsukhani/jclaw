import agents.AgentRunner;
import agents.ToolRegistry;
import llm.LlmResilience;
import models.Agent;
import models.Conversation;
import models.EventLog;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import utils.CircuitBreakers;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    // ─── GH-12: operator stop releases the queue for the next send ──────

    @Test
    void aSendRightAfterStopStreamsAFreshRunAndTheStoppedTurnCannotReleaseIt() throws Exception {
        var firstArrived = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondArrived = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var requests = new AtomicInteger();
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        llmServer.createContext("/chat/completions", exchange -> {
            boolean first = requests.getAndIncrement() == 0;
            try {
                (first ? firstArrived : secondArrived).countDown();
                (first ? releaseFirst : releaseSecond).await(60, TimeUnit.SECONDS);
                var body = """
                        data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """.formatted(first ? "stale" : "fresh");
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                var bytes = body.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException _) {
                // The stopped turn may have hung up already.
            }
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
        var provider = "gh12-stop-" + UUID.randomUUID();
        configureProvider(provider);
        var agent = persistAgent("gh12-stop-agent", provider, "test-model");
        var convo = persistConversation(agent, "web", "u-gh12");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var stoppedFlag = new AtomicBoolean(false);
        try {
            var stopped = streamAndAwait(agent, convo.id, "web", convo.peerId, "long task", stoppedFlag);
            assertTrue(firstArrived.await(30, TimeUnit.SECONDS), "the first turn reached the model");
            assertTrue(services.ConversationQueue.isBusy(convo.id));

            assertTrue(services.ConversationQueue.stop(convo.id));
            assertTrue(stoppedFlag.get(), "stop flips the running turn's own cancel flag");
            assertFalse(services.ConversationQueue.isBusy(convo.id), "stop releases the conversation at once");

            var fresh = streamAndAwait(agent, convo.id, "web", convo.peerId, "follow-up", new AtomicBoolean(false));
            assertTrue(secondArrived.await(30, TimeUnit.SECONDS),
                    "the follow-up acquired and reached the model instead of being queued");

            releaseFirst.countDown();
            assertTrue(stopped.terminated.await(60, TimeUnit.SECONDS), "the stopped turn terminates");
            assertTrue(services.ConversationQueue.isBusy(convo.id),
                    "the stopped turn's late release leaves the new turn's ownership intact");

            releaseSecond.countDown();
            assertTrue(fresh.terminated.await(60, TimeUnit.SECONDS));
            assertEquals("fresh", fresh.completed.get());
            assertFalse(services.ConversationQueue.isBusy(convo.id), "the new turn released on completion");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            services.ConversationQueue.releaseOwnership(convo.id);
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
        }
    }

    // ─── JCLAW-1385: a stopped turn writes nothing after the stop ───────

    private static final String HOLD_TOOL = "gh1385_hold";

    private static final String HOLD_TOOL_CALL_SSE = """
            data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_hold","type":"function","function":{"name":"gh1385_hold","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """;

    @Test
    void aToolThatFinishesAfterStopWritesNothingAndTheFollowUpOwnsEveryLaterRow() throws Exception {
        var toolEntered = new CountDownLatch(1);
        var releaseTool = new CountDownLatch(1);
        var secondArrived = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var requests = new AtomicInteger();
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        llmServer.createContext("/chat/completions", exchange -> {
            boolean first = requests.getAndIncrement() == 0;
            try {
                String body;
                if (first) {
                    body = HOLD_TOOL_CALL_SSE;
                } else {
                    secondArrived.countDown();
                    releaseSecond.await(60, TimeUnit.SECONDS);
                    body = contentSse("fresh");
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                var bytes = body.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException _) {
                // A turn may have hung up already.
            }
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
        var provider = "gh1385-stop-" + UUID.randomUUID();
        configureProvider(provider);
        var agent = persistAgent("gh1385-stop-" + UUID.randomUUID(), provider, "test-model");
        var convo = persistConversation(agent, "web", "u-gh1385");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        ToolRegistrySync.withTools(List.of(holdTool(toolEntered, releaseTool)), () -> {
            var stoppedFlag = new AtomicBoolean(false);
            try {
                var stopped = streamAndAwait(agent, convo.id, "web", convo.peerId, "long task", stoppedFlag);
                assertTrue(toolEntered.await(30, TimeUnit.SECONDS), "the stopped turn's tool is running");

                assertTrue(services.ConversationQueue.stop(convo.id));
                long maxIdAtStop = messages(convo.id).stream().mapToLong(m -> m.id).max().orElse(0);

                var fresh = streamAndAwait(agent, convo.id, "web", convo.peerId, "follow-up", new AtomicBoolean(false));
                assertTrue(secondArrived.await(30, TimeUnit.SECONDS), "the follow-up owns the conversation");

                releaseTool.countDown();
                assertTrue(stopped.terminated.await(60, TimeUnit.SECONDS), "the stopped turn terminates");
                assertTrue(services.ConversationQueue.isBusy(convo.id),
                        "the stopped turn's late release leaves the new turn's ownership intact");
                // The drop line is written in the stopped turn's finally, after its terminal callback and any capture.
                List<EventLog> drops = new java.util.ArrayList<>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (drops.isEmpty() && System.nanoTime() < deadline) {
                    EventLogger.flush();
                    JPA.em().clear();
                    for (Object o : EventLog.find("category = ?1 AND agentId = ?2 AND message LIKE ?3",
                            "queue", agent.name, "Dropped%").fetch()) {
                        drops.add((EventLog) o);
                    }
                    if (drops.isEmpty()) Thread.sleep(50);
                }
                assertEquals(1, drops.size(), "one drop line per stopped turn");
                assertTrue(drops.getFirst().message.contains("Dropped 2 message rows")
                                && drops.getFirst().message.contains("conversation " + convo.id),
                        drops.getFirst().message);
                assertFalse(memory.MemoryAutoCapture.captureRequestedForTest(convo.id),
                        "the stopped exchange is never captured");

                releaseSecond.countDown();
                assertTrue(fresh.terminated.await(60, TimeUnit.SECONDS));
                assertEquals("fresh", fresh.completed.get());
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (services.ConversationQueue.isBusy(convo.id) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertFalse(services.ConversationQueue.isBusy(convo.id), "the new turn released on completion");

                var all = messages(convo.id);
                var after = all.stream().filter(m -> m.id > maxIdAtStop).toList();
                assertEquals(List.of("user:follow-up", "assistant:fresh"),
                        after.stream().map(m -> m.role + ":" + m.content).toList(),
                        "every row after the stop is the follow-up's");
                assertTrue(all.stream().noneMatch(m -> MessageRole.TOOL.value.equals(m.role) || m.toolCalls != null),
                        "no tool-call or tool-result row from the stopped turn");
            } finally {
                releaseTool.countDown();
                releaseSecond.countDown();
                services.ConversationQueue.releaseOwnership(convo.id);
                CircuitBreakers.remove(LlmResilience.breakerName(provider));
            }
        });
    }

    @Test
    void anUnstoppedToolRoundPersistsItsRowsAndIsCaptured() throws Exception {
        var requests = new AtomicInteger();
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = requests.getAndIncrement() == 0 ? HOLD_TOOL_CALL_SSE : contentSse("done");
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            var bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
        var provider = "gh1385-ok-" + UUID.randomUUID();
        configureProvider(provider);
        var agent = persistAgent("gh1385-ok-" + UUID.randomUUID(), provider, "test-model");
        var convo = persistConversation(agent, "web", "u-gh1385-ok");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var released = new CountDownLatch(0);
        ToolRegistrySync.withTools(List.of(holdTool(new CountDownLatch(1), released)), () -> {
            try {
                var h = streamAndAwait(agent, convo.id, "web", convo.peerId, "run it", new AtomicBoolean(false));
                assertTrue(h.terminated.await(60, TimeUnit.SECONDS));
                assertEquals("done", h.completed.get());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (services.ConversationQueue.isBusy(convo.id) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertFalse(services.ConversationQueue.isBusy(convo.id));

                var rows = messages(convo.id);
                assertTrue(rows.stream().anyMatch(m -> m.toolCalls != null && m.toolCalls.contains(HOLD_TOOL)),
                        "the tool-call row persists");
                assertTrue(rows.stream().anyMatch(m -> MessageRole.TOOL.value.equals(m.role)),
                        "the tool-result row persists");
                EventLogger.flush();
                assertEquals(0, EventLog.count("category = ?1 AND agentId = ?2 AND message LIKE ?3",
                        "queue", agent.name, "Dropped%"), "an unstopped turn drops nothing");
                // The early release precedes onComplete, and capture follows finalize: wait for it too.
                while (!memory.MemoryAutoCapture.captureRequestedForTest(convo.id) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertTrue(memory.MemoryAutoCapture.captureRequestedForTest(convo.id),
                        "the test can see a capture request");
            } finally {
                services.ConversationQueue.releaseOwnership(convo.id);
                CircuitBreakers.remove(LlmResilience.breakerName(provider));
            }
        });
    }

    private static ToolRegistry.Tool holdTool(CountDownLatch entered, CountDownLatch release) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return HOLD_TOOL; }
            @Override public String description() { return "Blocks until the test releases it"; }
            @Override public java.util.Map<String, Object> parameters() {
                return java.util.Map.of("type", "object", "properties", java.util.Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                entered.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "held";
            }
        };
    }

    private static String contentSse(String content) {
        return """
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":"stop"}]}

                data: [DONE]

                """.formatted(content);
    }

    private static List<Message> messages(Long conversationId) {
        JPA.em().clear();
        List<Message> rows = new java.util.ArrayList<>();
        for (Object o : Message.find("conversation.id = ?1 order by id", conversationId).fetch()) {
            rows.add((Message) o);
        }
        return rows;
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

    // ─── JCLAW-1188: a breaker's refusal is not a transient 5xx ─────────

    @Test
    void runStreamingRetriesOnceOnATransient5xx() throws Exception {
        // The positive control for the case below: the retry rule and its log line are real.
        var calls = new AtomicInteger();
        startStatusServer(500, calls);
        configureProvider("jclaw1188-flaky");
        var agent = persistAgent("jclaw1188-flaky-agent", "jclaw1188-flaky", "test-model");
        var convo = persistConversation(agent, "web", "u-1188-flaky");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        try {
            var harness = streamAndAwait(agent, convo, "say hi");
            assertTrue(harness.terminated.await(60, TimeUnit.SECONDS));
            assertEquals(2, calls.get(), "one attempt and one retry reach the wire");
            assertEquals(1, retryLogLines(agent), "and the retry is announced once");
        } finally {
            CircuitBreakers.remove(LlmResilience.breakerName("jclaw1188-flaky"));
        }
    }

    @Test
    void runStreamingDoesNotRetryIntoAnOpenBreaker() throws Exception {
        var calls = new AtomicInteger();
        startStatusServer(500, calls);
        configureProvider("jclaw1188-open");
        var agent = persistAgent("jclaw1188-open-agent", "jclaw1188-open", "test-model");
        var convo = persistConversation(agent, "web", "u-1188-open");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        try {
            LlmResilience.breakerFor("jclaw1188-open").trip();
            var harness = streamAndAwait(agent, convo, "say hi");
            assertTrue(harness.terminated.await(60, TimeUnit.SECONDS));
            assertEquals(0, calls.get(), "refused before the wire");
            assertEquals(0, retryLogLines(agent),
                    "a refusal is not a transient error: no second refusal, no misleading log line");
        } finally {
            CircuitBreakers.remove(LlmResilience.breakerName("jclaw1188-open"));
        }
    }

    /**
     * Event-log rows are batched and the runner flushes its own on the stream thread after the
     * terminal callback, so the line can be mid-commit when the harness returns; poll briefly.
     */
    private static long retryLogLines(Agent agent) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            EventLogger.flush();
            var count = EventLog.count("agentId = ?1 AND message LIKE ?2", agent.name, "Retrying streaming%");
            if (count > 0 || System.nanoTime() >= deadline) return count;
            Thread.sleep(100);
        }
    }

    // ─── Audio-format-rejection → transcript re-stream recovery ─────────

    @Test
    void runStreamingRecoversFromAudioFormatRejectionViaTranscript() throws Exception {
        // A supportsAudio model rejects the audio FORMAT (Xiaomi: mp3/flac/m4a/wav/ogg only) on the
        // round-1 passthrough, BEFORE any tokens stream. streamRound1WithAudioFallback must await the
        // Whisper transcript, rewrite audio→text, and re-stream — recovering the turn instead of
        // surfacing the 400 as onError (the old behaviour, which interactive voice notes always hit).
        var requestBodies = new CopyOnWriteArrayList<String>();
        var calls = new AtomicInteger(0);
        startAudioRejectThenSucceedServer(requestBodies, calls);
        configureAudioProvider();

        var agent = persistAgent("stream-audio-retry", "test-provider", "test-model");
        var convo = persistConversation(agent, "web", "u-audio-retry");
        seedAudioMessage(agent, convo, "The spoken words were: meet me at noon.");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, convo, "and then what?");
        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS),
                "runStreaming must terminate within 60s on the audio-retry path");

        assertNull(harness.error.get(),
                "audio-format rejection must be recovered, not surfaced as onError; got: "
                        + (harness.error.get() != null ? harness.error.get().getMessage() : "(none)"));
        assertNotNull(harness.completed.get(), "the transcript re-stream must reach onComplete");
        assertEquals("Recovered reply", harness.completed.get());
        assertEquals(2, calls.get(),
                "exactly two LLM calls: the rejected passthrough + the transcript retry");
        assertTrue(requestBodies.get(0).contains("input_audio"),
                "round 1 must carry native audio: " + requestBodies.get(0));
        assertTrue(requestBodies.get(1).contains("meet me at noon"),
                "round 2 must carry the Whisper transcript text: " + requestBodies.get(1));
        assertFalse(requestBodies.get(1).contains("input_audio"),
                "round 2 must NOT re-send native audio");
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

    // JCLAW-1203: a first call cut off while still reasoning is retried with reasoning off.
    private static final String REASONING_ONLY_LENGTH = """
            data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"reasoning_content":"Planning the answer, but then ("},"finish_reason":null}]}

            data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":""},"finish_reason":"length"}]}

            data: [DONE]

            """;

    @Test
    void runStreamingRecoversAFirstReplyThatStoppedWhileReasoning() throws Exception {
        var bodies = new CopyOnWriteArrayList<String>();
        startSseSequenceServer(bodies, REASONING_ONLY_LENGTH, """
                data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Answer as content."},"finish_reason":"stop"}]}

                data: [DONE]

                """);
        configureThinkingProvider();
        var agent = persistAgent("stream-reason-cut", "test-provider", "test-model");
        agent.thinkingMode = "high";
        agent.save();
        var convo = persistConversation(agent, "web", "u-reason-cut");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, convo, "write a long answer");
        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS), "must terminate");
        assertNotNull(harness.completed.get());
        assertTrue(harness.completed.get().contains("Answer as content."),
                "the reasoning-off retry's content is the reply, got: " + harness.completed.get());
        assertEquals(2, bodies.size(), "first call + one retry");
        assertTrue(bodies.get(0).contains("\"reasoning_effort\":\"high\""), "the first call carried the agent's level");
        assertTrue(bodies.get(1).contains("\"reasoning_effort\":\"none\""),
                "the retry sends the explicit off-signal for a thinking-capable model, got: " + bodies.get(1));
        assertTrue(bodies.get(1).contains("Write the full answer now"), "the retry carries the answer nudge");

        JPA.em().clear();
        Message lastAssistant = null;
        for (var m : ConversationService.loadRecentMessages(ConversationService.findById(convo.id))) {
            if (MessageRole.ASSISTANT.value.equals(m.role)) lastAssistant = m;
        }
        assertNotNull(lastAssistant);
        assertEquals("Answer as content.", lastAssistant.content);
        assertFalse(lastAssistant.truncated, "a recovered reply is not marked truncated");
    }

    @Test
    void runStreamingReportsTheReasoningTailWhenEveryRetryIsEmpty() throws Exception {
        var bodies = new CopyOnWriteArrayList<String>();
        startSseSequenceServer(bodies, REASONING_ONLY_LENGTH, REASONING_ONLY_LENGTH);
        configureThinkingProvider();
        var agent = persistAgent("stream-reason-cut-twice", "test-provider", "test-model");
        var convo = persistConversation(agent, "web", "u-reason-cut-twice");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var harness = streamAndAwait(agent, convo, "write a long answer");
        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS), "must terminate");
        assertEquals(2, bodies.size(), "first call + one retry; no fallback configured");
        var reply = harness.completed.get();
        assertNotNull(reply);
        assertTrue(reply.contains("stopped before writing an answer") && reply.contains("but then ("),
                "the diagnostic quotes the reasoning tail, got: " + reply);

        JPA.em().clear();
        Message lastAssistant = null;
        for (var m : ConversationService.loadRecentMessages(ConversationService.findById(convo.id))) {
            if (MessageRole.ASSISTANT.value.equals(m.role)) lastAssistant = m;
        }
        assertNotNull(lastAssistant);
        assertTrue(lastAssistant.truncated, "still marked truncated when nothing recovered");
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
        return streamAndAwait(agent, conversationId, channelType, peerId, text, new AtomicBoolean(false));
    }

    private Harness streamAndAwait(Agent agent, Long conversationId, String channelType, String peerId,
                                   String text, AtomicBoolean isCancelled) {
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
                isCancelled, cb, null);
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

    /** Answers successive chat completions with successive SSE bodies and records each request body. */
    private void startSseSequenceServer(List<String> requestBodies, String... sseBodies) throws Exception {
        var next = new AtomicInteger(0);
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes()));
            var body = sseBodies[Math.min(next.getAndIncrement(), sseBodies.length - 1)];
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            var bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    /** As configureProvider, with a model that advertises reasoning so the level reaches the wire. */
    private void configureThinkingProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096,\"supportsThinking\":true}]");
        llm.ProviderRegistry.refresh();
    }

    private void configureProvider() {
        configureProvider("test-provider");
    }

    private void configureProvider(String name) {
        ConfigService.set("provider." + name + ".baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider." + name + ".apiKey", "sk-test");
        ConfigService.set("provider." + name + ".models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    /** Answers every chat completion with {@code status} and counts them. */
    private void startStatusServer(int status, AtomicInteger calls) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            var bytes = "{\"error\":{\"message\":\"boom\"}}".getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private void configureAudioProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096,\"supportsAudio\":true}]");
        llm.ProviderRegistry.refresh();
    }

    /** First /chat/completions → 400 audio-format rejection; subsequent → 200 SSE success. Records bodies. */
    private void startAudioRejectThenSucceedServer(List<String> bodies, AtomicInteger calls) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            if (calls.incrementAndGet() == 1) {
                var err = "{\"error\":{\"message\":\"invalid audio format, only mp3/flac/m4a/wav/ogg are supported\"}}";
                var bytes = err.getBytes();
                exchange.sendResponseHeaders(400, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                var sse = """
                        data: {"id":"r","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Recovered reply"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """;
                var bytes = sse.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    /** Persist a prior USER turn carrying a voice note (staged file + a known transcript). */
    private void seedAudioMessage(Agent agent, Conversation convo, String transcript) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var dir = services.AgentService.acquireWorkspacePath(agent.name, "attachments/" + convo.id);
        Files.createDirectories(dir);
        Files.write(dir.resolve(uuid + ".mp3"), new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        var msg = new Message();
        msg.conversation = convo;
        msg.role = MessageRole.USER.value;
        msg.content = "(voice note)";
        msg.save();
        var att = new MessageAttachment();
        att.message = msg;
        att.uuid = uuid;
        att.kind = MessageAttachment.KIND_AUDIO;
        att.mimeType = "audio/mp3";
        att.originalFilename = "voice.mp3";
        att.storagePath = agent.name + "/attachments/" + convo.id + "/" + uuid + ".mp3";
        att.sizeBytes = 8;
        att.transcript = transcript;
        att.save();
    }
}
