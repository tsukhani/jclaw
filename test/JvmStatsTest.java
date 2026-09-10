import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.JvmStats;
import utils.ProcessRss;

/**
 * JVM runtime figures behind the Maintenance panel.
 *
 * <p>Absolute values are unassertable — heap and RSS move between any two statements —
 * so what is pinned here are the invariants a caller relies on: the three memory figures
 * are distinct rather than one number copied three times, absent readings stay absent
 * instead of being rendered as a real 0, and the RSS cache actually caches.
 */
class JvmStatsTest extends UnitTest {

    @Test
    void readsTheHeapAsAConsistentTriple() {
        var s = JvmStats.snapshot();
        assertTrue(s.heapUsed() > 0, "a running JVM has a non-empty heap");
        assertTrue(s.heapCommitted() >= s.heapUsed(),
                "committed must cover used: used=%d committed=%d"
                        .formatted(s.heapUsed(), s.heapCommitted()));
        // heapMax is -1 when undefined, which is a legitimate reading, not a failure.
        assertTrue(s.heapMax() == -1 || s.heapMax() >= s.heapCommitted(),
                "max must cover committed when defined: committed=%d max=%d"
                        .formatted(s.heapCommitted(), s.heapMax()));
    }

    /**
     * The whole point of reporting non-heap separately. Metaspace and the code cache are
     * invisible to every heap figure, so a panel showing only the heap under-reports what
     * the JVM holds — this asserts the second figure is genuinely its own reading.
     */
    @Test
    void reportsNonHeapSeparatelyFromTheHeap() {
        var s = JvmStats.snapshot();
        assertTrue(s.nonHeapUsed() > 0, "classes are loaded, so non-heap is in use");
        assertNotEquals(s.heapUsed(), s.nonHeapUsed(),
                "non-heap must be its own reading, not the heap figure repeated");
    }

    @Test
    void countsPlatformThreadsAndTheirPeak() {
        var s = JvmStats.snapshot();
        assertTrue(s.platformThreads() > 0);
        assertTrue(s.peakPlatformThreads() >= s.platformThreads(),
                "peak cannot be below the current count");
    }

    /**
     * The bound that makes process memory drawable as a proportion. Absent stays absent:
     * a bar against a guessed machine size would be a confident lie.
     */
    @Test
    void reportsMachineMemoryAsTheBoundForProcessMemory() {
        var s = JvmStats.snapshot();
        var machine = s.machineMemoryBytes();
        if (machine != null) {
            assertTrue(machine > 0, "a reported machine size is never zero");
            if (s.rssBytes() != null) {
                assertTrue(s.rssBytes() <= machine,
                        "a process cannot be resident in more memory than the machine has: "
                                + s.rssBytes() + " > " + machine);
            }
        }
    }

    @Test
    void reportsUptimeAndProcessorCount() {
        var s = JvmStats.snapshot();
        assertTrue(s.uptimeMs() > 0, "the JVM running this test has been up for a while");
        assertEquals(Runtime.getRuntime().availableProcessors(), s.availableProcessors());
    }

    /**
     * Unavailable readings are null, never a number. A CPU share of 0.0 means "idle" and
     * a -1 sentinel rendered raw would show as -100% — both are lies the panel would
     * display with a straight face, so the absent case has to stay absent.
     */
    @Test
    void leavesAnUnavailableCpuShareAbsentRatherThanZero() {
        var cpu = JvmStats.snapshot().processCpuLoad();
        if (cpu != null) {
            assertTrue(cpu >= 0.0 && cpu <= 1.0, "a share must be a fraction, got " + cpu);
        }
    }

    /**
     * The overshoot the snapshot assertion above can only catch by luck. Two
     * {@code getProcessCpuLoad()} samples taken close together underflow the bean's
     * wall-time denominator; 2040.57 is a reading actually measured that way (JCLAW-1180).
     */
    @Test
    void clampsACpuShareThatOvershootsItsDocumentedCeiling() {
        assertEquals(1.0, JvmStats.cpuShare(2040.5714285714287));
        assertEquals(1.0, JvmStats.cpuShare(1.0000001));
        assertEquals(0.5, JvmStats.cpuShare(0.5), "a reading inside the range is passed through");
        assertEquals(0.0, JvmStats.cpuShare(0.0), "an idle process is 0, not absent");
    }

    /** The negative sentinel stays absent — a -1 rendered raw would show as -100%. */
    @Test
    void mapsTheUnavailableSentinelToNullRatherThanANumber() {
        assertNull(JvmStats.cpuShare(-1.0));
    }

    /** GC counters are cumulative; the panel derives a rate from successive samples. */
    @Test
    void accumulatesGcCountersAcrossCollectors() {
        var s = JvmStats.snapshot();
        assertTrue(s.gcCount() >= 0, "counts are summed only when positive, so never negative");
        assertTrue(s.gcTimeMs() >= 0);
    }

    /**
     * The cache is the reason RSS is safe to poll: on macOS each miss spawns {@code ps}.
     * Two calls in a row must therefore hit, and the value must survive invalidation
     * (i.e. re-reading gives an answer of the same availability, not a null on retry).
     */
    @Test
    void cachesTheRssReadingBetweenCalls() {
        ProcessRss.invalidateForTest();
        var first = ProcessRss.bytes();
        assertSame(first, ProcessRss.bytes(), "a second call inside the TTL must not re-read");

        ProcessRss.invalidateForTest();
        var afterInvalidate = ProcessRss.bytes();
        // Availability is a property of the platform, so it cannot flip between reads.
        assertEquals(first == null, afterInvalidate == null,
                "RSS availability must not vary run to run on one platform");
        if (first != null) assertTrue(first > 0, "a resident set is never zero bytes");
    }
}
