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
import tools.SessionsHistoryTool;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-274 tests: {@code sessions_history} tool.
 *
 * <p>Each test seeds a parent + child Agent, a parent + child Conversation,
 * a SubagentRun row linking them, and a small number of messages on the
 * child conversation. The tool is then invoked directly to verify shape,
 * ordering, permission gating, and pagination.
 */
class SessionsHistoryToolTest extends UnitTest {

    private Agent parentAgent;
    private Agent childAgent;
    private Conversation parentConv;
    private Conversation childConv;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        new jobs.ToolRegistrationJob().doJob();
        parentAgent = AgentService.create("p-hist", "openrouter", "gpt-4.1");
        childAgent = AgentService.create("c-hist", "openrouter", "gpt-4.1");
        childAgent.parentAgent = parentAgent;
        childAgent.save();
        parentConv = ConversationService.create(parentAgent, "web", "u-hist");
        childConv = ConversationService.create(childAgent, "subagent", null);
        childConv.parentConversation = parentConv;
        childConv.save();
    }

    @AfterEach
    void teardown() {
        EventLogger.clear();
    }

    @Test
    void toolIsRegisteredAndDiscoverable() {
        var tool = ToolRegistry.lookupTool(SessionsHistoryTool.TOOL_NAME);
        assertNotNull(tool, "sessions_history must be registered by ToolRegistrationJob");
        assertEquals(SessionsHistoryTool.TOOL_NAME, tool.name());
        assertEquals("System", tool.category());
    }

    @Test
    void happyPathReturnsAllChildMessagesWithRoleContentAndTimestamps() throws Exception {
        var run = seedRun();
        // Three messages on the child conversation, in order.
        seedMessage(childConv, MessageRole.USER, "first user turn", null);
        seedMessage(childConv, MessageRole.ASSISTANT, "first assistant reply", null);
        seedMessage(childConv, MessageRole.USER, "second user turn", null);

        var json = invokeTool(parentAgent.id,
                "{\"runId\":\"" + run.id + "\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(String.valueOf(run.id), parsed.get("run_id").getAsString());
        assertEquals(String.valueOf(childConv.id),
                parsed.get("child_conversation_id").getAsString());
        assertEquals(3, parsed.get("count").getAsInt(),
                "all 3 child messages must be returned, got: " + json);
        assertFalse(parsed.get("has_more").getAsBoolean(),
                "fewer-than-limit pages must not flag has_more");
        var messages = parsed.getAsJsonArray("messages");
        assertEquals(3, messages.size());

        // Oldest first within the page.
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("first user turn",
                messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("assistant",
                messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("first assistant reply",
                messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("user",
                messages.get(2).getAsJsonObject().get("role").getAsString());

        // Timestamps are present + ISO-8601 (the InstantTypeAdapter does
        // Instant.toString) — smoke-check by parsing them back.
        for (int i = 0; i < messages.size(); i++) {
            var ts = messages.get(i).getAsJsonObject().get("created_at").getAsString();
            assertNotNull(ts);
            assertNotNull(Instant.parse(ts), "created_at must parse as Instant: " + ts);
        }
    }

    @Test
    void permissionFailureForOtherParentReturnsErrorAndLeaksNoData() throws Exception {
        // A second parent agent that does NOT own the seeded run.
        var stranger = AgentService.create("p-stranger", "openrouter", "gpt-4.1");
        var run = seedRun();
        seedMessage(childConv, MessageRole.USER, "secret turn", null);
        seedMessage(childConv, MessageRole.ASSISTANT, "secret reply", null);

        var reply = invokeTool(stranger.id,
                "{\"runId\":\"" + run.id + "\"}");
        assertTrue(reply.startsWith("Error: runId " + run.id + " is not owned"),
                "non-parent caller must be rejected, got: " + reply);
        // No content from the child conversation leaked through.
        assertFalse(reply.contains("secret turn"),
                "rejection must not include any child-message content, got: " + reply);
        assertFalse(reply.contains("secret reply"),
                "rejection must not include any child-message content, got: " + reply);
    }

    @Test
    void missingRunIdReturnsError() throws Exception {
        var reply = invokeTool(parentAgent.id, "{}");
        assertTrue(reply.startsWith("Error: 'runId' is required"),
                "missing runId: " + reply);
    }

    @Test
    void unknownRunIdReturnsNotFound() throws Exception {
        var reply = invokeTool(parentAgent.id, "{\"runId\":\"99999999\"}");
        assertTrue(reply.startsWith("Error: no SubagentRun found"),
                "unknown id: " + reply);
    }

    @Test
    void paginationViaBeforeMessageIdWalksBack() throws Exception {
        var run = seedRun();
        // Seed five messages, capture their ids in chronological order.
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            var role = (i % 2 == 0) ? MessageRole.USER : MessageRole.ASSISTANT;
            var msg = seedMessage(childConv, role, "turn-" + i, null);
            ids.add(msg.id);
        }

        // First page: limit=3 returns the OLDEST 3 (oldest-first chronological
        // ordering within the most-recent page; with no cursor we fetch the
        // last 3 by id DESC, which are turn-2, turn-3, turn-4 reversed →
        // turn-2, turn-3, turn-4).
        var firstJson = invokeTool(parentAgent.id,
                "{\"runId\":\"" + run.id + "\",\"limit\":3}");
        var first = JsonParser.parseString(firstJson).getAsJsonObject();
        assertEquals(3, first.get("count").getAsInt(),
                "first page returns 3 messages, got: " + firstJson);
        assertTrue(first.get("has_more").getAsBoolean(),
                "with 5 messages and limit=3, has_more must be true");
        var firstMsgs = first.getAsJsonArray("messages");
        // Oldest in the returned page is turn-2 (the third-from-newest).
        assertEquals("turn-2",
                firstMsgs.get(0).getAsJsonObject().get("content").getAsString(),
                "page is oldest-first within the most recent 3");
        assertEquals("turn-4",
                firstMsgs.get(2).getAsJsonObject().get("content").getAsString());

        // Use the smallest id from the first page as the cursor for the
        // second page — that's the oldest in the page, i.e. turn-2's id.
        long smallestInFirst = firstMsgs.get(0).getAsJsonObject().get("id").getAsLong();
        var secondJson = invokeTool(parentAgent.id,
                "{\"runId\":\"" + run.id + "\",\"limit\":3,"
                        + "\"beforeMessageId\":\"" + smallestInFirst + "\"}");
        var second = JsonParser.parseString(secondJson).getAsJsonObject();
        // Two remaining (turn-0, turn-1) → count=2, has_more=false.
        assertEquals(2, second.get("count").getAsInt(),
                "second page returns remaining 2, got: " + secondJson);
        assertFalse(second.get("has_more").getAsBoolean(),
                "no more pages after walking through 5 messages");
        var secondMsgs = second.getAsJsonArray("messages");
        assertEquals("turn-0",
                secondMsgs.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("turn-1",
                secondMsgs.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void toolCallsAndResultsSurfaceInReturn() throws Exception {
        var run = seedRun();
        seedMessage(childConv, MessageRole.ASSISTANT, "calling a tool",
                "[{\"id\":\"call_1\",\"function\":{\"name\":\"date_time\"}}]");
        // Tool result row carries the tool result text in `content`.
        Tx.run(() -> {
            var msg = new Message();
            msg.conversation = childConv;
            msg.role = MessageRole.TOOL.value;
            msg.content = "2026-05-15T12:00:00Z";
            msg.toolResults = "{\"tool_call_id\":\"call_1\",\"result\":\"2026-05-15T12:00:00Z\"}";
            msg.save();
        });

        var json = invokeTool(parentAgent.id,
                "{\"runId\":\"" + run.id + "\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        var messages = parsed.getAsJsonArray("messages");
        assertEquals(2, messages.size());

        var assistantRow = messages.get(0).getAsJsonObject();
        assertNotNull(assistantRow.get("tool_calls"));
        assertFalse(assistantRow.get("tool_calls").isJsonNull(),
                "assistant tool_calls must be present in return");
        assertTrue(assistantRow.get("tool_calls").getAsString().contains("date_time"));

        var toolRow = messages.get(1).getAsJsonObject();
        assertEquals("tool", toolRow.get("role").getAsString());
        assertNotNull(toolRow.get("tool_results"));
        assertFalse(toolRow.get("tool_results").isJsonNull());
        assertTrue(toolRow.get("tool_results").getAsString().contains("call_1"));
    }

    // ──────── helpers ────────

    private SubagentRun seedRun() {
        return Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgent;
            run.parentConversation = parentConv;
            run.childConversation = childConv;
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.outcome = "test ok";
            run.save();
            return run;
        });
    }

    private Message seedMessage(Conversation conv, MessageRole role, String content, String toolCalls) {
        return Tx.run(() -> {
            var msg = new Message();
            msg.conversation = conv;
            msg.role = role.value;
            msg.content = content;
            msg.toolCalls = toolCalls;
            msg.save();
            // Ensure strictly-increasing createdAt across calls so the
            // chronological ordering assertion is stable on systems with
            // microsecond clock resolution.
            try { Thread.sleep(1); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            return msg;
        });
    }

    /** Invoke the tool on a fresh virtual thread (so the inner Tx.run opens
     *  its own JPA tx + entity-manager) and return the result string.
     *  Commits any pending setup-thread work first so the VT sees it. */
    private String invokeTool(Long callerAgentId, String argsJson) throws Exception {
        commitAndReopen();
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var caller = Tx.run(() -> (Agent) Agent.findById(callerAgentId));
                var tool = (SessionsHistoryTool) ToolRegistry.lookupTool(SessionsHistoryTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, caller));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(10_000);
        assertFalse(thread.isAlive(), "sessions_history must complete within 10s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }

    /** Commit pending parent setup rows so the VT-dispatched tool call can
     *  observe them through its own transaction. Mirrors the helper in
     *  SpawnSubagentToolTest. */
    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
