import agents.ToolRegistry;
import com.google.gson.JsonParser;
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
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool;
import tools.SubagentYieldTool;

import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-273 tests: companion {@code subagent_yield} tool plus the
 * yield-resume branch in {@code SubagentSpawnTool.runAsyncAndAnnounce}.
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
class SubagentYieldToolTest extends UnitTest {

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
        var tool = ToolRegistry.lookupTool(SubagentYieldTool.TOOL_NAME);
        assertNotNull(tool, "subagent_yield must be registered by ToolRegistrationJob");
        assertEquals(SubagentYieldTool.TOOL_NAME, tool.name());
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
        assertTrue(result.startsWith(SubagentYieldTool.YIELD_SENTINEL_PREFIX),
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

        // Missing both runId and conversationId (JCLAW-326: either-or contract).
        result = invokeYieldOnVt(parent.id, "{}");
        assertTrue(result.startsWith("Error: one of 'runId' or 'conversationId' is required"),
                "missing both ids must surface a clear error, got: " + result);

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
        assertTrue(result.startsWith("Error: run " + run.id + " is not owned by the calling agent"),
                "cross-parent yield must be refused, got: " + result);

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(run.id);
        assertFalse(fresh.yielded,
                "refused yield must leave the yielded flag unchanged");
    }

    @Test
    void yieldOnAlreadyTerminalRunReturnsStructuredOutcomeNotError() throws Exception {
        // Race-fix follow-up: when the child finishes between spawn returning
        // {status:RUNNING} and yield's lookup, yield must NOT error out. It
        // must return a structured "already_terminal" envelope carrying the
        // recorded reply so the LLM can use it on the current turn instead
        // of being told its yield failed. The announce posted by the
        // async-finalize VT is still SYSTEM-role (yielded was false at
        // post time), which is correct — flipping yielded after the fact
        // would race the announce in the opposite direction.
        var parent = createAgent("p-yield-terminal", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-terminal");
        var run = seedRunningSubagentRun(parent, parentConv);
        // Move the run to COMPLETED with a recorded outcome so the test
        // can assert the reply field surfaces verbatim.
        run.status = SubagentRun.Status.COMPLETED;
        run.outcome = "42";
        run.save();
        var childConvId = run.childConversation != null ? run.childConversation.id : null;

        commitAndReopen();

        var result = invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + run.id + "\"}");
        // Parse the result as JSON and assert each field individually rather
        // than substring-matching — keeps the test resilient to future
        // payload reorderings (LinkedHashMap insertion order is the only
        // thing that pins ordering today).
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("already_terminal", parsed.get("action").getAsString(),
                "must signal already_terminal, not 'yielded': " + result);
        assertEquals(String.valueOf(run.id), parsed.get("runId").getAsString());
        assertEquals("COMPLETED", parsed.get("status").getAsString());
        assertEquals("42", parsed.get("reply").getAsString(),
                "reply must echo the SubagentRun.outcome verbatim so the LLM "
                        + "sees the child's answer without waiting for an announce");
        if (childConvId != null) {
            assertEquals(String.valueOf(childConvId),
                    parsed.get("conversation_id").getAsString(),
                    "conversation_id link lets the chat UI / LLM jump to the "
                            + "child's transcript for full context");
        }

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(run.id);
        assertFalse(fresh.yielded,
                "already-terminal yield must NOT flip the yielded flag — "
                        + "the announce has already been posted as SYSTEM "
                        + "and flipping yielded post-hoc would create a "
                        + "rendering inconsistency between the column and "
                        + "the message row's role");
    }

