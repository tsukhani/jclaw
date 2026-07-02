import memory.JpaMemoryStore;
import memory.MemoryReranker;
import memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-527: the optional cross-encoder rerank wired through hybrid recall.
 * Runs on the Lucene HNSW backend — the same {@code fuseHydrateRerank} path
 * the Postgres backend lands on, so this is the shared-contract AC exercised
 * end-to-end. Canned embeddings via {@code setEmbedderForTest} and a canned
 * rank call via {@code setRankCallForTest}; installing the rank override makes
 * {@code MemoryReranker.active()} true, which is exactly the production
 * {@code memory.rerank.enabled} switch without flipping process-global config.
 * Both overrides are only safe under the LuceneTestSync lock (JCLAW-428).
 */
class JpaMemoryStoreRerankTest extends UnitTest {

    private static final String BERLIN = "The user lives in Berlin and enjoys hiking";
    private static final String VIM = "Favourite editor is Vim with a dark theme";
    private static final String FINANCE = "Quarterly finance report is due in October";
    /** Zero lexical overlap with any memory — recall is KNN-leg-only. */
    private static final String QUERY = "zebra quix flurble";
    /**
     * Lexically matches only the Vim memory (the unit lane's FTS leg is the
     * substring-LIKE fallback) while its vector points at Berlin/finance —
     * makes the two legs disagree so their union exceeds a small limit.
     */
    private static final String VIM_NEAR_BERLIN_QUERY = "Vim";

    /**
     * Cosine order for both query vectors: FINANCE (≈.998), BERLIN (≈.994),
     * VIM (≈.110) — the fused order the rerank then overrules.
     */
    private static final Map<String, float[]> EMBEDDINGS = Map.of(
            BERLIN, new float[] {1f, 0f, 0f, 0f},
            VIM, new float[] {0f, 1f, 0f, 0f},
            FINANCE, new float[] {0.95f, 0.05f, 0f, 0f},
            QUERY, new float[] {0.9f, 0.1f, 0f, 0f},
            VIM_NEAR_BERLIN_QUERY, new float[] {0.9f, 0.1f, 0f, 0f});

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
        MemoryReranker.setRankCallForTest(null);
        JpaMemoryStore.setEmbedderForTest(null);
        LuceneTestSync.release();
    }

    private String seedThreeMemories() {
        var a = new models.Agent();
        a.name = "rerank-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        var agent = String.valueOf(a.id);
        store.store(agent, BERLIN, "fact", 0.6);
        store.store(agent, VIM, "preference", 0.6);
        store.store(agent, FINANCE, "fact", 0.6);
        return agent;
    }

    @Test
    void rerankReordersTheFusedShortlistAndRescoresByRank() {
        var agent = seedThreeMemories();
        var prompt = new AtomicReference<String>();
        MemoryReranker.setRankCallForTest(msgs -> {
            prompt.set(String.valueOf(msgs.getLast().content()));
            return "[2,1,0]";   // exact reverse of the fused order
        });

        var results = store.search(agent, QUERY, 5);

        assertEquals(3, results.size());
        assertEquals(VIM, results.get(0).text(), "the rerank's top pick must lead");
        assertEquals(BERLIN, results.get(1).text());
        assertEquals(FINANCE, results.get(2).text(), "the fused winner drops to last");
        assertEquals(1.0, results.get(0).relevance(), 1e-9,
                "rerank output carries rank-derived relevance, top = 1.0");
        assertEquals(0.5, results.get(1).relevance(), 1e-9);
        assertEquals(0.0, results.get(2).relevance(), 1e-9);
        // The cross-encoder saw the query and the full shortlist.
        assertTrue(prompt.get().contains(QUERY), "rank prompt must carry the query");
        assertTrue(prompt.get().contains(BERLIN) && prompt.get().contains(VIM)
                && prompt.get().contains(FINANCE), "rank prompt must carry every candidate");
    }

    @Test
    void rerankRunsOverTheFullShortlistBeforeTheLimitCut() {
        var agent = seedThreeMemories();
        MemoryReranker.setRankCallForTest(_ -> "[2,0,1]");

        // Limit 2 with disagreeing legs: FTS matches only VIM, KNN's top-2 are
        // FINANCE then BERLIN. Union = 3 candidates for a limit of 2; fused
        // order is VIM, FINANCE (rank-1 ties, broken by id), then BERLIN.
        // BERLIN — fused LAST — can only win if the rerank sees the full
        // 3-candidate shortlist before the limit cut.
        var results = store.search(agent, VIM_NEAR_BERLIN_QUERY, 2);

        assertEquals(2, results.size(), "the caller's limit still caps the output");
        assertEquals(BERLIN, results.get(0).text(),
                "a fused-last candidate promoted by the rerank must survive the cut");
        assertEquals(VIM, results.get(1).text());
    }

    @Test
    void failingRankCallFailsOpenToTheFusedOrder() {
        var agent = seedThreeMemories();
        MemoryReranker.setRankCallForTest(_ -> {
            throw new IllegalStateException("rerank model unreachable");
        });

        var results = store.search(agent, QUERY, 5);

        assertEquals(3, results.size(), "a rerank failure must never lose results");
        assertEquals(FINANCE, results.get(0).text(), "fused (KNN) order stands");
        assertEquals(BERLIN, results.get(1).text());
        assertEquals(VIM, results.get(2).text());
        assertEquals(1.0, results.getFirst().relevance(), 1e-9,
                "fail-open keeps the normalized fused scores");
    }
}
