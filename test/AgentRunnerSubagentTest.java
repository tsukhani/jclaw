import agents.AgentRunner;
import agents.RunCancelledException;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-308: AgentRunner-level subagent integration branches NOT already
 * pinned by {@link tools.SubagentSpawnTool}'s own test file
 * (SubagentSpawnToolTest covers the spawn-tool surface — child Agent + FK,
 * inherit-mode parent-context, tool union grant, async announce, etc.).
 *
 * <p>This file pins the runner-side hooks the tool relies on:
 * <ul>
 *   <li>{@link AgentRunner#checkSubagentCancel} — fires at the
 *       conversation-forward boundary in {@code runAfterAcquire} and throws
 *       {@link RunCancelledException} when the
 *       {@link SubagentRegistry} cancel flag for the current
 *       conversation's SubagentRun has been flipped.</li>
 *   <li>{@link AgentRunner#runYieldResume} — async-yield resume entrypoint
 *       that runs the standard pipeline WITHOUT re-appending a user
 *       message (the announce row was already persisted by
 *       {@code SubagentSpawnTool#postAnnounceMessage}).</li>
 * </ul>
 *
 * <p>The AC "cancellation mid-tool-call stops the loop at the next safe
 * checkpoint and surfaces a cancelled status" is pinned at the lowest
 * layer here: the checkpoint itself. The integration between the
 * checkpoint and {@code SubagentSpawnTool}'s outer catch (which marks the
 * SubagentRun KILLED) is already covered in SubagentSpawnToolTest's
 * cancellation tests.
 */
class AgentRunnerSubagentTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

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
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
        SubagentRegistry.clear();
    }

    // ─── checkSubagentCancel ────────────────────────────────────────────

    @Test
    void checkSubagentCancelIsNoOpWhenConversationIsNull() {
        // Defensive guard — production callers don't pass null, but the
        // method contract must not NPE in case a future caller does.
        AgentRunner.checkSubagentCancel(null);
        // Reached this line without exception — pass.
    }

    @Test
    void checkSubagentCancelIsNoOpForNonSubagentConversation() {
        // The vast majority of conversations are NOT subagents; the
        // checkpoint must be a near-zero-cost lookup that returns
        // immediately without throwing.
        var agent = createAgent("ck-no-sub", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-no-sub");
        // No SubagentRun row points at this conversation; the lookup
        // returns null and the method returns silently.
        AgentRunner.checkSubagentCancel(convo);
    }

    @Test
    void checkSubagentCancelIsNoOpWhenFlagIsNotSet() {
        // A subagent conversation exists, the run is RUNNING, the registry
        // entry exists — but the cancel flag has not been flipped. The
        // checkpoint must not throw. Without this assertion, a regression
        // that always fires when an entry exists would silently kill every
        // subagent.
        var parentAgent = createAgent("ck-flag-parent", "test-provider", "test-model");
        var childAgent = createAgent("ck-flag-child", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-flag-parent");
        var childConv = ConversationService.create(childAgent, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        var run = Tx.run(() -> {
            var r = new SubagentRun();
            r.parentAgent = parentAgent;
            r.childAgent = childAgent;
            r.parentConversation = parentConv;
            r.childConversation = childConv;
            r.status = SubagentRun.Status.RUNNING;
            r.save();
            return r;
        });
        SubagentRegistry.register(run.id, new CompletableFuture<Void>());

        // Flag NOT flipped — checkpoint must return cleanly.
        AgentRunner.checkSubagentCancel(childConv);

        SubagentRegistry.unregister(run.id);
    }

    @Test
    void checkSubagentCancelThrowsRunCancelledExceptionWhenFlagIsSet() {
        // The load-bearing assertion: when the SubagentRegistry cancel
        // flag for the current conversation's run is set, the next
        // checkpoint throws {@link RunCancelledException} carrying the
        // run id. SubagentSpawnTool's outer catch then leaves the
        // already-stamped KILLED audit row intact (vs marking it FAILED).
        //
        // Production calls {@link SubagentRegistry#kill} which both flips
        // the flag AND transitions the audit row to KILLED in the same
        // call. But the checkpoint reads {@code status=RUNNING} when
        // looking up the runId, so a test that calls kill() first would
        // never observe the flag through the checkpoint. To pin the
        // checkpoint's contract in isolation we flip the flag directly
        // (mirroring the mid-kill window where status is still RUNNING
        // but the flag has just been set) via reflection on the
        // package-private Entry record.
        var parentAgent = createAgent("ck-throw-parent", "test-provider", "test-model");
        var childAgent = createAgent("ck-throw-child", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-throw-parent");
        var childConv = ConversationService.create(childAgent, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        var run = Tx.run(() -> {
            var r = new SubagentRun();
            r.parentAgent = parentAgent;
            r.childAgent = childAgent;
            r.parentConversation = parentConv;
            r.childConversation = childConv;
            r.status = SubagentRun.Status.RUNNING;
            r.save();
            return r;
        });
        SubagentRegistry.register(run.id, new CompletableFuture<Void>());

        // Flip the cancel flag while keeping the audit row in RUNNING
        // status. Mirrors the mid-kill window where the flag is set but
        // the status-update Tx hasn't landed yet.
        assertTrue(SubagentRegistry.cancelForTest(run.id),
                "test precondition: registry must contain an entry for the run before cancelForTest");
        assertTrue(SubagentRegistry.isCancelled(run.id),
                "test precondition: flag must be flipped before invoking the checkpoint");

        var thrown = assertThrows(RunCancelledException.class,
                () -> AgentRunner.checkSubagentCancel(childConv),
                "checkpoint must throw RunCancelledException when the cancel flag is set");
        assertEquals(run.id, thrown.runId(),
                "exception must carry the runId so the outer catch can correlate to the audit row");

        SubagentRegistry.unregister(run.id);
    }

    @Test
    void aStoppedChildStillStopsAtItsCheckpointAfterItsRunIsUnregistered() {
        // The timeout path: flag flipped, run unregistered and saved TIMEOUT, child thread still mid-tool.
        var runId = 708_0001L;
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var thrown = SubagentRegistry.callAsRun(runId, () -> {
            SubagentRegistry.requestStop(runId);
            SubagentRegistry.unregister(runId);
            return assertThrows(RunCancelledException.class, () -> AgentRunner.checkSubagentCancel(null),
                    "the child's own checkpoint must still see the stop");
        });
        assertEquals(runId, thrown.runId());

        assertNull(SubagentRegistry.currentRun(), "the binding ends with the call");
        AgentRunner.checkSubagentCancel(null);
        assertNull(SubagentRegistry.callAsRun(708_0002L, SubagentRegistry::currentRun),
                "a run that isn't registered runs unbound");
    }

    @Test
    void checkSubagentCancelIgnoresTerminalRunRows() {
        // A SubagentRun row exists for the conversation but is already
        // terminal (COMPLETED, FAILED, KILLED, TIMEOUT). The query in
        // checkSubagentCancel filters on status=RUNNING, so no checkpoint
        // should fire for terminal rows — even if a stale registry entry
        // somehow had its flag flipped. Without this filter, replaying a
        // conversation associated with a long-finished subagent would
        // instantly bail.
        var parentAgent = createAgent("ck-terminal-parent", "test-provider", "test-model");
        var childAgent = createAgent("ck-terminal-child", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-terminal-parent");
        var childConv = ConversationService.create(childAgent, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        var run = Tx.run(() -> {
            var r = new SubagentRun();
            r.parentAgent = parentAgent;
            r.childAgent = childAgent;
            r.parentConversation = parentConv;
            r.childConversation = childConv;
            r.status = SubagentRun.Status.COMPLETED;
            r.endedAt = java.time.Instant.now();
            r.outcome = "previously done";
            r.save();
            return r;
        });
        // Even with a (stale, hypothetical) registry entry whose flag is set:
        SubagentRegistry.register(run.id, new CompletableFuture<Void>());
        SubagentRegistry.kill(run.id, "shouldn't matter"); // no-op on terminal rows

        // The checkpoint's status=RUNNING filter excludes this row from
        // the lookup, so the kill flag is never consulted.
        AgentRunner.checkSubagentCancel(childConv); // must NOT throw

        SubagentRegistry.unregister(run.id);
    }

    /**
     * AC: "Cancellation mid-tool-call: a cancellation arriving while a
     * tool is mid-execution stops the loop at the next safe checkpoint,
     * persists the partial state, and surfaces a cancelled status to the
     * caller."
     *
     * <p>At the AgentRunner level this AC reduces to: a kill that lands
     * while AgentRunner.run is executing surfaces as a
     * {@link RunCancelledException} at the next checkpoint, and that
     * happens BEFORE the runner sends the next LLM round (no further
     * provider budget burned). The persistence side (audit row → KILLED)
     * is owned by SubagentRegistry.kill which has already stamped the row
     * by the time the checkpoint fires.
     */
    @Test
    void runRaisesRunCancelledExceptionWhenSubagentCancelFlagIsSetBeforeStart() throws Exception {
        // AgentRunner.run on a subagent conversation whose cancel flag is
        // set MUST throw at the first checkpoint (line 369 of AgentRunner)
        // BEFORE any LLM call is made. This is the end-to-end form of the
        // checkpoint-level test above: the AC's "next safe checkpoint"
        // requirement is that no provider budget is burned past the
        // cancellation. The flag is flipped directly (without going
        // through kill()) so the SubagentRun row remains RUNNING and the
        // checkpoint's status filter matches.
        var llmCallCount = new java.util.concurrent.atomic.AtomicInteger(0);
        startLlmServer(exchange -> {
            llmCallCount.incrementAndGet();
            var body = simpleResponse("should never be sent");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parentAgent = createAgent("run-cancel-parent", "test-provider", "test-model");
        var childAgent = createAgent("run-cancel-child", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-run-cancel-parent");
        var childConv = ConversationService.create(childAgent, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        var run = Tx.run(() -> {
            var r = new SubagentRun();
            r.parentAgent = parentAgent;
            r.childAgent = childAgent;
            r.parentConversation = parentConv;
            r.childConversation = childConv;
            r.status = SubagentRun.Status.RUNNING;
            r.save();
            return r;
        });
        SubagentRegistry.register(run.id, new CompletableFuture<Void>());
        assertTrue(SubagentRegistry.cancelForTest(run.id),
                "test precondition: registry must contain an entry for the run before cancelForTest");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        // AgentRunner.run on the child conv must throw on the first
        // checkpoint — no LLM call should happen.
        var errorRef = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var c = Tx.run(() -> (Conversation) Conversation.findById(childConv.id));
                var a = Tx.run(() -> (Agent) Agent.findById(childAgent.id));
                AgentRunner.run(a, c, "Hello");
            } catch (Throwable t) {
                errorRef.set(t);
            }
        });
        thread.join(10_000);
        assertFalse(thread.isAlive(), "runner must complete (throw) within 10s");

        var thrown = errorRef.get();
        assertNotNull(thrown,
                "set cancel flag before run must surface as a thrown exception, not a normal RunResult");
        assertTrue(thrown instanceof RunCancelledException,
                "checkpoint must throw RunCancelledException, got: "
                        + (thrown != null ? thrown.getClass().getName() + ": " + thrown.getMessage() : "null"));
        assertEquals(run.id, ((RunCancelledException) thrown).runId(),
                "thrown exception must carry the original runId");
        assertEquals(0, llmCallCount.get(),
                "checkpoint must short-circuit BEFORE the first LLM call (no provider budget burned)");

        SubagentRegistry.unregister(run.id);
    }

    // ─── runYieldResume ─────────────────────────────────────────────────

    /**
     * AC: "Subagent return: completed subagent run's final assistant
     * message routes back to parent's tool-result message."
     *
     * <p>At the AgentRunner layer this AC includes the {@code runYieldResume}
     * entrypoint, which {@code SubagentSpawnTool.runAsyncAndAnnounce}
     * invokes after a yielded async child terminates. The resume must NOT
     * re-append a user message (the announce was already persisted as a
     * USER-role row); it must run the standard pipeline against the
     * existing history and persist the assistant reply.
     */
    @Test
    void runYieldResumeDoesNotDuplicateTheAnnounceMessage() throws Exception {
        startLlmServer(exchange -> {
            var body = simpleResponse("Resumed reply after child finished.");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parentAgent = createAgent("resume-parent", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-resume");

        // Pre-existing parent turn — the parent had asked something before
        // yielding to the subagent.
        ConversationService.appendUserMessage(parentConv, "do investigative work");
        ConversationService.appendAssistantMessage(parentConv,
                "I'll yield to a subagent for that.", null);

        // Simulate what SubagentSpawnTool#postAnnounceMessage already did
        // BEFORE runAsyncAndAnnounce calls runYieldResume: persist a
        // USER-role announce row carrying the child's final reply.
        Tx.run(() -> {
            var conv = (Conversation) Conversation.findById(parentConv.id);
            return ConversationService.appendMessage(conv,
                    MessageRole.USER,
                    "[subagent reply] child's findings",
                    null, null, null);
        });

        long beforeCount = Tx.run(() -> Message.count("conversation = ?1", parentConv));
        assertEquals(3L, beforeCount,
                "pre-resume state should have 3 rows: original user + assistant + announce");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var resultRef = new AtomicReference<AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(parentAgent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(parentConv.id));
                resultRef.set(AgentRunner.runYieldResume(a, c));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "runYieldResume must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();

        var result = resultRef.get();
        assertNotNull(result);
        assertEquals("Resumed reply after child finished.", result.response(),
                "runYieldResume must surface the post-resume assistant reply");

        // The announce row must not have been duplicated — only ONE extra
        // row exists after the resume (the new assistant reply), bringing
        // the total to 4. If runYieldResume had re-appended a user message
        // we'd see 5 (3 prior + duplicated announce + assistant).
        JPA.em().clear();
        long afterCount = Tx.run(() -> Message.count("conversation = ?1",
                (Conversation) Conversation.findById(parentConv.id)));
        assertEquals(4L, afterCount,
                "runYieldResume must NOT re-append the announce — expected 4 rows "
                        + "(3 pre + 1 new assistant), got " + afterCount);

        // Verify the LAST row is the assistant reply and the announce is
        // unchanged (no duplicate).
        java.util.List<Message> all = Tx.run(() -> Message.<Message>find(
                "conversation = ?1 ORDER BY id ASC",
                (Conversation) Conversation.findById(parentConv.id)).fetch());
        Message last = all.getLast();
        assertEquals(MessageRole.ASSISTANT.value, last.role,
                "final row must be the new assistant reply");
        assertEquals("Resumed reply after child finished.", last.content);

        // The announce row count must be exactly one.
        long announceCount = all.stream()
                .filter(m -> "[subagent reply] child's findings".equals(m.content))
                .count();
        assertEquals(1L, announceCount,
                "announce row must appear exactly once after resume");
    }

    @Test
    void runYieldResumeReturnsCannedQueuedResponseWhenAnotherTurnHoldsQueue() {
        // Queue-busy path: if a concurrent turn already holds the
        // conversation queue when runYieldResume is invoked, the resume is
        // queued just like any inbound turn — the caller sees the canned
        // "queued" RunResult without the resume work happening immediately.
        //
        // This guards against a regression where runYieldResume's
        // tryAcquire short-circuit was bypassed and a yielded resume could
        // race a concurrent message, double-appending or interleaving
        // assistant turns.
        var parentAgent = createAgent("resume-busy-parent", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-resume-busy");

        // Manually acquire the queue under a different message to simulate
        // another turn in flight.
        var blocker = new services.ConversationQueue.QueuedMessage(
                "blocker message", "web", "u-resume-busy", parentAgent);
        boolean acquired = services.ConversationQueue.tryAcquire(parentConv.id, blocker);
        assertTrue(acquired, "test precondition: queue must be available to acquire");

        try {
            var result = AgentRunner.runYieldResume(parentAgent, parentConv);
            assertNotNull(result.response());
            assertTrue(result.response().toLowerCase().contains("queue"),
                    "runYieldResume into a busy queue must return the canned 'queued' "
                            + "response, got: " + result.response());
        } finally {
            // Defensive cleanup: release the queue so other tests in the
            // suite aren't blocked. The runYieldResume call above did not
            // acquire (tryAcquire returned false) so the only owner is the
            // blocker we just created.
            services.ConversationQueue.releaseOwnership(parentConv.id);
        }
    }

    @Test
    void queuedYieldResumeDoesNotAppendAnEmptyUserRowWhenDrained() throws Exception {
        // JCLAW-273 regression: runYieldResume passes skipUserAppend=true on the
        // immediate-acquire path, but a busy queue routes the resume through
        // enqueue -> drain, and the drain re-entered the runner via the appending
        // path carrying the resume's empty text. That persisted a blank USER row
        // and fed the LLM an empty turn after the announce. The flag now rides on
        // QueuedMessage so it survives the enqueue/drain boundary.
        var replies = new AtomicInteger();
        startLlmServer(exchange -> {
            var body = simpleResponse(replies.getAndIncrement() == 0
                    ? "Blocker reply." : "Resumed reply after drain.");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parentAgent = createAgent("resume-drain-parent", "test-provider", "test-model");
        var parentConv = ConversationService.create(parentAgent, "web", "u-resume-drain");

        // The announce SubagentSpawnTool already persisted before resuming.
        Tx.run(() -> {
            var conv = (Conversation) Conversation.findById(parentConv.id);
            return ConversationService.appendMessage(conv, MessageRole.USER,
                    "[subagent reply] child's findings", null, null, null);
        });

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        // Another turn is mid-flight, so the resume can only be queued.
        var blocker = new services.ConversationQueue.QueuedMessage(
                "blocker message", "web", "u-resume-drain", parentAgent);
        assertTrue(services.ConversationQueue.tryAcquire(parentConv.id, blocker),
                "test precondition: queue must be available to acquire");

        var queuedRef = new AtomicReference<AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(parentAgent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(parentConv.id));
                queuedRef.set(AgentRunner.runYieldResume(a, c));
                // Finish the blocking turn we own. Its finally drains the queue
                // and processes the resume on the agent-drain VT.
                AgentRunner.runWithOwnedQueue(a, c, "blocker message");
            } catch (Throwable t) {
                errorRef.set(t);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "blocking turn must complete within 30s");
        if (errorRef.get() != null) {
            throw new AssertionError("blocking turn threw", errorRef.get());
        }
        assertNotNull(queuedRef.get());
        assertTrue(queuedRef.get().response().toLowerCase().contains("queue"),
                "precondition: the resume must have been queued, not run inline");

        // The drain runs on its own VT and only releases ownership once the
        // resume's own run has finished and re-drained empty.
        long deadline = System.currentTimeMillis() + 30_000;
        while (services.ConversationQueue.isBusy(parentConv.id)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(services.ConversationQueue.isBusy(parentConv.id),
                "queue must be released once the drained resume completes");

        JPA.em().clear();
        java.util.List<Message> all = Tx.run(() -> Message.<Message>find(
                "conversation = ?1 ORDER BY id ASC",
                (Conversation) Conversation.findById(parentConv.id)).fetch());

        // The assertion the bug trips: the drain must not persist a blank turn.
        long blank = all.stream()
                .filter(m -> MessageRole.USER.value.equals(m.role))
                .filter(m -> m.content == null || m.content.isBlank())
                .count();
        assertEquals(0L, blank,
                "drained yield-resume must not persist an empty user row, found " + blank);

        assertEquals(4, all.size(),
                "expected 4 rows (announce, blocker user, blocker assistant, resume "
                        + "assistant), got " + all.stream().map(m -> m.role + "=" + m.content).toList());

        long announceCount = all.stream()
                .filter(m -> "[subagent reply] child's findings".equals(m.content))
                .count();
        assertEquals(1L, announceCount, "announce row must appear exactly once after drain");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

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
}
