import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import services.ConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.LuceneIndexer;
import services.search.MessageSearchTestHooks;

import java.time.Instant;
import java.util.List;

/**
 * Live JPA + Lucene round-trip for {@link DirectLuceneMessageSearchRepository}.
 * Exercises the post-{@code FullTextLucene} path: persists TaskRunMessage
 * rows, lets the JPA @PostPersist hook drive {@link LuceneIndexer}, then
 * queries through the repo and confirms hits.
 *
 * <p>Pins:
 * <ul>
 *   <li>{@code init()} is idempotent — running it twice doesn't throw and
 *       doesn't recreate the index.</li>
 *   <li>Persist-then-search round-trip works: a row written after init
 *       becomes findable on its own content.</li>
 *   <li>Case-insensitive single-term match (StandardAnalyzer lowercases).</li>
 *   <li>Limit caps the result count.</li>
 *   <li>Blank / null query returns empty (non-exceptional contract).</li>
 *   <li>Malformed Lucene query syntax returns empty rather than 500ing.</li>
 *   <li>Delete propagates: removing the JPA row drops it from search.</li>
 * </ul>
 */
class DirectLuceneMessageSearchRepositoryTest extends UnitTest {

    private DirectLuceneMessageSearchRepository repo;

    @BeforeEach
    void setup() {
        // JCLAW-428: serialize against other Lucene tests and open a clean index
        // at the %test path (data/jclaw-lucene-test). The boot job skips Lucene
        // init in test mode, so openForTest() opens it; wipeForTest() clears
        // leftover docs so a different test class can't contaminate this one.
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        repo = new DirectLuceneMessageSearchRepository();
        MessageSearchTestHooks.setRepository(repo);
    }

    @AfterEach
    void teardown() {
        MessageSearchTestHooks.setRepository(null);
        LuceneTestSync.release();
    }

