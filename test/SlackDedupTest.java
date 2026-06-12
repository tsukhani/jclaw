import channels.SlackDedup;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-357: the event-dedup primitive. Keys are unique per assertion ({@code nanoTime})
 * so the process-global cache isn't shared across runs/lanes — no clear() that would race
 * concurrent test lanes.
 */
class SlackDedupTest extends UnitTest {

    @Test
    void firstSightingProcessesAndRedeliveriesAreDuplicates() {
        var key = "dedup-" + System.nanoTime();
        assertTrue(SlackDedup.firstSeen(key), "first sighting must process");
        assertFalse(SlackDedup.firstSeen(key), "a redelivery of the same key is a duplicate");
        assertFalse(SlackDedup.firstSeen(key), "still a duplicate on a third delivery");
    }

    @Test
    void distinctKeysAreIndependent() {
        var a = "dedup-a-" + System.nanoTime();
        var b = "dedup-b-" + System.nanoTime();
        assertTrue(SlackDedup.firstSeen(a));
        assertTrue(SlackDedup.firstSeen(b), "a distinct message must not be suppressed");
    }

    @Test
    void nullOrBlankKeyAlwaysProcesses() {
        // An unidentifiable event can't be deduped — process rather than drop it.
        assertTrue(SlackDedup.firstSeen(null));
        assertTrue(SlackDedup.firstSeen(""));
        assertTrue(SlackDedup.firstSeen("   "));
    }
}
