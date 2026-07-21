import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.LatencyStats;
import utils.LatencyTrace;

class LatencyTraceTest extends UnitTest {

    @Test
    void doubleEndEmitsEachSegmentOnce() {
        // JCLAW-822: end() is fired from racing terminal callbacks, so its
        // single-shot guard must be an atomic CAS — a second end() must be a
        // no-op, not a second emit. Use a UNIQUE channel so this assertion is
        // isolated from the JVM-global LatencyStats singleton the concurrently
        // running latency tests share (we must not reset() it or reuse a
        // shared channel name — see the play1 concurrent-TestEngine constraint).
        var channel = "lt-double-end-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        // PROLOGUE_DONE is required for end() to emit (early-exit traces skip).
        trace.mark(LatencyTrace.PROLOGUE_DONE);

        trace.end();
        trace.end(); // must be a no-op

        var total = LatencyStats.snapshot()
                .getAsJsonObject(channel)
                .getAsJsonObject("total");
        assertEquals(1L, total.get("count").getAsLong(),
                "a second end() must not re-emit the segments");
    }
}