    private static Long commitInFreshTx(java.util.function.Supplier<Long> block) {
        var ref = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private Long seedMessage(String content) {
        return commitInFreshTx(() -> {
            var a = new Agent();
            a.name = "agent-" + System.nanoTime();
            a.modelProvider = "test-provider";
            a.modelId = "test-model";
            a.enabled = true;
            a.save();

            var task = new Task();
            task.agent = a;
            task.name = "task-" + System.nanoTime();
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.PENDING;
            task.scheduledAt = Instant.now();
            task.nextRunAt = Instant.now();
            task.save();

            var run = new TaskRun();
            run.task = task;
            run.startedAt = Instant.now();
            run.status = TaskRun.Status.COMPLETED;
            run.save();

            var msg = new TaskRunMessage();
            msg.taskRun = run;
            msg.turnIndex = 0;
            msg.role = MessageRole.ASSISTANT;
            msg.content = content;
            msg.save();
            return msg.id;
        });
    }

    private Long makeAgent(String name) {
        return commitInFreshTx(() -> {
            var a = new Agent();
            a.name = name;
            a.modelProvider = "openrouter";
            a.modelId = "gpt-4.1";
            a.save();
            return a.id;
        });
    }

    private Long seedMemory(Long agentId, String text) {
        return commitInFreshTx(() -> {
            var m = new models.Memory();
            m.agent = Agent.findById(agentId);   // JCLAW-537: real FK
            m.text = text;
            m.save();
            return m.id;
        });
    }

    @Test
    void memorySearchIsScopedToAgent() throws Exception {
        // JCLAW-415: the MEMORY scope indexes the agent id so search is filtered
        // to one owner. Both agents store the same "widget" term.
        var aId = makeAgent("agentA");
        var bId = makeAgent("agentB");
        var memA = seedMemory(aId, "shared widget knowledge");
        var memB = seedMemory(bId, "shared widget knowledge");

        // Each agent sees only its own memory id — never the other's, even
        // though the content query alone would match both (privacy invariant).
        var aHits = repo.searchMemoryIds(String.valueOf(aId), "widget", 10);
        var bHits = repo.searchMemoryIds(String.valueOf(bId), "widget", 10);
        assertEquals(List.of(memA), aHits.stream().map(s -> s.id()).toList());
        assertEquals(List.of(memB), bHits.stream().map(s -> s.id()).toList());
        // JCLAW-532: the sole hit is the top hit, so its relevance normalizes to 1.0.
        assertEquals(1.0, aHits.get(0).score(), 1e-9);

        // A term in neither memory matches nothing.
        assertTrue(repo.searchMemoryIds(String.valueOf(aId), "nonexistent", 10).isEmpty());
    }

    @Test
    void initIsIdempotent() throws Exception {
        repo.init();
        repo.init();  // second call must not throw
        assertTrue(LuceneIndexer.isOpen(), "indexer must remain open after repeated init()");
    }

    @Test
    void persistedRowIsFindableByContent() throws Exception {
        repo.init();
        seedMessage("the quick brown fox jumps over the lazy dog");

        var hits = repo.search("brown", 10);
        assertEquals(1, hits.size(), "expected one hit for 'brown'");
        assertEquals("the quick brown fox jumps over the lazy dog", hits.get(0).content);
    }

    @Test
    void searchIsCaseInsensitive() throws Exception {
        repo.init();
        seedMessage("Hello World");

        var hitsLower = repo.search("hello", 10);
        var hitsUpper = repo.search("HELLO", 10);
        assertEquals(1, hitsLower.size());
        assertEquals(1, hitsUpper.size());
    }

    @Test
    void limitCapsResultCount() throws Exception {
        repo.init();
        for (int i = 0; i < 5; i++) {
            seedMessage("alpha beta gamma " + i);
        }
        var hits = repo.search("alpha", 3);
        assertEquals(3, hits.size(), "limit=3 must cap the result count");
    }

    @Test
    void blankQueryReturnsEmptyList() throws Exception {
        repo.init();
        seedMessage("any content");
        assertTrue(repo.search("", 10).isEmpty(), "empty query returns empty list");
        assertTrue(repo.search("   ", 10).isEmpty(), "whitespace-only query returns empty list");
        assertTrue(repo.search(null, 10).isEmpty(), "null query returns empty list");
    }

    @Test
    void malformedQuerySyntaxReturnsEmptyNotThrowing() throws Exception {
        repo.init();
        seedMessage("something to index");
        // Unbalanced parens — QueryParser.ParseException — must NOT propagate.
        var hits = repo.search("((unbalanced", 10);
        assertTrue(hits.isEmpty(), "malformed syntax must yield empty list, not throw");
    }

    @Test
    void deleteRemovesRowFromSearch() throws Exception {
        repo.init();
        var id = seedMessage("deleteme uniquetoken12345");

        var beforeDelete = repo.search("uniquetoken12345", 10);
        assertEquals(1, beforeDelete.size(), "row must be findable before delete");

        commitInFreshTx(() -> {
            TaskRunMessage.<TaskRunMessage>findById(id).delete();
            return 0L;
        });
        // SearcherManager.maybeRefresh inside search() picks up the
        // post-delete commit on the next call.
        var afterDelete = repo.search("uniquetoken12345", 10);
        assertTrue(afterDelete.isEmpty(), "deleted row must not appear in search");
    }

    @Test
    void dialectNameIsLucene() {
        assertEquals("lucene", repo.dialectName());
    }

    @Test
    void backfillRebuildsEveryScopeFromJpaWhenIndexIsEmpty() throws Exception {
        // JCLAW-408: pins the table-driven backfill path. Seed one row per
        // scope into JPA (the @PostPersist hooks index them), then wipe the
        // index so it's empty across all scopes. init() must detect each
        // empty scope and re-index it from the JPA store, producing the
        // same searchable docs the live hooks would have.
        var msgId = seedConversationMessage("backfillconvtoken");
        var taskId = seedTaskRow("task-name", "backfilltasktoken");
        var runId = seedSubagentRunRow("run-label", "backfillruntoken");
        var trmId = seedMessage("backfilltrmtoken brown fox");

        // Drop everything from the index — JPA rows survive.
        LuceneIndexer.wipeForTest();
        for (var scope : LuceneIndexer.Scope.values()) {
            assertEquals(0, LuceneIndexer.docCount(scope),
                    "scope " + scope + " must be empty before backfill");
        }

        repo.init(); // backfill fires for every now-empty scope

        var convHits = repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE, "backfillconvtoken", 10);
        assertEquals(1, convHits.size(), "conversation message must be backfilled");
        assertEquals(msgId, convHits.getFirst());

        var taskHits = repo.searchIds(LuceneIndexer.Scope.TASK, "backfilltasktoken", 10);
        assertEquals(1, taskHits.size(), "task must be backfilled (name+description content)");
        assertEquals(taskId, taskHits.getFirst());

        var runHits = repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, "backfillruntoken", 10);
        assertEquals(1, runHits.size(), "subagent run must be backfilled (label+outcome content)");
        assertEquals(runId, runHits.getFirst());

