import memory.MemoryCategory;
import memory.MemoryForgetLog;
import memory.MemoryStoreFactory;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.MessageSearchTestHooks;
import tools.MemoryTool;

/**
 * JCLAW-1049: forget must find the memory the operator meant.
 *
 * <p>UAT: "forget everything you know about my swimming" reported "nothing to forget" while
 * {@code The user swims at the lido on Wednesday evenings.} sat in the store. Measured, the
 * whole miss was one uninflected pair — swims/swimming — putting containment at 0.750
 * against the capture-dedup floor of 0.82. Forget was being asked capture's question.
 *
 * <p>Runs with the Lucene index <em>open</em>, unlike {@code MemoryToolTest}. That matters:
 * with it closed, retrieval degrades to a whole-query {@code LIKE} substring scan, which
 * never returns this row at all — the test would fail at retrieval and say nothing about
 * the threshold under test. No vector backend is configured, so the semantic leg fails open
 * and the lexical rule is what these cases actually exercise.
 */
class MemoryForgetMatchTest extends UnitTest {

    private final MemoryTool tool = new MemoryTool();
    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        // Opening the index is not enough: searchByTextScored also checks activeDialect(),
        // which is "none" until a repository is installed, and falls back to a whole-query
        // LIKE scan. That fallback cannot express this defect at all — an inflected query is
        // by definition not a substring of the stored text — so the case has to run on real
        // token retrieval or it fails for the wrong reason.
        MessageSearchTestHooks.setRepository(new DirectLuceneMessageSearchRepository());
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        agent = new Agent();
        agent.name = "forget-match-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @AfterEach
    void teardown() {
        MessageSearchTestHooks.setRepository(null);
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        LuceneTestSync.release();
    }

    private String forget(String query) {
        return tool.execute("{\"action\":\"forget\",\"query\":\"%s\"}".formatted(query), agent);
    }

    private void seed(String text) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text, MemoryCategory.FACT.label, 0.6);
    }

    private java.util.List<String> remaining() {
        return MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                .map(m -> m.text()).toList();
    }

    @Test
    void forgetMatchesAMemoryThatDiffersByOneInflection() {
        seed("The user swims at the lido on Wednesday evenings.");

        var out = forget("user swimming lido Wednesday evenings");

        assertTrue(out.startsWith("Forgot"), "forget reported: " + out);
        assertTrue(remaining().isEmpty(), "the memory the operator meant survived: " + remaining());
    }

    @Test
    void forgetDoesNotTakeANeighboringMemoryWithIt() {
        // The threshold is lower than capture dedup's, so this is the AC that stops it
        // becoming destructive. Measured containment against the same query: the lido row
        // 0.750, the pool row 0.500, the Saturday row 0.250 — 0.70 separates them.
        seed("The user swims at the lido on Wednesday evenings.");
        seed("The user swims at the pool on Wednesday evenings.");
        seed("The user swims at the lido on Saturday mornings.");
        seed("The user cycles at the velodrome on Tuesday mornings.");

        forget("user swimming lido Wednesday evenings");

        var left = remaining();
        assertEquals(3, left.size(), "forget removed more than the row it was given: " + left);
        assertTrue(left.stream().noneMatch(t -> t.contains("Wednesday evenings") && t.contains("lido")),
                "the targeted row survived: " + left);
        assertTrue(left.stream().anyMatch(t -> t.contains("pool")), "pool row lost: " + left);
        assertTrue(left.stream().anyMatch(t -> t.contains("Saturday")), "Saturday row lost: " + left);
        assertTrue(left.stream().anyMatch(t -> t.contains("velodrome")), "velodrome row lost: " + left);
    }

    @Test
    void aForgetThatMissesNamesTheNearestMemory() {
        // "nothing to forget" alone reads as "there was nothing there" — UAT watched a model
        // take it that way and invent a reason. The near miss makes the two distinguishable.
        seed("The user swims at the lido on Wednesday evenings.");

        var out = forget("user swimming pool Wednesday evenings");

        assertFalse(out.startsWith("Forgot"), "expected no deletion, got: " + out);
        assertTrue(out.contains("nearest stored memory"), "no near miss offered: " + out);
        assertTrue(out.contains("lido"), "the near miss did not name the memory: " + out);
        assertEquals(1, remaining().size(), "a near miss must not delete anything");
    }

    /**
     * JCLAW-942: a semantic hit is topical, not the same fact. Measured on the live corpus,
     * "what movies I like" reaches the genre memory at 0.409 cosine and "the user does not
     * like Indian cinema" at 0.387, and "my views on colonialism" reaches three beliefs plus
     * a plan to write an article about them. Surfacing those is useful; deleting them
     * removes facts the operator never named, and no floor separates the two classes because
     * they interleave.
     */
    @Test
    void aSemanticOnlyMatchIsOfferedForConfirmationAndNotDeleted() {
        seed("The user's preferred movie genres are Action, Sci-Fi and Thriller.");
        seed("The user does not like Indian cinema.");
        var real = MemoryStoreFactory.get();
        var ids = real.list(String.valueOf(agent.id)).stream().map(m -> Long.valueOf(m.id())).toList();
        // Stand in for a vector backend: the real one is disabled in tests, and without a
        // semantic leg this case cannot arise at all.
        MemoryStoreFactory.setForTest(new SemanticStub(real, ids));

        var out = forget("what movies I like");

        assertEquals(2, remaining().size(), "a topical match must not delete: " + out);
        assertTrue(out.contains("NOT removed") || out.contains("look related"),
                "the related memories were not offered: " + out);
        assertTrue(out.contains("Indian cinema"), "the related memory was not named: " + out);
    }

    /** Delegates everything except the query-format semantic leg, which returns the seeded rows. */
    private record SemanticStub(memory.MemoryStore delegate, java.util.List<Long> ids)
            implements memory.MemoryStore {
        @Override
        public java.util.List<Long> semanticMatchesForQuery(String a, String q, int l, double c) {
            return ids;
        }

        @Override
        public String store(String a, String t, String c, double i) {
            return delegate.store(a, t, c, i);
        }

        @Override
        public java.util.List<MemoryEntry> search(String a, String q, int l) {
            return delegate.search(a, q, l);
        }

        @Override
        public void delete(String id) {
            delegate.delete(id);
        }

        @Override
        public java.util.List<MemoryEntry> list(String a) {
            return delegate.list(a);
        }

        @Override
        public int deleteAll(String a) {
            return delegate.deleteAll(a);
        }
    }

    @Test
    void aOneWordQueryCannotDeleteALongMemory() {
        // containment is |intersection| / min(|a|,|b|), so a single word scores 1.0 against
        // any memory containing it. minLengthRatio is what stops that, and lowering the
        // containment floor makes it load-bearing rather than incidental.
        seed("The user swims at the lido on Wednesday evenings.");

        forget("lido");

        assertEquals(1, remaining().size(), "a one-word query deleted a whole memory");
    }
}
