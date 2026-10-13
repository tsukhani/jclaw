import models.Agent;
import models.MessageRole;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.TaskExecutor;
import services.TaskWriteService;
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
 * distinctive concatenated tokens (immune to StandardTokenizer word-boundary
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
        var t = Thread.ofPlatform().start(() -> {
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

    // ── CONVERSATION_MESSAGE via AgentService.delete ──────────────────────

    @Test
    void agentDeleteEvictsConversationMessageDocs() throws Exception {
        var msgToken = "convmsgagentonlytoken";
        long agentId = commitInFreshTx(() -> {
            var agent = newAgent("cl-convmsg");
            agent.save();
            var convo = ConversationService.create(agent, "web", "c-" + System.nanoTime());
            ConversationService.appendUserMessage(convo, msgToken);
            return agent.id;
        });

        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE, msgToken, 10).size(),
                "conversation message must be indexed before delete");

        commitInFreshTx(() -> {
            AgentService.delete(Agent.findById(agentId));
            return null;
        });

        assertTrue(repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE, msgToken, 10).isEmpty(),
                "agent delete must evict the subtree conversations' CONVERSATION_MESSAGE docs");
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

    // ── JCLAW-1066: the child Agent row itself ────────────────────────────

    @Test
    void conversationDeleteRemovesTheSubagentsItCreated() {
        var ids = commitInFreshTx(() -> {
            var parent = newAgent("cl-aparent");
            parent.save();
            var child = newAgent("cl-achild");
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
            run.outcome = "subrunagentrowtoken";
            run.status = SubagentRun.Status.COMPLETED;
            run.endedAt = Instant.now();
            run.save();
            return List.of(pc.id, child.id, parent.id);
        });
        long parentConvoId = ids.get(0);
        long childAgentId = ids.get(1);
        long parentAgentId = ids.get(2);

        // Read through fresh transactions on both sides. A findById on the test's own
        // thread would seed its persistence context, and the post-delete lookup would
        // then be answered from that first-level cache instead of the database — the
        // row is gone but the assertion still sees the cached copy.
        assertNotNull(commitInFreshTx(() -> Agent.<Agent>findById(childAgentId)),
                "child agent must exist before the delete");

        commitInFreshTx(() -> ConversationService.deleteByIds(List.of(parentConvoId)));

        assertNull(commitInFreshTx(() -> Agent.<Agent>findById(childAgentId)),
                "deleting the conversation must delete the subagent it spawned, not just its run row");
        assertNotNull(commitInFreshTx(() -> Agent.<Agent>findById(parentAgentId)),
                "the parent agent owns the conversation but must survive its deletion");
    }

    // A run that named agentId ran on an agent the operator made; deleting the chat used to delete it.
    @Test
    void conversationDeleteKeepsAnAgentARunOnlyReused() {
        var ids = commitInFreshTx(() -> {
            var parent = newAgent("cl-rparent");
            parent.save();
            var reused = newAgent("cl-rreused");
            reused.save();
            var chat = ConversationService.create(parent, "web", "p-" + System.nanoTime());

            var inline = new SubagentRun();
            inline.parentAgent = parent;
            inline.childAgent = reused;
            inline.parentConversation = chat;
            inline.childConversation = chat;
            inline.status = SubagentRun.Status.COMPLETED;
            inline.endedAt = Instant.now();
            inline.save();
            return List.of(chat.id, reused.id, inline.id);
        });

        commitInFreshTx(() -> ConversationService.deleteByIds(List.of(ids.get(0))));

        assertNull(commitInFreshTx(() -> SubagentRun.<SubagentRun>findById(ids.get(2))), "the run goes with its chat");
        assertNotNull(commitInFreshTx(() -> Agent.<Agent>findById(ids.get(1))),
                "an agent the run reused is the operator's, not the run's to delete");
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

        // Through a committed transaction, like the agent- and conversation-delete cases above
        // and like production: TaskExecutor:99 calls this "in its own transaction (the run
        // above is already committed)". Called bare, it would join this test's ambient
        // transaction, and the eviction is now ordered after that transaction's commit
        // (JCLAW-1042) rather than fired mid-cascade.
        commitInFreshTx(() -> {
            TaskExecutor.pruneRunHistory(taskId);
            return null;
        });

        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, prunedToken + "0", 10).isEmpty(),
                "pruned run's TASK_RUN_MESSAGE doc must be evicted");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, prunedToken + "1", 10).isEmpty(),
                "pruned run's TASK_RUN_MESSAGE doc must be evicted");
        assertEquals(TaskExecutor.MAX_RUNS_PER_TASK,
                repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, keptToken, 20).size(),
                "kept runs' transcript docs must survive the prune");
    }

    // ── TASK_RUN_MESSAGE via the bulk-delete paths outside TaskExecutor ───

    @Test
    void deleteWithHistoryEvictsTaskRunMessageDocs() throws Exception {
        var token = "deletewithhistorytrmtoken";
        long taskId = commitInFreshTx(() -> {
            var agent = newAgent("cl-dwhagent");
            agent.save();
            var task = seedTask(agent, "delete-with-history-task");
            seedRunWithMessage(task, Instant.now(), token);
            return task.id;
        });

        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, token, 10).size(),
                "the transcript must be indexed before the delete");

        commitInFreshTx(() -> {
            TaskWriteService.deleteWithHistory(Task.findById(taskId));
            return null;
        });

        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, token, 10).isEmpty(),
                "deleting a task through TaskWriteService must evict its TASK_RUN_MESSAGE docs — "
                        + "the bulk JPQL DELETE never fires TaskRunMessage.@PostRemove");
    }

    private static Task seedTask(Agent agent, String name) {
        var task = new Task();
        task.agent = agent;
        task.name = name;
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.scheduledAt = Instant.now();
        task.nextRunAt = Instant.now();
        task.save();
        return task;
    }

    // ── JCLAW-1042 / VULN-085: the eviction must not outlive a rolled-back transaction ──

    @Test
    void aRolledBackEvictionLeavesTheTranscriptDocIntact() throws Exception {
        var token = "rollbackevicttrmtoken";
        long messageId = commitInFreshTx(() -> {
            var agent = newAgent("cl-rollback");
            agent.save();
            var task = new Task();
            task.agent = agent;
            task.name = "rollback-task";
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.PENDING;
            task.scheduledAt = Instant.now();
            task.nextRunAt = Instant.now();
            task.save();
            seedRunWithMessage(task, Instant.now(), token);
            return ((TaskRunMessage) TaskRunMessage.find("content = ?1", token).first()).id;
        });
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, token, 10).size(),
                "precondition: the transcript is indexed");

        // A cascade that evicts the doc and then fails, which is the shape every caller of
        // removeAll has: collect ids, delete rows, evict — with more work still to come.
        assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> {
            LuceneIndexer.removeAll(LuceneIndexer.Scope.TASK_RUN_MESSAGE, List.of(messageId));
            throw new IllegalStateException("cascade failed after the eviction");
        }));

        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, token, 10).size(),
                "a rolled-back transaction must not destroy the index entry: removeAll commits "
                        + "the index durably, so the row would survive in the database while its "
                        + "document was permanently gone — unsearchable until a restart noticed "
                        + "docCount < rowCount and rebuilt the whole scope");
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
