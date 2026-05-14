import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import services.AgentService;
import services.ConversationService;

import java.time.Instant;

/**
 * Persistence tests for the JCLAW-264 subagent data model: round-trips for
 * {@link SubagentRun} (all FKs + status + outcome + timestamps), and the
 * nullable {@code parent_agent_id} / {@code parent_conversation_id} columns
 * on {@link Agent} and {@link Conversation}.
 */
class SubagentRunTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    private Agent newAgent(String name) {
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    // =====================
    // SubagentRun round-trip
    // =====================

    @Test
    void subagentRunPersistsAllFields() {
        var parentAgent = newAgent("subrun-parent-agent");
        var childAgent = newAgent("subrun-child-agent");
        var parentConv = ConversationService.create(parentAgent, "web", "u-parent");
        var childConv = ConversationService.create(childAgent, "web", "u-child");

        var run = new SubagentRun();
        run.parentAgent = parentAgent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.save();

        SubagentRun refreshed = SubagentRun.findById(run.id);
        assertNotNull(refreshed);
        assertEquals(parentAgent.id, refreshed.parentAgent.id);
        assertEquals(childAgent.id, refreshed.childAgent.id);
        assertEquals(parentConv.id, refreshed.parentConversation.id);
        assertEquals(childConv.id, refreshed.childConversation.id);
        assertNotNull(refreshed.startedAt, "@PrePersist must populate startedAt");
        assertNull(refreshed.endedAt, "ended_at stays null until the run terminates");
        assertEquals(SubagentRun.Status.RUNNING, refreshed.status,
                "fresh row defaults to RUNNING");
        assertNull(refreshed.outcome, "outcome is null while the run is RUNNING");
    }

    @Test
    void subagentRunTerminalRowRoundTrips() {
        var parentAgent = newAgent("subrun-terminal-parent");
        var childAgent = newAgent("subrun-terminal-child");
        var parentConv = ConversationService.create(parentAgent, "web", "u-tp");
        var childConv = ConversationService.create(childAgent, "web", "u-tc");

        var run = new SubagentRun();
        run.parentAgent = parentAgent;
        run.childAgent = childAgent;
        run.parentConversation = parentConv;
        run.childConversation = childConv;
        run.status = SubagentRun.Status.COMPLETED;
        run.endedAt = Instant.now();
        run.outcome = "child reply text";
        run.save();

        SubagentRun refreshed = SubagentRun.findById(run.id);
        assertEquals(SubagentRun.Status.COMPLETED, refreshed.status);
        assertNotNull(refreshed.endedAt);
        assertEquals("child reply text", refreshed.outcome);
    }

    @Test
    void subagentRunAllStatusValuesPersist() {
        // Round-trip every value of the enum so a future rename / reorder
        // doesn't silently change the wire representation in the DB.
        var parentAgent = newAgent("subrun-status-parent");
        var childAgent = newAgent("subrun-status-child");
        var parentConv = ConversationService.create(parentAgent, "web", "u-sp");
        var childConv = ConversationService.create(childAgent, "web", "u-sc");

        for (var status : SubagentRun.Status.values()) {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgent;
            run.parentConversation = parentConv;
            run.childConversation = childConv;
            run.status = status;
            run.save();

            SubagentRun refreshed = SubagentRun.findById(run.id);
            assertEquals(status, refreshed.status, "status round-trip for " + status);
        }
    }

    // =====================
    // Agent.parentAgent
    // =====================

    @Test
    void agentParentAgentDefaultsToNull() {
        var agent = newAgent("parent-agent-default");
        Agent refreshed = Agent.findById(agent.id);
        assertNull(refreshed.parentAgent,
                "agents created via the normal path must have a null parentAgent");
    }

    @Test
    void agentParentAgentRoundTrips() {
        var parent = newAgent("parent-agent-rt-parent");
        var child = newAgent("parent-agent-rt-child");
        child.parentAgent = parent;
        child.save();

        Agent refreshed = Agent.findById(child.id);
        assertNotNull(refreshed.parentAgent);
        assertEquals(parent.id, refreshed.parentAgent.id);
    }

    // =====================
    // Conversation.parentConversation
    // =====================

    @Test
    void conversationParentConversationDefaultsToNull() {
        var agent = newAgent("parent-conv-default");
        var conv = ConversationService.create(agent, "web", "u");
        Conversation refreshed = Conversation.findById(conv.id);
        assertNull(refreshed.parentConversation,
                "conversations created via the normal path must have a null parentConversation");
    }

    @Test
    void conversationParentConversationRoundTrips() {
        var agent = newAgent("parent-conv-rt");
        var parentConv = ConversationService.create(agent, "web", "u-parent");
        var childConv = ConversationService.create(agent, "web", "u-child");
        childConv.parentConversation = parentConv;
        childConv.save();

        Conversation refreshed = Conversation.findById(childConv.id);
        assertNotNull(refreshed.parentConversation);
        assertEquals(parentConv.id, refreshed.parentConversation.id);
    }
}
