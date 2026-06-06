import services.search.LuceneIndexer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes Lucene-touching tests across play1's concurrent unit + functional
 * test lanes (JCLAW-428). The Lucene index is a JVM-global singleton — one
 * IndexWriter per scope — so without coordination two tests opening, wiping, or
 * closing it at once clobber each other:
 *
 * <ul>
 *   <li><b>Search tests</b> ({@link #openForTest()}) open the index, wipe it to
 *       a clean state, seed, and assert. Shared scopes (TASK_RUN_MESSAGE /
 *       CONVERSATION_MESSAGE / SUBAGENT_RUN) are touched by both the unit
 *       DirectLuceneMessageSearchRepositoryTest and the functional
 *       Api...SearchTest classes.</li>
 *   <li><b>Closed-index tests</b> ({@link #closedForTest()}) exercise the
 *       {@code Memory.searchByText} DB-LIKE fallback, which only fires while the
 *       index is closed (Memory.java: "Backend not initialized — substring
 *       fallback"). A concurrent search test opening the global index would flip
 *       them onto an empty Lucene path → "expected 1 but was 0". Holding the lock
 *       and forcing the index closed keeps them deterministic.</li>
 * </ul>
 *
 * <p>Both modes acquire one global lock so only one Lucene test runs at a time,
 * and {@link #release()} closes the index + unlocks in {@code @AfterEach}. The
 * index path is the {@code %test.jclaw.search.lucenePath} dir
 * (data/jclaw-lucene-test), never the production index.
 *
 * <p>Known residual (out of scope, JCLAW-428 AC4): a non-Lucene test that
 * incidentally indexes a same-scope entity during a search test's open window is
 * not covered — closing that fully would need a non-singleton LuceneIndexer.
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
            if (!LuceneIndexer.isOpen()) {
                LuceneIndexer.open();
            }
            LuceneIndexer.wipeForTest();
        } catch (Exception e) {
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
        } catch (RuntimeException e) {
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
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }
}
