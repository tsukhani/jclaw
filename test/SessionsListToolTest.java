import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
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
import tools.SessionsListTool;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-326 tests: {@code sessions_list} tool.
 *
 * <p>Each test seeds a parent agent and a handful of {@link SubagentRun} rows
 * the tool reads back. Ownership scope is baked into the WHERE clause, so
 * the first test seeds runs across two parents and asserts only the caller's
 * rows surface.
 */
class SessionsListToolTest extends UnitTest {

    private Agent parentAgent;
    private Agent otherParent;
    private Agent childAgentA;
    private Agent childAgentB;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        new jobs.ToolRegistrationJob().doJob();
        parentAgent = AgentService.create("p-list", "openrouter", "gpt-4.1");
        otherParent = AgentService.create("p-other-list", "openrouter", "gpt-4.1");
        childAgentA = AgentService.create("c-list-a", "openrouter", "gpt-4.1");
        childAgentB = AgentService.create("c-list-b", "openrouter", "gpt-4.1");
        childAgentA.parentAgent = parentAgent;
        childAgentA.save();
        childAgentB.parentAgent = parentAgent;
        childAgentB.save();
    }

    @AfterEach
    void teardown() {
        EventLogger.clear();
    }

    @Test
    void toolIsRegisteredAndDiscoverable() {
        var tool = ToolRegistry.lookupTool(SessionsListTool.TOOL_NAME);
        assertNotNull(tool, "sessions_list must be registered by ToolRegistrationJob");
        assertEquals(SessionsListTool.TOOL_NAME, tool.name());
        assertEquals("System", tool.category());
    }

    @Test
    void listReturnsParentOwnedRunsOnly() throws Exception {
        seedRun(parentAgent, childAgentA, "mine-1", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentA, "mine-2", SubagentRun.Status.COMPLETED);
        // Foreign run belonging to a different parent.
        seedRun(otherParent, childAgentA, "stranger", SubagentRun.Status.RUNNING);

        var json = invokeTool(parentAgent.id, "{}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, parsed.get("count").getAsInt(),
                "only caller-owned runs must surface, got: " + json);
        assertFalse(parsed.get("has_more").getAsBoolean());
        var runs = parsed.getAsJsonArray("runs");
        for (int i = 0; i < runs.size(); i++) {
            var label = runs.get(i).getAsJsonObject().get("label").getAsString();
            assertTrue(label.startsWith("mine-"),
                    "no foreign-parent runs in result, got label: " + label);
        }
    }

    @Test
    void filterByStatus() throws Exception {
        seedRun(parentAgent, childAgentA, "active-1", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentA, "active-2", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentA, "done", SubagentRun.Status.COMPLETED);

        var json = invokeTool(parentAgent.id, "{\"status\":\"RUNNING\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, parsed.get("count").getAsInt(), json);
        var runs = parsed.getAsJsonArray("runs");
        for (int i = 0; i < runs.size(); i++) {
            assertEquals("RUNNING",
                    runs.get(i).getAsJsonObject().get("status").getAsString());
        }
    }

    @Test
    void filterByLabelGlobMatchesPrefix() throws Exception {
        seedRun(parentAgent, childAgentA, "probe-alpha", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentA, "probe-beta", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentA, "other-label", SubagentRun.Status.RUNNING);

        var json = invokeTool(parentAgent.id, "{\"labelGlob\":\"probe-*\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, parsed.get("count").getAsInt(),
                "glob must match only probe-* rows, got: " + json);
    }

    @Test
    void filterByLabelGlobEscapesLiteralPercent() throws Exception {
        // A row whose label literally contains `%` must not match a glob that
        // doesn't intend to wildcard.
        seedRun(parentAgent, childAgentA, "10%complete", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentA, "matching", SubagentRun.Status.RUNNING);

        // labelGlob="match*" should match "matching" only — the literal % in
        // "10%complete" must not act as a wildcard.
        var json = invokeTool(parentAgent.id, "{\"labelGlob\":\"match*\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, parsed.get("count").getAsInt(), json);
        assertEquals("matching",
                parsed.getAsJsonArray("runs").get(0).getAsJsonObject()
                        .get("label").getAsString());
    }

    @Test
    void filterByAgentId() throws Exception {
        seedRun(parentAgent, childAgentA, "via-a", SubagentRun.Status.RUNNING);
        seedRun(parentAgent, childAgentB, "via-b", SubagentRun.Status.RUNNING);

        var json = invokeTool(parentAgent.id,
                "{\"agentId\":" + childAgentB.id + "}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, parsed.get("count").getAsInt(), json);
        assertEquals("via-b",
                parsed.getAsJsonArray("runs").get(0).getAsJsonObject()
                        .get("label").getAsString());
    }

    @Test
    void paginationLimitAndOffset() throws Exception {
        // Five runs, ordered by startedAt DESC. labels carry the seeding index.
        for (int i = 0; i < 5; i++) {
            seedRun(parentAgent, childAgentA, "row-" + i, SubagentRun.Status.RUNNING);
            // Strictly-increasing startedAt across seedings so ORDER BY DESC
            // is deterministic.
            try { Thread.sleep(2); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        // limit=2, offset=2 over 5 rows → returns rows 3,4 (counting from the
        // newest as 1); has_more=true (one more remains).
        var json = invokeTool(parentAgent.id, "{\"limit\":2,\"offset\":2}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, parsed.get("count").getAsInt(), json);
        assertTrue(parsed.get("has_more").getAsBoolean(),
                "5 rows with limit=2, offset=2 leaves one beyond → has_more=true, got: " + json);
    }

    @Test
    void outcomePreviewTruncatedAt200Chars() throws Exception {
        var longOutcome = "x".repeat(500);
        Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgentA;
            run.parentConversation = ConversationService.create(parentAgent, "web", "u-trunc");
            run.childConversation = ConversationService.create(childAgentA, "subagent", null);
            run.label = "long";
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.outcome = longOutcome;
            run.save();
        });
        var json = invokeTool(parentAgent.id, "{}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        var preview = parsed.getAsJsonArray("runs").get(0).getAsJsonObject()
                .get("outcomePreview").getAsString();
        assertTrue(preview.length() <= SessionsListTool.OUTCOME_PREVIEW_MAX_CHARS,
                "preview must be ≤ 200 chars, got len: " + preview.length());
        assertTrue(preview.endsWith("..."),
                "truncated preview must carry the ellipsis marker, got: " + preview);
    }

    @Test
    void nullLabelRendersAsNull() throws Exception {
        Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgentA;
            run.parentConversation = ConversationService.create(parentAgent, "web", "u-nullbl");
            run.childConversation = ConversationService.create(childAgentA, "subagent", null);
            run.label = null;
            run.status = SubagentRun.Status.RUNNING;
            run.save();
        });
        var json = invokeTool(parentAgent.id, "{}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        var row = parsed.getAsJsonArray("runs").get(0).getAsJsonObject();
        assertTrue(row.get("label").isJsonNull(),
                "null label must serialize as JSON null, got: " + json);
    }

    // ──────── helpers ────────

    private SubagentRun seedRun(Agent parent, Agent child, String label,
                                 SubagentRun.Status status) {
        return Tx.run(() -> {
            var pc = ConversationService.create(parent, "web", "u-" + label);
            var cc = ConversationService.create(child, "subagent", null);
            cc.parentConversation = pc;
            cc.save();
            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.label = label;
            run.status = status;
            if (status != SubagentRun.Status.RUNNING) {
                run.endedAt = Instant.now();
                run.outcome = "test outcome for " + label;
            }
            run.save();
            return run;
        });
    }

    private String invokeTool(Long callerAgentId, String argsJson) throws Exception {
        commitAndReopen();
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var caller = Tx.run(() -> (Agent) Agent.findById(callerAgentId));
                var tool = (SessionsListTool) ToolRegistry.lookupTool(SessionsListTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, caller));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(10_000);
        assertFalse(thread.isAlive(), "sessions_list must complete within 10s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
