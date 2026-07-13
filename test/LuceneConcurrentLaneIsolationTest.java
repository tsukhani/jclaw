import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.search.LuceneIndexer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-737: deterministic reproduction of the un-serialized lazy-open path that
 * lets a concurrent (non-Lucene) test lane flip a running closed-mode test's
 * index state. play1's TestEngine runs the unit and functional lanes
 * concurrently in one JVM sharing the single Lucene index, and
 * {@code LuceneTestSync}'s lock only serializes tests that go THROUGH it — a
 * bare {@link LuceneIndexer#open()} from another lane bypasses it.
 *
 * <p>{@code IntegrationTest.memoryRecalledDuringPromptAssembly} relies on the
 * index staying shut across its whole window so memory recall keeps taking the
 * DB-LIKE fallback; a concurrent open() flipping it onto an empty Lucene reader
 * made recall return 0. The interleaving here is forced with an explicit thread
 * + {@code join()}, not timing luck: on the pre-fix code the assertion fails
 * because the shared index opens; after the fix {@code closedForTest} holds the
 * index shut against any concurrent {@code open()}.
 */
class LuceneConcurrentLaneIsolationTest extends UnitTest {

    @Test
    void closedWindowStaysClosedWhenConcurrentLaneOpensIndex() throws Exception {
        LuceneTestSync.closedForTest();
        try {
            assertFalse(LuceneIndexer.isOpen(), "precondition: this test holds the index closed");

            // A different test lane opens the index WITHOUT going through
            // LuceneTestSync (the shape of DirectLuceneMessageSearchRepository.init
            // firing on a concurrent non-Lucene test).
            var error = new AtomicReference<Throwable>();
            var lane = new Thread(() -> {
                try {
                    LuceneIndexer.open();
                } catch (IOException e) {
                    error.set(new UncheckedIOException(e));
                } catch (RuntimeException e) {
                    error.set(e);
                }
            }, "concurrent-lane");
            lane.start();
            lane.join();
            if (error.get() != null) throw new AssertionError("concurrent lane errored", error.get());

            assertFalse(LuceneIndexer.isOpen(),
                    "a concurrent lane's open() must not flip THIS test's closed window (JCLAW-737)");
        } finally {
            LuceneTestSync.release();
        }
    }
}