    @Test
    void yieldResolvesByConversationIdWhenRunIdMissing() throws Exception {
        // JCLAW-326: conversationId alt-lookup. When runId is missing but
        // conversationId is provided, yield resolves the most-recent
        // SubagentRun whose childConversation matches.
        var parent = createAgent("p-yield-byconv", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-byconv");
        var run = seedRunningSubagentRun(parent, parentConv);
        var childConvId = run.childConversation.id;

        commitAndReopen();

        var result = invokeYieldOnVt(parent.id,
                "{\"conversationId\":\"" + childConvId + "\"}");
        assertTrue(result.startsWith(SubagentYieldTool.YIELD_SENTINEL_PREFIX),
                "conversationId lookup must succeed, got: " + result);
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals(String.valueOf(run.id), parsed.get("runId").getAsString(),
                "resolved runId must echo back in the sentinel payload");

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(run.id);
        assertTrue(fresh.yielded,
                "conversationId-resolved yield must flip the yielded flag");
    }

    @Test
    void yieldRunIdWinsWhenBothProvided() throws Exception {
        // When both runId and conversationId are provided, runId wins
        // (explicit beats inferred).
        var parent = createAgent("p-yield-bothids", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-bothids");
        var primary = seedNamedSubagentRun(parent, parentConv, "bothids-primary");
        var other = seedNamedSubagentRun(parent, parentConv, "bothids-other");

        commitAndReopen();

        // Provide primary's runId BUT other's child conversation id. runId
        // should win and only primary should flip yielded.
        var result = invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + primary.id + "\","
                        + "\"conversationId\":\"" + other.childConversation.id + "\"}");
        assertTrue(result.startsWith(SubagentYieldTool.YIELD_SENTINEL_PREFIX),
                "explicit runId must succeed even alongside an unrelated conversationId, got: " + result);

        JPA.em().clear();
        assertTrue(((SubagentRun) SubagentRun.findById(primary.id)).yielded,
                "primary run (named via runId) must be flipped");
        assertFalse(((SubagentRun) SubagentRun.findById(other.id)).yielded,
                "other run (only its conv id passed) must NOT be flipped — runId wins");
    }

    @Test
    void yieldTimeoutSecondsPersistsToRowAndClampsToMax() throws Exception {
        // JCLAW-326: timeoutSeconds is persisted on SubagentRun for the
        // watchdog to read. Values above MAX_TIMEOUT_SECONDS clamp silently;
        // values <= 0 fall back to DEFAULT_TIMEOUT_SECONDS.
        var parent = createAgent("p-yield-timeoutpersist", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-timeoutpersist");
        var runA = seedNamedSubagentRun(parent, parentConv, "to-a");
        var runB = seedNamedSubagentRun(parent, parentConv, "to-b");
        var runC = seedNamedSubagentRun(parent, parentConv, "to-c");

        commitAndReopen();

        // In-range value persists verbatim.
        invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + runA.id + "\",\"timeoutSeconds\":42}");
        // Above-max value clamps.
        invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + runB.id + "\",\"timeoutSeconds\":99999}");
        // Missing / non-positive falls back to DEFAULT.
        invokeYieldOnVt(parent.id, "{\"runId\":\"" + runC.id + "\"}");

        JPA.em().clear();
        var freshA = (SubagentRun) SubagentRun.findById(runA.id);
        var freshB = (SubagentRun) SubagentRun.findById(runB.id);
        var freshC = (SubagentRun) SubagentRun.findById(runC.id);
        assertEquals(Integer.valueOf(42), freshA.yieldTimeoutSeconds,
                "in-range timeoutSeconds must persist verbatim");
        assertEquals(Integer.valueOf(SubagentYieldTool.MAX_TIMEOUT_SECONDS),
                freshB.yieldTimeoutSeconds,
                "above-max timeoutSeconds must clamp to MAX_TIMEOUT_SECONDS");
        assertEquals(Integer.valueOf(SubagentYieldTool.DEFAULT_TIMEOUT_SECONDS),
                freshC.yieldTimeoutSeconds,
                "missing timeoutSeconds must fall back to DEFAULT");
    }

