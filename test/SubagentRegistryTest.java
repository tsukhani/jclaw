import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.SubagentRegistry;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import models.SubagentRun;
import models.Agent;

import java.time.Instant;

/**
 * Direct unit-level coverage for SubagentRegistry.kill — the controller
 * tests reach kill via /api/subagent-runs/{id}/kill but only exercise the
 * happy + 404 paths. This test hits the null-id and already-terminal arms
 * from the service layer directly.
 */
class SubagentRegistryTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        SubagentRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        SubagentRegistry.clear();
    }

    @Test
    void killReturnsErrorForNullRunId() {
        var result = SubagentRegistry.kill(null, "reason");
        assertFalse(result.killed());
        assertNull(result.finalStatus());
        assertTrue(result.message().contains("runId is required"),
                "message must call out missing runId: " + result.message());
    }

    @Test
    void killReturnsNotFoundForUnknownRunId() {
        var result = SubagentRegistry.kill(999999999L, "reason");
        assertFalse(result.killed());
        assertNull(result.finalStatus());
        assertTrue(result.message().contains("not found"),
                "message must say not found: " + result.message());
    }

    @Test
    void killReportsCurrentStatusForAlreadyCompletedRun() {
        // Already-terminal idempotent path: kill returns killed=false but
        // finalStatus is set to the existing terminal status.
        Long runId = Tx.run(() -> {
            Agent parent = AgentService.create("kill-completed-p", "openrouter", "gpt-4.1");
            Agent child = AgentService.create("kill-completed-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(parent, "web", "u");
            var cc = ConversationService.create(child, "subagent", null);
            SubagentRun run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.status = SubagentRun.Status.COMPLETED;
            run.startedAt = Instant.now().minusSeconds(60);
            run.endedAt = Instant.now();
            run.save();
            return run.id;
        });

        var result = SubagentRegistry.kill(runId, "ignored");
        assertFalse(result.killed());
        assertEquals(SubagentRun.Status.COMPLETED, result.finalStatus());
        assertTrue(result.message().contains("already"),
                "message: " + result.message());
    }

    @Test
    void killFlipsRunningRowToKilled() {
        // Happy path: RUNNING row gets stamped KILLED.
        Long runId = Tx.run(() -> {
            Agent parent = AgentService.create("kill-running-p", "openrouter", "gpt-4.1");
            Agent child = AgentService.create("kill-running-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(parent, "web", "u");
            var cc = ConversationService.create(child, "subagent", null);
            SubagentRun run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.status = SubagentRun.Status.RUNNING;
            run.startedAt = Instant.now();
            run.save();
            return run.id;
        });

        var result = SubagentRegistry.kill(runId, "operator-supplied");
        assertTrue(result.killed());
        assertEquals(SubagentRun.Status.KILLED, result.finalStatus());

        SubagentRun fresh = Tx.run(() -> (SubagentRun) SubagentRun.findById(runId));
        assertEquals(SubagentRun.Status.KILLED, fresh.status);
        assertNotNull(fresh.endedAt);
    }
}
