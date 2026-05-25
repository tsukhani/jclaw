import org.junit.jupiter.api.*;
import play.test.*;
import play.db.DB;
import org.h2.fulltext.FullTextLucene;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import services.search.LuceneIndexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Functional HTTP tests for {@code GET /api/task-runs/search} (JCLAW-294
 * commit 8). Routes through services.search.MessageSearch which dispatches
 * to H2 FullTextLucene under the test DB; tests both the empty/error paths
 * and the live-trigger indexing of newly inserted messages.
 */
class ApiTasksControllerSearchTest extends FunctionalTest {

    private static Path testIndexParent;

    @BeforeAll
    static void redirectLuceneIndex() throws Exception {
        // Redirect Lucene to a per-test-class temp directory so the
        // autotest JVM never opens the production index's FSDirectory.
        // Without this, a running production JClaw against the same
        // checkout holds data/jclaw-lucene/task_run_message/write.lock
        // and our MessageSearch.init() below crashes with
        // LockObtainFailedException.
        testIndexParent = Files.createTempDirectory("jclaw-lucene-test-");
        // JCLAW-304: setIndexPathForTest now takes the index root —
        // each scope's subdirectory (e.g. task_run_message/) is appended
        // by LuceneIndexer.indexPath(Scope) at open() time. Passing the
        // scope subpath explicitly here would double-resolve to
        // root/task_run_message/task_run_message/.
        LuceneIndexer.setIndexPathForTest(testIndexParent);
    }

    @AfterAll
    static void restoreLuceneIndex() throws Exception {
        LuceneIndexer.close();
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
        // Drop any FT state left from a prior test so Play's
        // Fixtures.deleteDatabase doesn't trip on the FT.INDEXES
        // housekeeping table — see H2LuceneMessageSearchRepositoryTest
        // for the same dance.
        dropFtSafely();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
        // FullTextSearchInitJob skips init in test mode; this test
        // wants the live triggers + Lucene index, so init explicitly.
        services.search.MessageSearch.init();
    }

    @AfterEach
    void cleanup() {
        // Tear down so the NEXT test class's Fixtures.deleteDatabase
        // doesn't blow up on FT.INDEXES.
        dropFtSafely();
    }

    private static void dropFtSafely() {
        try (var conn = DB.getDataSource().getConnection()) {
            FullTextLucene.dropAll(conn);
        } catch (Exception _) {
            // No FT state to drop — fine, defensive cleanup only.
        }
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "task-search-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name) {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "%s", "schedule": "1d"}
                """.formatted(agentId, name));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    /**
     * Seed a TaskRun + N TaskRunMessage rows in a fresh tx so H2's
     * FullTextLucene triggers fire on commit (the carrier-thread tx
     * never commits, so messages seeded inline aren't indexed yet
     * when the HTTP search hits the index).
     */
    private static void seedTranscript(Long taskId, String... contents) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var run = new TaskRun();
                    run.task = task;
                    run.startedAt = Instant.now();
                    run.completedAt = Instant.now();
                    run.durationMs = 100L;
                    run.status = TaskRun.Status.COMPLETED;
                    run.save();
                    for (String c : contents) {
                        var m = new TaskRunMessage();
                        m.taskRun = run;
                        m.role = MessageRole.ASSISTANT;
                        m.content = c;
                        m.save();
                    }
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    @Test
    void emptyQueryReturnsEmptyArray() {
        var resp = GET("/api/task-runs/search?q=");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void missingQueryReturnsEmptyArray() {
        var resp = GET("/api/task-runs/search");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void findsMatchingMessages() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "search-task");
        seedTranscript(taskId,
                "the quick brown fox jumps over the lazy dog",
                "completely unrelated content about cats");

        var resp = GET("/api/task-runs/search?q=fox");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("the quick brown fox"),
                "expected the fox message in results; got: " + body);
        assertFalse(body.contains("about cats"),
                "non-matching message should not appear; got: " + body);
    }

    @Test
    void noResultsReturnsEmptyArray() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "noresults-task");
        seedTranscript(taskId, "some content here");

        var resp = GET("/api/task-runs/search?q=zzzznomatchzzzz");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void includesParentTaskContext() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "context-task");
        seedTranscript(taskId, "unique-keyword-banana");

        var resp = GET("/api/task-runs/search?q=unique-keyword-banana");
        assertIsOk(resp);
        var body = getContent(resp);
        // Hit carries the parent Task name + Agent name + the run id, so the
        // monitoring UI can render a "found in task X by agent Y" link.
        assertTrue(body.contains("\"taskName\":\"context-task\""), body);
        assertTrue(body.contains("\"agentName\":\"task-search-agent\""), body);
        assertTrue(body.contains("\"taskRunId\":"), body);
    }

    @Test
    void respectsLimitParam() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "limit-task");
        // Seed 5 messages all matching "marker"
        seedTranscript(taskId,
                "marker one", "marker two", "marker three",
                "marker four", "marker five");

        var resp = GET("/api/task-runs/search?q=marker&limit=2");
        assertIsOk(resp);
        var body = getContent(resp);
        int hits = body.split("\"messageId\":", -1).length - 1;
        assertEquals(2, hits, "limit=2 should return exactly 2 results; body=" + body);
    }
}