    @Test
    void yieldOnAlreadyTerminalFailedRunSurfacesFailureReasonAsReply() throws Exception {
        // Cousin to the COMPLETED test: when the child terminated FAILED,
        // SubagentRun.outcome carries the failure reason (see
        // SubagentSpawnTool.runAsyncAndAnnounce). The already_terminal
        // envelope must surface that reason in the reply field too, not
        // just for the COMPLETED case — otherwise an LLM yielding into a
        // crashed child gets an empty reply with no signal about why.
        var parent = createAgent("p-yield-failed-terminal", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-yield-failed-terminal");
        var run = seedRunningSubagentRun(parent, parentConv);
        run.status = SubagentRun.Status.FAILED;
        run.outcome = "child crashed";
        run.save();

        commitAndReopen();

        var result = invokeYieldOnVt(parent.id,
                "{\"runId\":\"" + run.id + "\"}");
        var parsed = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("already_terminal", parsed.get("action").getAsString());
        assertEquals("FAILED", parsed.get("status").getAsString());
        assertEquals("child crashed", parsed.get("reply").getAsString(),
                "FAILED runs carry the failure reason in outcome — must surface here");
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
                SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        childConv.parentConversation = parentConv;
        childConv.save();
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.RUNNING;
        run.yielded = true; // simulate subagent_yield having flipped this
        run.save();

        var runId = run.id;
        var childConvId = childConv.id;
        var parentConvId = parentConv.id;
        var parentName = parent.name;

        commitAndReopen();

        SubagentSpawnTool.runAsyncAndAnnounce(
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
                SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE).fetch();
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
                SubagentSpawnTool.SUBAGENT_CHANNEL, null);
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
        SubagentSpawnTool.runAsyncAndAnnounce(
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
                SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE).fetch();
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
                SubagentSpawnTool.SUBAGENT_CHANNEL, null);
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
            SubagentSpawnTool.runAsyncAndAnnounce(
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
                    SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE).fetch();
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
                SubagentSpawnTool.SUBAGENT_CHANNEL, null);
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

        SubagentSpawnTool.runAsyncAndAnnounce(
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
                SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE).fetch();
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
    void loadRecentMessagesIncludesUserRoleAnnounceAndExcludesSystemAnnounce() {
        // JCLAW-273 filter: USER-role announces (yield resumes) flow into LLM
        // context; SYSTEM-role announces (plain async fire-and-forget) do not.
        var parent = createAgent("p-filter", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-filter");

        // System-role announce (filtered out).
        var sysAnnounce = new Message();
        sysAnnounce.conversation = parentConv;
        sysAnnounce.role = MessageRole.SYSTEM.value;
        sysAnnounce.content = "Subagent completed";
        sysAnnounce.messageKind = SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE;
        sysAnnounce.save();

        // User-role announce (kept in LLM context).
        var userAnnounce = new Message();
        userAnnounce.conversation = parentConv;
        userAnnounce.role = MessageRole.USER.value;
        userAnnounce.content = "Subagent completed (yield)";
        userAnnounce.messageKind = SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE;
        userAnnounce.save();

        commitAndReopen();

        var loaded = Tx.run(() -> ConversationService.loadRecentMessages(
                Conversation.findById(parentConv.id)));
        assertTrue(loaded.stream().anyMatch(m ->
                        MessageRole.USER.value.equals(m.role)
                                && SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE.equals(m.messageKind)),
                "USER-role yield announce must appear in LLM context");
        assertTrue(loaded.stream().noneMatch(m ->
                        MessageRole.SYSTEM.value.equals(m.role)
                                && SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE.equals(m.messageKind)),
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
                SubagentSpawnTool.SUBAGENT_CHANNEL, null);
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

    /** Variant of {@link #seedRunningSubagentRun} that takes a unique-name
     *  suffix, so a single test can seed multiple runs under the same parent
     *  without colliding on the Agent.name unique constraint. */
    private SubagentRun seedNamedSubagentRun(Agent parent, Conversation parentConv, String suffix) {
        var childAgent = createAgent(parent.name + "-" + suffix, "test-provider", "test-model");
        childAgent.parentAgent = parent;
        childAgent.save();
        var childConv = ConversationService.create(childAgent,
                SubagentSpawnTool.SUBAGENT_CHANNEL, null);
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

    /** Invoke SubagentYieldTool.execute on a VT so the body sees committed
     *  rows through a fresh persistence context — matches the
     *  SubagentSpawnToolTest pattern. */
    private String invokeYieldOnVt(Long parentAgentId, String argsJson) throws Exception {
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentAgentId));
                var tool = (SubagentYieldTool) ToolRegistry.lookupTool(SubagentYieldTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, parent));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "subagent_yield must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
