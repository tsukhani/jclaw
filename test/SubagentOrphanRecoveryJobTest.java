import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import jobs.SubagentOrphanRecoveryJob;
import models.SubagentRun;
import models.Agent;
import models.Conversation;
import services.AgentService;
import services.ConversationService;
import services.Tx;

import java.time.Instant;

class SubagentOrphanRecoveryJobTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void doJobIsNoopWhenNoOrphans() {
        // Empty DB → orphans list is empty → early return without touching
        // anything. The test passes if no exception escapes.
        assertDoesNotThrow(() -> new SubagentOrphanRecoveryJob().doJob());
    }

    @Test
    void doJobFlipsAgedRunningRowToFailed() {
        // Seed a RUNNING SubagentRun with startedAt older than the
        // ORPHAN_AGE_SECONDS cutoff so the recovery sweep picks it up.
        Long orphanId = Tx.run(() -> {
            Agent parent = AgentService.create("orphan-parent", "openrouter", "gpt-4.1");
            Agent child = AgentService.create("orphan-child", "openrouter", "gpt-4.1");
            Conversation pc = ConversationService.create(parent, "web", "u");
            Conversation cc = ConversationService.create(child, "subagent", null);
            SubagentRun run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.status = SubagentRun.Status.RUNNING;
            run.startedAt = Instant.now().minusSeconds(3600); // 1h ago
            run.save();
            return run.id;
        });

        new SubagentOrphanRecoveryJob().doJob();

        SubagentRun fresh = Tx.run(() -> (SubagentRun) SubagentRun.findById(orphanId));
        assertEquals(SubagentRun.Status.FAILED, fresh.status,
                "aged RUNNING orphan must be flipped to FAILED");
        assertNotNull(fresh.endedAt);
        assertTrue(fresh.outcome.contains("did not survive"),
                "outcome must annotate the recovery reason: " + fresh.outcome);
    }

    @Test
    void doJobLeavesYoungRunningRowAlone() {
        // startedAt just now → still inside the ORPHAN_AGE_SECONDS window,
        // so the sweep ignores it.
        Long youngId = Tx.run(() -> {
            Agent parent = AgentService.create("young-parent", "openrouter", "gpt-4.1");
            Agent child = AgentService.create("young-child", "openrouter", "gpt-4.1");
            Conversation pc = ConversationService.create(parent, "web", "u");
            Conversation cc = ConversationService.create(child, "subagent", null);
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

        new SubagentOrphanRecoveryJob().doJob();

        SubagentRun fresh = Tx.run(() -> (SubagentRun) SubagentRun.findById(youngId));
        assertEquals(SubagentRun.Status.RUNNING, fresh.status,
                "young RUNNING row must not be touched");
    }
}
