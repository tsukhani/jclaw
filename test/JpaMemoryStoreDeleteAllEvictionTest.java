import memory.JpaMemoryStore;
import memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.search.LuceneIndexer;

import java.util.Map;

/**
 * JCLAW-820: {@code deleteAll} evicts the agent's MEMORY-scope Lucene docs. The
 * bulk JPQL DELETE bypasses {@code @PostRemove}, so without the explicit
 * {@link LuceneIndexer#removeByAgent} pass the agent's FTS + HNSW vector docs
 * orphan. Runs on the real Lucene index (LuceneTestSync-serialized, JCLAW-428)
 * with vector memory on and canned embeddings, mirroring
 * {@code JpaMemoryStoreVectorTest}.
 */
class JpaMemoryStoreDeleteAllEvictionTest extends UnitTest {

    private static final String BERLIN = "The user lives in Berlin and enjoys hiking";
    private static final String FINANCE = "Quarterly finance report is due in October";
    /** Lexically matches NOTHING stored — only the KNN leg can recall via it. */
    private static final String NONSENSE_QUERY = "zebra quix flurble";

    /** Canned 4-dim embeddings; the query vector is near both memories. */
    private static final Map<String, float[]> EMBEDDINGS = Map.of(
            BERLIN, new float[] {1f, 0f, 0f, 0f},
            FINANCE, new float[] {0.95f, 0.05f, 0f, 0f},
            NONSENSE_QUERY, new float[] {0.9f, 0.1f, 0f, 0f});

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
    void deleteAllEvictsTheAgentsMemoryDocs() {
        var mine = agentId("evict-mine");
        var other = agentId("evict-other");
        store.store(mine, BERLIN, "fact", 0.6);
        store.store(other, FINANCE, "fact", 0.6);

        // Both agents are recallable before the delete.
        assertFalse(store.search(mine, NONSENSE_QUERY, 5).isEmpty(),
                "precondition: the target agent's memory is recallable");
        assertFalse(store.search(other, NONSENSE_QUERY, 5).isEmpty(),
                "precondition: the other agent's memory is recallable");

        var deleted = store.deleteAll(mine);

        assertEquals(1, deleted, "one Memory row removed for the target agent");
        // The contract is agent-isolated eviction: the deleted agent's MEMORY docs
        // (FTS + HNSW vector) become unrecallable while the other agent's survive.
        // Assert that by recall rather than exact Lucene doc counts, which straddle
        // two indexes (FTS + vector) with independent NRT-commit visibility and are
        // an implementation detail, not the contract. If removeByAgent over-deleted
        // across agents, the second assertion below catches it.
        assertTrue(store.search(mine, NONSENSE_QUERY, 5).isEmpty(),
                "deleteAll must evict the deleted agent's orphaned MEMORY docs");
        assertFalse(store.search(other, NONSENSE_QUERY, 5).isEmpty(),
                "another agent's memory must be untouched");
    }
}
