import channels.InboundEventDedup;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-357 (generalized JCLAW-446): the channel-agnostic event-dedup primitive.
 * Keys are unique per assertion ({@code nanoTime}) so the process-global cache
 * isn't shared across runs/lanes — no clear() that would race concurrent test
 * lanes.
 */
class InboundEventDedupTest extends UnitTest {

    @Test
    void firstSightingProcessesAndRedeliveriesAreDuplicates() {
        var key = "dedup-" + System.nanoTime();
        assertTrue(InboundEventDedup.firstSeen(key), "first sighting must process");
        assertFalse(InboundEventDedup.firstSeen(key), "a redelivery of the same key is a duplicate");
        assertFalse(InboundEventDedup.firstSeen(key), "still a duplicate on a third delivery");
    }

    @Test
    void distinctKeysAreIndependent() {
        var a = "dedup-a-" + System.nanoTime();
        var b = "dedup-b-" + System.nanoTime();
        assertTrue(InboundEventDedup.firstSeen(a));
        assertTrue(InboundEventDedup.firstSeen(b), "a distinct message must not be suppressed");
    }

    @Test
    void nullOrBlankKeyAlwaysProcesses() {
        // An unidentifiable event can't be deduped — process rather than drop it.
        assertTrue(InboundEventDedup.firstSeen(null));
        assertTrue(InboundEventDedup.firstSeen(""));
        assertTrue(InboundEventDedup.firstSeen("   "));
    }
}
