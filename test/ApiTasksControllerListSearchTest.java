import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Task;
import services.AgentService;
import services.Tx;
import services.search.LuceneIndexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * JCLAW-328: acceptance coverage for the {@code q:} keyword path on
 * {@code GET /api/tasks}. Distinct from
 * {@link ApiTasksControllerSearchTest} — that suite covers the
 * pre-JCLAW-304 {@code /api/task-runs/search} endpoint (transcript
 * search via {@link LuceneIndexer.Scope#TASK_RUN_MESSAGE}); this suite
 * covers the new task-list q parameter that resolves against
 * {@link LuceneIndexer.Scope#TASK}'s name + description virtual document.
 *
 * <p>Two cases pinned, mirroring the ticket's AC:
 * <ul>
 *   <li>{@link #searchByQueryFindsNameAndDescription} — a keyword in
 *       either Task.name or Task.description matches; neither matches
 *       a task that lacks the keyword in both fields.</li>
 *   <li>{@link #searchIntersectsWithStatusFilter} — q + status together
 *       is AND-semantics.</li>
 * </ul>
 */
class ApiTasksControllerListSearchTest extends FunctionalTest {

    private static Path testIndexParent;

    @BeforeAll
    static void redirectLuceneIndex() throws Exception {
        testIndexParent = Files.createTempDirectory("jclaw-lucene-tasks-test-");
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
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
        LuceneIndexer.close();
        services.search.MessageSearch.init();
        wipeIndex();
    }

    private static void wipeIndex() throws Exception {
        var fld = LuceneIndexer.class.getDeclaredField("WRITERS");
        fld.setAccessible(true);
        @SuppressWarnings("unchecked")
        var writers = (java.util.Map<LuceneIndexer.Scope, org.apache.lucene.index.IndexWriter>) fld.get(null);
        for (var w : writers.values()) {
            w.deleteAll();
            w.commit();
        }
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    void searchByQueryFindsNameAndDescription() {
        // Three tasks: keyword in name only, keyword in description only,
        // keyword in neither. The TASK Lucene scope indexes
        // {@code name + " " + description} as a single virtual doc, so
        // a query for the keyword must match both row 1 and row 2 but
        // not row 3.
        var ids = commitInFreshTx(() -> {
            var agent = AgentService.create("task-search-agent", "openrouter", "gpt-4.1");
            var inName = persistTask(agent, "summarytasktoken-cron", "unrelated description");
            var inDesc = persistTask(agent, "plain-name", "this is a summarytasktoken in description");
            var neither = persistTask(agent, "plain-name-2", "unrelated description");
            return new long[]{inName, inDesc, neither};
        });

        var resp = GET("/api/tasks?q=summarytasktoken");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "task with keyword in name must match: " + body);
        assertTrue(body.contains("\"id\":" + ids[1]),
                "task with keyword in description must match: " + body);
        assertFalse(body.contains("\"id\":" + ids[2]),
                "task with neither must be excluded: " + body);
    }

    @Test
    void searchIntersectsWithStatusFilter() {
        // Two tasks both containing the keyword, one PENDING and one
        // ACTIVE. q + status=PENDING returns only the PENDING row,
        // proving the intersection rather than the q result overriding
        // the status equality predicate.
        var ids = commitInFreshTx(() -> {
            var agent = AgentService.create("task-search-agent-2", "openrouter", "gpt-4.1");
            // PENDING is the default for IMMEDIATE tasks.
            var pending = persistTask(agent, "intersecttoken-immediate", "matches keyword");
            // ACTIVE is the default for CRON / INTERVAL tasks.
            var active = new Task();
            active.agent = agent;
            active.name = "intersecttoken-cron";
            active.description = "matches keyword";
            active.type = Task.Type.CRON;
            active.status = Task.Status.ACTIVE;
            active.cronExpression = "0 0 9 * * *";
            active.scheduledAt = Instant.now();
            active.nextRunAt = Instant.now();
            active.save();
            return new long[]{pending, active.id};
        });

        var resp = GET("/api/tasks?q=intersecttoken&status=PENDING");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "PENDING task must be present: " + body);
        assertFalse(body.contains("\"id\":" + ids[1]),
                "ACTIVE task must be filtered out by status=PENDING: " + body);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static long persistTask(Agent agent, String name, String description) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = description;
        t.type = Task.Type.IMMEDIATE;
        t.status = Task.Status.PENDING;
        t.scheduledAt = Instant.now();
        t.nextRunAt = Instant.now();
        t.save();
        return t.id;
    }

    private static long[] commitInFreshTx(Supplier<long[]> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<long[]>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
