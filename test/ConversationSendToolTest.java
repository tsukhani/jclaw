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
import tools.ConversationSendTool;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-326 tests: {@code conversation_send} tool.
 *
 * <p>Each test seeds the parent + child agent + conversation graph the tool
 * operates on. The tool is invoked directly from a fresh virtual thread so
 * the inner {@code Tx.run} opens its own JPA transaction and observes
 * setup-thread writes.
 */
class ConversationSendToolTest extends UnitTest {

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
        parentAgent = AgentService.create("p-send", "openrouter", "gpt-4.1");
        childAgent = AgentService.create("c-send", "openrouter", "gpt-4.1");
        childAgent.parentAgent = parentAgent;
        childAgent.save();
        parentConv = ConversationService.create(parentAgent, "web", "u-send");
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
        var tool = ToolRegistry.lookupTool(ConversationSendTool.TOOL_NAME);
        assertNotNull(tool, "conversation_send must be registered by ToolRegistrationJob");
        assertEquals(ConversationSendTool.TOOL_NAME, tool.name());
        assertEquals("System", tool.category());
    }

    @Test
    void parentToChildAppendsUserMessageOnChildConversation() throws Exception {
        var run = seedRun(SubagentRun.Status.RUNNING);
        var json = invokeTool(parentAgent.id,
                "{\"target\":\"child\",\"runId\":\"" + run.id + "\","
                        + "\"message\":\"check radarr\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("sent", parsed.get("action").getAsString(),
                "expected sent action, got: " + json);
        assertEquals("parent_to_child", parsed.get("direction").getAsString());
        assertEquals(String.valueOf(run.id), parsed.get("runId").getAsString());
        assertEquals(String.valueOf(childConv.id),
                parsed.get("childConversationId").getAsString());

        // Verify the row actually landed on the child conversation with the
        // right role, messageKind, and metadata source discriminator.
        var msgs = childMessagesFor(childConv);
        assertEquals(1, msgs.size(), "exactly one new message on child conversation");
        var msg = msgs.get(0);
        assertEquals("user", msg.role, "parent→child rides as USER so child LLM sees it");
        assertEquals("check radarr", msg.content);
        assertEquals(ConversationSendTool.MESSAGE_KIND, msg.messageKind,
                "discriminator for chat UI rendering");
        assertNotNull(msg.metadata);
        assertTrue(msg.metadata.contains("\"source\":\"parent\""),
                "metadata must carry source=parent, got: " + msg.metadata);
    }

    @Test
    void parentToChildRejectsForeignRun() throws Exception {
        var stranger = Tx.run(() -> AgentService.create("p-stranger-send", "openrouter", "gpt-4.1"));
        var run = seedRun(SubagentRun.Status.RUNNING);
        var reply = invokeTool(stranger.id,
                "{\"target\":\"child\",\"runId\":\"" + run.id + "\","
                        + "\"message\":\"secret\"}");
        assertTrue(reply.startsWith("Error: runId " + run.id + " is not owned"),
                "non-parent caller must be rejected, got: " + reply);
        // No message landed on the child conversation.
        assertEquals(0, childMessagesFor(childConv).size(),
                "rejected send must not write any message");
    }

    @Test
    void parentToChildRejectsTerminalRun() throws Exception {
        var run = seedRun(SubagentRun.Status.COMPLETED);
        var reply = invokeTool(parentAgent.id,
                "{\"target\":\"child\",\"runId\":\"" + run.id + "\","
                        + "\"message\":\"too late\"}");
        assertTrue(reply.startsWith("Error: cannot send to child"),
                "terminal run must reject sends, got: " + reply);
        assertTrue(reply.contains("completed"),
                "error must mention the actual status, got: " + reply);
        assertEquals(0, childMessagesFor(childConv).size());
    }

    @Test
    void parentToChildRequiresRunIdWhenTargetChild() throws Exception {
        var reply = invokeTool(parentAgent.id,
                "{\"target\":\"child\",\"message\":\"forgot runId\"}");
        assertTrue(reply.startsWith("Error: target=\"child\" requires 'runId'"),
                "explicit target=child without runId must fail clearly, got: " + reply);
    }

    @Test
    void childToParentDefaultsToParentWhenChildIsCaller() throws Exception {
        // Seed a RUNNING run where childAgent is the child — child's caller-
        // role inference should resolve to target=parent without explicit arg.
        var run = seedRun(SubagentRun.Status.RUNNING);
        var json = invokeTool(childAgent.id,
                "{\"message\":\"status: scanning\"}");
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("sent", parsed.get("action").getAsString(), json);
        assertEquals("child_to_parent", parsed.get("direction").getAsString(),
                "child caller defaults to parent direction, got: " + json);
        assertEquals(String.valueOf(run.id), parsed.get("runId").getAsString());
        assertEquals(String.valueOf(parentConv.id),
                parsed.get("parentConversationId").getAsString());

        var msgs = childMessagesFor(parentConv);
        assertEquals(1, msgs.size());
        var msg = msgs.get(0);
        assertEquals("user", msg.role, "child→parent rides as USER so parent LLM sees it");
        assertEquals("status: scanning", msg.content);
        assertEquals(ConversationSendTool.MESSAGE_KIND, msg.messageKind);
        assertTrue(msg.metadata.contains("\"source\":\"child\""),
                "metadata must carry source=child, got: " + msg.metadata);
    }

    @Test
    void childToParentErrorWhenCallerHasNoActiveRun() throws Exception {
        // Calling agent is the child agent but no SubagentRun exists.
        var reply = invokeTool(childAgent.id,
                "{\"target\":\"parent\",\"message\":\"orphan\"}");
        assertTrue(reply.contains("not currently a child of any run"),
                "child with no active run must be rejected, got: " + reply);
        assertEquals(0, childMessagesFor(parentConv).size());
    }

    @Test
    void childToParentRecordsPayloadTypeInMetadata() throws Exception {
        seedRun(SubagentRun.Status.RUNNING);
        var json = invokeTool(childAgent.id,
                "{\"target\":\"parent\",\"message\":\"{\\\"key\\\":\\\"value\\\"}\","
                        + "\"payloadType\":\"json\"}");
        JsonParser.parseString(json).getAsJsonObject(); // smoke-check parses
        var msgs = childMessagesFor(parentConv);
        assertEquals(1, msgs.size());
        assertTrue(msgs.get(0).metadata.contains("\"payloadType\":\"json\""),
                "explicit payloadType must land in metadata, got: " + msgs.get(0).metadata);
    }

    @Test
    void invalidJsonArgsReturnsErrorEnvelope() throws Exception {
        var reply = invokeTool(parentAgent.id, "{}");
        assertTrue(reply.startsWith("Error: 'message' is required"),
                "empty args must fail on missing message, got: " + reply);
    }

    // ──────── helpers ────────

    private SubagentRun seedRun(SubagentRun.Status status) {
        return Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgent;
            run.parentConversation = parentConv;
            run.childConversation = childConv;
            run.status = status;
            if (status != SubagentRun.Status.RUNNING) {
                run.endedAt = Instant.now();
                run.outcome = "terminal";
            }
            run.save();
            return run;
        });
    }

    private static List<Message> childMessagesFor(Conversation conv) {
        return Tx.run(() -> Message.<Message>find(
                "conversation = ?1 ORDER BY id ASC", conv).fetch());
    }

    private String invokeTool(Long callerAgentId, String argsJson) throws Exception {
        commitAndReopen();
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var caller = Tx.run(() -> (Agent) Agent.findById(callerAgentId));
                var tool = (ConversationSendTool) ToolRegistry.lookupTool(ConversationSendTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, caller));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(10_000);
        assertFalse(thread.isAlive(), "conversation_send must complete within 10s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
