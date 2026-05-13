import org.junit.jupiter.api.*;
import play.test.UnitTest;
import utils.BoundedPatternCache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Unit tests for the bounded LRU pattern cache (JCLAW-142). */
class BoundedPatternCacheTest extends UnitTest {

    @Test
    void memoizesRepeatedLookups() {
        var cache = new BoundedPatternCache(8);
        var first = cache.computeIfAbsent("foo", k -> Pattern.compile(k));
        var second = cache.computeIfAbsent("foo", k -> Pattern.compile(k));
        assertSame(first, second, "same key should return memoized pattern");
        assertEquals(1, cache.size());
    }

    @Test
    void evictsOldestEntriesBeyondCap() {
        var cap = 64;
        var cache = new BoundedPatternCache(cap);
        for (int i = 0; i < cap + 10; i++) {
            final int n = i;
            cache.computeIfAbsent("k" + n, k -> Pattern.compile(k));
        }
        assertEquals(cap, cache.size(), "cache size must not exceed cap");
    }

    @Test
    void sizeStaysBoundedUnderConcurrentInsert() throws Exception {
        var cap = 64;
        var cache = new BoundedPatternCache(cap);
        var workers = 20;
        var keysPerWorker = 100;
        var latch = new CountDownLatch(1);
        var done = new CountDownLatch(workers);
        var errors = new AtomicInteger();
        // Record the largest size each worker observes after an insert so the
        // bound check happens outside the worker's try/catch. The previous
        // shape called assertTrue inside catch (Throwable) — AssertionError is
        // a Throwable, so a real over-cap observation got swallowed into the
        // errors counter and the diagnostic message ("size exceeded cap: N")
        // never reached the test report. SonarQube S5779.
        var maxObservedSize = new AtomicInteger(0);

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < workers; w++) {
                final int workerId = w;
                exec.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < keysPerWorker; i++) {
                            cache.computeIfAbsent("w" + workerId + "-" + i,
                                    k -> Pattern.compile(Pattern.quote(k)));
                            int observed = cache.size();
                            maxObservedSize.accumulateAndGet(observed, Math::max);
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            latch.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers timed out");
        }

        assertEquals(0, errors.get(), "no worker threw unexpectedly");
        assertTrue(maxObservedSize.get() <= cap,
                "size exceeded cap during concurrent insert: peak=" + maxObservedSize.get());
        assertEquals(cap, cache.size(), "final size must equal cap after overfill");
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedPatternCache(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedPatternCache(-1));
    }
}
