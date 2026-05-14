import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.EventLog;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.SpawnSubagentTool;
import tools.YieldToSubagentTool;

import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-273 tests: companion {@code yield_to_subagent} tool plus the
 * yield-resume branch in {@code SpawnSubagentTool.runAsyncAndAnnounce}.
 *
 * <p>The two halves of the flow are exercised independently:
 * <ul>
 *   <li>The tool itself — validates {@code runId}, requires an owned
 *       RUNNING row, flips the {@code yielded} column, returns the
 *       sentinel JSON payload that AgentRunner scans for.</li>
 *   <li>The announce-VT body — given a {@link SubagentRun} pre-stamped
 *       {@code yielded=true}, posts the announce as a USER-role message
 *       and re-invokes AgentRunner on the parent conversation so a final
 *       assistant reply lands as the parent's next turn.</li>
 * </ul>
 * Driving each half directly (rather than wiring up a parent LLM that
 * calls yield mid-turn) keeps the tests deterministic — the suspend
 * mechanism in AgentRunner is covered by the AgentRunnerCoreTest mock,
 * and the full chain is exercised by the resume-from-yielded-flag tests
 * below.
 */
class YieldToSubagentToolTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        new jobs.ToolRegistrationJob().doJob();
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
        EventLogger.clear();
    }

    // ───────── Tool-level validation ─────────

    @Test
    void toolIsRegisteredAndDiscoverable() {
        var tool = ToolRegistry.lookupTool(YieldToSubagentTool.TOOL_NAME);
        assertNotNull(tool, "yield_to_subagent must be registered by ToolRegistrationJob");
        assertEquals(YieldToSubagentTool.TOOL_NAME, tool.name());
        assertEquals("System", tool.category());
    }

    @Test
    void yieldHappyPathFlipsYieldedFlagAndReturnsSentinel() throws Exception {
        var parent = createAgent("p-yield-ok", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-ok");
        var run = seedRunningSubagentRun(parent, parentConv);

        commitAndReopen();

        var result = invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + run.id + "\"}");

        // Sentinel: AgentRunner scans tool-result text for this exact prefix.
        assertTrue(result.startsWith(YieldToSubagentTool.YIELD_SENTINEL_PREFIX),
                "yield tool must return the YIELD_SENTINEL_PREFIX, got: " + result);
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("yielded", parsed.get("action").getAsString());
        assertEquals(String.valueOf(run.id), parsed.get("runId").getAsString());

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(run.id);
        assertNotNull(fresh);
        assertTrue(fresh.yielded,
                "yield tool must flip SubagentRun.yielded=true on success");
        // Status untouched — yielding only marks intent; the row stays RUNNING
        // until the child actually terminates.
        assertEquals(SubagentRun.Status.RUNNING, fresh.status,
                "yield must not change the SubagentRun status");
    }

    @Test
    void yieldRejectsInvalidRunId() throws Exception {
        var parent = createAgent("p-yield-bad", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-yield-bad");

        commitAndReopen();

        var result = invokeYieldOnVt(parent.id,
                "{\"runId\":\"999999999\"}");
        assertTrue(result.startsWith("Error: no SubagentRun found"),
                "nonexistent runId must surface a clear error, got: " + result);

        // Missing runId.
        result = invokeYieldOnVt(parent.id, "{}");
        assertTrue(result.startsWith("Error: 'runId' is required"),
                "missing runId must surface a required-field error, got: " + result);

        // Non-numeric runId.
        result = invokeYieldOnVt(parent.id, "{\"runId\":\"not-a-number\"}");
        assertTrue(result.startsWith("Error: 'runId' must be a numeric run id"),
                "non-numeric runId must surface a parse error, got: " + result);
    }

    @Test
    void yieldRejectsRunOwnedByDifferentParent() throws Exception {
        var owner = createAgent("p-yield-owner", "test-provider", "test-model");
        var ownerConv = ConversationService.create(owner, "web", "u-yield-owner");
        var run = seedRunningSubagentRun(owner, ownerConv);
        var intruder = createAgent("p-yield-intruder", "test-provider", "test-model");
        ConversationService.create(intruder, "web", "u-yield-intruder");

        commitAndReopen();

        // Intruder tries to yield into owner's run — must be refused.
        var result = invokeYieldOnVt(intruder.id,
                "{\"runId\":\"" + run.id + "\"}");
        assertTrue(result.startsWith("Error: runId " + run.id + " is not owned by the calling agent"),
                "cross-parent yield must be refused, got: " + result);

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(run.id);
        assertFalse(fresh.yielded,
                "refused yield must leave the yielded flag unchanged");
    }

    @Test
    void yieldRejectsAlreadyTerminalRun() throws Exception {
        var parent = createAgent("p-yield-terminal", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-terminal");
        var run = seedRunningSubagentRun(parent, parentConv);
        // Move the run to COMPLETED — yielding into it is now meaningless.
        run.status = SubagentRun.Status.COMPLETED;
        run.save();

        commitAndReopen();

        var result = invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + run.id + "\"}");
        assertTrue(result.startsWith("Error: runId " + run.id + " is not RUNNING"),
                "yielding into a terminal run must be refused, got: " + result);

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(run.id);
        assertFalse(fresh.yielded,
                "refused yield must leave the yielded flag unchanged");
    }

    // ───────── Announce-VT yield-resume branch ─────────

    @Test
    void yieldHappyPathPostsUserRoleAnnounceAndResumesParent() throws Exception {
        // Run the announce-VT body directly with yielded=true pre-stamped.
        // The mock LLM returns the same response for both the child run AND
        // the parent's resume turn — both call /chat/completions, both get
        // "Resumed reply".
        startLlmServer(simpleResponse("Resumed reply"));
        configureProvider();

        var parent = createAgent("p-yield-resume", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-resume");
        var childAgent = createAgent("p-yield-resume-child", "test-provider", "test-model");
        childAgent.parentAgent = parent;
        childAgent.save();
        var childConv = ConversationService.create(childAgent,
                SpawnSubagentTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.yielded = true; // simulate yield_to_subagent having flipped this
        run.save();

        var runId = run.id;
        var childConvId = childConv.id;
        var parentConvId = parentConv.id;
        var parentName = parent.name;

        commitAndReopen();

        SpawnSubagentTool.runAsyncAndAnnounce(
                runId, childAgent.id, childConvId, parentConvId,
                parentName, "session", "fresh", "yield-label",
                30, "do the thing");
        EventLogger.flush();

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.COMPLETED, fresh.status,
                "yielded run still terminates via the standard COMPLETE path");

        // Announce Message exists in the parent Conversation, USER-role.
        java.util.List<Message> announces = Message.find(
                "conversation = ?1 AND messageKind = ?2 ORDER BY createdAt ASC",
                Conversation.findById(parentConvId),
                SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
        assertEquals(1, announces.size(),
                "yield resume must post exactly one announce");
        var announce = announces.getFirst();
        assertEquals(MessageRole.USER.value, announce.role,
                "yielded announce must be USER-role so the LLM sees it as input");
        var payload = JsonParser.parseString(announce.metadata).getAsJsonObject();
        assertEquals("COMPLETED", payload.get("status").getAsString());
        assertEquals("Resumed reply", payload.get("reply").getAsString());
        assertTrue(payload.get("yielded").getAsBoolean(),
                "announce payload must carry the yielded flag for UI affordances");

        // Resume turn: the announce VT called AgentRunner.runYieldResume,
        // which appended a final ASSISTANT-role message to the parent
        // conversation. Look for any assistant message dated after the
        // announce.
        java.util.List<Message> parentAssistants = Message.find(
                "conversation = ?1 AND role = ?2 ORDER BY createdAt ASC",
                Conversation.findById(parentConvId), MessageRole.ASSISTANT.value).fetch();
        assertFalse(parentAssistants.isEmpty(),
                "yield resume must produce at least one assistant Message in the parent conversation");
        // The resume's final assistant content should equal the canned mock
        // response (which both the child and the resume's LLM call return).
        assertEquals("Resumed reply", ((Message) parentAssistants.getLast()).content,
                "yield resume must persist the canned final assistant reply");
    }

    @Test
    void yieldFailureCaseStillResumesWithStructuredFailure() throws Exception {
        // Force the child to fail (bogus childAgentId triggers the
        // IllegalStateException catch in runAsyncAndAnnounce). The yield
        // flag is still set, so the failure announce must land USER-role
        // and the parent must still get a resume turn.
        startLlmServer(simpleResponse("Resumed after failure"));
        configureProvider();

        var parent = createAgent("p-yield-fail", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-fail");
        var childAgent = createAgent("p-yield-fail-child", "test-provider", "test-model");
        childAgent.parentAgent = parent;
        childAgent.save();
        var childConv = ConversationService.create(childAgent,
                SpawnSubagentTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.yielded = true;
        run.save();

        var runId = run.id;
        var childConvId = childConv.id;
        var parentConvId = parentConv.id;

        commitAndReopen();

        // Bogus childAgentId: triggers the "Subagent rows vanished" branch.
        SpawnSubagentTool.runAsyncAndAnnounce(
                runId, 999_999_999L, childConvId, parentConvId,
                parent.name, "session", "fresh", "fail-label",
                30, "task");
        EventLogger.flush();

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.FAILED, fresh.status);

        java.util.List<Message> announces = Message.find(
                "conversation = ?1 AND messageKind = ?2",
                Conversation.findById(parentConvId),
                SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
        assertEquals(1, announces.size());
        assertEquals(MessageRole.USER.value, ((Message) announces.getFirst()).role,
                "yielded FAILED announce must still post USER-role so the parent's resume sees it");
        var payload = JsonParser.parseString(((Message) announces.getFirst()).metadata).getAsJsonObject();
        assertEquals("FAILED", payload.get("status").getAsString());

        // Parent received its resume turn (assistant message produced).
        java.util.List<Message> parentAssistants = Message.find(
                "conversation = ?1 AND role = ?2",
                Conversation.findById(parentConvId), MessageRole.ASSISTANT.value).fetch();
        assertFalse(parentAssistants.isEmpty(),
                "yield resume must fire even when the child failed");
    }

    @Test
    void yieldTimeoutResumesWithTimeoutMessage() throws Exception {
        // Block the LLM mock so AgentRunner.run inside runAsyncAndAnnounce
        // trips the 1s timeout. The resume LLM call returns the canned
        // success reply (which is fine — we only care that resume fired).
        // Two endpoints aren't possible on the test harness; instead, hold
        // the mock until the test releases the gate. The first call
        // (child) hits the gate; the resume's call (after the timeout)
        // releases through the same gate path.
        var llmGate = new java.util.concurrent.CountDownLatch(1);
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        startLlmServer(exchange -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                // First call: the child. Block until released.
                try { llmGate.await(15, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            }
            // Second call and beyond: respond immediately so the parent's
            // resume turn lands cleanly.
            var body = simpleResponse(n == 1 ? "child-late" : "resume-ok");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parent = createAgent("p-yield-timeout", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-timeout");
        var childAgent = createAgent("p-yield-timeout-child", "test-provider", "test-model");
        childAgent.parentAgent = parent;
        childAgent.save();
        var childConv = ConversationService.create(childAgent,
                SpawnSubagentTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.yielded = true;
        run.save();

        var runId = run.id;
        var childConvId = childConv.id;
        var parentConvId = parentConv.id;
        var childAgentId = childAgent.id;

        commitAndReopen();

        try {
            // 1-second budget; the gate blocks past it so the child times out.
            SpawnSubagentTool.runAsyncAndAnnounce(
                    runId, childAgentId, childConvId, parentConvId,
                    parent.name, "session", "fresh", "timeout-label",
                    1, "long task");
            EventLogger.flush();

            JPA.em().clear();
            SubagentRun fresh = SubagentRun.findById(runId);
            assertEquals(SubagentRun.Status.TIMEOUT, fresh.status);

            java.util.List<Message> announces = Message.find(
                    "conversation = ?1 AND messageKind = ?2",
                    Conversation.findById(parentConvId),
                    SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
            assertEquals(1, announces.size());
            assertEquals(MessageRole.USER.value, ((Message) announces.getFirst()).role,
                    "yielded TIMEOUT announce must still post USER-role");
            var payload = JsonParser.parseString(((Message) announces.getFirst()).metadata).getAsJsonObject();
            assertEquals("TIMEOUT", payload.get("status").getAsString());
            assertTrue(payload.get("reply").getAsString().contains("exceeded"),
                    "TIMEOUT reply must surface the budget-exceeded reason");
        } finally {
            llmGate.countDown(); // release any still-blocked mock-handler thread
        }
    }

    @Test
    void asyncWithoutYieldStillUsesSystemRoleRegression() throws Exception {
        // JCLAW-270 regression guard: when yielded=false (the default), the
        // announce stays SYSTEM-role and NO resume turn fires. The parent
        // sees the announce as a UI-only card.
        startLlmServer(simpleResponse("Plain async reply"));
        configureProvider();

        var parent = createAgent("p-no-yield", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-no-yield");
        var childAgent = createAgent("p-no-yield-child", "test-provider", "test-model");
        childAgent.parentAgent = parent;
        childAgent.save();
        var childConv = ConversationService.create(childAgent,
                SpawnSubagentTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.yielded = false;
        run.save();

        var runId = run.id;
        var childConvId = childConv.id;
        var parentConvId = parentConv.id;

        commitAndReopen();

        SpawnSubagentTool.runAsyncAndAnnounce(
                runId, childAgent.id, childConvId, parentConvId,
                parent.name, "session", "fresh", "regression",
                30, "task");
        EventLogger.flush();

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.COMPLETED, fresh.status);

        java.util.List<Message> announces = Message.find(
                "conversation = ?1 AND messageKind = ?2",
                Conversation.findById(parentConvId),
                SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
        assertEquals(1, announces.size());
        assertEquals(MessageRole.SYSTEM.value, ((Message) announces.getFirst()).role,
                "plain async (yielded=false) must keep the JCLAW-270 SYSTEM-role announce shape");

        // No resume turn: parent has no assistant message (the announce VT
        // body never re-invoked AgentRunner on the parent).
        java.util.List<Message> parentAssistants = Message.find(
                "conversation = ?1 AND role = ?2",
                Conversation.findById(parentConvId), MessageRole.ASSISTANT.value).fetch();
        assertTrue(parentAssistants.isEmpty(),
                "plain async (yielded=false) must NOT re-invoke AgentRunner on the parent");
    }

    @Test
    void loadRecentMessagesIncludesUserRoleAnnounceAndExcludesSystemAnnounce() throws Exception {
        // JCLAW-273 filter: USER-role announces (yield resumes) flow into LLM
        // context; SYSTEM-role announces (plain async fire-and-forget) do not.
        var parent = createAgent("p-filter", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-filter");

        // System-role announce (filtered out).
        var sysAnnounce = new Message();
        sysAnnounce.conversation = parentConv;
        sysAnnounce.role = MessageRole.SYSTEM.value;
        sysAnnounce.content = "Subagent completed";
        sysAnnounce.messageKind = SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE;
        sysAnnounce.save();

        // User-role announce (kept in LLM context).
        var userAnnounce = new Message();
        userAnnounce.conversation = parentConv;
        userAnnounce.role = MessageRole.USER.value;
        userAnnounce.content = "Subagent completed (yield)";
        userAnnounce.messageKind = SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE;
        userAnnounce.save();

        commitAndReopen();

        var loaded = Tx.run(() -> ConversationService.loadRecentMessages(
                Conversation.findById(parentConv.id)));
        assertTrue(loaded.stream().anyMatch(m ->
                        MessageRole.USER.value.equals(m.role)
                                && SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE.equals(m.messageKind)),
                "USER-role yield announce must appear in LLM context");
        assertTrue(loaded.stream().noneMatch(m ->
                        MessageRole.SYSTEM.value.equals(m.role)
                                && SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE.equals(m.messageKind)),
                "SYSTEM-role plain-async announce must remain filtered out");
    }

    // ───────── helpers ─────────

    private Agent createAgent(String name, String provider, String model) {
        var agent = AgentService.create(name, provider, model);
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private SubagentRun seedRunningSubagentRun(Agent parent, Conversation parentConv) {
        var childAgent = createAgent(parent.name + "-child", "test-provider", "test-model");
        childAgent.parentAgent = parent;
        childAgent.save();
        var childConv = ConversationService.create(childAgent,
                SpawnSubagentTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.save();
        return run;
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

    /** Commit pending setup rows so the VT-dispatched tool body sees them. */
    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    /** Invoke YieldToSubagentTool.execute on a VT so the body sees committed
     *  rows through a fresh persistence context — matches the
     *  SpawnSubagentToolTest pattern. */
    private String invokeYieldOnVt(Long parentAgentId, String argsJson) throws Exception {
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentAgentId));
                var tool = (YieldToSubagentTool) ToolRegistry.lookupTool(YieldToSubagentTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, parent));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "yield_to_subagent must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
