import memory.JpaMemoryStore;
import memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;

/**
 * JCLAW-555: store-then-recall through the Lucene HNSW vector backend — the
 * dialect the bundled H2 (all dev/test, default prod) routes to. Uses the real
 * Lucene index (LuceneTestSync-serialized, JCLAW-428) and canned embeddings via
 * the {@link JpaMemoryStore#setEmbedderForTest} seam, so no LLM provider is
 * needed. The store is constructed directly with {@code (vectorEnabled=true,
 * isPostgres=false)} instead of flipping process-global config — the play1 test
 * engine runs unit + functional lanes concurrently.
 */
class JpaMemoryStoreVectorTest extends UnitTest {

    private static final String BERLIN = "The user lives in Berlin and enjoys hiking";
    private static final String VIM = "Favourite editor is Vim with a dark theme";
    private static final String FINANCE = "Quarterly finance report is due in October";
    /** Lexically matches NOTHING stored — only the KNN leg can recall via it. */
    private static final String NONSENSE_QUERY = "zebra quix flurble";
    /** Lexically overlaps the Berlin memory — exercises FTS + KNN fusion. */
    private static final String BERLIN_QUERY = "hiking in Berlin";

    /**
     * Canned 4-dim embeddings. Berlin-ish vectors cluster on the first axis,
     * Vim on the second. The nonsense query is deliberately near Berlin so a
     * hit proves the vector leg (no lexical overlap exists). Unmapped text
     * returns null — the "embedding unavailable" degradation path.
     */
    private static final Map<String, float[]> EMBEDDINGS = Map.of(
            BERLIN, new float[] {1f, 0f, 0f, 0f},
            VIM, new float[] {0f, 1f, 0f, 0f},
            FINANCE, new float[] {0.95f, 0.05f, 0f, 0f},
            NONSENSE_QUERY, new float[] {0.9f, 0.1f, 0f, 0f},
            BERLIN_QUERY, new float[] {0.85f, 0.15f, 0f, 0f});

    private MemoryStore store;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        JpaMemoryStore.setEmbedderForTest(EMBEDDINGS::get);
        store = new JpaMemoryStore(true, false);
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

    @Test
    void knnRecallsSemanticMatchWithZeroLexicalOverlap() {
        var agent = agentId("vec-knn");
        store.store(agent, BERLIN, "fact", 0.6);
        store.store(agent, VIM, "preference", 0.6);

        var results = store.search(agent, NONSENSE_QUERY, 5);

        // No stored text contains any query token — only the KNN leg can match.
        assertFalse(results.isEmpty(), "vector leg must recall despite zero lexical overlap");
        assertEquals(BERLIN, results.getFirst().text(),
                "the cosine-nearest memory must rank first");
    }

    @Test
    void hybridFusionRanksTheDoublyMatchedMemoryFirst() {
        var agent = agentId("vec-fuse");
        store.store(agent, BERLIN, "fact", 0.6);
        store.store(agent, VIM, "preference", 0.6);

        var results = store.search(agent, BERLIN_QUERY, 5);

        assertFalse(results.isEmpty());
        assertEquals(BERLIN, results.getFirst().text(),
                "memory matched by both FTS and KNN legs must fuse to the top");
        assertEquals(1.0, results.getFirst().relevance(), 1e-9,
                "fused relevance is top-normalized (JCLAW-532 contract)");
        // KNN returns the k nearest regardless of distance, so Vim rides along
        // as a lower-ranked hit — verify it never outranks the double match.
        if (results.size() > 1) {
            assertEquals(VIM, results.get(1).text());
            assertTrue(results.get(1).relevance() < 1.0);
        }
    }

    @Test
    void recallNeverCrossesAgents() {
        var mine = agentId("vec-mine");
        var other = agentId("vec-other");
        store.store(mine, BERLIN, "fact", 0.6);
        // FINANCE's vector is deliberately the closest to the nonsense query —
        // if the agent filter leaked, it would win the KNN ranking.
        store.store(other, FINANCE, "fact", 0.9);

        var results = store.search(mine, NONSENSE_QUERY, 5);

        assertFalse(results.isEmpty());
        assertTrue(results.stream().noneMatch(e -> e.text().equals(FINANCE)),
                "another agent's memory must never surface (per-agent privacy)");
        assertEquals(BERLIN, results.getFirst().text());
    }

    @Test
    void unavailableQueryEmbeddingDegradesToTextSearch() {
        var agent = agentId("vec-degrade");
        store.store(agent, BERLIN, "fact", 0.6);

        // "Berlin" is not in the canned map → null embedding → FTS/LIKE path.
        var results = store.search(agent, "Berlin", 5);

        assertEquals(1, results.size(), "text search must still recall when embeddings are unavailable");
        assertEquals(BERLIN, results.getFirst().text());
    }

    @Test
    void storeOnH2WithVectorEnabledNeverTouchesPgvectorSql() {
        // The pre-555 gap: the raw `::vector` UPDATE ran on any dialect when the
        // flag was on, failing on every H2 store. Post-555 the H2 branch indexes
        // into Lucene instead — a successful store with a mapped embedding plus
        // a successful KNN recall is the positive proof the path never needs
        // Postgres syntax.
        var agent = agentId("vec-h2");
        var id = store.store(agent, BERLIN, "fact", 0.6);
        assertNotNull(id);
        assertEquals(1, store.list(agent).size());
        assertFalse(store.search(agent, NONSENSE_QUERY, 5).isEmpty());
    }
}
