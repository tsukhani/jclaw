import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.TokenCoalescer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for the JCLAW-200 SSE token coalescer.
 */
public class TokenCoalescerTest extends UnitTest {

    @Test
    void thresholdZero_passesEveryTokenStraightThrough() {
        var emitted = new ArrayList<String>();
        var c = new TokenCoalescer(0, emitted::add);

        c.accept("a");
        c.accept("bc");
        c.accept("def");

        assertEquals(List.of("a", "bc", "def"), emitted,
                "threshold=0 must preserve the pre-coalescer 1-flush-per-token contract");
    }

    @Test
    void threshold5_buffersUntilThresholdCrossed() {
        var emitted = new ArrayList<String>();
        var c = new TokenCoalescer(5, emitted::add);

        c.accept("ab");
        c.accept("cd");
        assertEquals(List.of(), emitted, "buffer at 4 chars should not flush yet (threshold 5)");

        c.accept("e");
        assertEquals(List.of("abcde"), emitted, "buffer hits 5 chars → one merged flush");

        c.accept("f");
        assertEquals(List.of("abcde"), emitted, "next token (1 char) below threshold, no new flush");

        c.drain();
        assertEquals(List.of("abcde", "f"), emitted, "drain emits the leftover 'f'");
    }

    @Test
    void threshold5_oneOversizedTokenFlushesAlone() {
        var emitted = new ArrayList<String>();
        var c = new TokenCoalescer(5, emitted::add);

        c.accept("hello world");
        assertEquals(List.of("hello world"), emitted,
                "single token already past threshold → one flush carrying that token verbatim");
    }

    @Test
    void drain_isIdempotent_andSafeOnEmpty() {
        var emitted = new ArrayList<String>();
        var c = new TokenCoalescer(10, emitted::add);

        c.drain();
        c.drain();
        assertEquals(List.of(), emitted, "drain on empty buffer must not emit anything");

        c.accept("hi");
        c.drain();
        c.drain();
        assertEquals(List.of("hi"), emitted, "second drain after a flush must not re-emit");
    }

    @Test
    void emptyOrNullToken_isSilentlyIgnored() {
        var emitted = new ArrayList<String>();
        var c = new TokenCoalescer(3, emitted::add);

        c.accept(null);
        c.accept("");
        c.accept("ab");
        c.accept("");
        c.accept("c");

        assertEquals(List.of("abc"), emitted,
                "null and empty tokens must not affect buffering or trigger flush");
    }

    @Test
    void concurrentAccept_doesNotCorruptBuffer() throws Exception {
        var emitted = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var c = new TokenCoalescer(1_000_000, emitted::add); // huge threshold so only drain flushes
        int threads = 8;
        int perThread = 1_000;
        var latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        c.accept("t" + tid + "-" + i + " ");
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "concurrent producers should finish quickly");
        c.drain();

        // Single drain emission. Total content length must equal the sum of the
        // individual token lengths — proves no token was dropped or duplicated.
        assertEquals(1, emitted.size(), "single drain after the concurrent producers");
        int expectedLen = 0;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                expectedLen += ("t" + t + "-" + i + " ").length();
            }
        }
        assertEquals(expectedLen, emitted.get(0).length(),
                "drained payload length must equal the sum of every accept call's content");
    }
}