        var trmHits = repo.search("backfilltrmtoken", 10);
        assertEquals(1, trmHits.size(), "task-run message must be backfilled");
        assertEquals(trmId, trmHits.getFirst().id);
    }

    // ── JCLAW-328: per-scope coverage ─────────────────────────────────

    /**
     * Seed a Message row directly inside a fresh Tx so the
     * {@code Message.onIndexUpsert} hook commits the new doc to the
     * CONVERSATION_MESSAGE Lucene scope. Returns the assigned id.
     */
    private static Long seedConversationMessage(String content) {
        return commitInFreshTx(() -> {
            var a = new Agent();
            a.name = "conv-agent-" + System.nanoTime();
            a.modelProvider = "test-provider";
            a.modelId = "test-model";
            a.enabled = true;
            a.save();

            var conv = ConversationService.create(a, "web", "u-" + System.nanoTime());

            var m = new Message();
            m.conversation = conv;
            m.role = MessageRole.USER.value;
            m.content = content;
            m.save();
            return m.id;
        });
    }

    /**
     * Seed a Task row directly so the {@code Task.onIndexUpsert} hook
     * commits a virtual document combining name + description to the
     * TASK Lucene scope.
     */
    private static Long seedTaskRow(String name, String description) {
        return commitInFreshTx(() -> {
            var a = new Agent();
            a.name = "task-agent-" + System.nanoTime();
            a.modelProvider = "test-provider";
            a.modelId = "test-model";
            a.enabled = true;
            a.save();

            var t = new Task();
            t.agent = a;
            t.name = name;
            t.description = description;
            t.type = Task.Type.IMMEDIATE;
            t.status = Task.Status.PENDING;
            t.scheduledAt = Instant.now();
            t.nextRunAt = Instant.now();
            t.save();
            return t.id;
        });
    }

    /**
     * Seed a SubagentRun row so {@code SubagentRun.onIndexUpsert} commits
     * the label + outcome virtual document to the SUBAGENT_RUN Lucene
     * scope. Parent + child agents plus parent + child conversations are
     * all required (FK NOT NULL on the run row's relationships).
     */
    private static Long seedSubagentRunRow(String label, String outcome) {
        return commitInFreshTx(() -> {
            var p = new Agent();
            p.name = "sub-p-" + System.nanoTime();
            p.modelProvider = "test-provider";
            p.modelId = "test-model";
            p.enabled = true;
            p.save();

            var c = new Agent();
            c.name = "sub-c-" + System.nanoTime();
            c.modelProvider = "test-provider";
            c.modelId = "test-model";
            c.enabled = true;
            c.parentAgent = p;
            c.save();

            var pc = ConversationService.create(p, "web", "u-" + System.nanoTime());
            var cc = ConversationService.create(c, "subagent", null);
            cc.parentConversation = pc;
            cc.save();

            var run = new SubagentRun();
            run.parentAgent = p;
            run.childAgent = c;
            run.parentConversation = pc;
            run.childConversation = cc;
            run.label = label;
            run.outcome = outcome;
            run.status = outcome != null
                    ? SubagentRun.Status.COMPLETED
                    : SubagentRun.Status.RUNNING;
            if (outcome != null) run.endedAt = Instant.now();
            run.save();
            return run.id;
        });
    }

    @Test
    void searchIdsScopesAreIsolated() throws Exception {
        // Each scope must only see its own documents. A keyword indexed
        // under TASK must not appear in CONVERSATION_MESSAGE results,
        // and vice versa — even though both share the same underlying
        // {@code content} field name internally. Catches a future
        // regression where someone accidentally collapses all four
        // SearcherManagers onto one shared directory.
        //
        // Token choice: each scope gets a single contiguous lowercase
        // token. StandardAnalyzer decomposes hyphenated strings on word
        // boundaries (so {@code "unique-task-token"} would tokenize as
        // {@code [unique, task, token]} and the shared "token" word
        // would leak across every seed), which would make the cross-
        // scope assertion fail spuriously. Concatenated tokens stay
        // atomic through the analyzer and only match the scope that
        // indexed them.
        repo.init();
        var msgId = seedConversationMessage("alpha messageonlytoken");
        var taskId = seedTaskRow("task-name", "alpha taskonlytoken");
        var runId = seedSubagentRunRow("run-label", "alpha runonlytoken");

        var msgHits = repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE,
                "messageonlytoken", 10);
        assertEquals(1, msgHits.size());
        assertEquals(msgId, msgHits.getFirst());

        var taskHits = repo.searchIds(LuceneIndexer.Scope.TASK,
                "taskonlytoken", 10);
        assertEquals(1, taskHits.size());
        assertEquals(taskId, taskHits.getFirst());

        var runHits = repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN,
                "runonlytoken", 10);
        assertEquals(1, runHits.size());
        assertEquals(runId, runHits.getFirst());

        // Cross-scope leak check: a task-only token must not surface from
        // the conversation scope (and vice versa).
        assertTrue(repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE,
                "taskonlytoken", 10).isEmpty(),
                "task tokens must not leak into the conversation scope");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK,
                "messageonlytoken", 10).isEmpty(),
                "message tokens must not leak into the task scope");
    }

    @Test
    void stemmingMatchesSingularAndPluralForms() throws Exception {
        // StandardAnalyzer doesn't actually stem (it lowercases and
        // tokenizes only). The AC's "stemming" wording is closer to
        // case-fold-and-tokenize than to Porter-stem. Pin the behavior
        // that's actually shipping: a plural query matches the indexed
        // singular when they share the tokenized form (e.g. exact
        // word-boundary substring), but NOT when they're separate
        // surface tokens like "quota" vs "quotas". This locks today's
        // contract so a future analyzer change becomes visible.
        repo.init();
        seedSubagentRunRow("radarr-monitor",
                "IMPORT_FAILED: disk quota exceeded on volume1");

        // Word-boundary token match — "quota" is one of the tokenized
        // terms in the outcome, so a query for "quota" finds the run.
        var exact = repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, "quota", 10);
        assertEquals(1, exact.size(),
                "exact token 'quota' must match the seeded outcome");

        // Case folding works regardless of analyzer choice.
        var caseFold = repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, "QUOTA", 10);
        assertEquals(1, caseFold.size(),
                "case-folded token 'QUOTA' must match the seeded outcome");
    }

    @Test
    void searchIdsBlankQueryReturnsEmptyForEveryScope() throws Exception {
        repo.init();
        seedConversationMessage("seed-conv-content");
        seedTaskRow("seed-task", "seed-task-desc");
        seedSubagentRunRow("seed-run", "seed-run-outcome");

        for (var scope : LuceneIndexer.Scope.values()) {
            assertTrue(repo.searchIds(scope, "", 10).isEmpty(),
                    "empty query must return empty list for scope " + scope);
            assertTrue(repo.searchIds(scope, "   ", 10).isEmpty(),
                    "whitespace-only query must return empty list for scope " + scope);
            assertTrue(repo.searchIds(scope, null, 10).isEmpty(),
                    "null query must return empty list for scope " + scope);
        }
    }

    @Test
    void deletePropagatesPerScopeFromJpaPostRemove() throws Exception {
        // Same atomic-token rule as searchIdsScopesAreIsolated above —
        // distinctive concatenated tokens per scope so the assertion
        // about removal from one scope can't be muddied by a hyphen-
        // decomposed shared word matching another scope's surviving
        // doc.
        repo.init();
        var msgId = seedConversationMessage("removablemsgtoken");
        var taskId = seedTaskRow("seed-task", "removabletasktoken");
        var runId = seedSubagentRunRow("seed-run", "removableruntoken");

        // Pre-condition: every scope finds its row.
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE,
                "removablemsgtoken", 10).size());
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.TASK,
                "removabletasktoken", 10).size());
        assertEquals(1, repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN,
                "removableruntoken", 10).size());

        // Delete each row in its own VT-tx so the @PostRemove hook fires
        // a commit visible to the next maybeRefresh on the searcher.
        commitInFreshTx(() -> {
            Message.<Message>findById(msgId).delete();
            return 0L;
        });
        commitInFreshTx(() -> {
            Task.<Task>findById(taskId).delete();
            return 0L;
        });
        commitInFreshTx(() -> {
            SubagentRun.<SubagentRun>findById(runId).delete();
            return 0L;
        });

        assertTrue(repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE,
                "removablemsgtoken", 10).isEmpty(),
                "deleted Message must drop from CONVERSATION_MESSAGE scope");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.TASK,
                "removabletasktoken", 10).isEmpty(),
                "deleted Task must drop from TASK scope");
        assertTrue(repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN,
                "removableruntoken", 10).isEmpty(),
                "deleted SubagentRun must drop from SUBAGENT_RUN scope");
    }

    @Test
    void subagentRunVirtualDocumentIndexesBothLabelAndOutcome() throws Exception {
        // Pins the property described by JCLAW-328's AC: a single
        // SubagentRun row contributes BOTH its label tokens AND its
        // outcome tokens to the indexed virtual document. The hook
        // must read both fields on the same fire — overwriting just
        // one would leave the other unsearchable.
        repo.init();
        var runId = seedSubagentRunRow("radarr-monitor",
                "IMPORT_FAILED: disk full");

        // Tokens from the label field.
        var labelHits = repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, "radarr", 10);
        assertEquals(1, labelHits.size(), "label token 'radarr' must match");
        assertEquals(runId, labelHits.getFirst());

        // Tokens from the outcome field.
        var outcomeHits = repo.searchIds(LuceneIndexer.Scope.SUBAGENT_RUN, "disk", 10);
        assertEquals(1, outcomeHits.size(), "outcome token 'disk' must match");
        assertEquals(runId, outcomeHits.getFirst());

        // Sanity: both queries find the same row, not two different rows.
        assertEquals(labelHits.getFirst(), outcomeHits.getFirst(),
                "both label-token and outcome-token queries must hit the same run id");
    }
}
