import services.search.LuceneIndexer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes Lucene-touching tests across play1's concurrent unit + functional
 * test lanes (JCLAW-428). The Lucene index is a single shared instance — one
 * IndexWriter per scope — because play1 runs functional-test controllers on one
 * shared executor thread ({@code TestEngine.functionalTestsExecutor}), so a
 * test's seed and its subsequent search must resolve to the same index (a
 * per-thread index would split them). Without coordination two tests opening,
 * wiping, or closing it at once clobber each other:
 *
 * <ul>
 *   <li><b>Search tests</b> ({@link #openForTest()}) open the index, wipe it to
 *       a clean state, seed, and assert. Shared scopes (TASK_RUN_MESSAGE /
 *       CONVERSATION_MESSAGE / SUBAGENT_RUN) are touched by both the unit
 *       DirectLuceneMessageSearchRepositoryTest and the functional
 *       Api...SearchTest classes.</li>
 *   <li><b>Closed-index tests</b> ({@link #closedForTest()}) exercise the
 *       {@code Memory.searchByText} DB-LIKE fallback, which fires while the index
 *       is closed. A concurrent lane opening the shared index used to flip them
 *       onto an empty Lucene path → "expected 1 but was 0"; JCLAW-737 closes that
 *       by having {@code closedForTest} tell {@link LuceneIndexer#holdClosedForTest}
 *       to refuse any {@link LuceneIndexer#open()} for the duration of the window,
 *       so the un-serialized lazy-open path can no longer flip it open.</li>
 * </ul>
 *
 * <p>Both modes acquire one global lock so only one Lucene test runs at a time,
 * and {@link #release()} closes the index, clears the hold, and unlocks in
 * {@code @AfterEach}. The index path is the {@code %test.jclaw.search.lucenePath}
 * dir (data/jclaw-lucene-test), never the production index.
 *
 * <p>Residual (JCLAW-428 AC4 / JCLAW-737): a non-Lucene test that incidentally
 * indexes a same-scope entity during a search test's OPEN window can still add a
 * doc to the shared index — a truly per-write-isolated index is precluded by the
 * shared functional-test executor above. It is harmless because every Lucene
 * test assertion is token-scoped (each searches for its own distinctive tokens),
 * so a foreign doc can neither hide a seeded hit nor inflate a count. The two
 * former global {@code docCount} checks (LuceneIndexerTest,
 * DirectLuceneMessageSearchRepositoryTest) were converted to token-scoped
 * assertions for exactly this reason.
 */
public final class LuceneTestSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private LuceneTestSync() {}

    /**
     * Search-test mode: lock, ensure the (test) index is open, and wipe it to a
     * clean state. Call first in {@code @BeforeEach}. On failure the lock is
     * released so it never leaks to the next test.
     */
    public static void openForTest() {
        LOCK.lock();
        try {
            LuceneIndexer.holdClosedForTest(false);
            if (!LuceneIndexer.isOpen()) {
                LuceneIndexer.open();
            }
            LuceneIndexer.wipeForTest();
        } catch (Exception e) {
            LuceneIndexer.holdClosedForTest(false);
            LOCK.unlock();
            throw new RuntimeException("LuceneTestSync.openForTest failed", e);
        }
    }

    /**
     * Closed-index mode: lock and guarantee the index is closed, so a test that
     * relies on the {@code Memory.searchByText} LIKE fallback can't be flipped
     * onto the Lucene path by a concurrent search test. Call first in
     * {@code @BeforeEach}.
     */
    public static void closedForTest() {
        LOCK.lock();
        try {
            if (LuceneIndexer.isOpen()) {
                LuceneIndexer.close();
            }
            // JCLAW-737: hold the index shut for the whole window so a concurrent
            // lane's un-serialized open() can't flip it open under this test.
            LuceneIndexer.holdClosedForTest(true);
        } catch (RuntimeException e) {
            LuceneIndexer.holdClosedForTest(false);
            LOCK.unlock();
            throw e;
        }
    }

    /**
     * Close the index and release the lock. Call in {@code @AfterEach} for both
     * modes — JUnit runs it even when the test or a later {@code @BeforeEach}
     * fails. Idempotent: only unlocks when the current thread holds the lock.
     */
    public static void release() {
        try {
            if (LuceneIndexer.isOpen()) {
                LuceneIndexer.close();
            }
        } finally {
            LuceneIndexer.holdClosedForTest(false);
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }
}
