import memory.JpaMemoryStore;
import memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-555: the Postgres arms of the dialect split, exercised on the H2 test
 * database via the test-visible {@code JpaMemoryStore(vectorEnabled, isPostgres)}
 * constructor — never by flipping process-global config (the play1 test engine
 * runs unit + functional lanes concurrently).
 *
 * <p>What is testable here is the <b>degradation contract</b>: every PG-only
 * SQL construct ({@code to_tsvector}, {@code ::vector}) fails on H2, and the
 * store must catch, warn, and fall back — hybrid → FTS → LIKE for recall, and
 * a swallowed warning for the pgvector embedding write — so a prod-mode boot
 * on the bundled H2 (or a transient PG error) never breaks memory. The
 * <b>success</b> paths of those queries need live Postgres + pgvector and are
 * covered there, not here; the rank-position scorer they share is pinned via
 * reflection instead.
 *
 * <p>The index is forced closed (LuceneTestSync, JCLAW-428) so the terminal
 * LIKE fallback is the deterministic agent-bounded substring scan.
 */
class JpaMemoryStorePgDialectTest extends UnitTest {

    private static final String BERLIN = "The user lives in Berlin and enjoys hiking";
    private static final String VIM = "Favourite editor is Vim with a dark theme";
    private static final float[] CANNED_VEC = {1f, 0f, 0f, 0f};

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        LuceneTestSync.release();
    }

    /** Create a real agent (memories carry a real FK, JCLAW-537). */
    private String agentId(String name) {
        var a = new models.Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return String.valueOf(a.id);
    }

    // --- fullTextSearch (vector off, isPostgres=true) ---

    @Test
    void fullTextDialectDegradesToLikeSearchWhenPgFtsSqlFails() {
        var store = new JpaMemoryStore(false, true);
        var agent = agentId("pg-fts");
        store.store(agent, BERLIN, "fact", 0.6);
        store.store(agent, VIM, "preference", 0.6);

        // to_tsvector/plainto_tsquery don't exist on H2 → the native query
        // throws → the catch must degrade to the agent-bounded LIKE scan.
        var results = store.search(agent, "berlin", 5);

        assertEquals(1, results.size(), "LIKE fallback must still recall the match");
        assertEquals(BERLIN, results.getFirst().text());
    }

    @Test
    void fullTextSearchReturnsEmptyForNonNumericAgentId() {
        var store = new JpaMemoryStore(false, true);
        assertTrue(store.search("not-a-pk", "anything", 5).isEmpty(),
                "the pk guard must return empty before any SQL is attempted");
    }

    // --- hybridSearch (vector on, isPostgres=true) ---

    @Test
    void hybridSearchDegradesThroughFtsToLikeWhenPgvectorSqlFails() {
        // Query embedding IS available — the pgvector hybrid SQL is reached,
        // fails on H2, falls to PG FTS, which also fails, landing on LIKE.
        JpaMemoryStore.setEmbedderForTest(Map.of("berlin", CANNED_VEC)::get);
        var store = new JpaMemoryStore(true, true);
        var agent = agentId("pg-hybrid");
        store.store(agent, BERLIN, "fact", 0.6);
        store.store(agent, VIM, "preference", 0.6);

        var results = store.search(agent, "berlin", 5);

        assertEquals(1, results.size(),
                "double fallback (hybrid → FTS → LIKE) must still recall");
        assertEquals(BERLIN, results.getFirst().text());
    }

    @Test
    void hybridSearchWithUnavailableEmbeddingSkipsStraightToFts() {
        // Null query embedding → the vector leg is never attempted; the
        // FTS branch (which fails on H2) degrades to LIKE.
        JpaMemoryStore.setEmbedderForTest(_ -> null);
        var store = new JpaMemoryStore(true, true);
        var agent = agentId("pg-hybrid-noembed");
        store.store(agent, BERLIN, "fact", 0.6);

        var results = store.search(agent, "berlin", 5);

        assertEquals(1, results.size(),
                "recall must survive an unavailable embeddings endpoint");
        assertEquals(BERLIN, results.getFirst().text());
    }

    @Test
    void hybridSearchReturnsEmptyForNonNumericAgentId() {
        JpaMemoryStore.setEmbedderForTest(_ -> CANNED_VEC);
        var store = new JpaMemoryStore(true, true);
        assertTrue(store.search("not-a-pk", "anything", 5).isEmpty(),
                "the pk guard must return empty before embedding or SQL work");
    }

    // --- store (vector on, isPostgres=true) ---

    @Test
    void storeSurvivesPgvectorEmbeddingWriteFailure() {
        // The raw `?::text::vector` UPDATE cannot run on H2. The write must be
        // caught and warned — the memory row itself must persist regardless
        // (an embedding failure must never lose the memory).
        JpaMemoryStore.setEmbedderForTest(_ -> CANNED_VEC);
        var store = new JpaMemoryStore(true, true);
        var agent = agentId("pg-store");

        var id = store.store(agent, BERLIN, "fact", 0.7);

        assertNotNull(id);
        var all = store.list(agent);
        assertEquals(1, all.size());
        assertEquals(id, all.getFirst().id());
        assertEquals(BERLIN, all.getFirst().text());
        assertEquals(0.7, all.getFirst().importance(), 1e-9);
    }

    @Test
    void storeSkipsPgvectorWriteWhenEmbeddingUnavailable() {
        // Null embedding → the pgvector UPDATE is never attempted; the row
        // still persists (the null-guard branch, not the exception branch).
        JpaMemoryStore.setEmbedderForTest(_ -> null);
        var store = new JpaMemoryStore(true, true);
        var agent = agentId("pg-store-noembed");

        var id = store.store(agent, VIM, "preference", 0.5);

        assertNotNull(id);
        assertEquals(1, store.list(agent).size());
    }

    // --- vector-leg SQL shape (JCLAW-527) ---

    @Test
    void pgVectorLegKeepsTheBareOrderByShapeHnswCanServe() {
        // The AC pins the query shape: a bare `ORDER BY embedding <=> ?` the
        // planner can serve from the JCLAW-528 HNSW index. The old hybrid
        // wrapped the ORDER BY in a ts_rank/cosine weighted sum, forcing a
        // sequential scan — executable only on live Postgres, so the shape is
        // pinned here the same way PgVectorProvisionerTest pins its DDL.
        var sql = JpaMemoryStore.PG_VECTOR_LEG_SQL;
        assertTrue(sql.contains("ORDER BY m.embedding <=> ?2::text::vector"),
                "the ORDER BY must be the bare cosine-distance operator");
        assertFalse(sql.contains("ts_rank"),
                "keyword scoring must not re-enter the vector leg (RRF fuses the legs instead)");
        assertFalse(sql.contains("+"),
                "no weighted-sum arithmetic may wrap the distance expression");
    }

    // --- toEntriesRankScored (shared PG-success scorer, JCLAW-532) ---

    private static Method rankScorer() throws Exception {
        var m = JpaMemoryStore.class.getDeclaredMethod("toEntriesRankScored", List.class);
        m.setAccessible(true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<MemoryStore.MemoryEntry> rankScore(JpaMemoryStore store,
                                                           List<models.Memory> rows) throws Exception {
        return (List<MemoryStore.MemoryEntry>) rankScorer().invoke(store, rows);
    }

    @Test
    void rankScoredRelevanceDescendsLinearlyFromTopByPosition() throws Exception {
        // The PG FTS/hybrid queries return rows already score-ordered but
        // without a cheap raw score; relevance is approximated from rank
        // position (top = 1.0, bottom = 0.0). Only reachable end-to-end on
        // live Postgres, so the scorer is pinned directly (house reflection
        // idiom, see JpaMemoryStoreTest).
        var store = new JpaMemoryStore(false, true);
        var agent = agentId("pg-rank");
        store.store(agent, "row one", "fact", 0.6);
        store.store(agent, "row two", "fact", 0.6);
        store.store(agent, "row three", "fact", 0.6);
        var rows = models.Memory.findByAgent(agent);
        assertEquals(3, rows.size());

        var entries = rankScore(store, rows);

        assertEquals(3, entries.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(rows.get(i).id.toString(), entries.get(i).id(),
                    "input ordering (the DB score order) must be preserved");
        }
        assertEquals(1.0, entries.get(0).relevance(), 1e-9);
        assertEquals(0.5, entries.get(1).relevance(), 1e-9);
        assertEquals(0.0, entries.get(2).relevance(), 1e-9);
    }

    @Test
    void rankScoredSingleRowGetsTopRelevanceAndEmptyStaysEmpty() throws Exception {
        var store = new JpaMemoryStore(false, true);
        var agent = agentId("pg-rank-one");
        store.store(agent, "only row", "fact", 0.6);
        var rows = models.Memory.findByAgent(agent);
        assertEquals(1, rows.size());

        var single = rankScore(store, rows);
        assertEquals(1, single.size());
        assertEquals(1.0, single.getFirst().relevance(), 1e-9,
                "a lone hit must not divide by zero — it is simply the top hit");

        assertTrue(rankScore(store, List.of()).isEmpty());
    }
}
