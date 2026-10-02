import memory.JpaMemoryStore;
import memory.MemoryAutoCapture;
import memory.MemoryStoreFactory;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import utils.CircuitBreaker;

import java.util.Map;


/**
 * JCLAW-922 semantic tier: a restatement sharing too little wording for any safe
 * lexical threshold is still NOOPed, because its embedding is near-identical.
 *
 * <p>Canned embeddings via {@link JpaMemoryStore#setEmbedderForTest} and a directly
 * constructed {@code (vectorEnabled=true, isPostgres=false)} store, matching
 * {@code JpaMemoryStoreVectorTest} — no provider call, and no flipping of
 * process-global config that the concurrent test lanes would see.
 */
class MemorySemanticDedupTest extends UnitTest {

    /** Stored first. */
    private static final String STORED =
            "Tarun has a reminder scheduled for the last Friday of every month at 5 PM to pay salaries.";
    /** Same fact, different wording — containment 0.818, under any safe lexical bound. */
    private static final String PARAPHRASE =
            "The user has a recurring reminder to pay salaries on the last Friday of every month at 5:00 PM.";
    /** Unrelated: proves the tier is discriminating, not just rejecting everything. */
    private static final String UNRELATED =
            "The office printer on the third floor jams on A3 paper.";

    /**
     * Cosine(STORED, PARAPHRASE) = 0.9999 — above the 0.90 default. UNRELATED is
     * orthogonal to both, so it must survive.
     */
    private static final Map<String, float[]> EMBEDDINGS = Map.of(
            STORED, new float[] {1f, 0f, 0f, 0f},
            PARAPHRASE, new float[] {0.99f, 0.01f, 0f, 0f},
            UNRELATED, new float[] {0f, 0f, 1f, 0f});

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        JpaMemoryStore.setEmbedderForTest(EMBEDDINGS::get);
        MemoryStoreFactory.setForTest(new JpaMemoryStore(true, false));
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        MemoryStoreFactory.reset();
        LuceneTestSync.release();
    }

    private String agentId() {
        var a = new models.Agent();
        a.name = "semantic-dedup-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return String.valueOf(a.id);
    }

    private int captureOne(String agentId, String text) {
        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"%s\",\"category\":\"fact\",\"importance\":0.7}]}".formatted(text);
        return MemoryAutoCapture.capture(agentId, "semantic-dedup-agent",
                "Here is something durable worth remembering about my setup.",
                "Understood, noted.", extractor, new CircuitBreaker(20, 0.5, 5, 30_000L)).captured();
    }

    @Test
    void paraphraseBeyondTheLexicalRuleIsRejectedByEmbeddingCosine() {
        var agent = agentId();
        MemoryStoreFactory.get().store(agent, STORED, "core", 0.9);

        assertEquals(0, captureOne(agent, PARAPHRASE),
                "cosine 0.9999 — the semantic tier must NOOP what the lexical rule cannot reach");
        assertEquals(1, Memory.findByAgent(agent).size(), "no second row may be written");
        int count = play.db.jpa.JPA.em()
                .createQuery("SELECT m.corroborationCount FROM Memory m WHERE m.id = :id", Integer.class)
                .setParameter("id", Memory.findByAgent(agent).getFirst().id).getSingleResult();
        assertEquals(1, count, "the cosine match corroborates its top neighbour (JCLAW-1318)");
    }

    @Test
    void aSupersededMemoryDoesNotNoopANewOneAsASemanticDuplicate() {
        // JCLAW-525's invariant, on the semantic tier. The pgvector leg filters
        // superseded rows in SQL; the Lucene leg gets its ids from the index, which can
        // still hold a document for a row superseded since it was written — during a
        // re-embed, for instance. Without the filter that stale vector would NOOP a
        // re-emerging fact, which is exactly what supersession is meant to allow.
        var agent = agentId();
        var store = MemoryStoreFactory.get();
        var id = store.store(agent, STORED, "core", 0.9);

        var superseded = (models.Memory) models.Memory.findById(Long.valueOf(id));
        superseded.supersede(999_999L);
        // Put the vector back, simulating an index that has not caught up with the
        // supersession — supersede() removes the document via @PostUpdate.
        services.search.LuceneIndexer.upsert(services.search.LuceneIndexer.Scope.MEMORY,
                superseded.id, STORED, String.valueOf(superseded.agent.id), EMBEDDINGS.get(STORED));

        assertEquals(1, captureOne(agent, PARAPHRASE),
                "a superseded memory must not block a re-emerging fact");
    }

    @Test
    void unrelatedFactIsStillStoredWithSemanticDedupActive() {
        var agent = agentId();
        MemoryStoreFactory.get().store(agent, STORED, "core", 0.9);

        assertEquals(1, captureOne(agent, UNRELATED),
                "an orthogonal embedding must not be treated as a duplicate");
        assertEquals(2, Memory.findByAgent(agent).size());
    }
}
