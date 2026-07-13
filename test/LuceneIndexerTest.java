import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.LuceneIndexer;

/**
 * Direct {@link LuceneIndexer} unit test for JCLAW-406: the per-write
 * {@code commit()} (fsync) was dropped from {@link LuceneIndexer#upsert}
 * and {@link LuceneIndexer#remove}. Search visibility must still hold —
 * the SearcherManager is built with the writer-NRT constructor, so
 * {@code maybeRefresh} sees in-RAM uncommitted segments. These tests
 * exercise the indexer directly (no JPA / no entity hooks) and query
 * through {@link DirectLuceneMessageSearchRepository#searchIds}, which
 * calls {@code maybeRefresh} on every query, to prove:
 *
 * <ul>
 *   <li>An upserted doc is searchable after a refresh <em>without</em> a
 *       per-write commit.</li>
 *   <li>A removed doc stops matching after a refresh, again without a
 *       per-write commit.</li>
 *   <li>An update overwrites prior content rather than duplicating the id.</li>
 * </ul>
 *
 * <p>Companion to {@link DirectLuceneMessageSearchRepositoryTest}, which
 * drives the same property through the full JPA @PostPersist/@PostRemove
 * round-trip. This class isolates the writer/searcher contract.
 */
class LuceneIndexerTest extends UnitTest {

    private static final LuceneIndexer.Scope SCOPE = LuceneIndexer.Scope.TASK_RUN_MESSAGE;

    private DirectLuceneMessageSearchRepository repo;

    @BeforeEach
    void setup() {
        // JCLAW-428: serialize against other Lucene tests and open a clean index
        // at the %test path (data/jclaw-lucene-test). openForTest() opens it (the
        // boot job skips Lucene init in test mode) and wipes leftover docs.
        LuceneTestSync.openForTest();
        repo = new DirectLuceneMessageSearchRepository();
    }

    @AfterEach
    void teardown() {
        LuceneTestSync.release();
    }

    @Test
    void upsertedDocIsSearchableAfterRefreshWithoutPerWriteCommit() throws Exception {
        // No commit() between upsert and search. searchIds() calls
        // maybeRefresh internally; the writer-NRT SearcherManager surfaces
        // the in-RAM segment even though it was never fsynced.
        LuceneIndexer.upsert(SCOPE, 42L, "the quick brown fox");

        var hits = repo.searchIds(SCOPE, "brown", 10);
        assertEquals(1, hits.size(), "upserted doc must be searchable via maybeRefresh, no per-write commit");
        assertEquals(Long.valueOf(42L), hits.getFirst());
    }

    @Test
    void removeMakesDocNonSearchableAfterRefresh() throws Exception {
        LuceneIndexer.upsert(SCOPE, 7L, "uniquetoken12345 payload");
        assertEquals(1, repo.searchIds(SCOPE, "uniquetoken12345", 10).size(),
                "doc must be findable before remove");

        LuceneIndexer.remove(SCOPE, 7L);

        // Again no commit() — maybeRefresh inside searchIds picks up the
        // in-RAM delete.
        assertTrue(repo.searchIds(SCOPE, "uniquetoken12345", 10).isEmpty(),
                "removed doc must drop from search via maybeRefresh, no per-write commit");
    }

    @Test
    void upsertOverwritesPriorContentForSameId() throws Exception {
        // updateDocument keys on the id Term, so a second upsert for the
        // same id replaces — not duplicates — the doc. Verifies the
        // commit-free path keeps the id unique.
        LuceneIndexer.upsert(SCOPE, 99L, "originalcontenttoken");
        assertEquals(1, repo.searchIds(SCOPE, "originalcontenttoken", 10).size());

        LuceneIndexer.upsert(SCOPE, 99L, "replacementcontenttoken");

        assertTrue(repo.searchIds(SCOPE, "originalcontenttoken", 10).isEmpty(),
                "old content must no longer match after overwrite");
        var hits = repo.searchIds(SCOPE, "replacementcontenttoken", 10);
        // Exactly one hit proves the id was overwritten, not duplicated — a
        // second copy of id 99 would surface as a second hit for this token.
        // Token-scoped (not a global docCount) so a concurrent test lane's
        // incidental doc can't perturb it (JCLAW-737: the shared-index residual).
        assertEquals(1, hits.size(), "new content must match the same id exactly once, not duplicated");
        assertEquals(Long.valueOf(99L), hits.getFirst());
    }
}
