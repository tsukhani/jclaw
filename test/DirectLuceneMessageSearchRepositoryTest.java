import models.Agent;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.LuceneIndexer;
import services.search.MessageSearchTestHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

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

    private static Path testIndexParent;
    private DirectLuceneMessageSearchRepository repo;

    @BeforeAll
    static void allOnce() throws Exception {
        // Boot job skips Lucene init in test mode, so the indexer is
        // closed by default. Open it pointing at a per-test-class temp
        // directory so we don't share state — and don't fight the
        // write.lock — with a production JVM running against the same
        // checkout.
        testIndexParent = Files.createTempDirectory("jclaw-lucene-test-");
        // JCLAW-304: setIndexPathForTest now takes the index root —
        // each scope's subdirectory (e.g. task_run_message/) is appended
        // by LuceneIndexer.indexPath(Scope) at open() time. Passing the
        // scope subpath explicitly here would double-resolve to
        // root/task_run_message/task_run_message/.
        LuceneIndexer.setIndexPathForTest(testIndexParent);
    }

    @AfterAll
    static void allCleanup() throws Exception {
        LuceneIndexer.close();
        // Clear the override BEFORE the directory delete so a stray
        // re-open during cleanup can't lock the temp tree we're about
        // to remove (no production code re-opens here, but the order
        // documents intent for future readers).
        LuceneIndexer.setIndexPathForTest(null);
        if (testIndexParent != null && Files.exists(testIndexParent)) {
            try (Stream<Path> walk = Files.walk(testIndexParent)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) { /* best-effort */ }
                });
            }
        }
    }

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        // Force a clean indexer state per test. Closing + reopening rebuilds
        // the SearcherManager too.
        LuceneIndexer.close();
        LuceneIndexer.open();
        wipeIndex();
        repo = new DirectLuceneMessageSearchRepository();
        MessageSearchTestHooks.setRepository(repo);
    }

    @AfterEach
    void teardown() {
        MessageSearchTestHooks.setRepository(null);
    }

    private static void wipeIndex() throws Exception {
        // Delete-all keeps the FSDirectory open but clears prior docs.
        // Cheaper than close/delete/reopen between tests. Post JCLAW-304
        // the indexer holds a per-scope EnumMap<Scope, IndexWriter>; wipe
        // every scope so leftover docs from a different test class can't
        // contaminate.
        var fld = LuceneIndexer.class.getDeclaredField("WRITERS");
        fld.setAccessible(true);
        @SuppressWarnings("unchecked")
        var writers = (java.util.Map<LuceneIndexer.Scope, org.apache.lucene.index.IndexWriter>) fld.get(null);
        for (var w : writers.values()) {
            w.deleteAll();
            w.commit();
        }
    }

    private static Long commitInFreshTx(java.util.function.Supplier<Long> block) {
        var ref = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
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
}
