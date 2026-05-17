import models.Agent;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.h2.fulltext.FullTextLucene;
import play.db.DB;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;
import services.search.H2LuceneMessageSearchRepository;

import java.time.Instant;

/**
 * Live H2 + Lucene round-trip for
 * {@link H2LuceneMessageSearchRepository}. The autotest JVM runs
 * against an H2 in-memory database so {@code FullTextLucene}'s
 * trigger machinery is exercised end-to-end with real Lucene index
 * files (in the in-memory FS the H2 backend manages).
 *
 * <p>What's pinned:
 * <ul>
 *   <li>{@code init()} is idempotent — running it twice doesn't
 *       throw.</li>
 *   <li>Indexed rows are findable by single-term content match,
 *       case-insensitive.</li>
 *   <li>Trigger-driven incremental indexing works — rows inserted
 *       AFTER {@code init()} also become searchable without an
 *       operator-side reindex.</li>
 *   <li>Limit caps the result count.</li>
 *   <li>Blank / null query returns an empty list (the
 *       intentional non-exceptional path).</li>
 * </ul>
 *
 * <p>The dialect-selection logic in {@link services.search.MessageSearch}
 * isn't exercised here — that's a one-line product-name check,
 * better validated by the live-startup smoke than by a unit test
 * that has to mock the {@code DataSource}.
 */
class H2LuceneMessageSearchRepositoryTest extends UnitTest {

    private H2LuceneMessageSearchRepository repo;
    private Agent agent;
    private Task task;
    private TaskRun run;

    @BeforeEach
    void setup() throws Exception {
        // Drop any FT artifacts from a prior test class (defensive —
        // the boot job skips FT init in test mode, but if a prior
        // test in this class left state behind, dropAll resets it).
        dropFtSafely();
        Fixtures.deleteDatabase();
        repo = new H2LuceneMessageSearchRepository();
        // Must commit before init() so FullTextLucene sees the
        // task_run_message table from its own JDBC connection.
        commitAndReopen();
        repo.init();

        agent = persistAgent();
        task = persistTask(agent);
        run = persistRun(task);
        commitAndReopen();
    }

    @AfterEach
    void cleanup() {
        // Tear down the FT schema so the next test class's
        // Fixtures.deleteDatabase doesn't trip on FT.INDEXES (the
        // FullTextLucene housekeeping table) — Play 1.x's
        // deleteDatabase uses unqualified DELETE FROM and explodes
        // when a table in a non-default schema is in the metadata
        // list. See FullTextSearchInitJob class javadoc.
        dropFtSafely();
    }

    private void dropFtSafely() {
        try (var conn = DB.datasource.getConnection()) {
            FullTextLucene.dropAll(conn);
        } catch (Exception _) {
            // No FT state to drop — fine, this is just defensive cleanup.
        }
    }

    @Test
    void initIsIdempotent() throws Exception {
        // Already ran in setup; running again must not throw and
        // must not duplicate the trigger.
        repo.init();
    }

    @Test
    void findsRowByExactTerm() throws Exception {
        insertMessage(MessageRole.ASSISTANT, "The weather forecast is sunny today.");
        insertMessage(MessageRole.USER, "Tell me about the moon.");
        commitAndReopen();

        var results = repo.search("forecast", 10);
        assertEquals(1, results.size(), "expected 1 hit for 'forecast'");
        assertTrue(results.getFirst().content.contains("forecast"));
    }

    @Test
    void findsRowsAddedAfterInit() throws Exception {
        // Trigger-driven incremental indexing — the FullTextLucene
        // triggers fire on INSERT and the row becomes searchable
        // without re-running init() or operator-side reindex.
        insertMessage(MessageRole.ASSISTANT, "Octopuses have three hearts.");
        commitAndReopen();

        var results = repo.search("octopuses", 10);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().content.contains("Octopuses"));
    }

    @Test
    void caseInsensitiveMatch() throws Exception {
        insertMessage(MessageRole.ASSISTANT, "QUARTZ scheduler runs out of CPU under load.");
        commitAndReopen();

        var lowered = repo.search("quartz", 10);
        assertEquals(1, lowered.size(),
                "Lucene's standard analyser lowercases — 'quartz' should find 'QUARTZ'");
    }

    @Test
    void limitCapsResults() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertMessage(MessageRole.ASSISTANT, "Match number " + i + " for keyword zebra.");
        }
        commitAndReopen();

        var capped = repo.search("zebra", 2);
        assertEquals(2, capped.size(), "limit=2 should cap to 2 results");
    }

    @Test
    void blankQueryReturnsEmptyList() throws Exception {
        insertMessage(MessageRole.ASSISTANT, "Some indexed content.");
        commitAndReopen();
        assertTrue(repo.search("", 10).isEmpty());
        assertTrue(repo.search("   ", 10).isEmpty());
        assertTrue(repo.search(null, 10).isEmpty());
    }

    @Test
    void noMatchReturnsEmptyList() throws Exception {
        insertMessage(MessageRole.ASSISTANT, "Some indexed content about cats.");
        commitAndReopen();
        assertTrue(repo.search("xylophone", 10).isEmpty());
    }

    @Test
    void resultsAreFullTaskRunMessageRows() throws Exception {
        // Spot-check that the row hydration carries the expected
        // columns — not just id, but role / content / taskRun
        // reference. Catches a regression where the IN-query was
        // tightened to projecting just one column.
        insertMessage(MessageRole.ASSISTANT, "Pangolins are mammals with scales.");
        commitAndReopen();

        var results = repo.search("pangolins", 10);
        assertEquals(1, results.size());
        var hit = results.getFirst();
        assertEquals(MessageRole.ASSISTANT, hit.role, "role survives hydration");
        assertNotNull(hit.taskRun, "taskRun FK populated");
        assertEquals(run.id, hit.taskRun.id);
    }

    @Test
    void dialectNameReportsH2() {
        assertEquals("h2", repo.dialectName());
    }

    // === Helpers ===

    private Agent persistAgent() {
        var a = new Agent();
        a.name = "fts-test-agent";
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private Task persistTask(Agent agent) {
        var t = new Task();
        t.agent = agent;
        t.name = "fts-test-task";
        t.description = "Test task for full-text search";
        t.type = Task.Type.IMMEDIATE;
        t.status = Task.Status.PENDING;
        t.nextRunAt = Instant.now();
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
    }

    private TaskRun persistRun(Task task) {
        var r = new TaskRun();
        r.task = task;
        r.startedAt = Instant.now();
        r.status = TaskRun.Status.RUNNING;
        r.save();
        return r;
    }

    private int turnIndex = 0;

    private void insertMessage(MessageRole role, String content) {
        var m = new TaskRunMessage();
        m.taskRun = run;
        m.turnIndex = turnIndex++;
        m.role = role;
        m.content = content;
        m.save();
    }

    private void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
