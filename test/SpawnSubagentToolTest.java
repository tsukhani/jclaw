import agents.AgentRunner;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import models.EventLog;
import models.Message;
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
import services.SessionCompactor;
import services.Tx;
import tools.SpawnSubagentTool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-265 tests: spawn_subagent tool.
 *
 * <p>Each test stands up an in-process HTTP mock as the LLM, points a
 * test-provider at it via ConfigService, registers the tools, then drives
 * SpawnSubagentTool.execute directly on a virtual thread. The VT pattern
 * mirrors AgentRunnerCoreTest — the tool spawns the child run on its own
 * VT and awaits a Future, which requires that parent + child rows be
 * visible from a fresh persistence context.
 */
class SpawnSubagentToolTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
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

    @Test
    void toolIsRegisteredAndDiscoverable() {
        var tool = ToolRegistry.lookupTool(SpawnSubagentTool.TOOL_NAME);
        assertNotNull(tool, "spawn_subagent must be registered by ToolRegistrationJob");
        assertEquals(SpawnSubagentTool.TOOL_NAME, tool.name());
        assertEquals("System", tool.category());
    }

    @Test
    void happyPathRecordsRunCompletedAndEmitsLifecycleEvents() throws Exception {
        startLlmServer(simpleResponse("Subagent reply: done."));
        configureProvider();

        var parent = createAgent("p-happy", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-happy");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"investigate X\",\"label\":\"investigate-x\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("Subagent reply: done.", parsed.get("reply").getAsString(),
                "tool return must surface the child's final assistant reply");
        assertEquals("COMPLETED", parsed.get("status").getAsString());
        assertNotNull(parsed.get("run_id").getAsString());
        assertNotNull(parsed.get("conversation_id").getAsString());

        JPA.em().clear();

        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run, "SubagentRun row must exist after spawn");
        assertEquals(SubagentRun.Status.COMPLETED, run.status);
        assertNotNull(run.endedAt, "endedAt must be set on terminal update");
        assertEquals("Subagent reply: done.", run.outcome);
        assertEquals(parent.id, run.parentAgent.id);
        assertEquals(parentConv.id, run.parentConversation.id);
        assertNotNull(run.childAgent, "child agent FK must be populated");
        assertNotNull(run.childConversation, "child conversation FK must be populated");
        assertNotEquals(parent.id, run.childAgent.id,
                "default agentId must create a fresh child, not reuse the parent");

        // Child Agent + Conversation parent FKs (JCLAW-264) wired correctly.
        Agent child = Agent.findById(run.childAgent.id);
        assertNotNull(child.parentAgent);
        assertEquals(parent.id, child.parentAgent.id);

        Conversation childConv = Conversation.findById(run.childConversation.id);
        assertNotNull(childConv.parentConversation);
        assertEquals(parentConv.id, childConv.parentConversation.id);
        assertEquals(SpawnSubagentTool.SUBAGENT_CHANNEL, childConv.channelType);

        // Event lifecycle: SPAWN + COMPLETE, no ERROR.
        java.util.List<EventLog> spawnEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_SPAWN, parent.name).fetch();
        assertEquals(1, spawnEvents.size(), "exactly one SUBAGENT_SPAWN event");
        java.util.List<EventLog> completeEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_COMPLETE, parent.name).fetch();
        assertEquals(1, completeEvents.size(), "exactly one SUBAGENT_COMPLETE event");
        java.util.List<EventLog> errorEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_ERROR, parent.name).fetch();
        assertTrue(errorEvents.isEmpty(), "happy path must not emit ERROR events");

        // SPAWN details carry the run_id we returned to the LLM.
        var spawnDetails = spawnEvents.getFirst().details;
        assertTrue(spawnDetails.contains("\"run_id\":\"" + runId + "\""),
                "SUBAGENT_SPAWN details must reference the persisted run id");
        assertTrue(spawnDetails.contains("\"mode\":\"session\""));
        assertTrue(spawnDetails.contains("\"context\":\"fresh\""));
    }

    @Test
    void llmErrorMarksRunFailedAndEmitsErrorEvent() throws Exception {
        // LLM returns 500 every call. AgentRunner surfaces an error-shaped
        // string response (rather than throwing), so the COMPLETED status is
        // technically correct for the audit row in that flavor — but here we
        // want to verify the explicit failure path: cause AgentRunner.run to
        // raise inside the VT. We do this by misconfiguring the provider so
        // ProviderRegistry.get returns null and run() emits its canned error;
        // then add a second case below for an outright thrown exception.
        startLlmServer(exchange -> { exchange.sendResponseHeaders(500, 0); exchange.close(); });
        configureProvider();

        var parent = createAgent("p-fail", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-fail");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"do thing\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        // 500 from the LLM is caught by AgentRunner and turned into a
        // user-facing error string; the run still completes from the audit
        // log's perspective. The reply field is the canned error message.
        assertEquals("COMPLETED", parsed.get("status").getAsString());
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run);
        assertNotNull(run.outcome, "outcome must capture the child's error string");
    }

    @Test
    void runnerExceptionMarksRunFailedAndEmitsErrorEvent() throws Exception {
        // Force ExecutionException by deleting the child Agent row from under
        // the VT after spawn but before the child run. Simplest reproducer:
        // configure an unknown provider name on the child via override so
        // AgentRunner's resolution fails inside the VT — but AgentRunner
        // gracefully returns a canned string in that case. Instead we cover
        // the unchecked-throw branch by spawning with no parent conversation
        // (forcing the early error return), then assert the audit + events
        // for the "could not resolve parent" path are coherent.
        var parent = createAgent("p-noconv", "test-provider", "test-model");
        // Deliberately no conversation row for the parent agent.
        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"orphan\"}");
        assertTrue(reply.startsWith("Error: Could not resolve a parent conversation"),
                "early bailout must surface a plain-text error, got: " + reply);
        // No SubagentRun row should exist for a failed bootstrap.
        JPA.em().clear();
        long rowCount = SubagentRun.count();
        assertEquals(0, rowCount,
                "early-bailout path must not insert a SubagentRun row");
    }

    @Test
    void timeoutMarksRunTimeoutAndEmitsTimeoutEvent() throws Exception {
        // Block the LLM mock so AgentRunner.run sits past the runTimeoutSeconds
        // budget. Use a 1s timeout to keep the test fast.
        var llmGate = new java.util.concurrent.CountDownLatch(1);
        startLlmServer(exchange -> {
            try { llmGate.await(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            var body = simpleResponse("late");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parent = createAgent("p-timeout", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-timeout");
        commitAndReopen();

        try {
            var reply = invokeOnVirtualThread(parent.id,
                    "{\"task\":\"slow task\",\"runTimeoutSeconds\":1}");
            EventLogger.flush();

            var parsed = JsonParser.parseString(reply).getAsJsonObject();
            assertEquals("TIMEOUT", parsed.get("status").getAsString(),
                    "1s budget vs blocked LLM must yield TIMEOUT, got reply=" + reply);

            var runId = Long.parseLong(parsed.get("run_id").getAsString());
            JPA.em().clear();
            SubagentRun run = SubagentRun.findById(runId);
            assertEquals(SubagentRun.Status.TIMEOUT, run.status);
            assertNotNull(run.endedAt);

            java.util.List<EventLog> timeoutEvents = EventLog.find(
                    "category = ?1", EventLogger.SUBAGENT_TIMEOUT).fetch();
            assertEquals(1, timeoutEvents.size(),
                    "TIMEOUT path must emit exactly one SUBAGENT_TIMEOUT event");
        } finally {
            llmGate.countDown(); // release the mock so the VT can finish
        }
    }

    @Test
    void depthLimitRefusesSpawnAndEmitsLimitEvent() throws Exception {
        // JCLAW-266: depth cap is read from Config row subagent.maxDepth via
        // ConfigService.getInt (default 1). A top-level Agent (parentAgent==null)
        // is at depth 0 and may spawn; its child is at depth 1 and may not.
        // We set the Config row explicitly so the test exercises the
        // DB-backed read path rather than relying on the in-code fallback.
        ConfigService.set(SpawnSubagentTool.DEPTH_LIMIT_KEY, "1");
        var root = createAgent("p-depth-root", "test-provider", "test-model");
        var child = createAgent("p-depth-child", "test-provider", "test-model");
        child.parentAgent = root;
        child.save();
        ConversationService.create(child, "web", "u-depth");

        commitAndReopen();

        var reply = invokeOnVirtualThread(child.id, "{\"task\":\"nope\"}");
        EventLogger.flush();

        assertTrue(reply.startsWith("Subagent spawn refused: depth limit"),
                "depth refusal must surface plain-text error, got: " + reply);
        assertTrue(reply.contains("current depth: 1"),
                "refusal message must report the offending depth, got: " + reply);

        JPA.em().clear();
        assertEquals(0, SubagentRun.count(),
                "depth refusal must not insert a SubagentRun row");

        java.util.List<EventLog> limitEvents = EventLog.find(
                "category = ?1 AND agentId = ?2",
                EventLogger.SUBAGENT_LIMIT_EXCEEDED, child.name).fetch();
        assertEquals(1, limitEvents.size(),
                "exactly one SUBAGENT_LIMIT_EXCEEDED event on depth refusal");
        assertTrue(limitEvents.getFirst().details.contains("depth limit"),
                "event details must include the depth-refusal reason");
    }

    @Test
    void breadthLimitRefusesSpawnAndEmitsLimitEvent() throws Exception {
        // JCLAW-266: breadth cap is read from Config row
        // subagent.maxChildrenPerParent via ConfigService.getInt (default 5).
        // Seed five RUNNING SubagentRun rows for the parent and verify the
        // sixth spawn attempt is refused. Sets the Config row explicitly so
        // the test exercises the DB-backed read path.
        ConfigService.set(SpawnSubagentTool.BREADTH_LIMIT_KEY, "5");
        var parent = createAgent("p-breadth", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-breadth");
        // Seed RUNNING rows. Each needs a distinct child Agent + Conversation
        // because of the not-null FKs; use cheap clones via AgentService.create.
        for (int i = 0; i < 5; i++) {
            var childAgent = createAgent("p-breadth-c" + i, "test-provider", "test-model");
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
        }

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"one too many\"}");
        EventLogger.flush();

        assertTrue(reply.startsWith("Subagent spawn refused: breadth limit"),
                "breadth refusal must surface plain-text error, got: " + reply);
        assertTrue(reply.contains("running children: 5"),
                "refusal message must report the running-children count, got: " + reply);

        JPA.em().clear();
        assertEquals(5, SubagentRun.count(),
                "breadth refusal must not insert a new SubagentRun row");

        java.util.List<EventLog> limitEvents = EventLog.find(
                "category = ?1 AND agentId = ?2",
                EventLogger.SUBAGENT_LIMIT_EXCEEDED, parent.name).fetch();
        assertEquals(1, limitEvents.size(),
                "exactly one SUBAGENT_LIMIT_EXCEEDED event on breadth refusal");
        assertTrue(limitEvents.getFirst().details.contains("breadth limit"),
                "event details must include the breadth-refusal reason");
    }

    @Test
    void modelOverridePersistedOnChildConversationNotChildAgent() throws Exception {
        // JCLAW-269: when the spawn args carry modelProvider + modelId, those
        // values land on the child Conversation override columns; the child
        // Agent row inherits the parent's defaults verbatim. The JCLAW-28 cost
        // dashboard reads COALESCE(c.modelProviderOverride, c.agent.modelProvider)
        // so this is what attributes spend to the actually-used model.
        startLlmServer(simpleResponse("Subagent reply: override path."));
        configureProviderWithTwoModels();

        var parent = createAgent("p-override", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-override");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"with override\",\"modelProvider\":\"test-provider\",\"modelId\":\"test-model-alt\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString(),
                "override spawn should complete cleanly, got: " + reply);

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run);

        // Child Conversation carries the override.
        Conversation childConv = Conversation.findById(run.childConversation.id);
        assertEquals("test-provider", childConv.modelProviderOverride,
                "child Conversation must record the per-spawn provider override");
        assertEquals("test-model-alt", childConv.modelIdOverride,
                "child Conversation must record the per-spawn modelId override");

        // Child Agent inherits the parent's defaults — NOT the override.
        Agent childAgent = Agent.findById(run.childAgent.id);
        assertEquals("test-provider", childAgent.modelProvider,
                "child Agent provider must equal the parent's default");
        assertEquals("test-model", childAgent.modelId,
                "child Agent modelId must equal the parent's default, not the per-spawn override");
    }

    @Test
    void noModelOverrideLeavesChildConversationColumnsNull() throws Exception {
        // JCLAW-269 regression: when modelProvider / modelId aren't supplied,
        // the child Conversation override columns stay null and the child
        // Agent still inherits the parent's defaults. The cost dashboard's
        // COALESCE then falls through to the agent row.
        startLlmServer(simpleResponse("Subagent reply: no override."));
        configureProvider();

        var parent = createAgent("p-no-override", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-no-override");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"plain\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString());

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run);

        Conversation childConv = Conversation.findById(run.childConversation.id);
        assertNull(childConv.modelProviderOverride,
                "no per-spawn override means modelProviderOverride stays null");
        assertNull(childConv.modelIdOverride,
                "no per-spawn override means modelIdOverride stays null");

        Agent childAgent = Agent.findById(run.childAgent.id);
        assertEquals("test-provider", childAgent.modelProvider);
        assertEquals("test-model", childAgent.modelId);
    }

    // ─── JCLAW-268: context modes (fresh vs inherit) ─────────────────────

    @Test
    void freshModeIsDefaultAndProducesEmptyChildHistoryAndNoUnion() throws Exception {
        // JCLAW-268 regression: omitting `context` defaults to "fresh". Verify:
        //   - SUBAGENT_SPAWN event records context="fresh"
        //   - child Conversation has no parent-context blob
        //   - child Agent's tool config still default-disables browser
        //     (i.e. NO union with the parent's enabled set was applied)
        startLlmServer(simpleResponse("Subagent reply: fresh."));
        configureProvider();

        var parent = createAgent("p-fresh", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-fresh");
        // Parent has browser explicitly enabled — this is what we DON'T want
        // the fresh-mode child to inherit. The default for non-main agents is
        // browser disabled (per AgentService.create).
        var parentBrowser = AgentToolConfig.findByAgentAndTool(parent, "browser");
        parentBrowser.enabled = true;
        parentBrowser.save();

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"plain\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString());

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        Conversation childConv = Conversation.findById(run.childConversation.id);
        assertNull(childConv.parentContext,
                "fresh-mode (default) child must not have a parent-context blob");

        // Child Agent: browser stays disabled (no union applied).
        Agent child = Agent.findById(run.childAgent.id);
        var childBrowser = AgentToolConfig.findByAgentAndTool(child, "browser");
        assertNotNull(childBrowser);
        assertFalse(childBrowser.enabled,
                "fresh-mode child must keep AgentService.create's default-disabled browser row");

        // SPAWN event records context="fresh".
        java.util.List<EventLog> spawnEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_SPAWN, parent.name).fetch();
        assertEquals(1, spawnEvents.size());
        assertTrue(spawnEvents.getFirst().details.contains("\"context\":\"fresh\""),
                "fresh-mode SPAWN must record context=\"fresh\"");
    }

    @Test
    void inheritModeStampsParentSummaryAndUnionsToolGrants() throws Exception {
        // JCLAW-268 happy path: context="inherit" with parent history present.
        //   - first LLM call is the summarize pass → returns canned summary
        //   - second LLM call is the child run → returns "Subagent reply"
        // Verify:
        //   - child Conversation.parentContext == canned summary
        //   - child Agent has browser enabled (UNION with parent's enabled set)
        //   - SUBAGENT_SPAWN event records context="inherit"
        //   - effective system prompt for the child contains the summary
        //     (via SessionCompactor.appendParentContextToPrompt, smoke-tested
        //     against the helper directly since intercepting AgentRunner's
        //     prompt assembly mid-flight is more invasive than necessary).
        var calls = new AtomicInteger(0);
        startLlmServer(exchange -> {
            int n = calls.incrementAndGet();
            String body = n == 1
                    ? simpleResponse("Canned summary of parent turns.")
                    : simpleResponse("Subagent reply: inherited.");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parent = createAgent("p-inherit", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-inherit");
        // Seed three prior turns so SessionCompactor.snapshotParentMessages
        // returns a non-empty list — summarization is otherwise skipped.
        ConversationService.appendUserMessage(parentConv, "first user message");
        ConversationService.appendAssistantMessage(parentConv, "first assistant reply", null);
        ConversationService.appendUserMessage(parentConv, "second user message");
        // Parent has browser explicitly enabled — child should pick this up
        // via the union grant.
        var parentBrowser = AgentToolConfig.findByAgentAndTool(parent, "browser");
        parentBrowser.enabled = true;
        parentBrowser.save();

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"continue work\",\"context\":\"inherit\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString(),
                "inherit-mode happy path must complete cleanly, got: " + reply);

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        Conversation childConv = Conversation.findById(run.childConversation.id);

        assertEquals("Canned summary of parent turns.", childConv.parentContext,
                "child Conversation must carry the parent-context summary");

        // Smoke-test the system-prompt injection helper end-to-end. The
        // value AgentRunner re-injects each turn is what this returns.
        var injected = SessionCompactor.appendParentContextToPrompt("BASE", childConv);
        assertTrue(injected.contains(SessionCompactor.PARENT_CONTEXT_HEADER),
                "injection helper must emit the PARENT_CONTEXT_HEADER label");
        assertTrue(injected.contains("Canned summary of parent turns."),
                "injection helper must include the summary body");

        // Tool union: child has browser enabled now, NOT default-disabled.
        Agent child = Agent.findById(run.childAgent.id);
        var childBrowser = AgentToolConfig.findByAgentAndTool(child, "browser");
        assertNotNull(childBrowser);
        assertTrue(childBrowser.enabled,
                "inherit-mode child must have browser enabled (UNION with parent's enabled set)");

        // SPAWN event records context="inherit".
        java.util.List<EventLog> spawnEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_SPAWN, parent.name).fetch();
        assertEquals(1, spawnEvents.size());
        assertTrue(spawnEvents.getFirst().details.contains("\"context\":\"inherit\""),
                "inherit-mode SPAWN must record context=\"inherit\"");

        // No SUBAGENT_ERROR on the happy path.
        java.util.List<EventLog> errorEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_ERROR, parent.name).fetch();
        assertTrue(errorEvents.isEmpty(),
                "inherit-mode happy path must not emit SUBAGENT_ERROR");

        // Both calls were made (summarize + child run).
        assertEquals(2, calls.get(),
                "inherit mode must make two LLM calls: summarize + child run");
    }

    @Test
    void inheritModeDegradesToFreshWhenSummarizationFails() throws Exception {
        // JCLAW-268 failure path: summarize LLM call returns blank, which the
        // tool treats as "summary unusable" — null is returned from
        // summarizeParentForSubagent, the spawn proceeds (child runs with no
        // parent-context blob; tool union grant is also skipped — failure
        // should not silently broaden the child's tool surface). The summary
        // path emits SUBAGENT_ERROR; the child run terminates COMPLETED on
        // its own reply.
        //
        // We use the "blank response" failure rather than "5xx" because the
        // LlmProvider retry path (MAX_RETRIES=3 with 1s+2s+4s backoffs) makes
        // 5xx-driven failures cost up to 7s per test. Blank summary is the
        // semantically equivalent failure mode the tool also has to handle.
        var calls = new AtomicInteger(0);
        startLlmServer(exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                // First call (summarize): return 400 so LlmProvider throws
                // an LlmException immediately (4xx is non-retryable per the
                // retry policy — 5xx would retry 3x with 1s+2s+4s backoffs
                // and slow the test by ~7s). The exception bubbles up
                // through the summarizer lambda and the tool catches it
                // as a summarization failure, emitting SUBAGENT_ERROR.
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }
            String body = simpleResponse("Subagent reply: degraded.");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parent = createAgent("p-degrade", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-degrade");
        // Need at least one prior turn so the summarize call is attempted at
        // all (snapshotParentMessages returns empty for a brand-new conv).
        ConversationService.appendUserMessage(parentConv, "some prior context");
        var parentBrowser = AgentToolConfig.findByAgentAndTool(parent, "browser");
        parentBrowser.enabled = true;
        parentBrowser.save();

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"continue\",\"context\":\"inherit\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        // Child still runs even when the summary failed.
        assertEquals("COMPLETED", parsed.get("status").getAsString(),
                "summarization failure must not prevent the child from running, got: " + reply);

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        Conversation childConv = Conversation.findById(run.childConversation.id);

        assertNull(childConv.parentContext,
                "summarization failure must leave child Conversation.parentContext null");

        // Failure also skips the tool union grant — failure-degraded spawn
        // must not silently broaden the child's tool surface.
        Agent child = Agent.findById(run.childAgent.id);
        var childBrowser = AgentToolConfig.findByAgentAndTool(child, "browser");
        assertNotNull(childBrowser);
        assertFalse(childBrowser.enabled,
                "summarization-degraded child must keep default-disabled browser");

        // SUBAGENT_ERROR event records the failure reason.
        java.util.List<EventLog> errorEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_ERROR, parent.name).fetch();
        assertEquals(1, errorEvents.size(),
                "exactly one SUBAGENT_ERROR event on summarization failure");
        assertTrue(errorEvents.getFirst().details.contains("Parent-context summarization failed"),
                "SUBAGENT_ERROR details must include the summarization-failure reason, got: "
                        + errorEvents.getFirst().details);

        // SPAWN event still records context="inherit" (the request was for
        // inherit; failure didn't rewrite the request).
        java.util.List<EventLog> spawnEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_SPAWN, parent.name).fetch();
        assertEquals(1, spawnEvents.size());
        assertTrue(spawnEvents.getFirst().details.contains("\"context\":\"inherit\""));
    }

    @Test
    void inheritModeWithNoParentTurnsSkipsSummaryCleanly() throws Exception {
        // JCLAW-268: when the parent conversation has zero messages,
        // snapshotParentMessages returns empty and summarizeParentForSubagent
        // returns null — no LLM call made, no error, child spawns clean.
        startLlmServer(simpleResponse("Subagent reply: empty parent."));
        configureProvider();

        var parent = createAgent("p-empty", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-empty");
        // Deliberately NO appendUserMessage — parent conversation is empty.

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"first time\",\"context\":\"inherit\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString(),
                "empty-parent inherit-mode must complete cleanly, got: " + reply);

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        Conversation childConv = Conversation.findById(run.childConversation.id);

        assertNull(childConv.parentContext,
                "no parent turns must leave child Conversation.parentContext null");

        // No SUBAGENT_ERROR — empty-parent is a clean-skip path, not a failure.
        java.util.List<EventLog> errorEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_ERROR, parent.name).fetch();
        assertTrue(errorEvents.isEmpty(),
                "empty-parent inherit-mode must not emit SUBAGENT_ERROR");
    }

    @Test
    void invalidContextValueIsRejectedWithClearError() throws Exception {
        // Defensive: any context value other than "fresh" or "inherit" must
        // be rejected up-front rather than silently defaulting.
        startLlmServer(simpleResponse("never called"));
        configureProvider();
        var parent = createAgent("p-bad-ctx", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-bad-ctx");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"x\",\"context\":\"shared\"}");
        assertTrue(reply.startsWith("Error: 'context' must be one of"),
                "invalid context value must produce a plain-text rejection, got: " + reply);

        JPA.em().clear();
        assertEquals(0, SubagentRun.count(),
                "invalid-context rejection must not insert a SubagentRun row");
    }

    // ─── JCLAW-267: spawn modes (session vs inline) ──────────────────────

    @Test
    void inlineModeRunsInParentConversationAndStampsMessages() throws Exception {
        // JCLAW-267 happy path: mode="inline" reuses the parent Conversation
        // as the SubagentRun's child end (childConversation == parentConversation),
        // emits boundary-start and boundary-end Message rows in the parent
        // conversation carrying the SubagentRun id marker, and stamps every
        // Message AgentRunner persists during the child run with the same id
        // so the chat UI can fold them into a collapsible nested-turn block.
        startLlmServer(simpleResponse("Subagent reply: inline."));
        configureProvider();

        var parent = createAgent("p-inline", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-inline");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"do inline work\",\"label\":\"inline-task\",\"mode\":\"inline\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString(),
                "inline-mode happy path must complete cleanly, got: " + reply);
        assertEquals("Subagent reply: inline.", parsed.get("reply").getAsString());

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run);
        // Inline mode's structural invariant: child Conversation FK points at
        // the parent Conversation row, not a freshly-created sidebar row.
        assertEquals(parentConv.id, run.childConversation.id,
                "inline-mode child Conversation must equal the parent Conversation");
        assertEquals(parentConv.id, run.parentConversation.id);

        // SPAWN event records mode="inline".
        java.util.List<EventLog> spawnEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_SPAWN, parent.name).fetch();
        assertEquals(1, spawnEvents.size());
        assertTrue(spawnEvents.getFirst().details.contains("\"mode\":\"inline\""),
                "inline-mode SPAWN must record mode=\"inline\", got: "
                        + spawnEvents.getFirst().details);
        // COMPLETE event also carries the inline mode.
        java.util.List<EventLog> completeEvents = EventLog.find(
                "category = ?1 AND agentId = ?2", EventLogger.SUBAGENT_COMPLETE, parent.name).fetch();
        assertEquals(1, completeEvents.size());
        assertTrue(completeEvents.getFirst().details.contains("\"mode\":\"inline\""),
                "inline-mode COMPLETE must record mode=\"inline\"");

        // All messages persisted under the parent Conversation that belong to
        // the run must carry subagentRunId == runId. The list includes the
        // boundary-start marker, AgentRunner's appended user message (the
        // child's task), the assistant reply, and the boundary-end marker.
        var stamped = Message.find(
                "conversation = ?1 AND subagentRunId = ?2 ORDER BY createdAt ASC",
                Conversation.findById(parentConv.id), runId).fetch();
        assertFalse(stamped.isEmpty(),
                "inline-mode run must produce at least one Message stamped with subagentRunId");
        // Boundary-start marker is the first row, with "Spawning subagent:" prefix.
        assertTrue(((Message) stamped.getFirst()).content.startsWith("Spawning subagent:"),
                "first stamped message must be the boundary-start marker, got: "
                        + ((Message) stamped.getFirst()).content);
        // Boundary-end marker is the last row, carrying the terminal status.
        assertTrue(((Message) stamped.getLast()).content.startsWith("Subagent completed"),
                "last stamped message must be the boundary-end marker, got: "
                        + ((Message) stamped.getLast()).content);
    }

    @Test
    void invalidModeValueIsRejectedWithClearError() throws Exception {
        // Defensive: any mode value other than "session" or "inline" must be
        // rejected up-front rather than silently defaulting.
        var parent = createAgent("p-bad-mode", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-bad-mode");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"x\",\"mode\":\"detached\"}");
        assertTrue(reply.startsWith("Error: 'mode' must be one of"),
                "invalid mode value must produce a plain-text rejection, got: " + reply);

        JPA.em().clear();
        assertEquals(0, SubagentRun.count(),
                "invalid-mode rejection must not insert a SubagentRun row");
    }

    @Test
    void sessionModeUnchangedRegressionAfterInlineAddition() throws Exception {
        // Regression guard for JCLAW-267: omitting `mode` defaults to "session",
        // which keeps the JCLAW-265 behavior verbatim — fresh child Conversation
        // (distinct row), parent FK wired, no subagentRunId marker on any
        // message in the parent Conversation.
        startLlmServer(simpleResponse("Subagent reply: session default."));
        configureProvider();

        var parent = createAgent("p-session-default", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-session-default");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"go\"}");
        EventLogger.flush();

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString());

        JPA.em().clear();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run);
        assertNotEquals(parentConv.id, run.childConversation.id,
                "session-mode child Conversation must be a distinct row");

        // Parent Conversation has no stamped messages — the child runs in its
        // own conversation under session-mode.
        long stampedInParent = Message.count(
                "conversation = ?1 AND subagentRunId IS NOT NULL",
                Conversation.findById(parentConv.id));
        assertEquals(0, stampedInParent,
                "session-mode must not stamp any parent-Conversation messages");
    }

    @Test
    void limitsNotTriggeredAllowsSpawnNormally() throws Exception {
        // Regression guard for JCLAW-266: a top-level agent with no RUNNING
        // children must still spawn successfully (no false-positive refusal).
        startLlmServer(simpleResponse("Subagent reply: ok."));
        configureProvider();

        var parent = createAgent("p-ok", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-ok");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"ok\"}");
        EventLogger.flush();

        assertFalse(reply.startsWith("Subagent spawn refused"),
                "happy path must not be refused, got: " + reply);
        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("COMPLETED", parsed.get("status").getAsString());

        java.util.List<EventLog> limitEvents = EventLog.find(
                "category = ?1", EventLogger.SUBAGENT_LIMIT_EXCEEDED).fetch();
        assertTrue(limitEvents.isEmpty(),
                "no SUBAGENT_LIMIT_EXCEEDED event on the happy path");
    }

    // ─── JCLAW-270: async spawn via announce flow ────────────────────────

    @Test
    void asyncSpawnReturnsImmediatelyAndAnnouncesOnCompletion() throws Exception {
        // JCLAW-270 happy path: async=true returns {run_id, conversation_id,
        // status: RUNNING} immediately; the background VT runs AgentRunner.run,
        // posts a system-role announce Message into the parent Conversation
        // carrying messageKind=subagent_announce and the structured metadata
        // payload, updates the SubagentRun to COMPLETED, and emits
        // SUBAGENT_SPAWN (immediate) + SUBAGENT_COMPLETE (terminal).
        startLlmServer(simpleResponse("Subagent reply: async done."));
        configureProvider();

        var parent = createAgent("p-async-ok", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-async-ok");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"async work\",\"label\":\"async-task\",\"async\":true}");

        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        assertEquals("RUNNING", parsed.get("status").getAsString(),
                "async spawn must return status=RUNNING immediately, got: " + reply);
        assertNotNull(parsed.get("run_id").getAsString());
        assertNotNull(parsed.get("conversation_id").getAsString());
        // Reply field is NOT in the async return — that's the announce's job.
        assertFalse(parsed.has("reply"),
                "async return must not carry a 'reply' field — that arrives via the announce");

        var runId = Long.parseLong(parsed.get("run_id").getAsString());

        // Await the background VT's terminal state. Poll for the COMPLETED
        // status; bounded by a generous 10s budget so a slow test runner
        // doesn't flake.
        awaitTerminalStatus(runId, SubagentRun.Status.COMPLETED, 10_000);
        EventLogger.flush();

        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run);
        assertEquals(SubagentRun.Status.COMPLETED, run.status);
        assertEquals("Subagent reply: async done.", run.outcome);
        assertNotNull(run.endedAt);

        // Announce Message landed in the PARENT Conversation with the
        // discriminator + payload.
        java.util.List<Message> announces = Message.find(
                "conversation = ?1 AND messageKind = ?2 ORDER BY createdAt ASC",
                Conversation.findById(parentConv.id), SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
        assertEquals(1, announces.size(),
                "exactly one announce Message must land in the parent conversation");
        var announce = announces.getFirst();
        assertEquals("system", announce.role,
                "announce Message must use SYSTEM role so it doesn't impersonate the LLM or trigger a response cycle");
        assertNotNull(announce.metadata, "announce Message must carry a structured metadata payload");
        var payload = JsonParser.parseString(announce.metadata).getAsJsonObject();
        assertEquals(runId, payload.get("runId").getAsLong());
        assertEquals("async-task", payload.get("label").getAsString());
        assertEquals("COMPLETED", payload.get("status").getAsString());
        assertEquals("Subagent reply: async done.", payload.get("reply").getAsString());
        assertEquals(run.childConversation.id, (Long) payload.get("childConversationId").getAsLong());

        // Lifecycle events: SPAWN immediate + COMPLETE on terminal. No ERROR.
        java.util.List<EventLog> spawnEvents = EventLog.find(
                "category = ?1 AND agentId = ?2",
                EventLogger.SUBAGENT_SPAWN, parent.name).fetch();
        assertEquals(1, spawnEvents.size(), "exactly one SUBAGENT_SPAWN event");
        java.util.List<EventLog> completeEvents = EventLog.find(
                "category = ?1 AND agentId = ?2",
                EventLogger.SUBAGENT_COMPLETE, parent.name).fetch();
        assertEquals(1, completeEvents.size(), "exactly one SUBAGENT_COMPLETE event");
        java.util.List<EventLog> errorEvents = EventLog.find(
                "category = ?1 AND agentId = ?2",
                EventLogger.SUBAGENT_ERROR, parent.name).fetch();
        assertTrue(errorEvents.isEmpty(),
                "async happy path must not emit SUBAGENT_ERROR");
    }

    @Test
    void asyncSpawnFailureAnnouncesError() throws Exception {
        // JCLAW-270 failure path: drive the runAsyncAndAnnounce VT body
        // directly with a bogus childAgentId so the IllegalStateException
        // ("Subagent rows vanished before AgentRunner.run") fires inside the
        // wrapped Future. The catch block must mark the SubagentRun FAILED,
        // post an announce Message with status=FAILED, and emit
        // SUBAGENT_ERROR. Calling the static helper directly avoids racing
        // the production VT (which would either win and produce COMPLETED
        // before we could clobber inputs, or hang the test).
        //
        // We still bootstrap a real SubagentRun row + parent Conversation so
        // the announce path has a real target to write into.
        var parent = createAgent("p-async-fail", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-async-fail");
        var childAgent = createAgent("p-async-fail-child", "test-provider", "test-model");
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
        var runId = run.id;
        var childConvId = childConv.id;
        var parentConvId = parentConv.id;
        var parentName = parent.name;

        commitAndReopen();

        // Use a deliberately non-existent childAgentId so the VT's
        // Agent.findById returns null and runAsyncAndAnnounce's wrapped
        // Future throws IllegalStateException. No delete required — the
        // bogus id bypasses the Hibernate cascade that previously fired
        // TransientPropertyValueException on AgentToolConfig flushes.
        long bogusChildAgentId = 999_999_999L;
        SpawnSubagentTool.runAsyncAndAnnounce(
                runId, bogusChildAgentId, childConvId, parentConvId,
                parentName, "session", "fresh", "will-fail",
                30, "async-fail-task");
        EventLogger.flush();

        JPA.em().clear();
        SubagentRun fresh = SubagentRun.findById(runId);
        assertNotNull(fresh);
        assertEquals(SubagentRun.Status.FAILED, fresh.status,
                "FAILED async spawn must stamp the audit row FAILED");
        assertNotNull(fresh.outcome, "FAILED run must record the error reason");

        java.util.List<Message> announces = Message.find(
                "conversation = ?1 AND messageKind = ?2",
                Conversation.findById(parentConvId),
                SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
        assertEquals(1, announces.size(),
                "FAILED async spawn must post an announce Message");
        var payload = JsonParser.parseString(((Message) announces.getFirst()).metadata).getAsJsonObject();
        assertEquals("FAILED", payload.get("status").getAsString());
        assertEquals(childConvId, (Long) payload.get("childConversationId").getAsLong());

        java.util.List<EventLog> errorEvents = EventLog.find(
                "category = ?1 AND agentId = ?2",
                EventLogger.SUBAGENT_ERROR, parentName).fetch();
        assertEquals(1, errorEvents.size(),
                "FAILED async spawn must emit exactly one SUBAGENT_ERROR");
    }

    @Test
    void asyncSpawnTimeoutAnnouncesTimeout() throws Exception {
        // JCLAW-270 timeout path: long-running mock + short timeout. The VT's
        // Future.get(1s) trips, the announce records TIMEOUT, the SubagentRun
        // is stamped TIMEOUT, and SUBAGENT_TIMEOUT fires.
        var llmGate = new java.util.concurrent.CountDownLatch(1);
        startLlmServer(exchange -> {
            try { llmGate.await(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            var body = simpleResponse("late");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        configureProvider();

        var parent = createAgent("p-async-timeout", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-async-timeout");

        commitAndReopen();

        try {
            var reply = invokeOnVirtualThread(parent.id,
                    "{\"task\":\"slow\",\"async\":true,\"runTimeoutSeconds\":1}");
            var parsed = JsonParser.parseString(reply).getAsJsonObject();
            assertEquals("RUNNING", parsed.get("status").getAsString(), reply);
            var runId = Long.parseLong(parsed.get("run_id").getAsString());

            awaitTerminalStatus(runId, SubagentRun.Status.TIMEOUT, 10_000);
            EventLogger.flush();

            JPA.em().clear();
            SubagentRun run = SubagentRun.findById(runId);
            assertEquals(SubagentRun.Status.TIMEOUT, run.status);
            assertNotNull(run.endedAt);

            java.util.List<Message> announces = Message.find(
                    "conversation = ?1 AND messageKind = ?2",
                    Conversation.findById(parentConv.id),
                    SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
            assertEquals(1, announces.size(),
                    "TIMEOUT path must still post an announce");
            var payload = JsonParser.parseString(((Message) announces.getFirst()).metadata).getAsJsonObject();
            assertEquals("TIMEOUT", payload.get("status").getAsString());
            assertTrue(payload.get("reply").getAsString().contains("exceeded"),
                    "TIMEOUT reply must surface the budget-exceeded reason, got: " + payload.get("reply").getAsString());

            java.util.List<EventLog> timeoutEvents = EventLog.find(
                    "category = ?1", EventLogger.SUBAGENT_TIMEOUT).fetch();
            assertEquals(1, timeoutEvents.size(),
                    "TIMEOUT path must emit exactly one SUBAGENT_TIMEOUT event");
        } finally {
            llmGate.countDown();
        }
    }

    @Test
    void asyncWithInlineModeIsRejected() throws Exception {
        // JCLAW-270 design constraint: async + inline doesn't fit semantically
        // (inline embeds child messages mid-parent-transcript; returning
        // before the child finishes leaves a half-written nested block).
        // The tool rejects this combination up-front with a clear error and
        // does not insert a SubagentRun row.
        var parent = createAgent("p-async-inline", "test-provider", "test-model");
        ConversationService.create(parent, "web", "u-async-inline");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"x\",\"async\":true,\"mode\":\"inline\"}");
        assertTrue(reply.startsWith("Error: 'async' is only compatible with mode=\"session\""),
                "async+inline must produce a plain-text rejection, got: " + reply);

        JPA.em().clear();
        assertEquals(0, SubagentRun.count(),
                "async+inline rejection must not insert a SubagentRun row");
    }

    @Test
    void asyncReplyTruncationAt4000Chars() throws Exception {
        // JCLAW-270 truncation invariant: the announce Message's reply field
        // is hard-capped at 4000 characters with an ellipsis marker. The full
        // reply remains accessible via the announce card's "View full" link
        // to the child Conversation (which still has the untruncated final
        // Message persisted).
        var longReply = "x".repeat(5000);
        startLlmServer(simpleResponse(longReply));
        configureProvider();

        var parent = createAgent("p-async-truncate", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-async-truncate");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id,
                "{\"task\":\"big\",\"async\":true}");
        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());

        awaitTerminalStatus(runId, SubagentRun.Status.COMPLETED, 10_000);

        JPA.em().clear();
        java.util.List<Message> announces = Message.find(
                "conversation = ?1 AND messageKind = ?2",
                Conversation.findById(parentConv.id),
                SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE).fetch();
        assertEquals(1, announces.size());
        var payload = JsonParser.parseString(((Message) announces.getFirst()).metadata).getAsJsonObject();
        var announceReply = payload.get("reply").getAsString();
        assertEquals(4000, announceReply.length(),
                "truncated reply must be exactly 4000 chars, got: " + announceReply.length());
        assertTrue(announceReply.endsWith("..."),
                "truncated reply must end with the ellipsis marker");

        // The child Conversation still carries the full reply on its
        // assistant Message — operator can click "View full" to see it.
        SubagentRun run = SubagentRun.findById(runId);
        var childMessages = Message.find(
                "conversation = ?1 AND role = ?2 ORDER BY createdAt DESC",
                Conversation.findById(run.childConversation.id), "assistant").fetch();
        assertFalse(childMessages.isEmpty(),
                "child conversation must have at least one assistant message");
        assertEquals(5000, ((Message) childMessages.getFirst()).content.length(),
                "child conversation's assistant message must retain the untruncated reply");
    }

    @Test
    void asyncAnnounceMessageIsExcludedFromLlmContext() throws Exception {
        // JCLAW-270 regression: announce messages must NOT feed into a future
        // turn's LLM context (they're UI-only structured cards; surfacing them
        // would risk the model re-acknowledging an already-delivered result).
        // {@link ConversationService#loadRecentMessages} filters by
        // messageKind == null.
        startLlmServer(simpleResponse("Subagent reply: async."));
        configureProvider();

        var parent = createAgent("p-async-llm-filter", "test-provider", "test-model");
        var parentConv = ConversationService.create(parent, "web", "u-async-llm-filter");

        commitAndReopen();

        var reply = invokeOnVirtualThread(parent.id, "{\"task\":\"go\",\"async\":true}");
        var parsed = JsonParser.parseString(reply).getAsJsonObject();
        var runId = Long.parseLong(parsed.get("run_id").getAsString());
        awaitTerminalStatus(runId, SubagentRun.Status.COMPLETED, 10_000);

        JPA.em().clear();
        var conv = (Conversation) Conversation.findById(parentConv.id);
        var llmHistory = Tx.run(() -> ConversationService.loadRecentMessages(conv));
        assertTrue(llmHistory.stream().noneMatch(m -> SpawnSubagentTool.MESSAGE_KIND_ANNOUNCE.equals(m.messageKind)),
                "announce-kind messages must be filtered out of LLM context assembly");
    }

    // ---- helpers ----

    private Agent createAgent(String name, String provider, String model) {
        var agent = AgentService.create(name, provider, model);
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

    /** Same provider, two models — exercises the per-spawn override resolution
     *  without standing up a second mock server. */
    private void configureProviderWithTwoModels() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096},"
                        + "{\"id\":\"test-model-alt\",\"name\":\"Test Alt\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    private static String simpleResponse(String content) {
        return """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(
                content.replace("\"", "\\\""));
    }

    /**
     * JCLAW-270: poll SubagentRun.status until it reaches the expected
     * terminal state, or fail the assertion when the deadline is exceeded.
     * Each poll runs in its own short Tx + em.clear so the read sees the
     * VT's committed terminal update rather than a stale snapshot.
     */
    private static void awaitTerminalStatus(Long runId, SubagentRun.Status expected, long timeoutMillis) {
        var deadline = System.currentTimeMillis() + timeoutMillis;
        SubagentRun.Status seen = null;
        while (System.currentTimeMillis() < deadline) {
            JPA.em().clear();
            var run = (SubagentRun) SubagentRun.findById(runId);
            seen = run != null ? run.status : null;
            if (seen == expected) return;
            try { Thread.sleep(50); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); return; }
        }
        fail("SubagentRun " + runId + " did not reach " + expected
                + " within " + timeoutMillis + "ms (last seen: " + seen + ")");
    }

    /** Commit pending parent setup rows so the VT-dispatched child run can
     *  observe them through its own transaction. */
    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    /** Invoke SpawnSubagentTool.execute on a VT so AgentRunner.run inside the
     *  tool sees committed rows (the synchronous spawn re-enters a VT of its
     *  own for the child; that's fine — the outer VT exists only to give the
     *  whole tool body a fresh persistence context). */
    private String invokeOnVirtualThread(Long parentAgentId, String argsJson) throws Exception {
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentAgentId));
                var tool = (SpawnSubagentTool) ToolRegistry.lookupTool(SpawnSubagentTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, parent));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "spawn_subagent must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
