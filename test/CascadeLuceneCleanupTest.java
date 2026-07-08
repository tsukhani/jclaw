import models.Agent;
import models.Conversation;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.TaskExecutor;
import services.Tx;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.LuceneIndexer;

import java.time.Instant;
import java.util.List;

/**
 * JCLAW-673: bulk / cascade deletes never fire an entity's {@code @PostRemove}
 * hook, so agent / conversation / task deletion orphaned the SUBAGENT_RUN, TASK,
 * and TASK_RUN_MESSAGE full-text docs. Each test seeds a doc via the JPA
 * @PostPersist round-trip, performs the parent delete that must clean it, and
 * asserts the doc is gone from search.
 *
 * <p>Uses {@code LuceneTestSync} to serialize against the JVM-global index and
 * distinctive concatenated tokens (immune to StandardAnalyzer word-boundary
 * splitting) so one scope's assertions can't be muddied by another's docs.
 */
class CascadeLuceneCleanupTest extends UnitTest {

    private DirectLuceneMessageSearchRepository repo;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        repo = new DirectLuceneMessageSearchRepository();
    }

    @AfterEach
    void teardown() {
        LuceneTestSync.release();
    }

    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static Agent newAgent(String prefix) {
        var a = new Agent();
        a.name = prefix + "-" + System.nanoTime();
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        return a;
    }

    // ── SUBAGENT_RUN + TASK + TASK_RUN_MESSAGE via AgentService.delete ─────

    @Test
    void agentDeleteEvictsSubagentRunTaskAndTaskRunMessageDocs() throws Exception {
        var runToken = "subrunagentonlytoken";
        var taskToken = "taskagentonlytoken";
        var trmToken = "trmagentonlytoken";
        long parentId = commitInFreshTx(() -> {
            var parent = newAgent("cl-parent");
            parent.save();
            var child = newAgent("cl-child");
            child.parentAgent = parent;
            child.save();

            var pc = ConversationService.create(parent, "web", "p-" + System.nanoTime());
            var cc = ConversationService.create(child, "subagent", null);
            cc.parentConversation = pc;
            cc.save();

            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.label = "run-label";
            run.outcome = runToken;
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.save();

            var task = new Task();
            task.agent = parent;
            task.name = "task-name";
            task.description = taskToken;
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.PENDING;
            task.scheduledAt = Instant.now();
            task.nextRunAt = Instant.now();
            task.save();
            seedRunWithMessage(task, Instant.now(), trmToken);
            return parent.id;
        });

        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, runToken, 10).size(),
                "subagent run must be indexed before delete");
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK, taskToken, 10).size(),
                "task must be indexed before delete");
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, trmToken, 10).size(),
                "task-run transcript must be indexed before delete");

        commitInFreshTx(() -> {
            AgentService.delete(Agent.findById(parentId));
            return null;
        });

        assertTrue(repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, runToken, 10).isEmpty(),
                "agent delete must evict the subtree's SUBAGENT_RUN docs");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK, taskToken, 10).isEmpty(),
                "agent delete must evict the subtree's TASK docs");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, trmToken, 10).isEmpty(),
                "agent delete must evict the subtree tasks' TASK_RUN_MESSAGE docs");
    }

    // ── SUBAGENT_RUN via ConversationService.deleteByIds ──────────────────

    @Test
    void conversationDeleteEvictsSubagentRunDocs() throws Exception {
        var runToken = "subrunconvonlytoken";
        long parentConvoId = commitInFreshTx(() -> {
            var parent = newAgent("cl-cparent");
            parent.save();
            var child = newAgent("cl-cchild");
            child.parentAgent = parent;
            child.save();

            var pc = ConversationService.create(parent, "web", "p-" + System.nanoTime());
            var cc = ConversationService.create(child, "subagent", null);
            cc.parentConversation = pc;
            cc.save();

            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.label = "run-label";
            run.outcome = runToken;
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.save();
            return pc.id;
        });

        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, runToken, 10).size(),
                "subagent run must be indexed before delete");

        commitInFreshTx(() -> ConversationService.deleteByIds(List.of(parentConvoId)));

        assertTrue(repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, runToken, 10).isEmpty(),
                "deleting the parent conversation must evict the swept SUBAGENT_RUN docs");
    }

    // ── TASK_RUN_MESSAGE via TaskExecutor.pruneRunHistory ─────────────────

    @Test
    void pruneRunHistoryEvictsTaskRunMessageDocs() throws Exception {
        var prunedToken = "prunedtrmonlytoken";
        var keptToken = "kepttrmonlytoken";
        long taskId = commitInFreshTx(() -> {
            var agent = newAgent("cl-tagent");
            agent.save();
            var task = new Task();
            task.agent = agent;
            task.name = "prune-task";
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.PENDING;
            task.scheduledAt = Instant.now();
            task.nextRunAt = Instant.now();
            task.save();

            var now = Instant.now();
            // Two OLD runs (get pruned) carry the pruned token; then exactly
            // MAX_RUNS_PER_TASK newer runs carry the kept token.
            for (int i = 0; i < 2; i++) {
                seedRunWithMessage(task, now.minusSeconds(10_000 + i), prunedToken + i);
            }
            for (int i = 0; i < TaskExecutor.MAX_RUNS_PER_TASK; i++) {
                seedRunWithMessage(task, now.plusSeconds(i), keptToken);
            }
            return task.id;
        });

        // Both old transcripts are indexed before the prune.
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, prunedToken + "0", 10).size());
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, prunedToken + "1", 10).size());
        assertEquals(TaskExecutor.MAX_RUNS_PER_TASK,
                repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, keptToken, 20).size());

        TaskExecutor.pruneRunHistory(taskId);

        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, prunedToken + "0", 10).isEmpty(),
                "pruned run's TASK_RUN_MESSAGE doc must be evicted");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, prunedToken + "1", 10).isEmpty(),
                "pruned run's TASK_RUN_MESSAGE doc must be evicted");
        assertEquals(TaskExecutor.MAX_RUNS_PER_TASK,
                repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, keptToken, 20).size(),
                "kept runs' transcript docs must survive the prune");
    }

    private static void seedRunWithMessage(Task task, Instant startedAt, String content) {
        var run = new TaskRun();
        run.task = task;
        run.startedAt = startedAt;
        run.status = TaskRun.Status.COMPLETED;
        run.save();

        var msg = new TaskRunMessage();
        msg.taskRun = run;
        msg.turnIndex = 0;
        msg.role = MessageRole.ASSISTANT;
        msg.content = content;
        msg.save();
    }
}
