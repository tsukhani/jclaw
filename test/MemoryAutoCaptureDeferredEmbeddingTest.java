import memory.JpaMemoryStore;
import memory.MemoryAutoCapture;
import memory.MemoryStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import utils.CircuitBreaker;

import java.util.Map;

/**
 * JCLAW-807-follow-up: the auto-capture apply phase must persist the survivor rows
 * inside its write tx but generate + write their (blocking) vector embeddings
 * AFTER that tx commits — so a pooled DB connection is never pinned across the
 * embedding HTTP round-trip. This drives {@link MemoryAutoCapture#capture} through
 * a real vector-enabled {@link JpaMemoryStore} (Lucene HNSW backend, canned
 * embeddings via the {@code setEmbedderForTest} seam) pinned behind
 * {@link MemoryStoreFactory}, and asserts the row is both persisted AND ultimately
 * embedded — proof the deferred post-commit pass ran. LuceneTestSync-serialized
 * (JCLAW-428): every {@code MemoryStoreFactory}-touching test holds that JVM-global
 * lock, so the pinned store is never observed by a sibling test.
 */
class MemoryAutoCaptureDeferredEmbeddingTest extends UnitTest {

    private static final String FACT = "The user relocated to Lisbon in March 2026";
    /** Zero lexical overlap with FACT — only a written vector can recall via it. */
    private static final String KNN_QUERY = "zonk qux flurble widgets";

    private static final Map<String, float[]> EMBEDDINGS = Map.of(
            FACT, new float[] {1f, 0f, 0f, 0f},
            KNN_QUERY, new float[] {0.95f, 0.05f, 0f, 0f});

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        JpaMemoryStore.setEmbedderForTest(EMBEDDINGS::get);
        // Vector-enabled Lucene-dialect store (not process-global config) so capture()
        // exercises the deferred embedding path end to end.
        MemoryStoreFactory.setForTest(new JpaMemoryStore(true, false));
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        MemoryStoreFactory.reset();
        LuceneTestSync.release();
    }

    private CircuitBreaker freshBreaker() {
        return new CircuitBreaker(20, 0.5, 5, 30_000L);
    }

    /** Create a real agent — memories carry a real FK (JCLAW-537). */
    private String agentId(String name) {
        var a = new models.Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return String.valueOf(a.id);
    }

    @Test
    void captureStoresTheRowAndEmbedsItAfterTheWriteTx() {
        var agent = agentId("cap-defer");
        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"" + FACT + "\",\"category\":\"fact\",\"importance\":0.6}]}";

        var result = MemoryAutoCapture.capture(agent, "cap-defer",
                "I just moved to Lisbon in March for a new role", "Noted your move.",
                extractor, freshBreaker());

        assertEquals(1, result.captured(), "the survivor row must be persisted");
        var store = MemoryStoreFactory.get();
        assertEquals(1, store.list(agent).size());
        // KNN_QUERY has zero lexical overlap with FACT, so recall via it can only come
        // from a written vector — proof the post-commit embedding pass ran (the embedding
        // was neither skipped nor left deferred).
        var recalled = store.search(agent, KNN_QUERY, 5);
        assertFalse(recalled.isEmpty(), "the deferred embedding pass must ultimately write the vector");
        assertEquals(FACT, recalled.getFirst().text());
    }
}
